import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import { WSClient, type WSState } from '@/utils/ws'

const LS_SIDEBAR = 'anticheat_sidebar_collapsed'

export type WSStatus = WSState

export const useGlobalStore = defineStore('global', () => {
  // ---------- state ----------
  const sidebarCollapsed = ref<boolean>(false)
  const serverName = ref<string>('AntiCheat Command Center')
  const wsState = ref<WSStatus>('disconnected')
  const wsLatency = ref<number>(0)
  // 顶部栏全局在线数与风险概览（由 Shell 定时拉取 dashboard/stats 更新）
  const onlinePlayers = ref<number>(0)
  const riskScore = ref<number>(0)
  const riskTriggers = ref<string[]>([])

  // hydrate sidebar
  try {
    const v = localStorage.getItem(LS_SIDEBAR)
    if (v !== null) sidebarCollapsed.value = v === '1'
  } catch { /* noop */ }

  watch(sidebarCollapsed, (v) => {
    try { localStorage.setItem(LS_SIDEBAR, v ? '1' : '0') } catch { /* noop */ }
  })

  // ---------- WS 实例 (延迟创建) ----------
  let wsClient: WSClient | null = null

  const wsConnected = computed<boolean>(() => wsState.value === 'connected')

  function toggleSidebar(): void {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  function setSidebarCollapsed(v: boolean): void {
    sidebarCollapsed.value = v
  }

  function setServerName(name: string): void {
    serverName.value = name
  }

  /** 由 Shell 拉取 dashboard/stats 后更新顶部栏全局概览 */
  function setOnlineStats(online: number, risk: number, triggers: string[]): void {
    onlinePlayers.value = online
    riskScore.value = risk
    riskTriggers.value = triggers
  }

  /** 懒加载 WebSocket 连接 */
  function ensureWs(url?: string): WSClient {
    if (wsClient) return wsClient
    // 推导 ws url：生产环境走同源 /ws?token=xxx；开发环境走 VITE_WS_URL 或本地默认
    let wsUrl = url
    if (!wsUrl) {
      if (import.meta.env.PROD) {
        const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
        let token = ''
        try { token = localStorage.getItem('anticheat_token') || '' } catch { /* noop */ }
        const qs = token ? `?token=${encodeURIComponent(token)}` : ''
        wsUrl = `${proto}//${location.host}/ws${qs}`
      } else {
        wsUrl = import.meta.env.VITE_WS_URL || 'ws://localhost:8080/ws'
      }
    }
    wsClient = new WSClient(wsUrl)
    wsClient.onStateChange = (state: WSState, latencyMs?: number) => {
      wsState.value = state
      if (typeof latencyMs === 'number') wsLatency.value = latencyMs
    }
    // 把 alert 推送派发为 window 事件，供 DashboardView / NotificationPanel 等监听
    wsClient.onmessage = (data: unknown) => {
      if (data && typeof data === 'object' && (data as { type?: string }).type === 'alert') {
        const payload = (data as { payload?: unknown }).payload
        if (payload && typeof window !== 'undefined') {
          window.dispatchEvent(new CustomEvent('app:alert', { detail: payload }))
        }
      }
    }
    return wsClient
  }

  function connectWs(url?: string): void {
    ensureWs(url).connect()
  }

  function disconnectWs(): void {
    if (wsClient) {
      wsClient.close()
      wsClient = null
      wsState.value = 'disconnected'
      wsLatency.value = 0
    }
  }

  function getWsClient(): WSClient | null {
    return wsClient
  }

  return {
    // state
    sidebarCollapsed,
    serverName,
    wsState,
    wsLatency,
    onlinePlayers,
    riskScore,
    riskTriggers,
    // getters
    wsConnected,
    // actions
    toggleSidebar,
    setSidebarCollapsed,
    setServerName,
    setOnlineStats,
    connectWs,
    disconnectWs,
    getWsClient,
    ensureWs
  }
})
