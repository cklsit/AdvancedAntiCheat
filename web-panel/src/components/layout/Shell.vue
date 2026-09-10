<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  LayoutDashboard, Users, FolderKanban, Map, PlaySquare, Sparkles, Settings,
  ClipboardList, Network, Menu, X
} from 'lucide-vue-next'
import { useGlobalStore } from '@/stores/global'
import { useNotificationStore } from '@/stores/notification'
import { getDashboardStats } from '@/api/dashboard'
import TopNav from '@/components/layout/TopNav.vue'
import Sidebar, { type SidebarNavEntry } from '@/components/layout/Sidebar.vue'
import NotificationPanel from '@/components/layout/NotificationPanel.vue'
import CommandPalette from '@/components/layout/CommandPalette.vue'
import type { NotificationItem } from '@/types'

const route = useRoute()
const globalStore = useGlobalStore()
const notifStore = useNotificationStore()

// 侧边栏导航
const navEntries: SidebarNavEntry[] = [
  { to: '/dashboard', name: 'Dashboard',  icon: LayoutDashboard, label: '总览' },
  { to: '/players',   name: 'Players',    icon: Users,           label: '玩家管理' },
  { to: '/cases',     name: 'Cases',      icon: FolderKanban,    label: '案件审理' },
  { to: '/live-map',  name: 'LiveMap',    icon: Map,             label: '实时地图' },
  { to: '/replay',    name: 'Replay',     icon: PlaySquare,      label: '违规回放' },
  { to: '/ai-lab',    name: 'AILab',      icon: Sparkles,        label: 'AI 实验室' },
  { to: '/config',    name: 'Config',     icon: Settings,        label: '系统配置' },
  { to: '/audit',     name: 'Audit',      icon: ClipboardList,   label: '审计日志' },
  { to: '/alliance',  name: 'Alliance',   icon: Network,         label: '联盟图谱' }
]

// 面板 visible
const showNotifPanel = ref(false)
const showCommandPalette = ref(false)
let statsTimer: ReturnType<typeof setInterval> | null = null

/** 拉取 dashboard/stats，更新顶部栏全局在线数与风险概览 */
async function refreshTopStats(): Promise<void> {
  try {
    const resp = await getDashboardStats()
    const ds = resp.data
    if (!ds) return
    const triggers: string[] = []
    // 从风险分布计算触发因素
    const high = ds.riskDistribution?.find(r => r.level === 2 /* HIGH */)?.count ?? 0
    const extreme = ds.riskDistribution?.find(r => r.level === 3 /* EXTREME */)?.count ?? 0
    if (high + extreme > 0) triggers.push(`${high + extreme} 个高风险玩家在线`)
    // 简易风险分：高风险占比 * 60，上限 100
    const total = ds.totalPlayers > 0 ? ds.totalPlayers : 1
    const ratio = Math.min(1, (high * 2 + extreme * 4) / Math.max(ds.onlinePlayers, 1))
    const score = Math.min(100, Math.round(ratio * 60 + (ds.todayViolations > 50 ? 10 : 0)))
    globalStore.setOnlineStats(ds.onlinePlayers ?? 0, score, triggers)
  } catch { /* noop，保持旧值 */ }
}

onMounted(() => {
  notifStore.fetchList().catch(() => void 0)
  // 建立 WS 连接
  try { globalStore.connectWs() } catch { /* noop */ }
  // 拉取顶部栏全局概览，并每 30 秒刷新
  refreshTopStats()
  statsTimer = setInterval(refreshTopStats, 30_000)

  // 全局 Ctrl+K 监听
  window.addEventListener('keydown', handleGlobalKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleGlobalKeydown)
  if (statsTimer) { clearInterval(statsTimer); statsTimer = null }
})

function handleGlobalKeydown(e: KeyboardEvent): void {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    showCommandPalette.value = true
  }
}

const collapsed = computed(() => globalStore.sidebarCollapsed)
const wsState = computed(() => globalStore.wsState)
const wsLatency = computed(() => globalStore.wsLatency)
const wsStateLabel = computed(() => {
  if (wsState.value === 'connected') return 'WS 已连接'
  if (wsState.value === 'reconnecting') return '重连中…'
  return 'WS 未连接'
})

const activeName = computed(() => (route.name as string) || '')

function toggleSidebar(): void {
  globalStore.toggleSidebar()
}

function handleOpenCommandPalette(): void {
  showCommandPalette.value = true
}

function handleOpenNotifPanel(): void {
  showNotifPanel.value = !showNotifPanel.value
}

function handleGotoNotif(item: NotificationItem): void {
  notifStore.markRead(item.id)
  // 若有玩家，跳到玩家详情
  if (item.playerUuid) {
    // 目前由 router 处理，这里可通过 emit jump 处理
  }
  showNotifPanel.value = false
}

function handleMarkAllRead(): void {
  notifStore.markAllRead()
}
</script>

<template>
  <div class="h-full w-full flex bg-bg-base text-text-primary">
    <!-- 侧边栏 -->
    <Sidebar
      :collapsed="collapsed"
      :nav-entries="navEntries"
      :active-name="activeName"
      :ws-state="wsState"
      :ws-latency="wsLatency"
      :ws-label="wsStateLabel"
      @toggle-collapse="toggleSidebar"
    />

    <!-- 主区域 -->
    <div class="flex-1 flex flex-col min-w-0 relative">
      <!-- 顶栏 -->
      <TopNav
        @open-command-palette="handleOpenCommandPalette"
        @open-notif-panel="handleOpenNotifPanel"
      />

      <!-- 移动端折叠按钮（仅 < 1024px 显示，位置下移避免遮挡第一项导航） -->
      <button
        v-if="collapsed"
        type="button"
        class="btn btn-ghost btn-icon lg:hidden fixed top-[92px] left-3 z-40 w-9 h-9 bg-bg-card/90 backdrop-blur-sm shadow-md border border-border-line rounded-btn"
        @click="toggleSidebar"
        aria-label="展开菜单"
      >
        <component :is="Menu" :size="18" />
      </button>

      <!-- 内容区 -->
      <main class="flex-1 min-w-0 overflow-auto pt-[56px]">
        <div class="p-3 md:p-4 md:p-5 min-h-full">
          <router-view v-slot="{ Component }">
            <Transition name="fade" mode="out-in">
              <component :is="Component" />
            </Transition>
          </router-view>
        </div>
      </main>
    </div>

    <!-- 通知面板 -->
    <NotificationPanel
      :visible="showNotifPanel"
      :list="notifStore.list"
      :unread-count="notifStore.unreadCount"
      @goto="handleGotoNotif"
      @mark-all-read="handleMarkAllRead"
      @close="showNotifPanel = false"
    />

    <!-- 命令面板 -->
    <CommandPalette
      v-model:visible="showCommandPalette"
    />

    <!-- 全局点击关闭通知面板 -->
    <div
      v-if="showNotifPanel"
      class="fixed inset-0 z-[44]"
      @click="showNotifPanel = false"
    />
  </div>
</template>
