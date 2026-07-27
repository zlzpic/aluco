import { WS_PATH } from '@/config'
import { useAuthStore } from '@/stores/authStore'
import { useMockStore } from '@/stores/mockStore'
import { useTelemetryStore } from '@/stores/telemetryStore'
import { useDeviceStore } from '@/stores/deviceStore'
import { useAlertStore } from '@/stores/alertStore'
import { MockLiveSocket } from '@/mock/mockWs'
import type { WsServerMsg, WsSubscribeFrame } from '@/types/ws'

type SocketLike = WebSocket | MockLiveSocket

/**
 * WebSocket 单例连接管理器（规格 8.3）：
 * - 登录后建立、登出/401 关闭；断线指数退避重连（1s 起步，封顶 30s）；
 * - 重连握手永远读取最新 token；
 * - 订阅增量管理，重连成功后自动恢复全部订阅；
 * - 服务端 30s ping 帧由浏览器 WS 自动回 pong，无需处理。
 */
class LiveClient {
  private socket: SocketLike | null = null
  private subscriptions = new Set<string>()
  private retry = 0
  private timer: number | null = null
  private manualClose = false

  connect(): void {
    const token = useAuthStore.getState().token
    if (!token) return
    this.manualClose = false
    this.cleanupSocket()

    if (useMockStore.getState().enabled) {
      const mock = new MockLiveSocket()
      mock.onopen = () => this.handleOpen()
      mock.onmessage = (msg) => this.dispatch(msg)
      mock.onclose = () => this.handleClose()
      this.socket = mock
      mock.open()
      return
    }

    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const ws = new WebSocket(
      `${proto}://${window.location.host}${WS_PATH}?token=${encodeURIComponent(token)}`,
    )
    ws.onopen = () => this.handleOpen()
    ws.onmessage = (e) => {
      try {
        this.dispatch(JSON.parse(e.data as string) as WsServerMsg)
      } catch {
        // 非 JSON 帧忽略
      }
    }
    ws.onclose = () => this.handleClose()
    ws.onerror = () => {
      try {
        ws.close()
      } catch {
        /* noop */
      }
    }
    this.socket = ws
  }

  disconnect(): void {
    this.manualClose = true
    this.retry = 0
    if (this.timer !== null) {
      window.clearTimeout(this.timer)
      this.timer = null
    }
    this.cleanupSocket()
  }

  subscribe(deviceIds: string[]): void {
    const fresh = deviceIds.filter((id) => !this.subscriptions.has(id))
    deviceIds.forEach((id) => this.subscriptions.add(id))
    if (fresh.length) this.send({ type: 'subscribe', deviceIds: fresh })
  }

  unsubscribe(deviceIds: string[]): void {
    const held = deviceIds.filter((id) => this.subscriptions.delete(id))
    if (held.length) this.send({ type: 'unsubscribe', deviceIds: held })
  }

  private handleOpen(): void {
    this.retry = 0
    // 重连成功后恢复全部订阅
    if (this.subscriptions.size) {
      this.send({ type: 'subscribe', deviceIds: [...this.subscriptions] })
    }
  }

  private handleClose(): void {
    if (this.manualClose) return
    const delay = Math.min(30_000, 1_000 * 2 ** this.retry)
    this.retry += 1
    if (this.timer !== null) window.clearTimeout(this.timer)
    this.timer = window.setTimeout(() => this.connect(), delay)
  }

  private cleanupSocket(): void {
    if (!this.socket) return
    try {
      this.socket.close()
    } catch {
      /* noop */
    }
    this.socket = null
  }

  private send(frame: WsSubscribeFrame): void {
    const raw = JSON.stringify(frame)
    if (this.socket instanceof WebSocket) {
      if (this.socket.readyState === WebSocket.OPEN) this.socket.send(raw)
    } else if (this.socket) {
      this.socket.send(raw)
    }
  }

  private dispatch(msg: WsServerMsg): void {
    switch (msg.type) {
      case 'telemetry':
        useTelemetryStore.getState().ingest(msg.deviceId, msg.ts, msg.metrics)
        break
      case 'presence':
        useDeviceStore.getState().applyPresence(msg.deviceId, msg.online)
        useTelemetryStore.getState().setOnline(msg.deviceId, msg.online)
        break
      case 'alert':
        useAlertStore.getState().handleWsAlert(msg.event)
        break
    }
  }
}

export const liveClient = new LiveClient()
