import { db, latestMetrics, nextAlertId, pushMockAlert, sampleMetrics } from './mockData'
import type { AlertEvent, Rule } from '@/types/api'
import type { WsServerMsg, WsSubscribeFrame } from '@/types/ws'

/**
 * Mock WebSocket：严格遵守对接文档 3.2 的消息格式，
 * 包括 alert 中 RESOLVED 事件 id 为 0 的 v1 行为——切真实后端时零差异。
 */
export class MockLiveSocket {
  onopen: () => void = () => {}
  onmessage: (msg: WsServerMsg) => void = () => {}
  onclose: () => void = () => {}

  private subscriptions = new Set<string>()
  private telemetryTimer: number | null = null
  private presenceTimer: number | null = null
  private closed = false
  private offlineDemo: string | null = null

  open(): void {
    window.setTimeout(() => {
      if (this.closed) return
      this.onopen()
      this.telemetryTimer = window.setInterval(() => this.tick(), 1_000)
      this.presenceTimer = window.setInterval(() => this.presenceTick(), 20_000)
    }, 100)
  }

  send(raw: string): void {
    try {
      const frame = JSON.parse(raw) as WsSubscribeFrame
      if (frame.type === 'subscribe') frame.deviceIds.forEach((id) => this.subscriptions.add(id))
      if (frame.type === 'unsubscribe') frame.deviceIds.forEach((id) => this.subscriptions.delete(id))
    } catch {
      /* 非 JSON 帧忽略 */
    }
  }

  close(): void {
    if (this.closed) return
    this.closed = true
    if (this.telemetryTimer !== null) window.clearInterval(this.telemetryTimer)
    if (this.presenceTimer !== null) window.clearInterval(this.presenceTimer)
    this.onclose()
  }

  private emit(msg: WsServerMsg): void {
    if (!this.closed) this.onmessage(msg)
  }

  /** 每秒检查各订阅设备是否到上报时刻（按各自 intervalSec） */
  private tick(): void {
    const now = Date.now()
    for (const key of this.subscriptions) {
      const d = db.devices.find((x) => x.deviceKey === key)
      if (!d || !d.online || now < d.nextTickAt) continue
      d.nextTickAt = now + d.intervalSec * 1_000
      d.lastSeenAt = now
      const metrics: Record<string, number> = { ...sampleMetrics(d, now) }
      latestMetrics.set(key, metrics)
      this.emit({ type: 'telemetry', deviceId: key, ts: now, metrics })
      this.evaluateRules(key, metrics, now)
    }
  }

  /** 告警状态机（参照规格 7.3-6）：触发/去重/恢复 */
  private evaluateRules(deviceKey: string, metrics: Record<string, number>, ts: number): void {
    for (const rule of db.rules) {
      if (!rule.enabled) continue
      if (!(rule.metric in metrics)) continue
      if (rule.deviceKey !== null && rule.deviceKey !== deviceKey) continue
      const violated = compare(metrics[rule.metric], rule)
      const stateKey = `${rule.id}:${deviceKey}`
      const firing = db.firing[stateKey] === true
      if (!firing && violated) {
        db.firing[stateKey] = true
        const event: AlertEvent = {
          id: nextAlertId(),
          ruleId: rule.id,
          deviceId: db.devices.find((d) => d.deviceKey === deviceKey)?.id ?? 0,
          status: 'FIRING',
          triggerVal: metrics[rule.metric],
          triggeredAt: ts,
          resolvedAt: null,
          ackedAt: null,
          ruleName: rule.name,
          deviceKey,
        }
        pushMockAlert(event)
        this.emit({
          type: 'alert',
          event: {
            id: event.id,
            ruleId: rule.id,
            ruleName: rule.name,
            deviceId: deviceKey,
            status: 'FIRING',
            value: event.triggerVal,
            triggeredAt: ts,
          },
        })
      } else if (firing && !violated) {
        db.firing[stateKey] = false
        const open = db.alerts.find((a) => a.ruleId === rule.id && a.deviceKey === deviceKey && a.status === 'FIRING')
        if (open) {
          open.status = 'RESOLVED'
          open.resolvedAt = ts
        }
        // v1 已知限制：RESOLVED 推送的 event.id 为 0
        this.emit({
          type: 'alert',
          event: {
            id: 0,
            ruleId: rule.id,
            ruleName: rule.name,
            deviceId: deviceKey,
            status: 'RESOLVED',
            value: metrics[rule.metric],
            triggeredAt: ts,
          },
        })
      }
    }
  }

  /** 周期性演示上下线：挑一台设备离线 20s 后恢复 */
  private presenceTick(): void {
    if (this.offlineDemo) {
      const d = db.devices.find((x) => x.deviceKey === this.offlineDemo)
      if (d) {
        d.online = true
        d.lastSeenAt = Date.now()
        this.emit({ type: 'presence', deviceId: d.deviceKey, online: true })
      }
      this.offlineDemo = null
      return
    }
    const candidates = db.devices.filter((d) => d.online && d.lastSeenAt !== null)
    if (!candidates.length) return
    const victim = candidates[Math.floor(Math.random() * candidates.length)]
    victim.online = false
    this.offlineDemo = victim.deviceKey
    this.emit({ type: 'presence', deviceId: victim.deviceKey, online: false })
  }
}

function compare(val: number, rule: Rule): boolean {
  switch (rule.op) {
    case 'GT':
      return val > rule.thresholdVal
    case 'GTE':
      return val >= rule.thresholdVal
    case 'LT':
      return val < rule.thresholdVal
    case 'LTE':
      return val <= rule.thresholdVal
    case 'EQ':
      return val === rule.thresholdVal
  }
}
