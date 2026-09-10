import type { ApiResp, Page, Player } from '@/types'
import { request } from './request'

/**
 * 玩家相关 API：始终调用真实后端 /api/players，不再回退 mock 数据。
 */

export interface PlayerListQuery {
  page?: number
  pageSize?: number
  keyword?: string
  status?: Player['status'] | ''
  minScore?: number
  maxScore?: number
}

export async function getPlayerList(q: PlayerListQuery = {}): Promise<ApiResp<Page<Player>>> {
  const data = await request.get<Page<Player>>('/players', { params: q })
  return { code: 0, message: 'ok', data }
}

export async function getPlayerDetail(uuid: string): Promise<ApiResp<Player>> {
  const data = await request.get<Player>(`/players/${encodeURIComponent(uuid)}`)
  return { code: 0, message: 'ok', data }
}

export async function banPlayer(uuid: string, payload: { duration: string; reason?: string }): Promise<ApiResp<null>> {
  await request.post<null>(`/players/${encodeURIComponent(uuid)}/ban`, payload)
  return { code: 0, message: 'ok', data: null }
}

export async function kickPlayer(uuid: string, payload: { reason?: string } = {}): Promise<ApiResp<null>> {
  await request.post<null>('/players/kick', { uuid, ...payload })
  return { code: 0, message: 'ok', data: null }
}

/** mode: 'OBSERVER'(观察) | 'SPECTATOR' | 'SURVIVAL' | 'CREATIVE' | 'ADVENTURE' */
export async function setPlayerGameMode(uuid: string, mode: 'OBSERVER' | 'SPECTATOR' | 'SURVIVAL' | 'CREATIVE' | 'ADVENTURE'): Promise<ApiResp<{ mode: string }>> {
  const data = await request.post<{ mode: string }>('/players/gamemode', { uuid, mode })
  return { code: 0, message: 'ok', data }
}
