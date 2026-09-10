import type { ApiResp } from '@/types'
import { request } from './request'

export interface MetaInfo {
  pluginVersion: string
  pluginName: string
  panelVersion: string
  mcVersion: string
  onlinePlayers: number
  serverName: string
}

export async function getMeta(): Promise<ApiResp<MetaInfo>> {
  const data = await request.get<MetaInfo>('/meta')
  return { code: 0, message: 'ok', data }
}
