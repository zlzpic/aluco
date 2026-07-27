// 规格 8.1：API baseURL 与 WS 地址走 .env
export const API_BASE: string = import.meta.env.VITE_API_BASE ?? '/api/v1'
export const WS_PATH: string = import.meta.env.VITE_WS_URL ?? '/ws/live'
export const ENV_MOCK: boolean = String(import.meta.env.VITE_MOCK ?? 'false') === 'true'
