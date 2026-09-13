<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import {
  Sparkles, RefreshCw, Activity, ShieldAlert, Brain, FlaskConical, Tags, Settings2,
  Radar, Play, RotateCcw, Trash2, Check, X, Plus, Undo2, Eye, Cpu, Database, Users2
} from 'lucide-vue-next'
import type {
  AILabCluster, AILabLabel, AILabOverview, AILabPlayerDetail,
  AILabSettings, AILabSimulateResult, AILabThresholds, AILabFeatureDictItem, AILabModelVersion
} from '@/types/ailab'
import {
  getAILabOverview, getClusters, resolveCluster, getLabels, addLabel, correctLabel, deleteLabel,
  triggerTrain, getModels, rollbackModel, simulate, getAILabSettings, updateAILabSettings,
  getThresholds, resetPlayerBaseline, getFeatureDict
} from '@/api/ailab'

type TabKey = 'dashboard' | 'clusters' | 'labels' | 'simulator' | 'settings'

const tabs: { key: TabKey, label: string, icon: typeof Brain }[] = [
  { key: 'dashboard', label: '模型仪表盘', icon: Brain },
  { key: 'clusters', label: '异常集群', icon: Radar },
  { key: 'labels', label: '标签管理', icon: Tags },
  { key: 'simulator', label: '评分模拟器', icon: FlaskConical },
  { key: 'settings', label: '学习设置', icon: Settings2 }
]

const activeTab = ref<TabKey>('dashboard')
const enabled = ref(true)
const toast = (type: 'success' | 'error', message: string) => {
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent('app:toast', { detail: { type, message } }))
  }
}
const fmtTime = (ts?: number) => ts ? new Date(ts).toLocaleString('zh-CN', { hour12: false }) : '—'

// ==================== 模型仪表盘 ====================
const overview = ref<AILabOverview | null>(null)
const thresholds = ref<AILabThresholds | null>(null)
const dashLoading = ref(false)
const dashError = ref<string | null>(null)

async function loadDashboard() {
  dashLoading.value = true
  dashError.value = null
  try {
    const [ov, th] = await Promise.all([getAILabOverview(), getThresholds()])
    overview.value = ov
    thresholds.value = th
    enabled.value = ov.enabled
  } catch (e) {
    dashError.value = (e as Error)?.message || '加载失败'
  } finally {
    dashLoading.value = false
  }
}

const health = computed(() => overview.value?.health)
const activeModel = computed(() => overview.value?.activeModel)
const settings = computed(() => overview.value?.settings)
const topPlayers = computed(() => overview.value?.topPlayers ?? [])

function scoreColor(v: number): string {
  if (v < 0) return 'var(--text-muted)'
  if (v >= 0.9) return 'var(--danger-red)'
  if (v >= 0.5) return 'var(--warning)'
  return 'var(--success)'
}

// ==================== 异常集群 ====================
const clusters = ref<AILabCluster[]>([])
const clusterLoading = ref(false)

async function loadClusters() {
  clusterLoading.value = true
  try {
    clusters.value = await getClusters()
  } catch (e) {
    toast('error', (e as Error)?.message || '集群加载失败')
  } finally {
    clusterLoading.value = false
  }
}

async function handleClusterAction(c: AILabCluster, action: 'dismiss' | 'rule') {
  const label = action === 'rule' ? '转为临时规则' : '标记为误报并丢弃'
  if (!window.confirm(`确认将集群 ${c.id}（${c.memberCount} 名玩家）${label}？`)) return
  try {
    await resolveCluster(c.id, action)
    toast('success', `集群 ${c.id} 已${label}`)
    await loadClusters()
  } catch (e) {
    toast('error', (e as Error)?.message || '操作失败')
  }
}

// ==================== 标签管理 ====================
const labels = ref<AILabLabel[]>([])
const labelTotal = ref(0)
const labelFilter = ref(-1)
const labelSource = ref('')
const labelName = ref('')
const labelPage = ref(0)
const labelLoading = ref(false)
const showAddForm = ref(false)
const addForm = ref({ uuid: '', cheat: true, note: '' })

const sourceOptions = ['ADMIN', 'CAPTCHA_FAIL', 'CAPTCHA_PASS', 'AUTO_BAN', 'BOUNTY', 'CLUSTER_DISMISS', 'WHITELIST']

async function loadLabels() {
  labelLoading.value = true
  try {
    const r = await getLabels({
      label: labelFilter.value,
      source: labelSource.value,
      name: labelName.value,
      page: labelPage.value,
      size: 15
    })
    labels.value = r.items
    labelTotal.value = r.total
  } catch (e) {
    toast('error', (e as Error)?.message || '标签加载失败')
  } finally {
    labelLoading.value = false
  }
}

