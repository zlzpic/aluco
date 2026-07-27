import { AxiosError, AxiosHeaders } from 'axios'
import type { AxiosRequestConfig, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { db, latestMetrics, sampleHistory } from './mockData'
import type {
  AlertEvent,
  AlertStatus,
  CreateDeviceRequest,
  CreateRuleRequest,
  Device,
  DeviceCreated,
  DeviceState,
  LoginRequest,
  Page,
  Rule,
  TelemetryInterval,
  TelemetryPoint,
  TelemetryResponse,
} from '@/types/api'

/**
 * Mock REST：作为 axios adapter 拦截全部 /api/v1 请求。
 * 路径、参数、分页信封、错误码与对接文档 3.1 完全一致；
 * 仅用于开发/预览，构建产物默认关闭。
 */

const LATENCY_MS = 200

interface MockRequest {
  method: string
  path: string
  query: URLSearchParams
  body: unknown
  auth: string | null
}

type MockResult = { status: number; data?: unknown; headers?: Record<string, string> }

function ok(data: unknown, status = 200, headers?: Record<string, string>): MockResult {
  return { status, data, headers }
}

function err(status: number, code: string, message: string): MockResult {
  return { status, data: { code, message } }
}

function pageOf<T>(list: T[], page: number, size: number): Page<T> {
  const start = (page - 1) * size
  return { list: list.slice(start, start + size), total: list.length, page, size }
}

function num(q: URLSearchParams, key: string, def: number): number {
  const v = Number(q.get(key))
  return Number.isFinite(v) && v > 0 ? v : def
}

const BUCKET_MS: Record<Exclude<TelemetryInterval, 'raw'>, number> = {
  '1m': 60_000,
  '5m': 300_000,
  '1h': 3_600_000,
  '1d': 86_400_000,
}

function route(req: MockRequest): MockResult {
  const { method, path, query, body } = req

  // ① POST /auth/login
  if (method === 'post' && path === '/auth/login') {
    const { username, password } = (body ?? {}) as Partial<LoginRequest>
    if (username === 'admin' && password === 'admin123') {
      return ok({ token: `mock-jwt-${Date.now()}`, expiresAt: Date.now() + 24 * 3_600_000 })
    }
    return err(401, 'BAD_CREDENTIALS', 'invalid username or password')
  }

  // 其余接口要求 Bearer
  if (!req.auth?.startsWith('Bearer ')) {
    return err(401, 'UNAUTHORIZED', 'missing or invalid token')
  }

  // ② POST /devices
  if (method === 'post' && path === '/devices') {
    const b = (body ?? {}) as Partial<CreateDeviceRequest>
    if (!b.deviceKey || !b.name || !b.siteId) return err(400, 'BAD_REQUEST', 'deviceKey/name/siteId 必填')
    if (db.devices.some((d) => d.deviceKey === b.deviceKey)) {
      return err(409, 'DEVICE_KEY_EXISTS', `deviceKey ${b.deviceKey} 已存在`)
    }
    const seq = ++db.deviceSeq
    const created: DeviceCreated = {
      id: seq,
      deviceKey: b.deviceKey,
      name: b.name,
      siteId: b.siteId,
      createdAt: Date.now(),
      online: false,
      lastSeenAt: null,
      token: crypto.randomUUID().replace(/-/g, ''),
    }
    db.devices.push({
      ...created,
      intervalSec: 1,
      phase: seq * 0.7,
      spikeLeft: 0,
      spikePeak: 0,
      nextTickAt: Date.now(),
    })
    return ok(created, 201)
  }

  // ③ GET /devices
  if (method === 'get' && path === '/devices') {
    const keyword = (query.get('keyword') ?? '').trim().toLowerCase()
    const filtered = keyword
      ? db.devices.filter(
          (d) => d.deviceKey.toLowerCase().includes(keyword) || d.name.toLowerCase().includes(keyword),
        )
      : db.devices
    const list = filtered.map<Device>((d) => ({
      id: d.id,
      deviceKey: d.deviceKey,
      name: d.name,
      siteId: d.siteId,
      createdAt: d.createdAt,
      online: d.online,
      lastSeenAt: d.lastSeenAt,
    }))
    return ok(pageOf(list, num(query, 'page', 1), num(query, 'size', 20)))
  }

  const deviceMatch = /^\/devices\/([^/]+)$/.exec(path)
  const stateMatch = /^\/devices\/([^/]+)\/state$/.exec(path)
  const telemetryMatch = /^\/devices\/([^/]+)\/telemetry$/.exec(path)
  const cmdMatch = /^\/devices\/([^/]+)\/commands\/set-interval$/.exec(path)

  const findDevice = (key: string) => db.devices.find((d) => d.deviceKey === decodeURIComponent(key))

  // ④ GET /devices/{key}
  if (method === 'get' && deviceMatch) {
    const d = findDevice(deviceMatch[1])
    if (!d) return err(404, 'DEVICE_NOT_FOUND', '设备不存在')
    const { intervalSec: _i, phase: _p, spikeLeft: _s, spikePeak: _sp, nextTickAt: _n, ...rest } = d
    return ok(rest)
  }

  // ⑤ DELETE /devices/{key}
  if (method === 'delete' && deviceMatch) {
    const idx = db.devices.findIndex((d) => d.deviceKey === decodeURIComponent(deviceMatch[1]))
    if (idx < 0) return err(404, 'DEVICE_NOT_FOUND', '设备不存在')
    db.devices.splice(idx, 1)
    return ok(undefined, 204)
  }

  // ⑥ GET /devices/{key}/state
  if (method === 'get' && stateMatch) {
    const d = findDevice(stateMatch[1])
    if (!d) return err(404, 'DEVICE_NOT_FOUND', '设备不存在')
    const state: DeviceState = d.lastSeenAt === null
      ? { deviceKey: d.deviceKey, metrics: {}, online: false, lastSeenAt: null }
      : {
          deviceKey: d.deviceKey,
          metrics: d.online ? latestMetrics.get(d.deviceKey) ?? {} : {},
          online: d.online,
          lastSeenAt: d.lastSeenAt,
        }
    return ok(state)
  }

  // ⑦ GET /devices/{key}/telemetry
  if (method === 'get' && telemetryMatch) {
    const d = findDevice(telemetryMatch[1])
    if (!d) return err(404, 'DEVICE_NOT_FOUND', '设备不存在')
    const metric = query.get('metric') ?? 'temp'
    const to = Number(query.get('to')) || Date.now()
    const from = Number(query.get('from')) || to - 3_600_000
    const interval = (query.get('interval') ?? 'raw') as TelemetryInterval
    const points: TelemetryPoint[] = []
    let truncated = false
    if (interval === 'raw') {
      for (let t = from; t <= to; t += 1_000) {
        if (points.length >= 10_000) {
          truncated = true
          break
        }
        points.push({ ts: t, val: sampleHistory(d.deviceKey, metric, t) })
      }
    } else {
      const bucket = BUCKET_MS[interval]
      for (let start = Math.floor(from / bucket) * bucket; start <= to; start += bucket) {
        // 时间桶 AVG：桶内取 4 个采样点求平均
        let sum = 0
        for (let i = 0; i < 4; i++) sum += sampleHistory(d.deviceKey, metric, start + (bucket * i) / 4)
        points.push({ ts: start, val: Math.round((sum / 4) * 100) / 100 })
      }
    }
    const res: TelemetryResponse = { metric, points }
    return ok(res, 200, { 'x-truncated': truncated ? 'true' : 'false' })
  }

  // ⑭ POST /devices/{key}/commands/set-interval
  if (method === 'post' && cmdMatch) {
    const d = findDevice(cmdMatch[1])
    if (!d) return err(404, 'DEVICE_NOT_FOUND', '设备不存在')
    const { intervalSec } = (body ?? {}) as { intervalSec?: number }
    if (!intervalSec || intervalSec < 1 || intervalSec > 3600) {
      return err(400, 'BAD_REQUEST', 'intervalSec 合法范围 1~3600')
    }
    d.intervalSec = intervalSec
    return ok({ cmdId: crypto.randomUUID() })
  }

  // ⑧ GET /rules
  if (method === 'get' && path === '/rules') {
    return ok(pageOf(db.rules, num(query, 'page', 1), num(query, 'size', 20)))
  }

  // ⑨ POST /rules
  if (method === 'post' && path === '/rules') {
    const b = (body ?? {}) as Partial<CreateRuleRequest>
    if (!b.name || !b.metric || !b.op || b.threshold === undefined) {
      return err(400, 'BAD_REQUEST', 'name/metric/op/threshold 必填')
    }
    if (b.deviceKey && !db.devices.some((d) => d.deviceKey === b.deviceKey)) {
      return err(404, 'DEVICE_NOT_FOUND', 'deviceKey 指定的设备不存在')
    }
    const now = Date.now()
    const rule: Rule = {
      id: ++db.ruleSeq,
      name: b.name,
      metric: b.metric,
      op: b.op,
      thresholdVal: b.threshold,
      deviceKey: b.deviceKey ?? null,
      enabled: true,
      createdAt: now,
      updatedAt: now,
    }
    db.rules.push(rule)
    return ok(rule, 201)
  }

  const ruleEnabledMatch = /^\/rules\/(\d+)\/enabled$/.exec(path)
  const ruleMatch = /^\/rules\/(\d+)$/.exec(path)

  // ⑩ PATCH /rules/{id}/enabled
  if (method === 'patch' && ruleEnabledMatch) {
    const rule = db.rules.find((r) => r.id === Number(ruleEnabledMatch[1]))
    if (!rule) return err(404, 'RULE_NOT_FOUND', '规则不存在')
    const { enabled } = (body ?? {}) as { enabled?: boolean }
    rule.enabled = enabled ?? rule.enabled
    rule.updatedAt = Date.now()
    return ok(rule)
  }

  // ⑪ DELETE /rules/{id}
  if (method === 'delete' && ruleMatch) {
    const id = Number(ruleMatch[1])
    const idx = db.rules.findIndex((r) => r.id === id)
    if (idx < 0) return err(404, 'RULE_NOT_FOUND', '规则不存在')
    db.rules.splice(idx, 1)
    // 其下 FIRING 事件自动置 RESOLVED
    const now = Date.now()
    db.alerts.forEach((a) => {
      if (a.ruleId === id && a.status === 'FIRING') {
        a.status = 'RESOLVED'
        a.resolvedAt = now
      }
    })
    Object.keys(db.firing).forEach((k) => {
      if (k.startsWith(`${id}:`)) delete db.firing[k]
    })
    return ok(undefined, 204)
  }

  // ⑫ GET /alerts
  if (method === 'get' && path === '/alerts') {
    const status = (query.get('status') ?? '') as AlertStatus | ''
    const deviceKey = query.get('deviceKey') ?? ''
    const filtered = db.alerts
      .filter((a) => (!status || a.status === status) && (!deviceKey || a.deviceKey === deviceKey))
      .slice()
      .sort((a, b) => b.id - a.id)
    return ok(pageOf(filtered, num(query, 'page', 1), num(query, 'size', 20)))
  }

  const ackMatch = /^\/alerts\/(\d+)\/ack$/.exec(path)

  // ⑬ POST /alerts/{id}/ack
  if (method === 'post' && ackMatch) {
    const alert = db.alerts.find((a) => a.id === Number(ackMatch[1]))
    if (!alert) return err(404, 'ALERT_NOT_FOUND', '事件不存在')
    if (alert.status !== 'FIRING') return err(409, 'ALERT_NOT_FIRING', '仅 FIRING 状态可确认')
    alert.status = 'ACKED'
    alert.ackedAt = Date.now()
    return ok(alert)
  }

  return err(404, 'NOT_FOUND', `Mock 未实现的接口：${method.toUpperCase()} ${path}`)
}

export async function mockAdapter(config: InternalAxiosRequestConfig): Promise<AxiosResponse> {
  const base = config.baseURL ?? ''
  const url = new URL(`${base}${config.url ?? ''}`, window.location.origin)
  Object.entries((config.params ?? {}) as Record<string, unknown>).forEach(([k, v]) => {
    if (v !== undefined && v !== null) url.searchParams.set(k, String(v))
  })

  const req: MockRequest = {
    method: (config.method ?? 'get').toLowerCase(),
    path: url.pathname.replace(/^\/api\/v1/, '') || '/',
    query: url.searchParams,
    body: typeof config.data === 'string' ? JSON.parse(config.data) : config.data,
    auth: (config.headers as AxiosHeaders)?.get?.('Authorization') as string | null,
  }

  await new Promise((r) => setTimeout(r, LATENCY_MS))
  const result = route(req)

  const response: AxiosResponse = {
    data: result.data,
    status: result.status,
    statusText: String(result.status),
    headers: result.headers ?? {},
    config,
  }
  if (result.status >= 200 && result.status < 300) return response
  throw new AxiosError(
    `Request failed with status code ${result.status}`,
    String(result.status),
    config,
    null,
    response,
  )
}
