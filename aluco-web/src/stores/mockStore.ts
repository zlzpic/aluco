import { create } from 'zustand'
import { ENV_MOCK } from '@/config'

const MOCK_KEY = 'aluco_mock'

interface MockState {
  /** Mock 演示模式：默认关（构建产物默认真实后端模式），仅作开发/预览开关 */
  enabled: boolean
  /** 切换后需要整页刷新，保证 REST/WS 状态干净重建 */
  setEnabled: (enabled: boolean) => void
}

function loadInitial(): boolean {
  const stored = localStorage.getItem(MOCK_KEY)
  if (stored === null) return ENV_MOCK
  return stored === '1'
}

export const useMockStore = create<MockState>((set) => ({
  enabled: loadInitial(),
  setEnabled: (enabled) => {
    localStorage.setItem(MOCK_KEY, enabled ? '1' : '0')
    set({ enabled })
  },
}))
