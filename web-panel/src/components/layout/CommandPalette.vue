<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { Search, Ban, User, Eye, Ghost, History, Megaphone } from 'lucide-vue-next'
import { getPlayerList } from '@/api/players'
import type { Player } from '@/types'

interface Props {
  visible: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:visible': [value: boolean]
  execute: [command: string, payload?: any]
  jump: [route: string]
}>()

const router = useRouter()
const inputRef = ref<HTMLInputElement | null>(null)
const query = ref('')
const selectedIdx = ref(0)

interface QuickCommand {
  key: string
  label: string
  icon: typeof Ban
  cmd: string
  description: string
}

const quickCommands: QuickCommand[] = [
  { key: 'ban',       label: 'ban',       icon: Ban,       cmd: 'ban',       description: '打开封禁面板' },
  { key: 'profile',   label: 'profile',   icon: User,      cmd: 'profile',   description: '查看玩家档案' },
  { key: 'watch',     label: 'watch',     icon: Eye,       cmd: 'watch',     description: '加入观察名单' },
  { key: 'phantom',   label: 'phantom',   icon: Ghost,     cmd: 'phantom',   description: '开启隐形巡查' },
  { key: 'scan-history', label: 'scan history', icon: History, cmd: 'scan-history', description: '扫描历史记录' },
  { key: 'broadcast', label: 'broadcast', icon: Megaphone, cmd: 'broadcast', description: '发送全服广播' }
]

// 配置项（模拟）
const configItems = [
  { key: 'threshold.killaura',  label: 'KillAura 阈值',     value: '95' },
  { key: 'threshold.speed',     label: '速度检测阈值',      value: '70' },
  { key: 'auto-ban.enable',     label: '自动封禁启用',      value: 'true' },
  { key: 'auto-ban.score',      label: '自动封禁最低分',    value: '95' },
  { key: 'ws.heartbeat',        label: 'WS 心跳间隔(ms)',   value: '5000' },
  { key: 'log.retention',       label: '日志保留天数',      value: '90' }
]

// 从真实 API 加载玩家（搜索时按关键词实时获取）
const players = ref<Player[]>([])
const playersLoading = ref(false)
let playersCache = '' // 缓存上一次搜索关键词，避免重复请求

async function fetchPlayers(keyword: string): Promise<void> {
  if (keyword === playersCache) return
  playersCache = keyword
  playersLoading.value = true
  try {
    const resp = await getPlayerList({ page: 1, pageSize: 20, keyword: keyword || undefined })
    players.value = resp.data?.list ?? []
  } catch {
    players.value = []
  } finally {
    playersLoading.value = false
  }
}

// 监听搜索输入，防抖 200ms 后请求
let debounceTimer: number | null = null
watch(query, () => {
  if (debounceTimer) clearTimeout(debounceTimer)
  const kw = query.value.trim()
  if (!kw) {
    players.value = []
    playersCache = ''
    return
  }
  debounceTimer = window.setTimeout(() => { fetchPlayers(kw) }, 200)
})

const q = computed(() => query.value.trim().toLowerCase())

// 匹配玩家（players 已由 API 按关键词过滤，前端只做二次筛选 + 限流）
const matchedPlayers = computed(() => {
  if (!q.value) return []
  return players.value
    .filter((p) =>
      p.name.toLowerCase().includes(q.value) ||
      p.ip.includes(q.value) ||
      p.uuid.toLowerCase().includes(q.value)
    )
    .slice(0, 6)
})

// 匹配命令
const matchedCommands = computed(() => {
  if (!q.value) return []
  return quickCommands.filter(
    (c) => c.label.includes(q.value) || c.description.toLowerCase().includes(q.value)
  )
})

// 匹配配置
const matchedConfigs = computed(() => {
  if (!q.value) return []
  return configItems.filter(
    (c) => c.key.toLowerCase().includes(q.value) || c.label.toLowerCase().includes(q.value)
  )
})

// 空态（快捷操作）
const isEmpty = computed(() => !q.value)

// 所有结果项（用于键盘选择）
interface FlatItem {
  kind: 'player' | 'cmd' | 'cfg'
  data: any
  index: number
}

const flatList = computed<FlatItem[]>(() => {
  if (isEmpty.value) {
    return quickCommands.map((c, i) => ({ kind: 'cmd' as const, data: c, index: i }))
  }
  const arr: FlatItem[] = []
  matchedPlayers.value.forEach((p, i) => arr.push({ kind: 'player', data: p, index: i }))
  matchedCommands.value.forEach((c, i) => arr.push({ kind: 'cmd', data: c, index: i }))
  matchedConfigs.value.forEach((c, i) => arr.push({ kind: 'cfg', data: c, index: i }))
  return arr
})

