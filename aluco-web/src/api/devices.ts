import { http } from './http'
import type {
  CreateDeviceRequest,
  Device,
  DeviceCreated,
  DeviceState,
  Page,
  SetIntervalResponse,
  TelemetryInterval,
  TelemetryResponse,
} from '@/types/api'

/** ② 创建设备（token 仅此响应返回一次，前端必须弹窗展示） */
export async function createDevice(req: CreateDeviceRequest): Promise<DeviceCreated> {
  const { data } = await http.post<DeviceCreated>('/devices', req)
  return data
}

/** ③ 设备列表（keyword 模糊匹配 deviceKey/name，可空） */
export async function pageDevices(keyword: string, page: number, size: number): Promise<Page<Device>> {
  const { data } = await http.get<Page<Device>>('/devices', { params: { keyword: keyword || undefined, page, size } })
  return data
}

/** ④ 设备详情 */
export async function getDevice(deviceKey: string): Promise<Device> {
  const { data } = await http.get<Device>(`/devices/${encodeURIComponent(deviceKey)}`)
  return data
}

/** ⑤ 删除设备（204；state 级联删除，历史遥测/告警保留） */
export async function deleteDevice(deviceKey: string): Promise<void> {
  await http.delete(`/devices/${encodeURIComponent(deviceKey)}`)
}

/** ⑥ 设备实时状态 */
export async function getDeviceState(deviceKey: string): Promise<DeviceState> {
  const { data } = await http.get<DeviceState>(`/devices/${encodeURIComponent(deviceKey)}/state`)
  return data
}

export interface TelemetryResult {
  data: TelemetryResponse
  /** 响应头 X-Truncated: true 表示 raw 超 10000 点被截断 */
  truncated: boolean
}

/** ⑦ 历史遥测（降采样） */
export async function getTelemetry(
  deviceKey: string,
  metric: string,
  from: number,
  to: number,
  interval: TelemetryInterval,
): Promise<TelemetryResult> {
  const res = await http.get<TelemetryResponse>(`/devices/${encodeURIComponent(deviceKey)}/telemetry`, {
    params: { metric, from, to, interval },
  })
  const truncated = String(res.headers['x-truncated'] ?? 'false') === 'true'
  return { data: res.data, truncated }
}

/** ⑭ 下发上报频率（1~3600；成功仅表示命令已发到 broker，v1 无设备回执） */
export async function setReportInterval(deviceKey: string, intervalSec: number): Promise<SetIntervalResponse> {
  const { data } = await http.post<SetIntervalResponse>(
    `/devices/${encodeURIComponent(deviceKey)}/commands/set-interval`,
    { intervalSec },
  )
  return data
}
