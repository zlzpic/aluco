import { create } from 'zustand'
import type { WsAlertEvent } from '@/types/ws'

export interface AlertToast {
  key: number
  event: WsAlertEvent
}

interface AlertState {
  /**
   * 告警版本号：每次收到 WS alert 自增。
   * 告警页 / 总览页监听它重新拉一次 REST，保证与后端一致
   * （规避 RESOLVED 推送 event.id 可能为 0 的 v1 行为，不在前端做行级匹配）。
   */
  alertVersion: number
  /** 待弹出的全局 toast 队列（由 App 内的桥接组件消费，走 AntD App context） */
  toasts: AlertToast[]
  handleWsAlert: (event: WsAlertEvent) => void
  consumeToast: (key: number) => void
}

let toastSeq = 0

export const useAlertStore = create<AlertState>((set) => ({
  alertVersion: 0,
  toasts: [],
  handleWsAlert: (event) =>
    set((s) => ({
      alertVersion: s.alertVersion + 1,
      toasts: [...s.toasts, { key: ++toastSeq, event }],
    })),
  consumeToast: (key) => set((s) => ({ toasts: s.toasts.filter((t) => t.key !== key) })),
}))
