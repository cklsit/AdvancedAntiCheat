<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { Network, Users, ShieldAlert, Link2, Monitor, RefreshCw } from 'lucide-vue-next'
import type { AllianceGraph, AllianceNode, AllianceEdge } from '@/types'
import { getAllianceGraph } from '@/api/alliance'

// ========== 数据加载 ==========
const loading = ref(true)
const error = ref<string | null>(null)
const graph = ref<AllianceGraph | null>(null)

async function loadGraph(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const resp = await getAllianceGraph()
    graph.value = resp.data
    // 自动选中首个节点（若有）
    selectedId.value = graph.value.nodes[0]?.id ?? null
  } catch (e) {
    // 从 axios 错误提取 HTTP 状态，给出中文友好提示
    const status = (e as any)?.response?.status
    if (status === 401) {
      error.value = '登录已过期，请重新登录后再试'
    } else if (status === 403) {
      error.value = '当前账号无权访问联盟图谱，请联系管理员'
    } else if (status >= 500) {
      error.value = '服务器内部错误，稍后重试'
    } else {
      error.value = '加载联盟图谱失败，请检查服务器状态'
    }
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void loadGraph()
  const t = () => { phase.value += 0.01; rafId = requestAnimationFrame(t) }
  rafId = requestAnimationFrame(t)
})
onBeforeUnmount(() => cancelAnimationFrame(rafId))

// ========== 派生数据 ==========
const nodes = computed<AllianceNode[]>(() => graph.value?.nodes ?? [])
const edges = computed<AllianceEdge[]>(() => graph.value?.edges ?? [])
const groups = computed(() => graph.value?.groups ?? [])

const nodeMap = computed(() => {
  const m = new Map<string, AllianceNode>()
  nodes.value.forEach((n) => m.set(n.id, n))
  return m
})

const extremeRiskCount = computed(() => nodes.value.filter((n) => n.score >= 90).length)

const avgDensity = computed(() => {
  const g = groups.value
  if (g.length === 0) return 0
  return g.reduce((s, x) => s + (x.density || 0), 0) / g.length
})

const topGroups = computed(() =>
  [...groups.value].sort((a, b) => b.suspicionScore - a.suspicionScore).slice(0, 5)
)

function sv(v: number): string {
  return loading.value || error.value ? '--' : String(v)
}

// ========== 动画 ==========
const phase = ref(0)
let rafId = 0

// ========== 节点 / 边样式 ==========
function nodeColor(n: AllianceNode): string {
  if (n.score >= 90) return '#F85149'
  if (n.score >= 70) return '#FF6D00'
  if (n.score >= 50) return '#D29922'
  return '#3FB950'
}
function nodeRadius(n: AllianceNode): number {
  return 7 + n.score / 14
}
function edgeColor(e: AllianceEdge): string {
  return e.kind === 'hardware' ? '#BC8CFF' : e.kind === 'ip' ? '#388BFD' : '#00E5FF'
}
function edgeKindLabel(k: AllianceEdge['kind']): string {
  return k === 'hardware' ? '硬件关联' : k === 'ip' ? 'IP 关联' : '行为相似'
}

// ========== 选中态 ==========
const selectedId = ref<string | null>(null)
const selected = computed<AllianceNode | null>(() =>
  selectedId.value ? nodeMap.value.get(selectedId.value) ?? null : null
)
function pick(id: string): void {
  selectedId.value = id
}

const relatedEdges = computed(() => {
  const n = selected.value
  if (!n) return [] as { edge: AllianceEdge; other: AllianceNode | undefined }[]
  return edges.value
    .filter((e) => e.source === n.id || e.target === n.id)
    .map((e) => {
      const otherId = e.source === n.id ? e.target : e.source
      return { edge: e, other: nodeMap.value.get(otherId) }
    })
})

// 预计算边的几何坐标，避免模板内重复查找
const edgeLines = computed(() =>
  edges.value
    .map((e, i) => {
      const s = nodeMap.value.get(e.source)
      const t = nodeMap.value.get(e.target)
      if (!s || !t) return null
      return {
        i,
        x1: s.x, y1: s.y, x2: t.x, y2: t.y,
        color: edgeColor(e),
        width: 0.2 + e.weight * 0.4,
        kind: e.kind
      }
    })
    .filter((x): x is NonNullable<typeof x> => x !== null)
)
</script>

