import axios, { AxiosError } from 'axios'
import type { InternalAxiosRequestConfig } from 'axios'
import { API_BASE } from '@/config'
import { useAuthStore } from '@/stores/authStore'
import { useMockStore } from '@/stores/mockStore'
import { liveClient } from '@/ws/liveClient'
import { mockAdapter } from '@/mock/mockServer'
import type { ApiErrorBody } from '@/types/api'

export const http = axios.create({ baseURL: API_BASE, timeout: 15_000 })

/** 由 App 内桥接组件注册，走 AntD App context 的 message，保证深色主题生效 */
let errorToast: (msg: string) => void = () => {}
export function registerErrorToast(fn: (msg: string) => void): void {
  errorToast = fn
}

declare module 'axios' {
  interface AxiosRequestConfig {
    /** 登录等场景：401 不触发强制登出跳转，由调用方自行提示 */
    _noAuthRedirect?: boolean
  }
}

http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = useAuthStore.getState().token
  if (token) config.headers.Authorization = `Bearer ${token}`
  // Mock 开关仅影响数据通道；接口路径、参数、信封完全一致
  if (useMockStore.getState().enabled) config.adapter = mockAdapter
  return config
})

http.interceptors.response.use(
  (res) => res,
  (error: AxiosError<ApiErrorBody>) => {
    const status = error.response?.status
    const message = error.response?.data?.message
    if (status === 401) {
      if (!error.config?._noAuthRedirect) {
        // 401 → 清 token、关 WS、跳登录（对接文档 3.0 / 3.4-4）
        liveClient.disconnect()
        useAuthStore.getState().clear()
        if (window.location.hash !== '#/login') window.location.hash = '#/login'
      }
      if (message) errorToast(message)
    } else if (status === 400 || status === 404 || status === 409) {
      // 409/404/400 → toast message
      errorToast(message ?? `请求失败（HTTP ${status}）`)
    } else if (!status) {
      errorToast('网络异常，无法连接后端服务（如在预览环境，可开启右上角 Mock 演示模式）')
    }
    return Promise.reject(error)
  },
)
