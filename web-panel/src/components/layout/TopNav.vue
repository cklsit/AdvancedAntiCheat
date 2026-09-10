<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ShieldAlert, Bell, Search, LogOut, User, Server
} from 'lucide-vue-next'
import { useGlobalStore } from '@/stores/global'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notification'
import StatusDot from '@/components/common/StatusDot.vue'
import RiskIndicator from '@/components/common/RiskIndicator.vue'

const router = useRouter()
const route = useRoute()
const globalStore = useGlobalStore()
const authStore = useAuthStore()
const notifStore = useNotificationStore()

const emit = defineEmits<{
  openCommandPalette: []
  openNotifPanel: []
}>()

const showUserMenu = ref(false)
const searchFocused = ref(false)
const currentTime = ref(new Date())
let timeTimer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  notifStore.fetchList().catch(() => void 0)
  timeTimer = setInterval(() => (currentTime.value = new Date()), 1_000)
  // 监听全局点击关闭用户菜单
  document.addEventListener('click', handleGlobalClick)
})

onBeforeUnmount(() => {
  if (timeTimer) clearInterval(timeTimer)
  document.removeEventListener('click', handleGlobalClick)
})

function handleGlobalClick(e: MouseEvent): void {
  const target = e.target as HTMLElement
  if (!target.closest('[data-user-menu]')) {
    showUserMenu.value = false
  }
}

// 在线玩家数、综合风险分、触发因素：均来自全局 store（由 Shell 定时拉取 dashboard/stats 更新）
const onlineCount = computed(() => globalStore.onlinePlayers)
const riskScore = computed(() => globalStore.riskScore)
const riskTriggers = computed(() => globalStore.riskTriggers)

const formattedTime = computed(() => {
  const d = currentTime.value
  const pad = (n: number) => n.toString().padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
})

function goHome(): void {
  router.push('/dashboard').catch(() => void 0)
}

function goPlayers(): void {
  router.push('/players').catch(() => void 0)
}

async function handleLogout(): Promise<void> {
  await authStore.logout()
  router.replace('/login').catch(() => void 0)
}

function handleSearchClick(): void {
  emit('openCommandPalette')
}

function handleBellClick(): void {
  emit('openNotifPanel')
}

function onSearchKeydown(e: KeyboardEvent): void {
  if (e.key === 'Enter') {
    e.preventDefault()
    emit('openCommandPalette')
  }
}
</script>

