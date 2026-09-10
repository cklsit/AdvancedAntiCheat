<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { MapPin, Users, CircleDot, ZoomIn, ZoomOut, Move, Locate, AlertTriangle, Loader2, RefreshCw } from 'lucide-vue-next'
import type { Player } from '@/types'
import { getPlayerList } from '@/api/players'
import { scoreToHexColor, playerStatusMeta } from '@/utils/risk'

const players = ref<Player[]>([])
const loading = ref(true)
const errorMsg = ref<string | null>(null)

async function load(): Promise<void> {
  loading.value = true
  errorMsg.value = null
  try {
    const res = await getPlayerList({ page: 1, pageSize: 200 })
    players.value = res.data?.list ?? []
  } catch (e: unknown) {
    const status = (e as any)?.response?.status
    if (status === 401) errorMsg.value = '登录已过期，请重新登录后再试'
    else if (status === 403) errorMsg.value = '当前账号无权访问实时地图'
    else if (status >= 500) errorMsg.value = '服务器内部错误'
    else errorMsg.value = '加载在线玩家失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

const worldCount = computed(() => new Set(players.value.map((p) => p.world)).size)
const highRiskCount = computed(() => players.value.filter((p) => p.riskScore >= 70).length)

type PositionedPlayer = Player & { leftPercent: number; topPercent: number }
const playerPositions = computed<PositionedPlayer[]>(() => {
  const list = players.value
  if (list.length === 0) return []
  const xs = list.map((p) => p.locationX)
  const zs = list.map((p) => p.locationZ)
  const minX = Math.min(...xs), maxX = Math.max(...xs)
  const minZ = Math.min(...zs), maxZ = Math.max(...zs)
  const rangeX = maxX - minX
  const rangeZ = maxZ - minZ
  return list.map((p) => ({
    ...p,
    leftPercent: rangeX === 0 ? 50 : ((p.locationX - minX) / rangeX) * 80 + 10,
    topPercent: rangeZ === 0 ? 50 : ((p.locationZ - minZ) / rangeZ) * 80 + 10
  }))
})

const viewport = ref({ zoom: 1, tx: 0, ty: 0 })
const hoveredPlayer = ref<PositionedPlayer | null>(null)

let rafId = 0
const phase = ref(0)
function tick(): void {
  phase.value += 0.004
  rafId = requestAnimationFrame(tick)
}

onMounted(() => {
  void load()
  rafId = requestAnimationFrame(tick)
})
onBeforeUnmount(() => cancelAnimationFrame(rafId))

function zoom(delta: number): void {
  viewport.value.zoom = Math.max(0.6, Math.min(2.4, viewport.value.zoom + delta))
}
function pan(dx: number, dy: number): void {
  viewport.value.tx += dx
  viewport.value.ty += dy
}
function recenter(): void {
  viewport.value = { zoom: 1, tx: 0, ty: 0 }
}
</script>

<template>
  <div class="space-y-4">
    <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
      <div class="stat-tile"><div>
        <div class="stat-tile-label">实时在线玩家</div>
        <div class="stat-tile-value">{{ players.length }}</div>
        <div class="text-caption text-text-secondary">来自 {{ worldCount }} 个世界</div>
      </div><div class="stat-tile-icon-wrap" style="background: rgba(0,229,255,0.12); color: var(--accent-cyan);"><Users :size="22"/></div></div>
      <div class="stat-tile"><div>
        <div class="stat-tile-label">高风险在线</div>
        <div class="stat-tile-value" style="color: var(--danger-red);">
          {{ highRiskCount }}
        </div>
        <div class="text-caption text-text-secondary">正在重点跟踪</div>
      </div><div class="stat-tile-icon-wrap" style="background: rgba(248,81,73,0.12); color: var(--danger-red);"><AlertTriangle :size="22"/></div></div>
    </div>

    <div class="card">
      <div class="card-header">
        <h3 class="flex items-center gap-2"><MapPin :size="16" style="color: var(--accent-cyan);"/> 实时地图</h3>
        <div class="flex items-center gap-1">
          <span class="tag tag-green"><span class="status-dot online" style="width:6px;height:6px;"></span>流更新</span>
          <div class="w-px h-5 bg-border-line mx-2"></div>
          <button class="btn btn-sm btn-ghost btn-icon" @click="zoom(0.2)" title="放大"><ZoomIn :size="16"/></button>
          <button class="btn btn-sm btn-ghost btn-icon" @click="zoom(-0.2)" title="缩小"><ZoomOut :size="16"/></button>
          <button class="btn btn-sm btn-ghost btn-icon" title="平移" @click="pan(20, 10)"><Move :size="16"/></button>
          <button class="btn btn-sm btn-ghost btn-icon" @click="recenter" title="重置视图"><Locate :size="16"/></button>
          <span class="ml-3 text-caption text-text-muted font-mono">×{{ viewport.zoom.toFixed(2) }}</span>
        </div>
      </div>
      <div class="card-body p-0 relative overflow-hidden" style="height: 560px;">
        <!-- 网格背景 -->
        <div class="absolute inset-0"
             style="background-image:
               linear-gradient(rgba(48,54,61,0.35) 1px, transparent 1px),
               linear-gradient(90deg, rgba(48,54,61,0.35) 1px, transparent 1px);
               background-size: 40px 40px, 40px 40px;
               background-position: center;">
        </div>

        <!-- 中心标记 -->
        <div class="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 w-40 h-40 rounded-full"
             style="border: 1px dashed rgba(0,229,255,0.25);"></div>
        <div class="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 w-80 h-80 rounded-full"
             style="border: 1px dashed rgba(56,139,253,0.15);"></div>

        <!-- 玩家点（坐标归一化映射到画布 80% 区域，保留 transform 缩放/平移） -->
        <div
          class="absolute inset-0"
          :style="{ transform: `translate(${viewport.tx}px, ${viewport.ty}px) scale(${viewport.zoom})` }"
        >
          <div
            v-for="p in playerPositions"
            :key="p.uuid"
            class="absolute group"
            :style="{ left: p.leftPercent + '%', top: p.topPercent + '%', transform: 'translate(-50%, -50%)' }"
            @mouseenter="hoveredPlayer = p"
            @mouseleave="hoveredPlayer = null"
          >
            <span class="status-dot" :class="playerStatusMeta(p.status).dot"
                  :style="{ width: 10, height: 10, boxShadow: `0 0 0 2px ${scoreToHexColor(p.riskScore)}55` }"></span>
            <span class="absolute -inset-2 rounded-full opacity-50 pointer-events-none"
                  :style="{ background: `radial-gradient(circle, ${scoreToHexColor(p.riskScore)} 0%, transparent 70%)` }"></span>
            <div
              class="absolute left-1/2 -translate-x-1/2 top-full mt-2 whitespace-nowrap text-caption px-2 py-1 rounded-tag border border-border-line bg-bg-card shadow-md opacity-0 group-hover:opacity-100 transition-opacity pointer-events-none"
            >
              <span class="text-text-primary font-medium">{{ p.name }}</span>
              <span class="mx-1.5 text-text-muted">·</span>
              <span class="font-mono" :style="{ color: scoreToHexColor(p.riskScore) }">{{ p.riskScore }}</span>
              <span class="mx-1.5 text-text-muted">·</span>
              <span class="text-text-secondary">{{ p.world }}</span>
              <span class="mx-1.5 text-text-muted">·</span>
              <span class="font-mono text-text-muted">{{ p.ping }}ms</span>
            </div>
          </div>
        </div>

        <!-- 加载态 -->
        <div v-if="loading" class="absolute inset-0 flex items-center justify-center">
          <div class="flex flex-col items-center gap-2 text-text-secondary">
            <Loader2 :size="24" class="animate-spin" style="color: var(--accent-cyan);"/>
            <span class="text-caption">正在加载在线玩家…</span>
          </div>
        </div>

        <!-- 错误态 + 重试 -->
        <div v-else-if="errorMsg" class="absolute inset-0 flex items-center justify-center">
          <div class="flex flex-col items-center gap-2 text-text-secondary">
            <AlertTriangle :size="24" style="color: var(--danger-orange);"/>
            <span class="text-caption">{{ errorMsg }}</span>
            <button class="btn btn-sm btn-ghost" @click="load"><RefreshCw :size="14"/> 重试</button>
          </div>
        </div>

        <!-- 空状态 -->
        <div v-else-if="players.length === 0" class="absolute inset-0 flex items-center justify-center">
          <div class="flex flex-col items-center gap-2 text-text-muted">
            <Users :size="28"/>
            <span class="text-caption">当前无在线玩家</span>
          </div>
        </div>

        <!-- 左上：图例 -->
        <div class="absolute top-4 left-4 card shadow-md !p-3 !rounded-tag text-caption space-y-1.5" style="min-width: 180px;">
          <div class="flex items-center gap-2 mb-1.5"><CircleDot :size="14" style="color: var(--accent-cyan);"/> 风险图例</div>
          <div v-for="r in (['低','中','高','极高'] as const)" :key="r" class="flex items-center gap-2">
            <span class="w-2 h-2 rounded-full" :style="{ background: r==='低' ? '#3FB950' : r==='中' ? '#D29922' : r==='高' ? '#FF6D00' : '#F85149' }"></span>
            <span class="text-text-secondary">{{ r }}风险</span>
            <span class="ml-auto font-mono text-text-muted">
              {{ players.filter((p) => r==='低' && p.riskScore<50 || r==='中' && p.riskScore>=50 && p.riskScore<70 || r==='高' && p.riskScore>=70 && p.riskScore<90 || r==='极高' && p.riskScore>=90).length }}
            </span>
          </div>
        </div>

        <!-- 右上：悬停详情 -->
        <div class="absolute top-4 right-4 card shadow-md !rounded-tag overflow-hidden" style="width: 300px;">
          <div v-if="hoveredPlayer" class="p-3">
            <div class="flex items-center gap-2 mb-2">
              <div class="avatar avatar-sm">{{ hoveredPlayer.avatar }}</div>
              <div class="min-w-0 flex-1">
                <div class="text-sm font-medium text-text-primary truncate">{{ hoveredPlayer.name }}</div>
                <div class="text-caption text-text-muted font-mono">{{ hoveredPlayer.world }} · Ping {{ hoveredPlayer.ping }}ms</div>
              </div>
              <span class="tag" :class="playerStatusMeta(hoveredPlayer.status).cls">{{ playerStatusMeta(hoveredPlayer.status).label }}</span>
            </div>
            <div class="flex items-center gap-3">
              <span class="text-caption text-text-secondary">风险分</span>
              <div class="risk-bar flex-1"><div class="risk-bar-fill" :style="{ width: hoveredPlayer.riskScore + '%', background: scoreToHexColor(hoveredPlayer.riskScore) }"></div></div>
              <span class="font-mono text-sm" :style="{ color: scoreToHexColor(hoveredPlayer.riskScore) }">{{ hoveredPlayer.riskScore }}</span>
            </div>
          </div>
          <div v-else class="p-4 text-center text-caption text-text-secondary">
            将鼠标悬停在玩家点上查看详情 <br/>（共 {{ players.length }} 个活跃标记）
          </div>
        </div>

        <!-- 底部：时间轴 -->
        <div class="absolute bottom-4 left-1/2 -translate-x-1/2 flex items-center gap-3 px-4 py-2 rounded-card border border-border-line bg-bg-card shadow-md">
          <CircleDot :size="14" style="color: var(--accent-cyan);" :class="{ 'animate-pulse': true }"/>
          <span class="text-caption text-text-secondary font-mono">LIVE · {{ new Date().toLocaleTimeString('zh-CN', { hour12: false }) }}</span>
          <div class="w-px h-4 bg-border-line"></div>
          <div class="w-[240px] h-1 rounded-full bg-bg-hover overflow-hidden">
            <div class="h-full rounded-full"
                 :style="{ width: (40 + Math.sin(phase) * 35) + '%', background: 'linear-gradient(90deg, var(--accent-blue), var(--accent-cyan))' }">
            </div>
          </div>
          <span class="text-caption text-text-muted">最近 10 分钟热点</span>
        </div>
      </div>
    </div>
  </div>
</template>