const totalCount = computed(() => flatList.value.length)

// 监听 visible → 聚焦 & 清空
watch(() => props.visible, async (v) => {
  if (v) {
    query.value = ''
    selectedIdx.value = 0
    await nextTick()
    inputRef.value?.focus()
  }
})

function close(): void {
  emit('update:visible', false)
}

function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape') {
    e.preventDefault()
    close()
  } else if (e.key === 'ArrowDown') {
    e.preventDefault()
    if (totalCount.value > 0) {
      selectedIdx.value = (selectedIdx.value + 1) % totalCount.value
      scrollToSelected()
    }
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    if (totalCount.value > 0) {
      selectedIdx.value = (selectedIdx.value - 1 + totalCount.value) % totalCount.value
      scrollToSelected()
    }
  } else if (e.key === 'Enter') {
    e.preventDefault()
    executeSelected()
  }
}

function scrollToSelected(): void {
  requestAnimationFrame(() => {
    const el = document.querySelector<HTMLElement>('[data-palette-selected="true"]')
    el?.scrollIntoView({ block: 'nearest' })
  })
}

function executeSelected(): void {
  if (totalCount.value === 0) return
  const item = flatList.value[selectedIdx.value]
  if (!item) return
  executeItem(item)
}

function executeItem(item: FlatItem): void {
  if (item.kind === 'player') {
    const p = item.data as Player
    emit('jump', `/players/${p.uuid}`)
    router.push(`/players/${p.uuid}`).catch(() => void 0)
    close()
  } else if (item.kind === 'cmd') {
    const c = item.data as QuickCommand
    emit('execute', c.cmd)
    close()
  } else if (item.kind === 'cfg') {
    const c = item.data
    emit('execute', 'config', { key: c.key })
    emit('jump', '/config')
    close()
  }
}

function isSelected(kind: string, idx: number): boolean {
  const flatIdx = flatList.value.findIndex((f) => f.kind === kind && f.index === idx)
  return flatIdx === selectedIdx.value
}

// 全局 keydown
let onGlobalKey: ((e: KeyboardEvent) => void) | null = null

onMounted(() => {
  onGlobalKey = (e: KeyboardEvent) => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
      if (!props.visible) {
        e.preventDefault()
        emit('update:visible', true)
      }
    }
  }
  window.addEventListener('keydown', onGlobalKey)
})

onBeforeUnmount(() => {
  if (onGlobalKey) window.removeEventListener('keydown', onGlobalKey)
})

function handlePlayerJump(p: Player): void {
  emit('jump', `/players/${p.uuid}`)
  router.push(`/players/${p.uuid}`).catch(() => void 0)
  close()
}

function handlePlayerBan(p: Player): void {
  emit('execute', 'ban', { player: p })
  close()
}
</script>

