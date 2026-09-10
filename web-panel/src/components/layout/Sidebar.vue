<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { Component } from 'vue'
import { ChevronLeft, ChevronRight, ShieldAlert } from 'lucide-vue-next'
import StatusDot from '@/components/common/StatusDot.vue'
import type { WSStatus } from '@/stores/global'
import { getMeta, type MetaInfo } from '@/api/meta'

export interface SidebarNavEntry {
  to: string
  name: string
  icon: Component
  label: string
}

interface Props {
  collapsed: boolean
  navEntries: SidebarNavEntry[]
  activeName: string
  wsState: WSStatus
  wsLatency: number
  wsLabel: string
}

const props = defineProps<Props>()
const emit = defineEmits<{
  toggleCollapse: []
}>()

const router = useRouter()

const meta = ref<MetaInfo | null>(null)

onMounted(async () => {
  try {
    const resp = await getMeta()
    meta.value = resp.data
  } catch { /* 保持 null，用默认值 */ }
})

const widthStyle = computed(() => ({
  width: props.collapsed ? '56px' : '220px'
}))

const dotStatus = computed<
  'online' | 'offline' | 'banned' | 'watching' | 'reconnecting' | 'connected' | 'disconnected'
>(() => {
  if (props.wsState === 'connected') return 'online'
  if (props.wsState === 'reconnecting') return 'reconnecting'
  return 'offline'
})

function isActive(name: string): boolean {
  return props.activeName === name
}

function go(to: string): void {
  router.push(to).catch(() => void 0)
}
</script>

<template>
  <aside
    class="flex flex-col border-r border-border-line bg-bg-card transition-[width] duration-200 shrink-0 relative z-30"
    :style="widthStyle"
  >
    <!-- 折叠切换按钮：右侧中部（不遮挡 Logo 与第一项导航） -->
    <button
      type="button"
      class="absolute -right-3 top-[76px] z-40 w-6 h-6 rounded-full bg-bg-card border border-border-line
             flex items-center justify-center hover:bg-bg-hover transition-colors shadow-md"
      @click="emit('toggleCollapse')"
      :title="collapsed ? '展开' : '折叠'"
    >
      <component
        :is="collapsed ? ChevronRight : ChevronLeft"
        :size="14"
        class="text-text-secondary"
      />
    </button>

    <!-- Logo 区 -->
    <div class="h-[56px] flex items-center gap-2 px-3 border-b border-border-line shrink-0 overflow-hidden">
      <div
        class="w-9 h-9 rounded-btn flex items-center justify-center shrink-0"
        style="background: linear-gradient(135deg, var(--accent-cyan), var(--accent-blue));"
      >
        <ShieldAlert :size="20" color="#0D1117" :stroke-width="2.2" />
      </div>
      <div v-if="!collapsed" class="min-w-0">
        <div class="text-sm font-semibold text-text-primary truncate leading-tight">AntiCheat</div>
        <div class="text-caption text-text-secondary truncate leading-tight">指挥中心</div>
      </div>
    </div>

    <!-- 导航区 -->
    <nav class="flex-1 py-2 space-y-0.5 overflow-y-auto overflow-x-hidden">
      <a
        v-for="n in navEntries"
        :key="n.name"
        class="flex items-center gap-3 px-3 py-2.5 text-sm cursor-pointer whitespace-nowrap transition-colors border-l-[3px] mx-1 rounded-r-btn"
        :class="[
          isActive(n.name)
            ? 'border-accent-cyan bg-bg-hover text-accent-cyan'
            : 'border-transparent text-text-secondary hover:bg-bg-hover hover:text-text-primary'
        ]"
        :title="collapsed ? n.label : undefined"
        @click.prevent="go(n.to)"
      >
        <component :is="n.icon" :size="20" class="shrink-0" />
        <span
          class="truncate transition-opacity duration-150"
          :class="collapsed ? 'opacity-0 w-0 pointer-events-none' : 'opacity-100'"
        >
          {{ n.label }}
        </span>
      </a>
    </nav>

    <!-- 底部状态区：固定 bottom-0 -->
    <div class="border-t border-border-line p-3 shrink-0 space-y-1.5">
      <!-- WS 状态 + 延迟 -->
      <div class="flex items-center gap-2 min-w-0 overflow-hidden">
        <StatusDot :status="dotStatus" size="sm" />
        <span
          class="text-xs text-text-secondary truncate transition-opacity duration-150"
          :class="collapsed ? 'opacity-0 w-0 pointer-events-none' : 'opacity-100'"
        >
          {{ wsLabel }}
        </span>
        <span
          class="ml-auto text-[11px] font-mono text-text-muted transition-opacity duration-150 shrink-0"
          :class="collapsed ? 'opacity-0 w-0 pointer-events-none' : 'opacity-100'"
        >
          {{ wsState === 'connected' ? (wsLatency + 'ms') : wsState === 'reconnecting' ? '…' : 'off' }}
        </span>
      </div>

      <!-- 版本号两行 -->
      <div
        class="text-[10px] leading-relaxed text-text-muted space-y-0.5 transition-opacity duration-150"
        :class="collapsed ? 'opacity-0 h-0 pointer-events-none' : 'opacity-100 pt-1'"
      >
        <div>{{ meta?.pluginVersion ? `插件 v${meta.pluginVersion}` : '插件 v2.1.0' }}</div>
        <div>{{ meta?.panelVersion ? `面板 v${meta.panelVersion}` : '面板 v1.7.0' }}</div>
      </div>
    </div>
  </aside>
</template>
