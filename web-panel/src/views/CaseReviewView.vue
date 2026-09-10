<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft, Ban, Shield, UserCheck, UserX, Eye, FileText, Download,
  PlaySquare, AlertTriangle, BarChart3, ShieldAlert
} from 'lucide-vue-next'
import type { CaseEntity } from '@/types'
import { getCaseById } from '@/api/cases'
import { getReplayPlayers, getReplayArchives } from '@/api/replays'
import { formatNumber, formatDate } from '@/utils/format'
import { caseStatusMeta, scoreToHexColor, levelToCssClass, levelToLabel } from '@/utils/risk'

const route = useRoute()
const router = useRouter()
const loading = ref(true)
const caseEntity = ref<CaseEntity | null>(null)
const notFound = ref(false)
const replayLoading = ref(false)

onMounted(async () => {
  try {
    const resp = await getCaseById(route.params.id as string)
    caseEntity.value = resp.data
  } catch {
    notFound.value = true
  } finally {
    loading.value = false
  }
})

function back(): void { router.back() }
const ce = computed(() => caseEntity.value as CaseEntity)

// —— 全局 toast：复用 App.vue 的 __anticheatToast / app:toast 事件
type ToastKind = 'success' | 'error' | 'info'
interface ToastPayload { type: ToastKind; message: string; durationMs?: number }
function toast(p: ToastPayload): void {
  const w = window as typeof window & { __anticheatToast?: (p: ToastPayload) => void }
  if (typeof w.__anticheatToast === 'function') { w.__anticheatToast(p); return }
  window.dispatchEvent(new CustomEvent('app:toast', { detail: p }))
}

/**
 * 开始回放联动：
 * 1. 优先检查玩家是否在线 → 实时回放；
 * 2. 其次检查历史存档 → 加载存档；
 * 3. 都没有则提示。
 */
async function startReplay(): Promise<void> {
  if (replayLoading.value) return
  if (!import.meta.env.PROD) {
    toast({ type: 'info', message: '当前为 mock 案例数据，暂无关联的真实回放' })
    return
  }
  const uuid = ce.value?.playerUuid
  if (!uuid) {
    toast({ type: 'info', message: '该违规暂无回放数据' })
    return
  }
  replayLoading.value = true
  try {
    // 1) 优先尝试在线实时回放
    const players = await getReplayPlayers()
    const live = players.find(p => p.uuid === uuid)
    if (live) {
      router.push({ name: 'ReplayWatch', query: { uuid } })
      return
    }
    // 2) 其次尝试历史存档
    const archives = await getReplayArchives()
    // 找到该玩家最近的一份存档
    const arch = archives
      .filter(a => a.playerName === ce.value?.playerName)
      .sort((a, b) => b.startTime - a.startTime)[0]
    if (arch) {
      router.push({ name: 'ReplayWatch', query: { archive: arch.filename } })
      return
    }
    toast({ type: 'info', message: '该违规暂无回放数据' })
  } catch {
    toast({ type: 'error', message: '查询回放数据失败，请稍后重试' })
  } finally {
    replayLoading.value = false
  }
}
</script>