<template>
  <Teleport to="body">
    <Transition name="fade">
      <div
        v-if="visible"
        class="fixed inset-0 z-[60] flex items-start justify-center pt-[15vh]"
        style="background: rgba(0,0,0,0.5); backdrop-filter: blur(4px); -webkit-backdrop-filter: blur(4px);"
        @click.self="close"
      >
        <div
          class="w-[680px] max-w-[94vw] rounded-modal border border-border-line bg-bg-card shadow-lg overflow-hidden"
          @click.stop
        >
          <!-- 搜索输入区 -->
          <div class="flex items-center gap-3 px-4 py-3 border-b border-border-line">
            <Search :size="18" class="text-text-secondary shrink-0" />
            <input
              ref="inputRef"
              v-model="query"
              type="text"
              class="flex-1 bg-bg-base rounded-btn px-3 py-2.5 text-sm text-text-primary
                     border border-border-line outline-none focus:border-accent-blue
                     placeholder:text-text-muted transition-colors"
              placeholder="输入命令或搜索... Esc"
              @keydown="onKeydown"
            />
          </div>

          <!-- 结果区 -->
          <div style="max-height: 480px;" class="overflow-y-auto p-3 space-y-4">
            <!-- 空态：快捷操作分组 -->
            <template v-if="isEmpty">
              <div>
                <div class="text-[11px] uppercase tracking-wider text-text-muted mb-2 px-1">
                  快捷操作
                </div>
                <div class="space-y-0.5">
                  <div
                    v-for="(c, i) in quickCommands"
                    :key="c.key"
                    class="flex items-center gap-3 px-3 py-2 rounded-btn cursor-pointer transition-colors"
                    :class="isSelected('cmd', i) ? 'bg-bg-hover border border-accent-cyan/50' : 'hover:bg-bg-hover border border-transparent'"
                    :data-palette-selected="isSelected('cmd', i)"
                    @click="executeItem({ kind: 'cmd', data: c, index: i })"
                    @mouseenter="selectedIdx = flatList.findIndex(f => f.kind==='cmd' && f.index===i)"
                  >
                    <span class="text-accent-cyan shrink-0 text-sm">▸</span>
                    <span class="text-sm text-text-secondary font-mono">{{ c.label }}</span>
                  </div>
                </div>
              </div>
            </template>

            <!-- 有值：三组结果 -->
            <template v-else>
              <!-- 🎯 玩家 -->
              <div v-if="matchedPlayers.length > 0">
                <div class="text-[11px] uppercase tracking-wider text-text-muted mb-2 px-1">
                  🎯 玩家
                </div>
                <div class="space-y-0.5">
                  <div
                    v-for="(p, i) in matchedPlayers"
                    :key="p.uuid"
                    class="flex items-center gap-3 px-3 py-2 rounded-btn cursor-pointer transition-colors"
                    :class="isSelected('player', i) ? 'bg-bg-hover border border-accent-cyan/50' : 'hover:bg-bg-hover border border-transparent'"
                    :data-palette-selected="isSelected('player', i)"
                    @click="handlePlayerJump(p)"
                    @mouseenter="selectedIdx = flatList.findIndex(f => f.kind==='player' && f.index===i)"
                  >
                    <div class="avatar avatar-sm">{{ p.avatar }}</div>
                    <div class="min-w-0 flex-1">
                      <div class="text-sm text-text-primary font-medium leading-tight">{{ p.name }}</div>
                      <div class="text-[11px] text-text-secondary">{{ p.ip }} · 风险分 {{ p.riskScore }}</div>
                    </div>
                    <div class="flex items-center gap-1 shrink-0">
                      <button
                        type="button"
                        class="btn btn-sm btn-secondary"
                        @click.stop="handlePlayerJump(p)"
                      >跳转</button>
                      <button
                        type="button"
                        class="btn btn-sm btn-danger"
                        @click.stop="handlePlayerBan(p)"
                      >封禁</button>
                    </div>
                  </div>
                </div>
              </div>

              <!-- 📋 命令 -->
              <div v-if="matchedCommands.length > 0">
                <div class="text-[11px] uppercase tracking-wider text-text-muted mb-2 px-1">
                  📋 命令
                </div>
                <div class="space-y-0.5">
                  <div
                    v-for="(c, i) in matchedCommands"
                    :key="c.key"
                    class="flex items-center gap-3 px-3 py-2 rounded-btn cursor-pointer transition-colors"
                    :class="isSelected('cmd', i) ? 'bg-bg-hover border border-accent-cyan/50' : 'hover:bg-bg-hover border border-transparent'"
                    :data-palette-selected="isSelected('cmd', i)"
                    @click="executeItem({ kind: 'cmd', data: c, index: i })"
                    @mouseenter="selectedIdx = flatList.findIndex(f => f.kind==='cmd' && f.index===i)"
                  >
                    <component :is="c.icon" :size="16" class="text-text-secondary shrink-0" />
                    <span class="text-sm text-text-primary font-mono">{{ c.label }}</span>
                    <span class="text-xs text-text-muted ml-auto">{{ c.description }}</span>
                  </div>
                </div>
              </div>

              <!-- ⚙️ 配置 -->
              <div v-if="matchedConfigs.length > 0">
                <div class="text-[11px] uppercase tracking-wider text-text-muted mb-2 px-1">
                  ⚙️ 配置
                </div>
                <div class="space-y-0.5">
                  <div
                    v-for="(c, i) in matchedConfigs"
                    :key="c.key"
                    class="flex items-center gap-3 px-3 py-2 rounded-btn cursor-pointer transition-colors"
                    :class="isSelected('cfg', i) ? 'bg-bg-hover border border-accent-cyan/50' : 'hover:bg-bg-hover border border-transparent'"
                    :data-palette-selected="isSelected('cfg', i)"
                    @click="executeItem({ kind: 'cfg', data: c, index: i })"
                    @mouseenter="selectedIdx = flatList.findIndex(f => f.kind==='cfg' && f.index===i)"
                  >
                    <span class="text-xs text-text-muted shrink-0 font-mono">{{ c.key }}</span>
                    <span class="text-sm text-text-primary flex-1">{{ c.label }}</span>
                    <span class="text-xs text-accent-cyan font-mono">{{ c.value }}</span>
                  </div>
                </div>
              </div>

              <!-- 完全无结果 -->
              <div
                v-if="matchedPlayers.length === 0 && matchedCommands.length === 0 && matchedConfigs.length === 0"
                class="py-8 text-center text-text-secondary text-sm"
              >
                没有找到与「{{ query }}」相关的结果
              </div>
            </template>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>
