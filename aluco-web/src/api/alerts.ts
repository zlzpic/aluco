import { http } from './http'
import type { AlertEvent, AlertStatus, Page } from '@/types/api'

export interface AlertQuery {
  status?: AlertStatus
  deviceKey?: string
  page: number
  size: number
}

/** ⑫ 告警列表（按 id 倒序，最新在前） */
export async function pageAlerts(query: AlertQuery): Promise<Page<AlertEvent>> {
  const { data } = await http.get<Page<AlertEvent>>('/alerts', {
    params: {
      status: query.status || undefined,
      deviceKey: query.deviceKey || undefined,
      page: query.page,
      size: query.size,
    },
  })
  return data
}

/** ⑬ 确认告警（仅 FIRING 可 ack，否则 409 ALERT_NOT_FIRING） */
export async function ackAlert(id: number): Promise<AlertEvent> {
  const { data } = await http.post<AlertEvent>(`/alerts/${id}/ack`)
  return data
}