<template>
  <header
    class="h-[56px] fixed top-0 left-0 right-0 z-40 bg-bg-card border-b border-border-line flex items-center px-4 gap-3 shrink-0"
  >
    <!-- 左侧：Logo + 服名 -->
    <div
      class="flex items-center gap-2 cursor-pointer shrink-0"
      @click="goHome"
    >
      <ShieldAlert :size="22" class="text-accent-cyan shrink-0" :stroke-width="2.2" />
      <span class="text-[14px] font-semibold text-text-primary truncate leading-tight hidden sm:block">
        {{ globalStore.serverName }}
      </span>
    </div>

    <!-- 中部：在线数 pill + RiskIndicator -->
    <div class="flex items-center gap-3 ml-4 md:ml-6 shrink-0">
      <!-- 在线数 pill -->
      <button
        type="button"
        class="inline-flex items-center gap-2 h-9 px-3 rounded-btn border border-border-line bg-bg-base hover:bg-bg-hover transition-colors"
        @click="goPlayers"
      >
        <StatusDot status="online" size="sm" />
        <span class="text-[13px] text-text-primary whitespace-nowrap">在线 {{ onlineCount }}</span>
      </button>

      <!-- 风险指示器 -->
      <RiskIndicator :score="riskScore" :triggers="riskTriggers" />
    </div>

    <!-- 右侧容器 -->
    <div class="ml-auto flex items-center gap-2 md:gap-3 shrink-0">
      <!-- 搜索框 -->
      <div
        class="relative flex items-center gap-2 h-9 rounded-btn border border-border-line bg-bg-base transition-[width] duration-200 overflow-hidden"
        :class="searchFocused ? 'w-[500px]' : 'w-[300px]'"
      >
        <Search :size="16" class="ml-3 shrink-0 text-text-secondary" />
        <input
          type="text"
          readonly
          class="flex-1 bg-transparent outline-none text-sm text-text-primary placeholder:text-text-muted cursor-pointer"
          placeholder="搜索... Ctrl+K"
          @focus="searchFocused = true"
          @blur="searchFocused = false"
          @click="handleSearchClick"
          @keydown="onSearchKeydown"
        />
      </div>

      <!-- Bell 图标 + 未读 badge -->
      <div class="relative shrink-0">
        <button
          type="button"
          class="btn btn-ghost btn-icon relative"
          @click="handleBellClick"
        >
          <Bell :size="18" class="shrink-0" />
          <span
            v-if="notifStore.unreadCount > 0"
            class="badge-num absolute -top-0.5 -right-0.5"
          >
            {{ notifStore.unreadCount > 99 ? '99+' : notifStore.unreadCount }}
          </span>
        </button>
      </div>

      <!-- 用户按钮：下拉菜单 -->
      <div class="relative shrink-0" data-user-menu>
        <button
          type="button"
          class="h-9 pl-1 pr-2 rounded-btn border border-border-line bg-bg-base hover:bg-bg-hover transition-colors flex items-center gap-2"
          @click.stop="showUserMenu = !showUserMenu"
        >
          <div class="avatar avatar-sm">{{ authStore.user?.avatar || 'U' }}</div>
          <div class="hidden md:block text-left leading-tight">
            <div class="text-sm text-text-primary font-medium leading-tight">
              {{ authStore.user?.nickname || authStore.user?.username }}
            </div>
            <div class="text-caption text-text-secondary leading-tight">
              {{ authStore.roleLabel }}
            </div>
          </div>
        </button>

        <!-- 用户下拉菜单 -->
        <Transition name="slide-up">
          <div
            v-if="showUserMenu"
            class="absolute right-0 top-[calc(100%+8px)] w-[240px] rounded-card border border-border-line bg-bg-card shadow-lg overflow-hidden z-50"
          >
            <div class="p-3 border-b border-border-line flex items-center gap-3">
              <div class="avatar avatar-lg">{{ authStore.user?.avatar || 'U' }}</div>
              <div class="min-w-0 flex-1">
                <div class="text-card-title text-text-primary truncate">
                  {{ authStore.user?.nickname || authStore.user?.username }}
                </div>
                <div class="text-caption text-text-secondary truncate">
                  @{{ authStore.user?.username }}
                </div>
              </div>
            </div>
            <div class="p-2">
              <button
                type="button"
                class="btn btn-ghost w-full justify-start opacity-60 cursor-not-allowed"
                disabled
              >
                <User :size="16" />
                <span>账户设置</span>
              </button>
              <button
                type="button"
                class="btn btn-ghost w-full justify-start opacity-60 cursor-not-allowed"
                disabled
              >
                <Server :size="16" />
                <span>切换服务器</span>
              </button>
              <div class="h-px my-1 bg-border-line"></div>
              <button
                type="button"
                class="btn btn-ghost w-full justify-start !text-danger-red hover:!bg-danger-red/10"
                @click="handleLogout"
              >
                <LogOut :size="16" />
                <span>退出登录</span>
              </button>
            </div>
          </div>
        </Transition>
      </div>
    </div>

    <!-- 标题/时间（中部右侧，当空间不足时隐藏） -->
    <div class="hidden xl:block absolute left-1/2 -translate-x-1/2 text-center pointer-events-none">
      <div class="text-page-title text-text-primary leading-tight truncate">
        {{ (route.meta?.title as string) || '总览' }}
      </div>
      <div class="text-caption text-text-secondary">{{ formattedTime }}</div>
    </div>
  </header>
</template>