async function submitLabel() {
  if (!addForm.value.uuid) { toast('error', '请填写玩家 UUID'); return }
  try {
    await addLabel({ ...addForm.value, source: 'ADMIN' })
    toast('success', '标签已添加')
    showAddForm.value = false
    addForm.value = { uuid: '', cheat: true, note: '' }
    await loadLabels()
  } catch (e) {
    toast('error', (e as Error)?.message || '添加失败')
  }
}

async function toggleLabel(s: AILabLabel) {
  try {
    await correctLabel(s.id, s.label === 1 ? 0 : 1)
    toast('success', `样本 #${s.id} 已修正为 ${s.label === 1 ? '正常' : '作弊'}`)
    await loadLabels()
  } catch (e) {
    toast('error', (e as Error)?.message || '修正失败')
  }
}

async function removeLabel(s: AILabLabel) {
  if (!window.confirm(`确认删除样本 #${s.id}（${s.playerName}）？`)) return
  try {
    await deleteLabel(s.id)
    toast('success', '样本已删除')
    await loadLabels()
  } catch (e) {
    toast('error', (e as Error)?.message || '删除失败')
  }
}

// ==================== 评分模拟器 ====================
const featureDict = ref<AILabFeatureDictItem[]>([])
const simMode = ref<'player' | 'features'>('player')
const simPlayer = ref('')
const simFeatures = ref<Record<string, number>>({})
const simResult = ref<AILabSimulateResult | null>(null)
const simLoading = ref(false)

async function loadFeatureDict() {
  if (featureDict.value.length > 0) return
  try {
    featureDict.value = await getFeatureDict()
  } catch { /* 非关键数据 */ }
}

async function runSimulate() {
  simLoading.value = true
  simResult.value = null
  try {
    const body = simMode.value === 'player'
      ? { player: simPlayer.value }
      : { features: Object.fromEntries(Object.entries(simFeatures.value).filter(([, v]) => Number.isFinite(v))) }
    simResult.value = await simulate(body)
  } catch (e) {
    toast('error', (e as Error)?.message || '模拟失败')
  } finally {
    simLoading.value = false
  }
}

const simKeyFeatures = ['cpsMean', 'yawRateEntropy', 'attackIntervalEntropy', 'hSpeedMean', 'pitchExtremeRatio', 'hitRatio']

// ==================== 学习设置 ====================
const settingsForm = ref<AILabSettings | null>(null)
const models = ref<AILabModelVersion[]>([])
const settingsLoading = ref(false)
const resetUuid = ref('')

async function loadSettings() {
  settingsLoading.value = true
  try {
    const [s, m] = await Promise.all([getAILabSettings(), getModels()])
    settingsForm.value = s
    models.value = m
  } catch (e) {
    toast('error', (e as Error)?.message || '设置加载失败')
  } finally {
    settingsLoading.value = false
  }
}

async function saveSettings() {
  if (!settingsForm.value) return
  try {
    settingsForm.value = await updateAILabSettings(settingsForm.value)
    toast('success', 'AI 设置已更新（运行时生效，重启后以 config.yml 为准）')
  } catch (e) {
    toast('error', (e as Error)?.message || '保存失败')
  }
}

async function rollback(v: AILabModelVersion) {
  if (!window.confirm(`确认回滚到模型 v${v.version}（AUC=${v.auc}）？`)) return
  try {
    await rollbackModel(v.version)
    toast('success', `已回滚到 v${v.version}`)
    await Promise.all([loadSettings(), loadDashboard()])
  } catch (e) {
    toast('error', (e as Error)?.message || '回滚失败')
  }
}

async function manualTrain() {
  try {
    await triggerTrain()
    toast('success', '训练任务已受理，完成后模型自动热切换')
  } catch (e) {
    toast('error', (e as Error)?.message || '触发失败')
  }
}

async function doResetBaseline() {
  if (!resetUuid.value) { toast('error', '请填写玩家 UUID'); return }
  try {
    const r = await resetPlayerBaseline(resetUuid.value)
    toast(r.reset ? 'success' : 'error', r.reset ? '该玩家基线已重置，将重新学习' : '重置失败（玩家不在线或不存在）')
  } catch (e) {
    toast('error', (e as Error)?.message || '重置失败')
  }
}

