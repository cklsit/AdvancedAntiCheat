<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart, PieChart } from 'echarts/charts'
import {
  TitleComponent, TooltipComponent, LegendComponent, GridComponent, DatasetComponent
} from 'echarts/components'
import {
  Users, Ban, AlertTriangle, FolderKanban, Sparkles, Swords, Gauge,
  Server, Cpu, Activity, ShieldAlert, RefreshCw, Brain
} from 'lucide-vue-next'
import type { DashboardStats } from '@/types'
import { RiskLevel } from '@/types'
import { getDashboardStats } from '@/api/dashboard'
import { formatNumber } from '@/utils/format'

use([CanvasRenderer, BarChart, PieChart,
  TitleComponent, TooltipComponent, LegendComponent, GridComponent, DatasetComponent])

// ========== 数据加载 ==========
const loading = ref(true)
const error = ref<string | null>(null)
const stats = ref<DashboardStats | null>(null)

async function loadStats(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const resp = await getDashboardStats()
    stats.value = resp.data
  } catch (e) {
    const status = (e as any)?.response?.status
    if (status === 401) error.value = '登录已过期，请重新登录后再试'
    else if (status === 403) error.value = '当前账号无权访问检测统计'
    else if (status >= 500) error.value = '服务器内部错误'
    else error.value = '加载检测数据失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void loadStats()
})

const ds = computed(() => stats.value as DashboardStats)

// ========== 模块触发排名（柱状图，替代假 Loss 曲线） ==========
const moduleOption = computed(() => {
  if (!stats.value) return {}
  const data = stats.value.moduleTriggers
  return {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, backgroundColor: '#161B22', borderColor: '#30363D', textStyle: { color: '#E6EDF3', fontSize: 12 } },
    grid: { left: 80, right: 50, top: 10, bottom: 10 },
    xAxis: { type: 'value', axisLine: { show: false }, splitLine: { lineStyle: { color: '#1C2333' } }, axisLabel: { color: '#8B949E', fontSize: 11 } },
    yAxis: {
      type: 'category', data: data.map((d) => d.module).reverse(),
      axisLine: { lineStyle: { color: '#30363D' } }, axisTick: { show: false },
      axisLabel: { color: '#E6EDF3', fontSize: 12 }
    },
    series: [{
      type: 'bar', data: data.map((d) => d.count).reverse(), barWidth: 14,
      itemStyle: {
        borderRadius: [0, 4, 4, 0],
        color: { type: 'linear', x: 0, y: 0, x2: 1, y2: 0,
          colorStops: [{ offset: 0, color: '#388BFD' }, { offset: 1, color: '#00E5FF' }] }
      },
      label: {
        show: true, position: 'right', color: '#8B949E', fontSize: 11,
        formatter: (p: { dataIndex: number }) => {
          const d = data.slice().reverse()[p.dataIndex]
          return (d.trend >= 0 ? '↑ ' : '↓ ') + Math.abs(d.trend).toFixed(1) + '%'
        }
      }
    }]
  }
})

// ========== 风险分布（环形图，替代假混淆矩阵） ==========
const riskOption = computed(() => {
  if (!stats.value) return {}
  const levels = ['低风险', '中风险', '高风险', '极高风险']
  const colors = ['#3FB950', '#D29922', '#FF6D00', '#F85149']
  const order: RiskLevel[] = [RiskLevel.LOW, RiskLevel.MEDIUM, RiskLevel.HIGH, RiskLevel.EXTREME]
  const byLevel = new Map<RiskLevel, number>(stats.value.riskDistribution.map((r) => [r.level, r.count]))
  return {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'item', backgroundColor: '#161B22', borderColor: '#30363D', textStyle: { color: '#E6EDF3', fontSize: 12 }, formatter: '{b}: {c} ({d}%)' },
    legend: { bottom: 0, textStyle: { color: '#8B949E', fontSize: 11 }, itemWidth: 8, itemHeight: 8 },
    series: [{
      type: 'pie', radius: ['58%', '78%'], center: ['50%', '45%'],
      avoidLabelOverlap: true, label: { show: false }, labelLine: { show: false },
      data: order.map((lvl, i) => ({ name: levels[i], value: byLevel.get(lvl) ?? 0, itemStyle: { color: colors[i] } }))
    }]
  }
})

// ========== 检测引擎状态（真实服务器状态） ==========
const healthyCount = computed(() => stats.value?.serverStatus.filter((s) => s.status === 'healthy').length ?? 0)
const warningCount = computed(() => stats.value?.serverStatus.filter((s) => s.status === 'warning').length ?? 0)
const criticalCount = computed(() => stats.value?.serverStatus.filter((s) => s.status === 'critical').length ?? 0)
const avgTps = computed(() => {
  const arr = stats.value?.serverStatus ?? []
  if (arr.length === 0) return 0
  return arr.reduce((s, x) => s + x.tps, 0) / arr.length
})
const avgMem = computed(() => {
  const arr = stats.value?.serverStatus ?? []
  if (arr.length === 0) return 0
  return arr.reduce((s, x) => s + x.memory, 0) / arr.length
})
const totalOnline = computed(() => stats.value?.serverStatus.reduce((s, x) => s + x.online, 0) ?? 0)
</script>

