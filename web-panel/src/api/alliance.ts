import type { ApiResp, AllianceGraph } from '@/types'
import { request } from './request'

/**
 * 获取联盟关联图谱（真实数据：在线玩家间的行为交互 / 同 IP / 疑似小号 / 团伙簇）。
 * 数据来自后端 SocialGraph + AssociationDetector，无关联时返回空图谱。
 */
export async function getAllianceGraph(): Promise<ApiResp<AllianceGraph>> {
  const data = await request.get<AllianceGraph>('/alliance/graph')
  return { code: 0, message: 'ok', data }
}
