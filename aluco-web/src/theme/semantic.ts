import type { AlertStatus, RuleOp } from '@/types/api'

/** 规格 8.2 语义色：FIRING 红 / RESOLVED 灰 / ACKED 蓝 */
export const ALERT_STATUS_COLOR: Record<AlertStatus, string> = {
  FIRING: 'red',
  RESOLVED: 'default',
  ACKED: 'blue',
}

export const ALERT_STATUS_TEXT: Record<AlertStatus, string> = {
  FIRING: '告警中',
  RESOLVED: '已恢复',
  ACKED: '已确认',
}

/** op 枚举 → UI 符号（对接文档 3.1-⑧：显示 > >= < <= =） */
export const OP_TEXT: Record<RuleOp, string> = {
  GT: '>',
  GTE: '>=',
  LT: '<',
  LTE: '<=',
  EQ: '=',
}

export const OP_OPTIONS: { value: RuleOp; label: string }[] = [
  { value: 'GT', label: '>（大于）' },
  { value: 'GTE', label: '>=（大于等于）' },
  { value: 'LT', label: '<（小于）' },
  { value: 'LTE', label: '<=（小于等于）' },
  { value: 'EQ', label: '=（等于）' },
]