<template>
  <div class="space-y-4">
    <!-- 顶部标题区 -->
    <div class="flex items-start justify-between gap-4">
      <div class="flex items-center gap-3">
        <div class="w-10 h-10 rounded-card flex items-center justify-center"
             style="background: rgba(188,140,255,0.12); color: var(--accent-purple);">
          <Sparkles :size="20"/>
        </div>
        <div>
          <h2 class="m-0 text-xl font-semibold text-text-primary leading-tight">AI 实验室</h2>
          <p class="m-0 mt-0.5 text-caption text-text-secondary">真实检测数据概览</p>
        </div>
      </div>
      <button class="btn btn-sm btn-ghost text-text-secondary" @click="loadStats">
        <RefreshCw :size="14"/> 刷新
      </button>
    </div>

    <!-- loading -->
    <div v-if="loading" class="h-[60vh] flex items-center justify-center text-text-secondary">
      <div class="flex items-center gap-3 text-sm"><Activity :size="18" class="animate-pulse text-accent-cyan"/> 正在加载检测数据…</div>
    </div>

    <!-- error -->
    <div v-else-if="error" class="h-[40vh] flex flex-col items-center justify-center gap-3 text-center">
      <ShieldAlert :size="28" style="color: var(--danger-red);"/>
      <div class="text-sm text-text-secondary">{{ error }}</div>
      <button class="btn btn-sm btn-ghost" @click="loadStats">重试</button>
    </div>

    <template v-else>
      <!-- 统计卡 -->
      <div class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-4">
        <div class="stat-tile">
          <div>
            <div class="stat-tile-label">在线玩家</div>
            <div class="stat-tile-value">{{ formatNumber(ds.onlinePlayers) }}</div>
            <div class="mt-1 flex items-center gap-2 text-caption text-text-secondary">
              <span class="status-dot online"></span>
              总注册 {{ formatNumber(ds.totalPlayers, true) }}
            </div>
          </div>
          <div class="stat-tile-icon-wrap" style="background: rgba(0,229,255,0.12); color: var(--accent-cyan);"><Users :size="22"/></div>
        </div>

        <div class="stat-tile">
          <div>
            <div class="stat-tile-label">今日违规</div>
            <div class="stat-tile-value">{{ formatNumber(ds.todayViolations, true) }}</div>
            <div class="mt-1 flex items-center gap-2 text-caption text-text-secondary">
              <AlertTriangle :size="14" style="color: var(--warning);"/> 实时累计
            </div>
          </div>
          <div class="stat-tile-icon-wrap" style="background: rgba(210,153,34,0.12); color: var(--warning);"><AlertTriangle :size="22"/></div>
        </div>

        <div class="stat-tile">
          <div>
            <div class="stat-tile-label">今日封禁</div>
            <div class="stat-tile-value">{{ ds.todayBans }}</div>
            <div class="mt-1 flex items-center gap-2 text-caption text-text-secondary">
              <Ban :size="14" style="color: var(--danger-red);"/> 自动判决
            </div>
          </div>
          <div class="stat-tile-icon-wrap" style="background: rgba(248,81,73,0.12); color: var(--danger-red);"><Ban :size="22"/></div>
        </div>

        <div class="stat-tile">
          <div>
            <div class="stat-tile-label">待审案件</div>
            <div class="stat-tile-value">{{ ds.activeCases }}</div>
            <div class="mt-1 flex items-center gap-2 text-caption text-text-secondary">
              <FolderKanban :size="14" style="color: var(--accent-blue);"/> 等待人审
            </div>
          </div>
          <div class="stat-tile-icon-wrap" style="background: rgba(56,139,253,0.12); color: var(--accent-blue);"><FolderKanban :size="22"/></div>
        </div>
      </div>

      <!-- 模块触发排名 + 风险分布 -->
      <div class="grid grid-cols-1 xl:grid-cols-2 gap-4">
        <div class="card">
          <div class="card-header">
            <h3 class="flex items-center gap-2"><Swords :size="16" style="color: var(--accent-blue);"/> 检测模块触发排名</h3>
            <span class="text-caption text-text-secondary">真实触发统计</span>
          </div>
          <div class="card-body" style="height: 300px;">
            <VChart :option="moduleOption" autoresize style="width:100%;height:100%"/>
          </div>
        </div>

        <div class="card">
          <div class="card-header">
            <h3 class="flex items-center gap-2"><Gauge :size="16" style="color: var(--accent-purple);"/> 玩家风险分布</h3>
            <span class="text-caption text-text-secondary">{{ formatNumber(ds.totalPlayers, true) }} 账号</span>
          </div>
          <div class="card-body" style="height: 300px;">
            <VChart :option="riskOption" autoresize style="width:100%;height:100%"/>
          </div>
        </div>
      </div>

      <!-- 检测引擎状态 -->
      <div class="grid grid-cols-1 xl:grid-cols-3 gap-4">
        <div class="card xl:col-span-2">
          <div class="card-header">
            <h3 class="flex items-center gap-2"><Server :size="16" style="color: var(--success);"/> 检测引擎状态</h3>
            <div class="flex items-center gap-3 text-caption text-text-secondary">
              <span><Cpu :size="14" class="inline mr-1"/> TPS 健康区 ≥ 18</span>
            </div>
          </div>
          <div class="card-body p-0">
            <div class="table-wrap">
              <table class="data-table">
                <thead>
                  <tr>
                    <th>服务器</th><th>区域</th><th>在线</th><th>TPS</th><th>内存</th><th>状态</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="s in ds.serverStatus" :key="s.id">
                    <td class="font-medium text-text-primary">{{ s.name }}</td>
                    <td class="text-text-secondary">{{ s.region }}</td>
                    <td class="text-text-primary font-mono">{{ s.online }}</td>
                    <td>
                      <div class="flex items-center gap-3">
                        <span class="font-mono" :style="{ color: s.tps >= 18 ? 'var(--success)' : s.tps >= 15 ? 'var(--warning)' : 'var(--danger-red)' }">{{ s.tps.toFixed(1) }}</span>
                        <div class="w-24 h-1.5 rounded-full bg-bg-hover overflow-hidden">
                          <div class="h-full rounded-full transition-all" :style="{ width: Math.min(100, s.tps / 20 * 100) + '%', background: s.tps >= 18 ? 'var(--success)' : s.tps >= 15 ? 'var(--warning)' : 'var(--danger-red)' }"></div>
                        </div>
                      </div>
                    </td>
                    <td>
                      <div class="flex items-center gap-3">
                        <span class="font-mono text-text-secondary">{{ s.memory }}%</span>
                        <div class="w-24 h-1.5 rounded-full bg-bg-hover overflow-hidden">
                          <div class="h-full rounded-full" :style="{ width: s.memory + '%', background: s.memory < 70 ? 'var(--accent-blue)' : s.memory < 85 ? 'var(--warning)' : 'var(--danger-red)' }"></div>
                        </div>
                      </div>
                    </td>
                    <td>
                      <span class="tag" :class="s.status === 'healthy' ? 'tag-green' : s.status === 'warning' ? 'tag-yellow' : 'tag-red'">
                        <span class="status-dot" :class="s.status === 'healthy' ? 'online' : s.status === 'warning' ? 'watching' : 'banned'" style="width:6px;height:6px;"></span>
                        {{ s.status === 'healthy' ? '正常' : s.status === 'warning' ? '告警' : '异常' }}
                      </span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>

        <div class="card flex flex-col">
          <div class="card-header"><h3 class="flex items-center gap-2"><Brain :size="16" style="color: var(--accent-purple);"/> 引擎概要</h3></div>
          <div class="card-body space-y-3 text-sm flex-1">
            <div class="flex justify-between"><span class="text-text-secondary">服务器实例</span><span class="font-mono text-text-primary">{{ ds.serverStatus.length }}</span></div>
            <div class="flex justify-between"><span class="text-text-secondary">总在线</span><span class="font-mono text-text-primary">{{ formatNumber(totalOnline) }}</span></div>
            <div class="flex justify-between"><span class="text-text-secondary">平均 TPS</span><span class="font-mono" :style="{ color: avgTps >= 18 ? 'var(--success)' : avgTps >= 15 ? 'var(--warning)' : 'var(--danger-red)' }">{{ avgTps.toFixed(1) }}</span></div>
            <div class="flex justify-between"><span class="text-text-secondary">平均内存</span><span class="font-mono text-text-primary">{{ avgMem.toFixed(0) }}%</span></div>
            <div class="flex justify-between"><span class="text-text-secondary">正常 / 告警 / 异常</span>
              <span class="font-mono">
                <span style="color: var(--success);">{{ healthyCount }}</span>
                <span class="text-text-muted"> / </span>
                <span style="color: var(--warning);">{{ warningCount }}</span>
                <span class="text-text-muted"> / </span>
                <span style="color: var(--danger-red);">{{ criticalCount }}</span>
              </span>
            </div>
            <div class="divider my-0"></div>
            <div class="p-3 rounded-btn" style="background: rgba(188,140,255,0.08); border: 1px solid rgba(188,140,255,0.3);">
              <div class="flex items-start gap-2 mb-1.5">
                <Sparkles :size="16" style="color: var(--accent-purple); flex-shrink: 0; margin-top: 1px;"/>
                <span class="text-sm font-medium text-text-primary">检测说明</span>
              </div>
              <p class="text-caption text-text-secondary m-0 leading-relaxed">
                基于贝叶斯概率融合的真实检测数据，模块触发与风险分布均为实时统计。
              </p>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
