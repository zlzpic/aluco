import dayjs from 'dayjs'

/** API 层时间一律 epoch 毫秒，可空为 null */
export function fmtTime(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return '—'
  return dayjs(ms).format('YYYY-MM-DD HH:mm:ss')
}

export function fmtClock(ms: number): string {
  return dayjs(ms).format('HH:mm:ss')
}

export function fmtVal(v: number | null | undefined): string {
  if (v === null || v === undefined) return '—'
  return Number.isInteger(v) ? String(v) : v.toFixed(2)
}
