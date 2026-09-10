import { request } from './request'

export interface NotificationItem {
  id: string
  level: 'info' | 'success' | 'warning' | 'danger'
  title: string
  content: string
  time: string
  read: boolean
  category: 'system' | 'player' | 'case'
  playerName: string | null
  playerUuid: string | null
  caseId?: string
  extra?: Record<string, any>
}

export interface NotificationQuery {
  page?: number
  pageSize?: number
  category?: string
  level?: string
  unreadOnly?: boolean
  keyword?: string
}

export interface NotificationRespData {
  list: NotificationItem[]
  total: number
  page: number
  pageSize: number
  unreadCount?: number
}

export interface ApiResp<T> {
  code: number
  message: string
  data?: T
}

export async function getNotifications(params: NotificationQuery): Promise<ApiResp<NotificationRespData>> {
  const qs: Record<string, string> = {}
  if (params.page !== undefined) qs.page = String(params.page)
  if (params.pageSize !== undefined) qs.pageSize = String(params.pageSize)
  if (params.category) qs.category = params.category
  if (params.level) qs.level = params.level
  if (params.unreadOnly !== undefined) qs.unreadOnly = String(params.unreadOnly)
  if (params.keyword) qs.keyword = params.keyword
  const search = new URLSearchParams(qs).toString()
  const data = await request.get<NotificationRespData>(`/notifications${search ? '?' + search : ''}`)
  return { code: 0, message: 'ok', data }
}

export async function markNotificationRead(id: string): Promise<ApiResp<null>> {
  await request.post(`/notifications/${id}/read`)
  return { code: 0, message: 'ok' }
}

export async function markAllNotificationsRead(): Promise<ApiResp<null>> {
  await request.post('/notifications/read-all')
  return { code: 0, message: 'ok' }
}

export async function clearAllNotifications(): Promise<ApiResp<null>> {
  await request.delete('/notifications')
  return { code: 0, message: 'ok' }
}