// ==================== Tab 切换加载 ====================
function switchTab(t: TabKey) {
  activeTab.value = t
  if (t === 'dashboard') void loadDashboard()
  else if (t === 'clusters') void loadClusters()
  else if (t === 'labels') void loadLabels()
  else if (t === 'simulator') void loadFeatureDict()
  else if (t === 'settings') void loadSettings()
}

onMounted(() => {
  void loadDashboard()
})
</script>

<template>
  <div class="space-y-4">
    <!-- 顶部标题 -->
    <div class="flex items-start justify-between gap-4">
      <div class="flex items-center gap-3">
        <div class="w-10 h-10 rounded-card flex items-center justify-center"
             style="background: rgba(188,140,255,0.12); color: var(--accent-purple);">
          <Sparkles :size="20"/>
        </div>
        <div>
          <h2 class="m-0 text-xl font-semibold text-text-primary leading-tight">AI 实验室</h2>
          <p class="m-0 mt-0.5 text-caption text-text-secondary">双模驱动 · 持续学习 · 人机协同闭环</p>
        </div>
      </div>
      <button class="btn btn-sm btn-ghost text-text-secondary" @click="switchTab(activeTab)">
        <RefreshCw :size="14"/> 刷新
      </button>
    </div>

    <!-- Tab 导航 -->
    <div class="flex gap-1 overflow-x-auto pb-0.5">
      <button v-for="t in tabs" :key="t.key" class="btn btn-sm flex items-center gap-1.5 whitespace-nowrap"
              :class="activeTab === t.key ? 'btn-primary' : 'btn-ghost text-text-secondary'"
              @click="switchTab(t.key)">
        <component :is="t.icon" :size="14"/> {{ t.label }}
      </button>
    </div>

    <!-- 未启用提示 -->
    <div v-if="!enabled" class="card">
      <div class="card-body flex flex-col items-center gap-3 py-16 text-center">
        <ShieldAlert :size="32" style="color: var(--warning);"/>
        <div class="text-sm text-text-primary font-medium">AI 实验室未启用</div>
        <p class="text-caption text-text-secondary m-0">
          当前运行在纯规则模式。可在 config.yml 中设置 <code>ailab.enabled: true</code> 后重启服务器开启。
        </p>
      </div>
    </div>

    <template v-else>
      <!-- ============ 模型仪表盘 ============ -->
      <template v-if="activeTab === 'dashboard'">
        <div v-if="dashLoading" class="h-[50vh] flex items-center justify-center text-text-secondary">
          <div class="flex items-center gap-3 text-sm"><Activity :size="18" class="animate-pulse text-accent-cyan"/> 正在加载模型状态…</div>
        </div>
        <div v-else-if="dashError" class="h-[40vh] flex flex-col items-center justify-center gap-3">
          <ShieldAlert :size="28" style="color: var(--danger-red);"/>
          <div class="text-sm text-text-secondary">{{ dashError }}</div>
          <button class="btn btn-sm btn-ghost" @click="loadDashboard">重试</button>
        </div>
        <template v-else>
          <!-- 统计卡 -->
          <div class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-4">
            <div class="stat-tile">
              <div>
                <div class="stat-tile-label">全局孤立森林</div>
                <div class="stat-tile-value" :style="{ color: health?.forestReady ? 'var(--success)' : 'var(--warning)' }">
                  {{ health?.forestReady ? '就绪' : '预热中' }}
                </div>
                <div class="mt-1 text-caption text-text-secondary">
                  样本库 {{ health?.forestHistory ?? 0 }} 条 · 集群 {{ health?.openClusters ?? 0 }} 个
                </div>
              </div>
              <div class="stat-tile-icon-wrap" style="background: rgba(0,229,255,0.12); color: var(--accent-cyan);"><Radar :size="22"/></div>
            </div>
            <div class="stat-tile">
              <div>
                <div class="stat-tile-label">监督模型</div>
                <div class="stat-tile-value">
                  {{ activeModel?.exists ? `v${activeModel.version}` : '待训练' }}
                </div>
                <div class="mt-1 text-caption text-text-secondary">
                  AUC {{ activeModel?.exists ? activeModel.auc?.toFixed(3) : '—' }} · {{ activeModel?.sampleCount ?? 0 }} 样本
                </div>
              </div>
              <div class="stat-tile-icon-wrap" style="background: rgba(188,140,255,0.12); color: var(--accent-purple);"><Brain :size="22"/></div>
            </div>
            <div class="stat-tile">
              <div>
                <div class="stat-tile-label">标签样本</div>
                <div class="stat-tile-value">{{ health?.labelsTotal ?? 0 }}</div>
                <div class="mt-1 text-caption text-text-secondary">
                  作弊 {{ health?.labelsCheat ?? 0 }} / 正常 {{ health?.labelsBenign ?? 0 }}
                </div>
              </div>
              <div class="stat-tile-icon-wrap" style="background: rgba(56,139,253,0.12); color: var(--accent-blue);"><Database :size="22"/></div>
            </div>
            <div class="stat-tile">
              <div>
                <div class="stat-tile-label">追踪玩家</div>
                <div class="stat-tile-value">{{ health?.trackedPlayers ?? 0 }}</div>
                <div class="mt-1 text-caption text-text-secondary">
                  距下次训练 {{ Math.max(0, (settings?.minNewLabelsForTraining ?? 20) - (health?.labelsNewSinceTrain ?? 0)) }} 新标签
                </div>
              </div>
              <div class="stat-tile-icon-wrap" style="background: rgba(63,185,80,0.12); color: var(--success);"><Users2 :size="22"/></div>
            </div>
          </div>

          <!-- 在线玩家评分 -->
          <div class="grid grid-cols-1 xl:grid-cols-3 gap-4">
            <div class="card xl:col-span-2">
              <div class="card-header">
                <h3 class="flex items-center gap-2"><Users2 :size="16" style="color: var(--accent-blue);"/> 在线玩家 AI 评分</h3>
                <span class="text-caption text-text-secondary">融合分 Top 10</span>
              </div>
              <div class="card-body p-0">
                <div class="table-wrap">
                  <table class="data-table">
                    <thead>
                      <tr><th>玩家</th><th>个人异常</th><th>全局异常</th><th>监督模型</th><th>融合分</th><th>状态</th></tr>
                    </thead>
                    <tbody>
                      <tr v-for="p in topPlayers" :key="p.uuid">
                        <td class="font-medium text-text-primary">{{ p.name }}</td>
                        <td><span class="font-mono" :style="{ color: scoreColor(p.personal) }">{{ p.personal < 0 ? '学习前' : p.personal.toFixed(2) }}</span></td>
                        <td><span class="font-mono" :style="{ color: scoreColor(p.global) }">{{ p.global.toFixed(2) }}</span></td>
                        <td><span class="font-mono" :style="{ color: scoreColor(p.supervised) }">{{ p.supervised < 0 ? '—' : p.supervised.toFixed(2) }}</span></td>
                        <td>
                          <div class="flex items-center gap-2">
                            <span class="font-mono" :style="{ color: scoreColor(p.fused) }">{{ p.fused.toFixed(2) }}</span>
                            <div class="w-16 h-1.5 rounded-full bg-bg-hover overflow-hidden">
                              <div class="h-full rounded-full" :style="{ width: (p.fused * 100) + '%', background: scoreColor(p.fused) }"></div>
                            </div>
                          </div>
                        </td>
                        <td>
                          <span class="tag" :class="p.watchlisted ? 'tag-yellow' : 'tag-green'">
                            {{ p.watchlisted ? '静默观察' : '正常' }}
                          </span>
                        </td>
                      </tr>
                      <tr v-if="topPlayers.length === 0">
                        <td colspan="6" class="text-center text-text-secondary py-8">暂无在线玩家数据</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </div>
            </div>

            <!-- 自适应阈值 -->
            <div class="card">
              <div class="card-header">
                <h3 class="flex items-center gap-2"><Settings2 :size="16" style="color: var(--accent-purple);"/> 自适应阈值灵敏度</h3>
                <span class="text-caption text-text-secondary">目标误报率 {{ ((thresholds?.targetFpr ?? 0.005) * 100).toFixed(1) }}%</span>
              </div>
              <div class="card-body space-y-2.5">
                <div v-for="(v, k) in thresholds?.current" :key="k" class="flex items-center gap-3">
                  <span class="text-caption text-text-secondary w-28 truncate">{{ k }}</span>
                  <div class="flex-1 h-1.5 rounded-full bg-bg-hover overflow-hidden">
                    <div class="h-full rounded-full transition-all" :style="{
                      width: Math.min(100, (v / 2) * 100) + '%',
                      background: v > 1.2 ? 'var(--warning)' : v < 0.8 ? 'var(--accent-blue)' : 'var(--success)'
                    }"></div>
                  </div>
                  <span class="font-mono text-caption w-10 text-right">{{ v.toFixed(2) }}</span>
                </div>
                <p class="text-caption text-text-secondary m-0 leading-relaxed pt-1">
                  管理员赦免（误报反馈）越多的模块，灵敏度自动下调；PID 控制器按目标误报率持续修正。
                </p>
              </div>
            </div>
          </div>
        </template>
      </template>

      <!-- ============ 异常集群 ============ -->
      <template v-else-if="activeTab === 'clusters'">
        <div v-if="clusterLoading" class="h-[40vh] flex items-center justify-center text-text-secondary">
          <div class="flex items-center gap-3 text-sm"><Activity :size="18" class="animate-pulse text-accent-cyan"/> 正在扫描集群…</div>
        </div>
        <div v-else-if="clusters.length === 0" class="card">
          <div class="card-body flex flex-col items-center gap-2 py-16 text-center">
            <Radar :size="28" style="color: var(--success);"/>
            <div class="text-sm text-text-primary">暂未发现异常集群</div>
            <p class="text-caption text-text-secondary m-0">系统每 10 分钟对高分异常玩家做特征聚类，相近行为向量将自动归簇</p>
          </div>
        </div>
        <div v-else class="grid grid-cols-1 xl:grid-cols-2 gap-4">
          <div v-for="c in clusters" :key="c.id" class="card">
            <div class="card-header">
              <h3 class="flex items-center gap-2">
                <Radar :size="16" style="color: var(--warning);"/> {{ c.id }}
                <span class="tag" :class="c.status === 'open' ? 'tag-yellow' : c.status === 'ruled' ? 'tag-blue' : 'tag-green'">
                  {{ c.status === 'open' ? '待处置' : c.status === 'ruled' ? '已转规则' : '已丢弃' }}
                </span>
              </h3>
              <span class="text-caption text-text-secondary">{{ fmtTime(c.discoveredAt) }}</span>
            </div>
            <div class="card-body space-y-3">
              <div class="flex items-center justify-between text-sm">
                <span class="text-text-secondary">成员（{{ c.memberCount }}）</span>
                <span class="font-mono text-text-primary">{{ c.members.join(', ') }}</span>
              </div>
              <div class="flex items-center justify-between text-sm">
                <span class="text-text-secondary">平均全局异常分</span>
                <span class="font-mono" :style="{ color: scoreColor(c.avgGlobalScore) }">{{ c.avgGlobalScore.toFixed(3) }}</span>
              </div>
              <div>
                <div class="text-caption text-text-secondary mb-1.5">共同特征签名（Top 偏差维度）</div>
                <div class="flex flex-wrap gap-1.5">
                  <span v-for="f in c.fingerprint" :key="f.dim" class="tag tag-purple text-caption">
                    {{ f.desc }} {{ f.delta >= 0 ? '↑' : '↓' }}{{ Math.abs(f.delta).toFixed(2) }}
                  </span>
                </div>
              </div>
              <div v-if="c.status === 'open'" class="flex gap-2 pt-1">
                <button class="btn btn-sm btn-primary flex items-center gap-1.5" @click="handleClusterAction(c, 'rule')">
                  <Check :size="13"/> 转临时规则
                </button>
                <button class="btn btn-sm btn-ghost flex items-center gap-1.5" @click="handleClusterAction(c, 'dismiss')">
                  <X :size="13"/> 标记误报
                </button>
              </div>
            </div>
          </div>
        </div>
      </template>

      <!-- ============ 标签管理 ============ -->
      <template v-else-if="activeTab === 'labels'">
        <div class="card">
          <div class="card-header">
            <h3 class="flex items-center gap-2"><Tags :size="16" style="color: var(--accent-blue);"/> 标签样本（{{ labelTotal }}）</h3>
            <button class="btn btn-sm btn-primary flex items-center gap-1.5" @click="showAddForm = !showAddForm">
              <Plus :size="13"/> 添加标签
            </button>
          </div>
          <div class="card-body space-y-3">
            <!-- 添加表单 -->
            <div v-if="showAddForm" class="p-3 rounded-btn space-y-2.5" style="background: var(--bg-hover); border: 1px solid var(--border);">
              <div class="grid grid-cols-1 md:grid-cols-3 gap-2.5">
                <input v-model="addForm.uuid" class="input" placeholder="玩家 UUID（必填）"/>
                <select v-model="addForm.cheat" class="input">
                  <option :value="true">作弊（正样本）</option>
                  <option :value="false">正常（负样本 / 误报）</option>
                </select>
                <input v-model="addForm.note" class="input" placeholder="备注（可选）"/>
              </div>
              <div class="flex gap-2">
                <button class="btn btn-sm btn-primary" @click="submitLabel">提交</button>
                <button class="btn btn-sm btn-ghost" @click="showAddForm = false">取消</button>
              </div>
              <p class="text-caption text-text-secondary m-0">
                强标签：以玩家当前实时特征向量入库，直接驱动监督模型训练。
              </p>
            </div>

            <!-- 筛选 -->
            <div class="grid grid-cols-1 md:grid-cols-4 gap-2.5">
              <select v-model.number="labelFilter" class="input" @change="labelPage = 0; loadLabels()">
                <option :value="-1">全部标签</option>
                <option :value="1">仅作弊</option>
                <option :value="0">仅正常</option>
              </select>
              <select v-model="labelSource" class="input" @change="labelPage = 0; loadLabels()">
                <option value="">全部来源</option>
                <option v-for="s in sourceOptions" :key="s" :value="s">{{ s }}</option>
              </select>
              <input v-model="labelName" class="input" placeholder="按玩家名筛选" @keyup.enter="labelPage = 0; loadLabels()"/>
              <button class="btn btn-sm btn-ghost" @click="labelPage = 0; loadLabels()">查询</button>
            </div>

            <!-- 列表 -->
            <div class="table-wrap">
              <table class="data-table">
                <thead>
                  <tr><th>#</th><th>玩家</th><th>标签</th><th>来源</th><th>特征</th><th>时间</th><th>备注</th><th>操作</th></tr>
                </thead>
                <tbody>
                  <tr v-for="s in labels" :key="s.id">
                    <td class="font-mono text-text-secondary">{{ s.id }}</td>
                    <td class="font-medium text-text-primary">{{ s.playerName || s.uuid.slice(0, 8) }}</td>
                    <td>
                      <span class="tag" :class="s.label === 1 ? 'tag-red' : 'tag-green'">
                        {{ s.label === 1 ? '作弊' : '正常' }}
                      </span>
                    </td>
                    <td class="text-caption text-text-secondary">{{ s.source }}</td>
                    <td><span class="tag" :class="s.hasFeatures ? 'tag-blue' : ''">{{ s.hasFeatures ? '48 维' : '无' }}</span></td>
                    <td class="text-caption text-text-secondary">{{ fmtTime(s.timestamp) }}</td>
                    <td class="text-caption text-text-secondary max-w-[180px] truncate">{{ s.note || '—' }}</td>
                    <td>
                      <div class="flex gap-1.5">
                        <button class="btn btn-sm btn-ghost" title="切换标签" @click="toggleLabel(s)"><Undo2 :size="12"/></button>
                        <button class="btn btn-sm btn-ghost" style="color: var(--danger-red);" title="删除" @click="removeLabel(s)"><Trash2 :size="12"/></button>
                      </div>
                    </td>
                  </tr>
                  <tr v-if="labels.length === 0">
                    <td colspan="8" class="text-center text-text-secondary py-8">
                      {{ labelLoading ? '加载中…' : '暂无标签样本 — 管理员判决 / Captcha 结果 / 赏金报告会自动回流至此' }}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>

            <!-- 分页 -->
            <div v-if="labelTotal > 15" class="flex items-center justify-between">
              <span class="text-caption text-text-secondary">第 {{ labelPage + 1 }} 页</span>
              <div class="flex gap-2">
                <button class="btn btn-sm btn-ghost" :disabled="labelPage === 0"
                        @click="labelPage--; loadLabels()">上一页</button>
                <button class="btn btn-sm btn-ghost" :disabled="(labelPage + 1) * 15 >= labelTotal"
                        @click="labelPage++; loadLabels()">下一页</button>
              </div>
            </div>
          </div>
        </div>
      </template>

      <!-- ============ 评分模拟器 ============ -->
      <template v-else-if="activeTab === 'simulator'">
        <div class="grid grid-cols-1 xl:grid-cols-2 gap-4">
          <div class="card">
            <div class="card-header">
              <h3 class="flex items-center gap-2"><FlaskConical :size="16" style="color: var(--accent-purple);"/> 输入</h3>
              <div class="flex gap-1">
                <button class="btn btn-sm" :class="simMode === 'player' ? 'btn-primary' : 'btn-ghost'" @click="simMode = 'player'">按玩家</button>
                <button class="btn btn-sm" :class="simMode === 'features' ? 'btn-primary' : 'btn-ghost'" @click="simMode = 'features'">按特征</button>
              </div>
            </div>
            <div class="card-body space-y-3">
              <template v-if="simMode === 'player'">
                <input v-model="simPlayer" class="input" placeholder="输入在线玩家名，查看当前各模型评分与判断依据"/>
              </template>
              <template v-else>
                <p class="text-caption text-text-secondary m-0">未填写的维度使用全局均值填充：</p>
                <div v-for="k in simKeyFeatures" :key="k" class="flex items-center gap-3">
                  <span class="text-caption text-text-secondary w-40 truncate" :title="featureDict.find(f => f.name === k)?.desc">{{ k }}</span>
                  <input type="number" step="any" class="input flex-1" v-model.number="simFeatures[k]" :placeholder="featureDict.find(f => f.name === k)?.desc || k"/>
                </div>
              </template>
              <button class="btn btn-primary flex items-center gap-1.5 self-start" :disabled="simLoading" @click="runSimulate">
                <Play :size="14"/> {{ simLoading ? '计算中…' : '运行模拟' }}
              </button>
            </div>
          </div>

          <div class="card">
            <div class="card-header">
              <h3 class="flex items-center gap-2"><Cpu :size="16" style="color: var(--accent-cyan);"/> 模型输出</h3>
            </div>
            <div class="card-body space-y-4">
              <template v-if="simResult">
                <div class="space-y-2.5">
                  <div class="flex items-center gap-3">
                    <span class="text-caption text-text-secondary w-24">全局异常分</span>
                    <div class="flex-1 h-2 rounded-full bg-bg-hover overflow-hidden">
                      <div class="h-full rounded-full" :style="{ width: (Math.max(0, simResult.global) * 100) + '%', background: scoreColor(simResult.global) }"></div>
                    </div>
                    <span class="font-mono text-sm" :style="{ color: scoreColor(simResult.global) }">{{ simResult.global < 0 ? '未就绪' : simResult.global.toFixed(3) }}</span>
                  </div>
                  <div class="flex items-center gap-3">
                    <span class="text-caption text-text-secondary w-24">监督模型</span>
                    <div class="flex-1 h-2 rounded-full bg-bg-hover overflow-hidden">
                      <div class="h-full rounded-full" :style="{ width: (Math.max(0, simResult.supervised) * 100) + '%', background: scoreColor(simResult.supervised) }"></div>
                    </div>
                    <span class="font-mono text-sm" :style="{ color: scoreColor(simResult.supervised) }">{{ simResult.supervised < 0 ? '无模型' : simResult.supervised.toFixed(3) }}</span>
                  </div>
                  <div class="flex items-center gap-3">
                    <span class="text-caption text-text-secondary w-24">融合分</span>
                    <div class="flex-1 h-2 rounded-full bg-bg-hover overflow-hidden">
                      <div class="h-full rounded-full" :style="{ width: (simResult.fused * 100) + '%', background: scoreColor(simResult.fused) }"></div>
                    </div>
                    <span class="font-mono text-sm" :style="{ color: scoreColor(simResult.fused) }">{{ simResult.fused.toFixed(3) }}</span>
                  </div>
                </div>
                <div class="divider my-0"></div>
                <div>
                  <div class="text-caption text-text-secondary mb-2">主要贡献维度（标准化偏差）</div>
                  <div class="space-y-1.5">
                    <div v-for="f in simResult.topFactors" :key="f.name" class="flex items-center gap-2 text-caption">
                      <span class="text-text-secondary w-32 truncate" :title="f.desc">{{ f.name }}</span>
                      <span class="font-mono w-16 text-right">{{ f.value?.toFixed(2) }}</span>
                      <div class="flex-1 h-1 rounded-full bg-bg-hover overflow-hidden">
                        <div class="h-full rounded-full" :style="{
                          width: Math.min(100, Math.abs(f.z ?? 0) * 25) + '%',
                          background: (f.z ?? 0) > 0 ? 'var(--danger-red)' : 'var(--accent-blue)'
                        }"></div>
                      </div>
                      <span class="font-mono w-12 text-right">{{ (f.z ?? 0).toFixed(2) }}</span>
                    </div>
                  </div>
                </div>
              </template>
              <div v-else class="flex flex-col items-center gap-2 py-12 text-center">
                <Eye :size="24" class="text-text-muted"/>
                <p class="text-caption text-text-secondary m-0">运行模拟查看模型如何评估该行为向量</p>
              </div>
            </div>
          </div>
        </div>
      </template>

      <!-- ============ 学习设置 ============ -->
      <template v-else>
        <div v-if="settingsLoading" class="h-[40vh] flex items-center justify-center text-text-secondary">
          <div class="flex items-center gap-3 text-sm"><Activity :size="18" class="animate-pulse text-accent-cyan"/> 正在加载设置…</div>
        </div>
        <template v-else-if="settingsForm">
          <div class="grid grid-cols-1 xl:grid-cols-2 gap-4">
            <!-- 学习开关 -->
            <div class="card">
              <div class="card-header">
                <h3 class="flex items-center gap-2"><Settings2 :size="16" style="color: var(--accent-purple);"/> 学习开关</h3>
                <button class="btn btn-sm btn-primary" @click="saveSettings">保存</button>
              </div>
              <div class="card-body space-y-3">
                <label v-for="(label, key) in ({
                  enabled: '总开关（关闭即纯规则模式）',
                  baselineEnabled: '个人行为基线（在线 K-Means）',
                  forestEnabled: '全局孤立森林',
                  supervisedEnabled: '监督分类器',
                  clusterEnabled: '异常集群发现',
                  learningEnabled: '自动训练（标签达标触发）'
                })" :key="key" class="flex items-center justify-between text-sm">
                  <span class="text-text-secondary">{{ label }}</span>
                  <button class="btn btn-sm" :class="settingsForm[key] ? 'btn-primary' : 'btn-ghost'"
                          @click="settingsForm[key] = !settingsForm[key]">
                    {{ settingsForm[key] ? '开启' : '关闭' }}
                  </button>
                </label>
                <div class="divider my-0"></div>
                <div class="grid grid-cols-3 gap-2.5">
                  <div>
                    <div class="text-caption text-text-secondary mb-1">个人权重</div>
                    <input type="number" step="0.05" min="0" max="1" class="input" v-model.number="settingsForm.weights.personal"/>
                  </div>
                  <div>
                    <div class="text-caption text-text-secondary mb-1">全局权重</div>
                    <input type="number" step="0.05" min="0" max="1" class="input" v-model.number="settingsForm.weights.global"/>
                  </div>
                  <div>
                    <div class="text-caption text-text-secondary mb-1">监督权重</div>
                    <input type="number" step="0.05" min="0" max="1" class="input" v-model.number="settingsForm.weights.supervised"/>
                  </div>
                </div>
                <p class="text-caption text-text-secondary m-0">
                  设置仅运行时生效；持久化请修改 config.yml 的 ailab 段后重启。
                </p>
              </div>
            </div>

            <div class="space-y-4">
              <!-- 模型版本 -->
              <div class="card">
                <div class="card-header">
                  <h3 class="flex items-center gap-2"><Brain :size="16" style="color: var(--accent-blue);"/> 模型版本</h3>
                  <button class="btn btn-sm btn-ghost flex items-center gap-1.5" @click="manualTrain">
                    <Play :size="13"/> 立即训练
                  </button>
                </div>
                <div class="card-body p-0">
                  <div class="table-wrap">
                    <table class="data-table">
                      <thead><tr><th>版本</th><th>AUC</th><th>样本</th><th>训练时间</th><th>操作</th></tr></thead>
                      <tbody>
                        <tr v-for="m in models" :key="m.version" :style="m.active ? 'background: rgba(63,185,80,0.06);' : ''">
                          <td>
                            <span class="font-mono">v{{ m.version }}</span>
                            <span v-if="m.active" class="tag tag-green ml-1.5">活跃</span>
                          </td>
                          <td class="font-mono">{{ m.auc.toFixed(3) }}</td>
                          <td class="font-mono text-text-secondary">{{ m.sampleCount }}</td>
                          <td class="text-caption text-text-secondary">{{ fmtTime(m.trainedAt) }}</td>
                          <td>
                            <button v-if="!m.active" class="btn btn-sm btn-ghost flex items-center gap-1" @click="rollback(m)">
                              <RotateCcw :size="11"/> 回滚
                            </button>
                            <span v-else class="text-caption text-text-secondary">—</span>
                          </td>
                        </tr>
                        <tr v-if="models.length === 0">
                          <td colspan="5" class="text-center text-text-secondary py-6">尚无训练记录 — 积累 {{ settingsForm.minLabelsForTraining }} 个标签后自动训练</td>
                        </tr>
                      </tbody>
                    </table>
                  </div>
                </div>
              </div>

              <!-- 基线重置 -->
              <div class="card">
                <div class="card-header">
                  <h3 class="flex items-center gap-2"><RotateCcw :size="16" style="color: var(--warning);"/> 重置玩家基线</h3>
                </div>
                <div class="card-body space-y-2.5">
                  <p class="text-caption text-text-secondary m-0">
                    确认玩家被盗号后重置：清除其个人行为基线，重新以 5 分钟 warmup 学习新行为。
                  </p>
                  <div class="flex gap-2">
                    <input v-model="resetUuid" class="input flex-1" placeholder="玩家 UUID"/>
                    <button class="btn btn-sm btn-ghost" @click="doResetBaseline">重置</button>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </template>
      </template>
    </template>
  </div>
</template>
