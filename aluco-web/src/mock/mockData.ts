import type { AlertEvent, Device, Rule } from '@/types/api'

/**
 * Mock 种子数据。波形参照规格 9.2：
 * temp = 22 + 3·sin(t/10min) + N(0,0.3)；humidity = 55 + 10·sin(t/17min+φ) + N(0,1)
 * φ 按设备编号分散；含尖峰注入与 3 周期线性回落，用于演示告警触发/恢复。
 */
export interface MockDevice extends Device {
  intervalSec: number
  phase: number
  /** 尖峰剩余回落周期数（>0 表示处于尖峰/回落中） */
  spikeLeft: number
  spikePeak: number
  nextTickAt: number
}

export interface MockDb {
  devices: MockDevice[]
  rules: Rule[]
  alerts: AlertEvent[]
  deviceSeq: number
  ruleSeq: number
  alertSeq: number
  /** 告警状态机：(ruleId, deviceKey) → 是否 FIRING 中 */
  firing: Record<string, boolean>
}

function deviceIndex(key: string): number {
  const m = /(\d+)$/.exec(key)
  return m ? parseInt(m[1], 10) : 0
}

function makeDevice(seq: number, key: string, name: string, siteId: string, createdAt: number): MockDevice {
  return {
    id: seq,
    deviceKey: key,
    name,
    siteId,
    createdAt,
    online: true,
    lastSeenAt: Date.now(),
    intervalSec: 1,
    phase: deviceIndex(key) * 0.7,
    spikeLeft: 0,
    spikePeak: 0,
    nextTickAt: Date.now() + Math.floor(Math.random() * 1000),
  }
}

export function createMockDb(): MockDb {
  const now = Date.now()
  const devices: MockDevice[] = []
  for (let i = 1; i <= 12; i++) {
    const key = `TH-${String(i).padStart(4, '0')}`
    const site = i <= 6 ? 'site-01' : 'site-02'
    devices.push(makeDevice(i, key, `温湿度传感器 ${i}`, site, now - i * 86_400_000))
  }
  // 一台从未上报过的设备：metrics {}、offline、lastSeenAt null
  const never = makeDevice(13, 'TH-0013', '备用传感器（未上报）', 'site-02', now - 86_400_000)
  never.online = false
  never.lastSeenAt = null
  devices.push(never)

  const rules: Rule[] = [
    {
      id: 1,
      name: '温度过高',
      metric: 'temp',
      op: 'GT',
      thresholdVal: 30,
      deviceKey: null,
      enabled: true,
      createdAt: now - 3_600_000,
      updatedAt: now - 3_600_000,
    },
    {
      id: 2,
      name: 'TH-0001 温度告警（演示单设备）',
      metric: 'temp',
      op: 'GT',
      thresholdVal: 24,
      deviceKey: 'TH-0001',
      enabled: true,
      createdAt: now - 3_000_000,
      updatedAt: now - 3_000_000,
    },
    {
      id: 3,
      name: '湿度过低',
      metric: 'humidity',
      op: 'LT',
      thresholdVal: 40,
      deviceKey: null,
      enabled: false,
      createdAt: now - 2_400_000,
      updatedAt: now - 2_400_000,
    },
  ]

  const alerts: AlertEvent[] = [
    {
      id: 3,
      ruleId: 1,
      deviceId: 2,
      status: 'ACKED',
      triggerVal: 31.4,
      triggeredAt: now - 5_400_000,
      resolvedAt: now - 5_100_000,
      ackedAt: now - 5_000_000,
      ruleName: '温度过高',
      deviceKey: 'TH-0002',
    },
    {
      id: 2,
      ruleId: 1,
      deviceId: 4,
      status: 'RESOLVED',
      triggerVal: 30.8,
      triggeredAt: now - 3_900_000,
      resolvedAt: now - 3_700_000,
      ackedAt: null,
      ruleName: '温度过高',
      deviceKey: 'TH-0004',
    },
    {
      id: 1,
      ruleId: 2,
      deviceId: 1,
      status: 'FIRING',
      triggerVal: 24.6,
      triggeredAt: now - 600_000,
      resolvedAt: null,
      ackedAt: null,
      ruleName: 'TH-0001 温度告警（演示单设备）',
      deviceKey: 'TH-0001',
    },
  ]

  return { devices, rules, alerts, deviceSeq: 13, ruleSeq: 3, alertSeq: 3, firing: {} }
}

export const db = createMockDb()

/** 各设备最近一次推送的指标值（供 GET /state 返回） */
export const latestMetrics = new Map<string, Record<string, number>>()

export function pushMockAlert(event: AlertEvent): void {
  db.alerts.push(event)
}

export function nextAlertId(): number {
  return ++db.alertSeq
}

/** 确定性伪随机（同一 ts 桶内可复现），近似 N(0,1) */
function noise(seed: number, t: number): number {
  const x = Math.sin(seed * 127.1 + Math.floor(t / 1000) * 0.311) * 43758.5453
  return (x - Math.floor(x)) * 2 - 1
}

export interface WaveValues {
  temp: number
  humidity: number
}

/** 规格 9.2 波形 + 尖峰注入（概率 0.002/点）与 3 周期线性回落 */
export function sampleMetrics(d: MockDevice, t: number): WaveValues {
  const seed = deviceIndex(d.deviceKey)
  let temp = 22 + 3 * Math.sin(t / 600_000 + d.phase) + noise(seed, t) * 0.3
  const humidity = 55 + 10 * Math.sin(t / 1_020_000 + d.phase) + noise(seed + 1000, t) * 1

  if (d.spikeLeft === 0 && Math.random() < 0.002) {
    d.spikePeak = 12 + Math.random() * 6
    d.spikeLeft = 3
    temp += d.spikePeak
  } else if (d.spikeLeft > 0) {
    temp += (d.spikePeak * d.spikeLeft) / 3
    d.spikeLeft -= 1
  }

  return { temp: round1(temp), humidity: round1(humidity) }
}

/** 历史曲线用：确定性采样（不触发尖峰状态变化），含少量历史尖峰让曲线有起伏 */
export function sampleHistory(deviceKey: string, metric: string, t: number): number {
  const seed = deviceIndex(deviceKey)
  const phase = seed * 0.7
  if (metric === 'humidity') {
    return round1(55 + 10 * Math.sin(t / 1_020_000 + phase) + noise(seed + 1000, t) * 1)
  }
  if (metric === 'temp') {
    let v = 22 + 3 * Math.sin(t / 600_000 + phase) + noise(seed, t) * 0.3
    // 周期性历史尖峰，便于演示阈值告警区间
    const cyc = Math.sin(t / 3_600_000 + phase * 2)
    if (cyc > 0.93) v += 8 * (cyc - 0.93) * 14
    return round1(v)
  }
  // 未知指标：给一个稳定波形，体现「系统对指标名无硬编码」
  return round1(10 + 5 * Math.sin(t / 900_000 + phase) + noise(seed + 2000, t) * 0.5)
}

function round1(v: number): number {
  return Math.round(v * 10) / 10
}
