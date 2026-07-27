// 与对接文档 3.2 / 规格 5.4 严格一一对应

// 客户端 → 服务端
export interface WsSubscribeFrame {
  type: 'subscribe' | 'unsubscribe'
  deviceIds: string[]
}

// 服务端 → 客户端
export interface WsTelemetryMsg {
  type: 'telemetry'
  deviceId: string
  ts: number
  metrics: Record<string, number>
}

export type WsAlertStatus = 'FIRING' | 'RESOLVED'

export interface WsAlertEvent {
  id: number // 注意：RESOLVED 推送可能为 0（v1 已知限制）
  ruleId?: number | null
  ruleName: string | null
  deviceId: string // 此处为 deviceKey
  status: WsAlertStatus
  value: number
  triggeredAt: number
}

export interface WsAlertMsg {
  type: 'alert'
  event: WsAlertEvent
}

export interface WsPresenceMsg {
  type: 'presence'
  deviceId: string
  online: boolean
}

export type WsServerMsg = WsTelemetryMsg | WsAlertMsg | WsPresenceMsg
