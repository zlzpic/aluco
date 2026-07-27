import { http } from './http'
import type { LoginRequest, LoginResponse } from '@/types/api'

/** ① 登录 */
export async function login(req: LoginRequest): Promise<LoginResponse> {
  const { data } = await http.post<LoginResponse>('/auth/login', req, { _noAuthRedirect: true })
  return data
}
