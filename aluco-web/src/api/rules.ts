import { http } from './http'
import type { CreateRuleRequest, Page, Rule } from '@/types/api'

/** ⑧ 规则列表 */
export async function pageRules(page: number, size: number): Promise<Page<Rule>> {
  const { data } = await http.get<Page<Rule>>('/rules', { params: { page, size } })
  return data
}

/**
 * ⑨ 创建规则。
 * 契约差异在此层单点处理：请求字段 threshold，响应字段 thresholdVal，
 * 页面层不感知（对接文档 3.4-1）。
 */
export async function createRule(req: CreateRuleRequest): Promise<Rule> {
  const { data } = await http.post<Rule>('/rules', req)
  return data
}

/** ⑩ 启停规则 */
export async function setRuleEnabled(id: number, enabled: boolean): Promise<Rule> {
  const { data } = await http.patch<Rule>(`/rules/${id}/enabled`, { enabled })
  return data
}

/** ⑪ 删除规则（其下 FIRING 事件自动置 RESOLVED） */
export async function deleteRule(id: number): Promise<void> {
  await http.delete(`/rules/${id}`)
}
