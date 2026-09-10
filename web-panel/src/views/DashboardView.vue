<script setup lang="ts">
import { ref, onMounted, computed, h } from 'vue'
import { useRouter } from 'vue-router'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, BarChart, PieChart, GaugeChart } from 'echarts/charts'
import {
  TitleComponent, TooltipComponent, LegendComponent, GridComponent, DatasetComponent
} from 'echarts/components'
import {
  Users, Ban, AlertTriangle, FolderKanban, Server, Cpu, Activity,
  ChevronRight, ShieldAlert, Swords, Footprints, Gauge, PlaySquare
} from 'lucide-vue-next'
import type { DashboardStats, AlertItem } from '@/types'
import { getDashboardStats, getRecentAlerts } from '@/api/dashboard'
import { formatNumber, formatDate } from '@/utils/format'
import { scoreToHexColor } from '@/utils/risk'

use([CanvasRenderer, LineChart, BarChart, PieChart, GaugeChart,
  TitleComponent, TooltipComponent, LegendComponent, GridComponent, DatasetComponent])

const router = useRouter()
const loading = ref(true)
const stats = ref<DashboardStats | null>(null)
const alerts = ref<AlertItem[]>([])

onMounted(async () => {
  const [s, a] = await Promise.all([
    getDashboardStats().then((r) => r.data),
    getRecentAlerts().then((r) => r.data).catch(() => [])
  ])
  stats.value = s
  alerts.value = a
  loading.value = false
})

const ds = computed(() => stats.value as DashboardStats)

// ========== 趋势图 ==========
const trendOption = computed(() => {
  if (!stats.value) return {}
  return {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis', backgroundColor: '#161B22', borderColor: '#30363D', textStyle: { color: '#E6EDF3', fontSize: 12 } },
    legend: { textStyle: { color: '#8B949E' }, right: 10, top: 4, itemWidth: 10, itemHeight: 10 },
    grid: { left: 36, right: 20, top: 40, bottom: 28 },
    xAxis: {
      type: 'category', data: stats.value.hourlyTrend.map((h) => h.hour),
      axisLine: { lineStyle: { color: '#30363D' } }, axisLabel: { color: '#8B949E', fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      axisLine: { show: false }, splitLine: { lineStyle: { color: '#1C2333' } },
      axisLabel: { color: '#8B949E', fontSize: 11 }
    },
    series: [
      {
        name: '违规事件',
        type: 'line', smooth: true, symbol: 'none',
        lineStyle: { width: 2, color: '#00E5FF' },
        areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [
          { offset: 0, color: 'rgba(0,229,255,0.35)' }, { offset: 1, color: 'rgba(0,229,255,0.02)' }
        ]}},
        data: stats.value.hourlyTrend.map((h) => h.violations)
      },
      {
        name: '自动封禁',
        type: 'bar', barWidth: 10,
        itemStyle: { color: '#F85149', borderRadius: [3, 3, 0, 0] },
        data: stats.value.hourlyTrend.map((h) => h.bans)
      }
    ]
  }
})

// 风险分布环形图
const riskOption = computed(() => {
  if (!stats.value) return {}
  const levels = ['低风险', '中风险', '高风险', '极高风险']
  const colors = ['#3FB950', '#D29922', '#FF6D00', '#F85149']
  return {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'item', backgroundColor: '#161B22', borderColor: '#30363D', textStyle: { color: '#E6EDF3', fontSize: 12 }, formatter: '{b}: {c} ({d}%)' },
    legend: { bottom: 0, textStyle: { color: '#8B949E', fontSize: 11 }, itemWidth: 8, itemHeight: 8 },
    series: [{
      type: 'pie', radius: ['58%', '78%'], center: ['50%', '45%'],
      avoidLabelOverlap: true, label: { show: false }, labelLine: { show: false },
      data: stats.value.riskDistribution.map((r, i) => ({ name: levels[i], value: r.count, itemStyle: { color: colors[i] } }))
    }]
  }
})

// 模块触发横条图
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

function alertLevelColor(level: string): string {
  return level === 'critical' ? 'var(--danger-red)'
    : level === 'high' ? 'var(--danger-orange)'
    : level === 'medium' ? 'var(--warning)' : 'var(--accent-blue)'
}

function openPlayer(playerName?: string): void {
  if (!playerName) return
  router.push(`/players?k=${encodeURIComponent(playerName)}`).catch(() => void 0)
}
</script>

