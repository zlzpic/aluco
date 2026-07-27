// 与对接文档 3.1 / 规格 5.5 严格一一对应；不含文档外字段

export interface Page<T> {
  list: T[]
  total: number
  page: number
  size: number
}

export interface ApiErrorBody {
  code: string
  message: string
}

// ① 登录
export interface LoginRequest {
  username: string
  password: string
}
export interface LoginResponse {
  token: string
  expiresAt: number
}

// 设备
export interface Device {
  id: number
  deviceKey: string
  name: string
  siteId: string
  createdAt: number
  online: boolean
  lastSeenAt: number | null
}
// ② 创建设备响应比列表元素多一个一次性 token
export interface DeviceCreated extends Device {
  token: string
}
export interface CreateDeviceRequest {
  deviceKey: string
  name: string
  siteId: string
}

// ⑥ 设备实时状态
export interface DeviceState {
  deviceKey: string
  metrics: Record<string, number>
  online: boolean
  lastSeenAt: number | null
}

// ⑦ 历史遥测
export type TelemetryInterval = 'raw' | '1m' | '5m' | '1h' | '1d'
export interface TelemetryPoint {
  ts: number
  val: number
}
export interface TelemetryResponse {
  metric: string
  points: TelemetryPoint[]
}

// 规则（⑧⑨⑩⑪）。注意：请求字段 threshold，响应字段 thresholdVal
export type RuleOp = 'GT' | 'GTE' | 'LT' | 'LTE' | 'EQ'
export interface Rule {
  id: number
  name: string
  metric: string
  op: RuleOp
  thresholdVal: number
  deviceKey: string | null
  enabled: boolean
  createdAt: number
  updatedAt: number
}
export interface CreateRuleRequest {
  name: string
  metric: string
  op: RuleOp
  threshold: number
  deviceKey: string | null
}

// 告警（⑫⑬）
export type AlertStatus = 'FIRING' | 'RESOLVED' | 'ACKED'
export interface AlertEvent {
  id: number
  ruleId: number
  deviceId: number
  status: AlertStatus
  triggerVal: number
  triggeredAt: number
  resolvedAt: number | null
  ackedAt: number | null
  ruleName: string | null // v1 限制：列表接口可能为 null，UI 用 ruleId 兜底
  deviceKey: string
}

// ⑭ 下发上报频率
export interface SetIntervalRequest {
  intervalSec: number
}
export interface SetIntervalResponse {
  cmdId: string
}