<template>
  <div v-if="loading" class="h-[50vh] flex items-center justify-center text-text-secondary text-sm">加载案件中…</div>
  <div v-else-if="notFound" class="card">
    <div class="card-body p-10 text-center">
      <ShieldAlert :size="40" class="mx-auto mb-4" style="color: var(--danger-red);"/>
      <h3 class="text-card-title mb-2">未找到该案件</h3>
      <button class="btn btn-secondary mt-4" @click="router.replace('/cases')">返回案件列表</button>
    </div>
  </div>

  <div v-else class="space-y-4">
    <!-- 顶部头 -->
    <div class="card">
      <div class="card-body p-5 flex flex-wrap gap-4 items-start">
        <button class="btn btn-secondary shrink-0" @click="back"><ArrowLeft :size="16"/>返回</button>
        <div class="flex-1 min-w-0">
          <div class="flex flex-wrap items-center gap-3 mb-2">
            <h2 class="m-0 text-[20px] font-semibold text-text-primary leading-tight">
              案件 <span class="font-mono text-accent-blue">{{ ce.id }}</span>
              <span class="mx-2 text-text-muted">·</span>
              玩家 {{ ce.playerName }}
            </h2>
            <span class="tag" :class="caseStatusMeta(ce.status).cls">
              <span class="status-dot" :class="caseStatusMeta(ce.status).dot" style="width:6px;height:6px;"></span>
              {{ caseStatusMeta(ce.status).label }}
            </span>
            <span class="tag" :class="levelToCssClass(ce.riskLevel)">{{ levelToLabel(ce.riskLevel) }}</span>
            <span v-if="ce.verdict" class="tag"
              :class="ce.verdict==='guilty' ? 'tag-red' : ce.verdict==='innocent' ? 'tag-green' : 'tag-cyan'">
              {{ ce.verdict === 'guilty' ? '已判有罪' : ce.verdict === 'innocent' ? '判定无罪' : '已观察' }}
            </span>
          </div>
          <div class="flex flex-wrap gap-x-5 gap-y-1 text-caption text-text-secondary">
            <span class="font-mono">创建: {{ formatDate(ce.createdAt, 'full') }}</span>
            <span class="font-mono">{{ ce.age < 24 ? ce.age + 'h ago' : Math.floor(ce.age/24) + 'd ago' }}</span>
            <span v-if="ce.assignedTo">指派: @{{ ce.assignedTo }}</span>
          </div>
        </div>
        <div class="flex flex-wrap gap-2 justify-end">
          <button class="btn btn-secondary"><FileText :size="14"/>生成报告</button>
          <button class="btn btn-secondary"><Download :size="14"/>导出证据包</button>
          <button class="btn btn-outline-cyan" :disabled="replayLoading" @click="startReplay"><PlaySquare :size="14"/>开始回放</button>
          <button class="btn btn-secondary" :disabled="ce.verdict==='innocent'"><UserCheck :size="14"/>标记无罪</button>
          <button class="btn btn-secondary" :disabled="ce.verdict==='watched'"><Eye :size="14"/>观察模式</button>
          <button class="btn btn-danger" :disabled="ce.verdict==='guilty'"><Ban :size="14"/>判为有罪 / 封禁</button>
        </div>
      </div>
    </div>

    <!-- 摘要统计 -->
    <div class="grid grid-cols-2 md:grid-cols-4 gap-4">
      <div class="stat-tile"><div>
        <div class="stat-tile-label">最高模块</div>
        <div class="stat-tile-value !text-[20px]">{{ ce.topModule }}</div>
        <div class="text-caption text-text-secondary">峰值 {{ ce.topScore }}</div>
      </div><div class="stat-tile-icon-wrap" style="background: rgba(0,229,255,0.12); color: var(--accent-cyan);"><AlertTriangle :size="22"/></div></div>
      <div class="stat-tile"><div>
        <div class="stat-tile-label">证据数量</div>
        <div class="stat-tile-value !text-[22px]">{{ ce.evidenceCount }}</div>
        <div class="text-caption text-text-secondary">条记录待复核</div>
      </div><div class="stat-tile-icon-wrap" style="background: rgba(56,139,253,0.12); color: var(--accent-blue);"><BarChart3 :size="22"/></div></div>
      <div class="stat-tile"><div>
        <div class="stat-tile-label">综合风险</div>
        <div class="stat-tile-value !text-[22px]" :style="{ color: scoreToHexColor(ce.topScore) }">{{ ce.topScore }}</div>
        <div class="text-caption text-text-secondary">{{ levelToLabel(ce.riskLevel) }}</div>
      </div><div class="stat-tile-icon-wrap" style="background: rgba(248,81,73,0.12); color: var(--danger-red);"><ShieldAlert :size="22"/></div></div>
      <div class="stat-tile"><div>
        <div class="stat-tile-label">案件年龄</div>
        <div class="stat-tile-value !text-[22px]">
          {{ ce.age < 24 ? ce.age + 'h' : Math.floor(ce.age/24) + 'd' }}
        </div>
        <div class="text-caption text-text-secondary">SLA: 24h 内办结</div>
      </div><div class="stat-tile-icon-wrap" style="background: rgba(210,153,34,0.12); color: var(--warning);"><Shield :size="22"/></div></div>
    </div>

    <!-- 模块详情 + 证据 -->
    <div class="grid grid-cols-1 lg:grid-cols-2 gap-4">
      <div class="card">
        <div class="card-header"><h3>模块触发汇总</h3></div>
        <div class="card-body space-y-3">
          <div v-for="m in ce.modules" :key="m.name">
            <div class="flex items-center justify-between mb-1.5">
              <span class="text-sm text-text-primary">{{ m.name }}</span>
              <span class="text-caption text-text-secondary font-mono">
                {{ formatNumber(m.triggerCount) }} 次 · AVG {{ m.avgScore.toFixed(0) }} · Peak
                <span :style="{ color: scoreToHexColor(m.peakScore) }">{{ m.peakScore }}</span>
              </span>
            </div>
            <div class="risk-bar h-2"><div class="risk-bar-fill" :style="{ width: m.peakScore + '%', background: scoreToHexColor(m.peakScore) }"></div></div>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card-header"><h3>证据摘要</h3></div>
        <div class="card-body p-0">
          <div class="table-wrap">
            <table class="data-table">
              <thead><tr><th>类型</th><th>数量</th><th>峰值分</th><th>最近时间</th></tr></thead>
              <tbody>
                <tr v-for="e in ce.evidenceSummary" :key="e.type">
                  <td class="text-text-primary">{{ e.type }}</td>
                  <td class="font-mono text-text-primary">{{ e.count }}</td>
                  <td class="font-mono" :style="{ color: scoreToHexColor(e.peakScore) }">{{ e.peakScore }}</td>
                  <td class="text-caption text-text-secondary font-mono">{{ formatDate(e.lastTime, 'relative') }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>

    <!-- 回放与协作占位 -->
    <div class="card">
      <div class="card-header">
        <h3 class="flex items-center gap-2"><PlaySquare :size="16" style="color: var(--accent-cyan);"/> 证据回放工作区</h3>
        <div class="flex gap-2">
          <button class="btn btn-sm btn-secondary">上一条</button>
          <button class="btn btn-sm btn-primary">▶ 播放</button>
          <button class="btn btn-sm btn-secondary">下一条</button>
        </div>
      </div>
      <div class="card-body">
        <div class="w-full h-[340px] rounded-card flex items-center justify-center text-text-secondary border border-dashed border-border-line"
             style="background: repeating-linear-gradient(135deg, rgba(255,255,255,0.02) 0 12px, transparent 12px 24px);">
          <div class="text-center">
            <PlaySquare :size="42" class="mx-auto mb-3 opacity-60"/>
            <div class="text-sm text-text-secondary">回放引擎已就绪 · 选择左侧证据条目开始查看</div>
            <div class="text-caption text-text-muted mt-1">支持 1x / 2x / 4x 倍速 + 三维视角切换</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