<template>
  <div v-if="loading" class="h-[60vh] flex items-center justify-center text-text-secondary">
    <div class="flex items-center gap-3 text-sm"><Activity :size="18" class="animate-pulse text-accent-cyan"/> 正在加载仪表盘…</div>
  </div>

  <div v-else class="space-y-4">
    <!-- 顶部统计卡 -->
    <div class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-4">
      <div class="stat-tile">
        <div>
          <div class="stat-tile-label">在线玩家 / 总注册</div>
          <div class="stat-tile-value">{{ formatNumber(ds.onlinePlayers) }} <span class="text-sm font-normal text-text-secondary">/ {{ formatNumber(ds.totalPlayers, true) }}</span></div>
          <div class="mt-1 flex items-center gap-2 text-caption">
            <span class="status-dot online"></span>
            <span class="text-text-secondary">来自 {{ ds.serverStatus.length }} 个服务器实例</span>
          </div>
        </div>
        <div class="stat-tile-icon-wrap" style="background: rgba(0,229,255,0.12); color: var(--accent-cyan);"><Users :size="22"/></div>
      </div>

      <div class="stat-tile">
        <div>
          <div class="stat-tile-label">今日违规事件</div>
          <div class="stat-tile-value">{{ formatNumber(ds.todayViolations, true) }}</div>
          <div class="mt-1 flex items-center gap-2 text-caption text-text-secondary">
            今日累计统计
          </div>
        </div>
        <div class="stat-tile-icon-wrap" style="background: rgba(210,153,34,0.12); color: var(--warning);"><AlertTriangle :size="22"/></div>
      </div>

      <div class="stat-tile">
        <div>
          <div class="stat-tile-label">今日自动封禁</div>
          <div class="stat-tile-value">{{ ds.todayBans }}</div>
          <div class="mt-1 flex items-center gap-2 text-caption text-text-secondary">
            <Ban :size="14" style="color: var(--danger-red);"/> 自动执行
          </div>
        </div>
        <div class="stat-tile-icon-wrap" style="background: rgba(248,81,73,0.12); color: var(--danger-red);"><Ban :size="22"/></div>
      </div>

      <div class="stat-tile">
        <div>
          <div class="stat-tile-label">待处理案件</div>
          <div class="stat-tile-value">{{ ds.activeCases }}</div>
          <div class="mt-1 flex items-center gap-2 text-caption text-text-secondary">
            <FolderKanban :size="14" style="color: var(--accent-blue);"/> 待人工审理
          </div>
        </div>
        <div class="stat-tile-icon-wrap" style="background: rgba(56,139,253,0.12); color: var(--accent-blue);"><FolderKanban :size="22"/></div>
      </div>
    </div>

    <!-- 主体：趋势图 + 侧栏告警 -->
    <div class="grid grid-cols-1 xl:grid-cols-3 gap-4">
      <div class="card xl:col-span-2">
        <div class="card-header">
          <h3 class="flex items-center gap-2"><Activity :size="16" style="color: var(--accent-cyan);"/> 24 小时违规趋势</h3>
          <div class="flex items-center gap-2 text-caption text-text-secondary">
            <span class="tag tag-cyan">实时</span>
            <span>每 10s 刷新</span>
          </div>
        </div>
        <div class="card-body" style="height: 320px;">
          <v-chart :option="trendOption" autoresize style="width: 100%; height: 100%;" />
        </div>
      </div>

      <div class="card flex flex-col">
        <div class="card-header">
          <h3 class="flex items-center gap-2"><ShieldAlert :size="16" style="color: var(--danger-red);"/> 实时告警</h3>
          <button class="btn btn-sm btn-ghost text-text-secondary" @click="router.push('/cases')">全部 <ChevronRight :size="14"/></button>
        </div>
        <div class="card-body flex-1 overflow-y-auto p-0">
          <div
            v-for="a in alerts" :key="a.id"
            class="px-4 py-3 border-b last:border-b-0 border-border-line hover:bg-bg-hover cursor-pointer transition-colors"
            @click="openPlayer(a.playerName)"
          >
            <div class="flex items-start gap-3">
              <span class="mt-1 w-2 h-2 rounded-full shrink-0" :style="{ background: alertLevelColor(a.level) }"></span>
              <div class="min-w-0 flex-1">
                <div class="flex items-center justify-between gap-2 mb-1">
                  <span class="text-sm font-medium text-text-primary truncate">{{ a.title }}</span>
                  <span class="tag" :class="a.level==='critical'||a.level==='high' ? 'tag-red' : a.level==='medium' ? 'tag-yellow' : 'tag-blue'">
                    {{ a.module }} {{ a.score ? '· ' + a.score : '' }}
                  </span>
                </div>
                <p class="text-caption text-text-secondary m-0 line-clamp-2">{{ a.message }}</p>
                <div class="mt-1 flex items-center justify-between gap-2">
                  <span class="text-caption text-text-muted font-mono">{{ formatDate(a.time, 'relative') }}</span>
                  <!-- 违规回放联动：告警带 replayId 时可直接跳转对应回放 -->
                  <button v-if="a.replayId" class="btn btn-sm btn-ghost !px-2 !py-0.5 gap-1" title="查看该违规的回放"
                          @click.stop="router.push({ path: '/replay', query: { replayId: String(a.replayId) } })">
                    <PlaySquare :size="14" style="color: var(--accent-cyan);"/>回放
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 中部 风险分布 + 模块统计 -->
    <div class="grid grid-cols-1 lg:grid-cols-2 gap-4">
      <div class="card">
        <div class="card-header">
          <h3 class="flex items-center gap-2"><Gauge :size="16" style="color: var(--accent-purple);"/> 玩家风险分布</h3>
          <span class="text-caption text-text-secondary">{{ formatNumber(ds.totalPlayers, true) }} 个账号画像</span>
        </div>
        <div class="card-body" style="height: 280px;">
          <v-chart :option="riskOption" autoresize style="width: 100%; height: 100%;" />
        </div>
      </div>

      <div class="card">
        <div class="card-header">
          <h3 class="flex items-center gap-2"><Swords :size="16" style="color: var(--accent-blue);"/> 模块触发排名</h3>
          <span class="text-caption text-text-secondary">相比上一周期</span>
        </div>
        <div class="card-body" style="height: 280px;">
          <v-chart :option="moduleOption" autoresize style="width: 100%; height: 100%;" />
        </div>
      </div>
    </div>

    <!-- 服务器健康 -->
    <div class="card">
      <div class="card-header">
        <h3 class="flex items-center gap-2"><Server :size="16" style="color: var(--success);"/> 服务器状态</h3>
        <div class="flex items-center gap-3 text-caption text-text-secondary">
          <span><Cpu :size="14" class="inline mr-1"/> TPS 健康区 ≥ 18</span>
        </div>
      </div>
      <div class="card-body p-0">
        <div class="table-wrap">
          <table class="data-table">
            <thead>
              <tr>
                <th>服务器</th><th>区域</th><th>在线</th><th>TPS</th><th>内存占用</th><th>状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in ds.serverStatus" :key="s.id" class="cursor-pointer">
                <td class="font-medium text-text-primary">{{ s.name }}</td>
                <td class="text-text-secondary">{{ s.region }}</td>
                <td class="text-text-primary font-mono">{{ s.online }}</td>
                <td>
                  <div class="flex items-center gap-3">
                    <span class="font-mono" :style="{ color: s.tps >= 18 ? 'var(--success)' : s.tps >= 15 ? 'var(--warning)' : 'var(--danger-red)' }">{{ s.tps.toFixed(1) }}</span>
                    <div class="w-32 h-1.5 rounded-full bg-bg-hover overflow-hidden">
                      <div class="h-full rounded-full transition-all" :style="{ width: Math.min(100, s.tps / 20 * 100) + '%', background: s.tps >= 18 ? 'var(--success)' : s.tps >= 15 ? 'var(--warning)' : 'var(--danger-red)' }"></div>
                    </div>
                  </div>
                </td>
                <td>
                  <div class="flex items-center gap-3">
                    <span class="font-mono text-text-secondary">{{ s.memory }}%</span>
                    <div class="w-32 h-1.5 rounded-full bg-bg-hover overflow-hidden">
                      <div class="h-full rounded-full" :style="{ width: s.memory + '%', background: s.memory < 70 ? 'var(--accent-blue)' : s.memory < 85 ? 'var(--warning)' : 'var(--danger-red)' }"></div>
                    </div>
                  </div>
                </td>
                <td>
                  <span
                    class="tag"
                    :class="s.status === 'healthy' ? 'tag-green' : s.status === 'warning' ? 'tag-yellow' : 'tag-red'"
                  >
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
  </div>
</template>

<style scoped>
.line-clamp-2 {
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
</style>
