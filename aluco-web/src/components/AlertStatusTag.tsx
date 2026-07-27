import { Tag } from 'antd'
import { ALERT_STATUS_COLOR, ALERT_STATUS_TEXT } from '@/theme/semantic'
import type { AlertStatus } from '@/types/api'

/** FIRING 红 / RESOLVED 灰 / ACKED 蓝（规格 8.2 语义色） */
export function AlertStatusTag({ status }: { status: AlertStatus }) {
  return <Tag color={ALERT_STATUS_COLOR[status]}>{ALERT_STATUS_TEXT[status]}</Tag>
}
