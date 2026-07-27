import type { TelemetryInterval } from '@/types/api'

export interface TimeWindow {
  key: string
  label: string
  ms: number
  /** 自动映射的降采样档位：raw 适合 ≤1h 窗口；长时间窗用 5m/1h/1d */
  interval: TelemetryInterval
}

/**
 * 时间窗 → interval 自动映射表。
 * 15 分钟档用 raw：1Hz 设备 15 分钟 = 900 点；即使 SET_INTERVAL 调成高频，
 * 也有 raw 上限 10000 点 + X-Truncated 提示兜底，安全。
 */
export const TIME_WINDOWS: TimeWindow[] = [
  { key: '15m', label: '最近 15 分钟', ms: 15 * 60_000, interval: 'raw' },
  { key: '1h', label: '最近 1 小时', ms: 3_600_000, interval: 'raw' },
  { key: '6h', label: '最近 6 小时', ms: 6 * 3_600_000, interval: '5m' },
  { key: '24h', label: '最近 24 小时', ms: 24 * 3_600_000, interval: '1h' },
  { key: '7d', label: '最近 7 天', ms: 7 * 24 * 3_600_000, interval: '1d' },
]

export const INTERVAL_OPTIONS: { value: TelemetryInterval; label: string }[] = [
  { value: 'raw', label: 'raw（原始点，上限 10000）' },
  { value: '1m', label: '1m（分钟均值）' },
  { value: '5m', label: '5m（5 分钟均值）' },
  { value: '1h', label: '1h（小时均值）' },
  { value: '1d', label: '1d（日均值）' },
]
