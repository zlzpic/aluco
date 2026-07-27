import { create } from 'zustand'

interface DevicePresenceState {
  /** WS presence 广播的在线状态覆盖表：deviceKey → online */
  presence: Record<string, boolean>
  applyPresence: (deviceKey: string, online: boolean) => void
}

export const useDeviceStore = create<DevicePresenceState>((set) => ({
  presence: {},
  applyPresence: (deviceKey, online) =>
    set((s) => ({ presence: { ...s.presence, [deviceKey]: online } })),
}))
