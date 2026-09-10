import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { NotificationItem } from '@/types'
import { getNotifications, markNotificationRead, markAllNotificationsRead, clearAllNotifications } from '@/api/notification'

const LS_READ_IDS = 'anticheat_notif_read_ids'

export const useNotificationStore = defineStore('notification', () => {
  // ---------- state ----------
  const list = ref<NotificationItem[]>([])
  const loaded = ref(false)
  const loading = ref(false)

  // ---------- hydrate read flag (兼容旧数据) ----------
  let readIds = new Set<string>()
  try {
    const raw = localStorage.getItem(LS_READ_IDS)
    if (raw) readIds = new Set(JSON.parse(raw) as string[])
  } catch { /* noop */ }

  function persistRead(): void {
    try {
      localStorage.setItem(LS_READ_IDS, JSON.stringify(Array.from(readIds)))
    } catch { /* noop */ }
  }

  function applyReadFlags(items: NotificationItem[]): NotificationItem[] {
    return items.map((n) => ({ ...n, read: n.read || readIds.has(n.id) }))
  }

  // ---------- getters ----------
  const unreadCount = computed<number>(() => list.value.filter((n) => !n.read).length)

  // ---------- actions ----------
  async function fetchList(): Promise<void> {
    loading.value = true
    try {
      // 优先使用真实 API，失败时返回空列表（不填充 mock 默认数据）
      try {
        const resp = await getNotifications({ page: 1, pageSize: 50 })
        if (resp && resp.code === 0 && resp.data && Array.isArray(resp.data.list)) {
          list.value = applyReadFlags(resp.data.list as NotificationItem[]).sort(
            (a, b) => (a.time < b.time ? 1 : -1)
          )
        } else {
          list.value = []
        }
      } catch {
        list.value = []
      }
      loaded.value = true
    } finally {
      loading.value = false
    }
  }

  async function markRead(id: string): Promise<void> {
    const item = list.value.find((n) => n.id === id)
    if (item && !item.read) {
      item.read = true
      readIds.add(id)
      persistRead()
      try { await markNotificationRead(id) } catch { /* noop */ }
    }
  }

  async function markAllRead(): Promise<void> {
    list.value.forEach((n) => {
      if (!n.read) {
        n.read = true
        readIds.add(n.id)
      }
    })
    persistRead()
    try { await markAllNotificationsRead() } catch { /* noop */ }
  }

  /** 对外: 新通知到达 (被 WS 消息驱动调用) */
  function pushNotification(n: NotificationItem): void {
    list.value.unshift(applyReadFlags([n])[0])
  }

  async function clearAll(): Promise<void> {
    list.value = []
    readIds = new Set()
    persistRead()
    try { await clearAllNotifications() } catch { /* noop */ }
  }

  return {
    // state
    list,
    loaded,
    loading,
    // getters
    unreadCount,
    // actions
    fetchList,
    markRead,
    markAllRead,
    pushNotification,
    clearAll
  }
})
