import { create } from 'zustand'
import type { DeviceState } from '@/types/api'

export interface LivePoint {
  ts: number
  val: number
}

export interface DeviceLive {
  /** 各指标实时滚动序列（每指标保留最近 200 点） */
  points: Record<string, LivePoint[]>
  /** 已见过的 metrics key 并集（指标选择器数据源） */
  seenMetrics: string[]
  /** 各指标当前值 */
  current: Record<string, number>
  online: boolean | null
  lastSeenAt: number | null
}

const MAX_LIVE_POINTS = 200
const RATE_WINDOW_MS = 10_000

interface TelemetryState {
  byDevice: Record<string, DeviceLive>
  /** 最近 10s 内收到的遥测消息时间戳（用于总览页接入速率） */
  msgTimestamps: number[]
  /** 进入详情页先用 GET /state 快照播种，再等 WS 推送（对接文档 3.4-3：不自己造数据） */
  seedSnapshot: (state: DeviceState) => void
  ingest: (deviceKey: string, ts: number, metrics: Record<string, number>) => void
  setOnline: (deviceKey: string, online: boolean) => void
  clearDevice: (deviceKey: string) => void
}

function emptyLive(): DeviceLive {
  return { points: {}, seenMetrics: [], current: {}, online: null, lastSeenAt: null }
}

export const useTelemetryStore = create<TelemetryState>((set) => ({
  byDevice: {},
  msgTimestamps: [],

  seedSnapshot: (state) =>
    set((s) => {
      const prev = s.byDevice[state.deviceKey] ?? emptyLive()
      const seenMetrics = Array.from(new Set([...prev.seenMetrics, ...Object.keys(state.metrics)]))
      return {
        byDevice: {
          ...s.byDevice,
          [state.deviceKey]: {
            ...prev,
            seenMetrics,
            current: { ...prev.current, ...state.metrics },
            online: state.online,
            lastSeenAt: state.lastSeenAt,
          },
        },
      }
    }),

  ingest: (deviceKey, ts, metrics) =>
    set((s) => {
      const prev = s.byDevice[deviceKey] ?? emptyLive()
      const points: Record<string, LivePoint[]> = { ...prev.points }
      const current: Record<string, number> = { ...prev.current }
      let seenMetrics = prev.seenMetrics
      for (const [metric, val] of Object.entries(metrics)) {
        const arr = points[metric] ? [...points[metric], { ts, val }] : [{ ts, val }]
        if (arr.length > MAX_LIVE_POINTS) arr.splice(0, arr.length - MAX_LIVE_POINTS)
        points[metric] = arr
        current[metric] = val
        if (!seenMetrics.includes(metric)) seenMetrics = [...seenMetrics, metric]
      }
      const now = Date.now()
      const msgTimestamps = [...s.msgTimestamps, now].filter((t) => now - t <= RATE_WINDOW_MS)
      return {
        byDevice: {
          ...s.byDevice,
          [deviceKey]: { points, seenMetrics, current, online: true, lastSeenAt: ts },
        },
        msgTimestamps,
      }
    }),

  setOnline: (deviceKey, online) =>
    set((s) => {
      const prev = s.byDevice[deviceKey] ?? emptyLive()
      return { byDevice: { ...s.byDevice, [deviceKey]: { ...prev, online } } }
    }),

  clearDevice: (deviceKey) =>
    set((s) => {
      const next = { ...s.byDevice }
      delete next[deviceKey]
      return { byDevice: next }
    }),
}))

/** 接入速率（条/秒）：非响应式读取，组件内用定时器轮询即可 */
export function currentIngestRate(): number {
  const now = Date.now()
  const recent = useTelemetryStore.getState().msgTimestamps.filter((t) => now - t <= 5_000)
  return recent.length / 5
}