<template>
  <div class="space-y-4">
    <!-- 统计卡 -->
    <div class="grid grid-cols-2 md:grid-cols-4 gap-4">
      <div class="stat-tile"><div>
        <div class="stat-tile-label">活跃团伙</div>
        <div class="stat-tile-value !text-[20px]">{{ sv(groups.length) }}</div>
        <div class="text-caption text-text-secondary">检测到的团伙簇</div>
      </div><div class="stat-tile-icon-wrap" style="background: rgba(188,140,255,0.12); color: var(--accent-purple);"><Network :size="22"/></div></div>

      <div class="stat-tile"><div>
        <div class="stat-tile-label">监控账号</div>
        <div class="stat-tile-value !text-[20px]">{{ sv(nodes.length) }}</div>
        <div class="text-caption text-text-secondary">在线玩家节点</div>
      </div><div class="stat-tile-icon-wrap" style="background: rgba(0,229,255,0.12); color: var(--accent-cyan);"><Users :size="22"/></div></div>

      <div class="stat-tile"><div>
        <div class="stat-tile-label">极高风险节点</div>
        <div class="stat-tile-value !text-[20px]" style="color: var(--danger-red);">{{ sv(extremeRiskCount) }}</div>
        <div class="text-caption text-text-secondary">风险分 ≥ 90</div>
      </div><div class="stat-tile-icon-wrap" style="background: rgba(248,81,73,0.12); color: var(--danger-red);"><ShieldAlert :size="22"/></div></div>

      <div class="stat-tile"><div>
        <div class="stat-tile-label">关联边数</div>
        <div class="stat-tile-value !text-[20px]" style="color: var(--accent-cyan);">{{ sv(edges.length) }}</div>
        <div class="text-caption text-text-secondary">玩家间关联数</div>
      </div><div class="stat-tile-icon-wrap" style="background: rgba(0,229,255,0.12); color: var(--accent-cyan);"><Link2 :size="22"/></div></div>
    </div>

    <div class="grid grid-cols-1 xl:grid-cols-[1fr_380px] gap-4">
      <!-- 图谱卡 -->
      <div class="card">
        <div class="card-header">
          <h3 class="flex items-center gap-2"><Network :size="16" style="color: var(--accent-purple);"/> 联盟图谱</h3>
          <div class="flex items-center gap-3 text-caption text-text-secondary">
            <span class="inline-flex items-center gap-1.5"><span class="w-2.5 h-2.5 rounded-full" style="background:#BC8CFF;"></span>硬件关联</span>
            <span class="inline-flex items-center gap-1.5"><span class="w-2.5 h-2.5 rounded-full" style="background:#388BFD;"></span>IP 关联</span>
            <span class="inline-flex items-center gap-1.5"><span class="w-2.5 h-2.5 rounded-full" style="background:#00E5FF;"></span>行为相似</span>
          </div>
        </div>
        <div class="card-body p-0 relative overflow-hidden" style="height: 560px;">
          <!-- 背景 -->
          <div class="absolute inset-0" style="background:
            radial-gradient(700px 350px at 20% 10%, rgba(188,140,255,0.10), transparent 60%),
            radial-gradient(600px 300px at 90% 100%, rgba(0,229,255,0.10), transparent 55%);"></div>
          <div class="absolute inset-0"
               style="background-image:
                 radial-gradient(circle, rgba(48,54,61,0.4) 1px, transparent 1px);
                 background-size: 24px 24px;"></div>

          <!-- 加载态 -->
          <div v-if="loading" class="absolute inset-0 flex items-center justify-center text-text-secondary text-sm">
            加载中…
          </div>

          <!-- 错误态 -->
          <div v-else-if="error" class="absolute inset-0 flex flex-col items-center justify-center gap-3 text-center px-6">
            <ShieldAlert :size="28" style="color: var(--danger-red);"/>
            <div class="text-sm text-text-secondary">{{ error }}</div>
            <button class="btn btn-sm btn-secondary" @click="loadGraph"><RefreshCw :size="14"/> 重试</button>
          </div>

          <!-- 空状态 -->
          <div v-else-if="nodes.length === 0" class="absolute inset-0 flex items-center justify-center text-text-secondary text-sm">
            当前无在线玩家或关联数据
          </div>

          <!-- 图谱 SVG -->
          <svg v-else viewBox="0 0 100 100" preserveAspectRatio="none" class="absolute inset-0 w-full h-full">
            <!-- 边 -->
            <g>
              <line
                v-for="ln in edgeLines" :key="'e'+ln.i"
                :x1="ln.x1" :y1="ln.y1" :x2="ln.x2" :y2="ln.y2"
                :stroke="ln.color" :stroke-width="ln.width" stroke-opacity="0.7"
                stroke-dasharray="0.5, 0.5"
                :style="`animation: dash 2s linear infinite; animation-delay: -${ln.i * 0.15}s;`"
              />
            </g>
            <!-- 节点 -->
            <g>
              <g v-for="n in nodes" :key="n.id" :transform="`translate(${n.x}, ${n.y})`"
                 :class="n.id === selectedId ? 'opacity-100' : 'opacity-90 hover:opacity-100'"
                 style="cursor: pointer; transform-box: fill-box;"
                 @click="pick(n.id)"
              >
                <!-- 脉动光环 -->
                <circle v-if="n.score >= 80"
                        :r="nodeRadius(n) + (2 + Math.sin(phase * 3 + n.x) * 1.2)"
                        fill="none" :stroke="nodeColor(n)" stroke-opacity="0.35" stroke-width="0.3"/>
                <!-- 主圆 -->
                <circle :r="nodeRadius(n)" :fill="nodeColor(n)"
                        :stroke="n.id === selectedId ? '#fff' : 'rgba(13,17,23,0.9)'"
                        stroke-width="0.6" stroke-opacity="0.9"/>
              </g>
            </g>
          </svg>

          <!-- 节点标签 (HTML 叠加) -->
          <template v-if="!loading && !error && nodes.length > 0">
            <div
              v-for="n in nodes" :key="'label'+n.id"
              class="absolute -translate-x-1/2 -translate-y-1/2 pointer-events-none"
              :style="{ left: n.x + '%', top: n.y + '%' }"
            >
              <div class="text-[10px] font-mono px-1 rounded-tag whitespace-nowrap"
                   :style="{ transform: `translateY(${nodeRadius(n) * 3.8}px)`,
                            background: 'rgba(13,17,23,0.7)', color: nodeColor(n),
                            outline: '1px solid rgba(255,255,255,0.06)' }">
                {{ n.label }} · {{ n.score }}
              </div>
            </div>

            <!-- 左下统计 -->
            <div class="absolute bottom-4 left-4 card shadow-md !p-3 !rounded-tag text-caption space-y-1.5" style="min-width: 180px;">
              <div class="flex items-center gap-2 mb-1"><Monitor :size="14" style="color: var(--accent-cyan);"/> 图谱统计</div>
              <div class="flex justify-between"><span class="text-text-secondary">节点</span><span class="font-mono text-text-primary">{{ nodes.length }}</span></div>
              <div class="flex justify-between"><span class="text-text-secondary">边数</span><span class="font-mono text-text-primary">{{ edges.length }}</span></div>
              <div class="flex justify-between"><span class="text-text-secondary">平均密度</span><span class="font-mono text-text-primary">{{ avgDensity.toFixed(3) }}</span></div>
              <div class="flex justify-between"><span class="text-text-secondary">团伙簇</span><span class="font-mono text-text-primary">{{ groups.length }}</span></div>
            </div>
          </template>
        </div>
      </div>

      <!-- 右侧详情 -->
      <div class="space-y-4">
        <div class="card">
          <div class="card-header"><h3 class="flex items-center gap-2"><Link2 :size="16" style="color: var(--accent-cyan);"/> 选中详情</h3></div>
          <div v-if="selected" class="card-body space-y-3">
            <div class="flex items-center gap-3">
              <div class="w-12 h-12 rounded-card flex items-center justify-center text-white font-semibold shrink-0"
                   :style="{ background: nodeColor(selected) }">
                {{ selected.label.charAt(0) }}
              </div>
              <div class="min-w-0 flex-1">
                <div class="text-card-title text-text-primary truncate">{{ selected.label }}</div>
                <div class="text-caption text-text-secondary">玩家账号</div>
              </div>
              <span
                class="tag"
                :class="selected.score >= 90 ? 'tag-red' : selected.score >= 70 ? 'tag-orange' : selected.score >= 50 ? 'tag-yellow' : 'tag-green'"
              >{{ selected.score }} 分</span>
            </div>

            <div class="divider"></div>

            <!-- 基本信息 -->
            <div class="space-y-1.5 text-caption">
              <div class="flex justify-between">
                <span class="text-text-secondary">UUID</span>
                <span class="font-mono text-text-primary truncate ml-2" style="max-width: 220px;">{{ selected.id }}</span>
              </div>
              <div v-if="selected.ip" class="flex justify-between">
                <span class="text-text-secondary">IP</span>
                <span class="font-mono text-text-primary">{{ selected.ip }}</span>
              </div>
              <div v-if="selected.world" class="flex justify-between">
                <span class="text-text-secondary">所在世界</span>
                <span class="font-mono text-text-primary">{{ selected.world }}</span>
              </div>
            </div>

            <div class="divider"></div>

            <div>
              <div class="text-caption text-text-secondary mb-2">关联实体 ({{ relatedEdges.length }})</div>
              <div class="space-y-1.5">
                <div
                  v-for="(r, idx) in relatedEdges" :key="(r.other?.id ?? idx) + '-' + idx"
                  class="flex items-center gap-3 p-2 rounded-btn hover:bg-bg-hover cursor-pointer border border-transparent hover:border-border-line transition-colors"
                  @click="r.other && pick(r.other.id)"
                >
                  <div class="w-7 h-7 rounded-btn flex items-center justify-center text-[11px] font-semibold text-white shrink-0"
                       :style="{ background: r.other ? nodeColor(r.other) : 'var(--text-muted)' }">
                    {{ r.other ? r.other.label.charAt(0) : '?' }}
                  </div>
                  <div class="min-w-0 flex-1">
                    <div class="text-sm text-text-primary truncate">
                      {{ r.other ? r.other.label : '未知节点' }}
                    </div>
                    <div class="text-caption text-text-muted">
                      {{ edgeKindLabel(r.edge.kind) }}
                      <template v-if="r.other"> · 风险 {{ r.other.score }}</template>
                    </div>
                  </div>
                </div>
                <div v-if="relatedEdges.length === 0" class="text-caption text-text-muted text-center py-2">无关联实体</div>
              </div>
            </div>

            <div class="divider"></div>
            <div class="flex gap-2">
              <button class="btn btn-secondary flex-1">查看画像</button>
              <button class="btn btn-danger flex-1">联动封禁</button>
            </div>
          </div>
          <div v-else class="card-body text-center text-text-secondary text-sm">点击左侧节点查看详情</div>
        </div>

        <!-- 风险团伙 Top 5 -->
        <div class="card">
          <div class="card-header"><h3 class="flex items-center gap-2"><ShieldAlert :size="16" style="color: var(--danger-red);"/> 风险团伙 Top 5</h3></div>
          <div class="card-body p-0">
            <div v-if="topGroups.length > 0" class="divide-y divide-border-line">
              <div v-for="(t, idx) in topGroups" :key="idx" class="px-4 py-3 flex items-center gap-3 hover:bg-bg-hover cursor-pointer">
                <div class="w-7 h-7 rounded-btn flex items-center justify-center font-mono text-sm font-semibold"
                     :style="{ background: idx === 0 ? 'rgba(248,81,73,0.15)' : 'rgba(139,148,158,0.12)', color: idx === 0 ? 'var(--danger-red)' : 'var(--text-secondary)' }">
                  {{ idx + 1 }}
                </div>
                <div class="flex-1 min-w-0">
                  <div class="text-sm font-medium text-text-primary">团伙 #{{ idx + 1 }}</div>
                  <div class="text-caption text-text-secondary">{{ t.members.length }} 成员 · 密度 {{ t.density.toFixed(2) }}</div>
                </div>
                <div class="font-mono text-sm"
                     :style="{ color: t.suspicionScore >= 0.9 ? 'var(--danger-red)' : t.suspicionScore >= 0.7 ? 'var(--danger-orange)' : 'var(--warning)' }">
                  {{ Math.round(t.suspicionScore * 100) }}
                </div>
              </div>
            </div>
            <div v-else class="px-4 py-10 text-center text-text-secondary text-sm">暂无检测到的团伙</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style>
@keyframes dash { to { stroke-dashoffset: -2 } }
</style>
