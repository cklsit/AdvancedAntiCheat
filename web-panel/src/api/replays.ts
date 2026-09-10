import type {
  ReplayPlayer,
  ReplaySession,
  ReplayDelta,
  ReplayArchive,
  ServerClock
} from '@/types/replay'
import { request } from './request'
import service from './request'

/**
 * 回放相关 API：对接后端 /api/replays/* 完整重构接口。
 * axios 默认自动解压 gzip，无需手动处理。
 */

// ==================== 实时玩家 ====================
/** 获取在线玩家列表（持续录制中的玩家） */
export async function getReplayPlayers(): Promise<ReplayPlayer[]> {
  return request.get<ReplayPlayer[]>('/replays/players')
}

/** 获取指定玩家的完整轨迹快照（gzip 压缩） */
export async function getReplaySession(uuid: string): Promise<ReplaySession> {
  return request.get<ReplaySession>(`/replays/players/${encodeURIComponent(uuid)}/session`)
}

/** 增量拉取：sinceMs 之后的新轨迹点和新违规 */
export async function getReplayDelta(uuid: string, sinceMs: number): Promise<ReplayDelta> {
  return request.get<ReplayDelta>(
    `/replays/players/${encodeURIComponent(uuid)}/delta`,
    { params: { sinceMs } }
  )
}

// ==================== 历史存档 ====================
/** 获取历史存档列表 */
export async function getReplayArchives(): Promise<ReplayArchive[]> {
  return request.get<ReplayArchive[]>('/replays/archives')
}

/**
 * 下载历史存档文件（.zip 二进制流）
 * 返回 Blob，前端用 JSZip 解压后读取 replay.json。
 */
export async function downloadReplayArchiveBlob(filename: string): Promise<Blob> {
  const resp = await service.get(
    `/replays/archives/${encodeURIComponent(filename)}`,
    { responseType: 'blob' }
  )
  // axios 拦截器对二进制响应不做解包，resp 就是 Blob
  return resp as unknown as Blob
}

/** 删除指定历史存档 */
export async function deleteReplayArchive(filename: string): Promise<null> {
  return request.delete<null>(`/replays/archives/${encodeURIComponent(filename)}`)
}

// ==================== 时钟校正（§3.3 时间对齐的前提） ====================

/**
 * 拉取服务端墙钟。调用方应按三点法估算时钟偏移：
 *   t0 = now() → 请求 → t1 = now()
 *   offset ≈ serverTimeMs + (t1 - t0) / 2 - t1
 * 之后 serverNow() = Date.now() + offset。
 */
export async function getServerClock(): Promise<ServerClock> {
  return request.get<ServerClock>('/replay/clock')
}
