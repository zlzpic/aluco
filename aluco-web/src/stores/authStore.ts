import { create } from 'zustand'

const TOKEN_KEY = 'aluco_token'
const EXPIRES_KEY = 'aluco_token_expires_at'

interface AuthState {
  token: string | null
  expiresAt: number | null
  setAuth: (token: string, expiresAt: number) => void
  clear: () => void
}

function loadInitial(): { token: string | null; expiresAt: number | null } {
  const token = localStorage.getItem(TOKEN_KEY)
  const expiresAt = Number(localStorage.getItem(EXPIRES_KEY) ?? '0') || null
  // token 有效期 24h；过期即视为未登录
  if (token && expiresAt && expiresAt > Date.now()) return { token, expiresAt }
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(EXPIRES_KEY)
  return { token: null, expiresAt: null }
}

export const useAuthStore = create<AuthState>((set) => ({
  ...loadInitial(),
  setAuth: (token, expiresAt) => {
    localStorage.setItem(TOKEN_KEY, token)
    localStorage.setItem(EXPIRES_KEY, String(expiresAt))
    set({ token, expiresAt })
  },
  clear: () => {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(EXPIRES_KEY)
    set({ token: null, expiresAt: null })
  },
}))
