<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import {
  Settings2, ShieldAlert, Shield, Bell, Server, Database, Globe, Save, RotateCcw,
  ShieldCheck, Lock, Unlock, AlertTriangle
} from 'lucide-vue-next'
import { scoreToHexColor } from '@/utils/risk'
import { request } from '@/api/request'
import type { ConfigModule } from '@/types'

/** 单条检测项阈值行（与后端 ConfigModuleDTO 对齐，含前端可视化派生字段） */
interface Threshold {
  id: string
  name: string
  module: string
  enabled: boolean
  maxViolations: number
  banTime: string
  kickThreshold: number
  humanReviewThreshold: number
  warningCooldownSecs: number
  notifyCooldownMs: number
  autoBan: number
  humanReview: number
}

/** 封禁时长可选项 */
const BAN_TIME_OPTIONS = [
  { label: '10 分钟', value: '10m' },
  { label: '30 分钟', value: '30m' },
  { label: '1 小时', value: '1h' },
  { label: '6 小时', value: '6h' },
  { label: '1 天', value: '1d' },
  { label: '7 天', value: '7d' },
  { label: '永久封禁', value: 'permanent' }
]

/** 兜底默认值（API 失败时使用） */
const FALLBACK_THRESHOLDS: Threshold[] = [
  { id: 'killaura',  name: '战斗 / KillAura',  module: 'killaura',  enabled: true,  maxViolations: 5, banTime: '1d',  kickThreshold: 20, humanReviewThreshold: 15, warningCooldownSecs: 2, notifyCooldownMs: 5000, autoBan: 100, humanReview: 80 },
  { id: 'speed',    name: '移动 / 速度',       module: 'speed',     enabled: true,  maxViolations: 5, banTime: '30m', kickThreshold: 20, humanReviewThreshold: 15, warningCooldownSecs: 2, notifyCooldownMs: 5000, autoBan: 100, humanReview: 80 },
  { id: 'fly',      name: '移动 / 飞行',       module: 'fly',       enabled: true,  maxViolations: 5, banTime: '1h',  kickThreshold: 20, humanReviewThreshold: 15, warningCooldownSecs: 2, notifyCooldownMs: 5000, autoBan: 100, humanReview: 80 },
  { id: 'esp',      name: '视觉 / ESP',        module: 'esp',       enabled: true,  maxViolations: 3, banTime: '6h',  kickThreshold: 15, humanReviewThreshold: 10, warningCooldownSecs: 2, notifyCooldownMs: 5000, autoBan: 80,  humanReview: 60 },
  { id: 'reach',    name: '交互 / Reach',      module: 'reach',     enabled: true,  maxViolations: 5, banTime: '2h',  kickThreshold: 20, humanReviewThreshold: 15, warningCooldownSecs: 2, notifyCooldownMs: 5000, autoBan: 100, humanReview: 80 },
  { id: 'scaffold', name: '搭建 / Scaffold',   module: 'scaffold',  enabled: false, maxViolations: 5, banTime: '2h',  kickThreshold: 20, humanReviewThreshold: 15, warningCooldownSecs: 2, notifyCooldownMs: 5000, autoBan: 100, humanReview: 80 }
]

const thresholds = reactive<Threshold[]>(FALLBACK_THRESHOLDS.map(t => ({ ...t })))

const system = reactive({
  serverName: 'AntiCheat Command Center',
  dataRetentionDays: 180,
  wsHeartbeat: 15,
  autoBanEnabled: true,
  reviewQueue: true,
  aiVerdictEnabled: true,
  notifyEmail: true,
  notifyWebhook: false,
  notifyWebhookUrl: 'https://hooks.example.com/xxxxx',
  dbHost: '10.0.2.50',
  dbPort: 3306,
  redisHost: '10.0.2.51',
  wsPort: 8080
})

const changes = ref(0)
const loading = ref(false)
const saving = ref(false)
const toast = ref<{ type: 'success' | 'error' | 'info'; message: string } | null>(null)

// ==================== 回放监视配置（需求 1：maxConcurrent 热更） ====================
const replayCfg = reactive({
  surveillanceEnabled: true,
  autoOnJoin: true,
  maxConcurrent: 3,
  queueEnabled: true,
  queueMaxSize: 50,
  cameraMode: 'attach',
  autoShoulderOnViolation: true,
  observerPoolSize: 3,
  effectiveMaxConcurrent: 3
})

async function loadReplayConfig(): Promise<void> {
  try {
    const cfg = await request.get<Record<string, unknown>>('/config/replay')
    if (cfg && typeof cfg === 'object') {
      if (typeof cfg.surveillanceEnabled === 'boolean') replayCfg.surveillanceEnabled = cfg.surveillanceEnabled
      if (typeof cfg.autoOnJoin === 'boolean') replayCfg.autoOnJoin = cfg.autoOnJoin
      if (typeof cfg.maxConcurrent === 'number') replayCfg.maxConcurrent = cfg.maxConcurrent
      if (typeof cfg.queueEnabled === 'boolean') replayCfg.queueEnabled = cfg.queueEnabled
      if (typeof cfg.queueMaxSize === 'number') replayCfg.queueMaxSize = cfg.queueMaxSize
      if (typeof cfg.cameraMode === 'string') replayCfg.cameraMode = cfg.cameraMode
      if (typeof cfg.autoShoulderOnViolation === 'boolean') replayCfg.autoShoulderOnViolation = cfg.autoShoulderOnViolation
      if (typeof cfg.observerPoolSize === 'number') replayCfg.observerPoolSize = cfg.observerPoolSize
      if (typeof cfg.effectiveMaxConcurrent === 'number') replayCfg.effectiveMaxConcurrent = cfg.effectiveMaxConcurrent
    }
  } catch (e) {
    // eslint-disable-next-line no-console
    console.warn('[ConfigView] 加载回放配置失败', e)
  }
}

async function saveReplayConfig(): Promise<void> {
  if (saving.value) return
  saving.value = true
  try {
    await request.put('/config/replay', {
      surveillanceEnabled: replayCfg.surveillanceEnabled,
      autoOnJoin: replayCfg.autoOnJoin,
      maxConcurrent: replayCfg.maxConcurrent,
      queueEnabled: replayCfg.queueEnabled,
      queueMaxSize: replayCfg.queueMaxSize,
      cameraMode: replayCfg.cameraMode,
      autoShoulderOnViolation: replayCfg.autoShoulderOnViolation
    })
    showToast('success', '回放监视配置已保存并热加载')
    await loadReplayConfig()
  } catch (e) {
    showToast('error', '回放配置保存失败，请检查权限')
    // eslint-disable-next-line no-console
    console.error('[ConfigView] 保存回放配置失败', e)
  } finally {
    saving.value = false
  }
}

function showToast(type: 'success' | 'error' | 'info', message: string): void {
  toast.value = { type, message }
  setTimeout(() => { toast.value = null }, 3000)
}

function markDirty(): void { changes.value += 1 }

/** 从后端拉取检测项配置覆盖本地 */
async function loadConfig(): Promise<void> {
  loading.value = true
  try {
    const list = await request.get<ConfigModule[]>('/config/modules')
    if (Array.isArray(list) && list.length > 0) {
      // 用真实数据覆盖本地兜底
      thresholds.splice(0, thresholds.length, ...list.map(m => ({
        id: m.id,
        name: m.name,
        module: m.module,
        enabled: m.enabled,
        maxViolations: m.maxViolations ?? 5,
        banTime: m.banTime ?? '1h',
        kickThreshold: m.kickThreshold ?? 20,
        humanReviewThreshold: m.humanReviewThreshold ?? 15,
        warningCooldownSecs: m.warningCooldownSecs ?? 2,
        notifyCooldownMs: m.notifyCooldownMs ?? 5000,
        autoBan: m.autoBan ?? 100,
        humanReview: m.humanReview ?? 80
      })))
    }
  } catch (e) {
    // 静默失败，保留兜底数据
    // eslint-disable-next-line no-console
    console.warn('[ConfigView] 加载检测项配置失败，使用兜底数据', e)
  } finally {
    loading.value = false
  }
}

function reset(): void {
  loadConfig().then(() => {
    changes.value = 0
    showToast('info', '已重置为服务器最新配置')
  })
}

/** 保存所有改动到后端，逐行 PUT，并触发热加载 */
async function save(): Promise<void> {
  if (saving.value) return
  saving.value = true
  let failed = 0
  for (const t of thresholds) {
    try {
      await request.put(`/config/modules/${t.id}`, {
        id: t.id,
        name: t.name,
        module: t.module,
        enabled: t.enabled,
        maxViolations: t.maxViolations,
        banTime: t.banTime,
        kickThreshold: t.kickThreshold,
        humanReviewThreshold: t.humanReviewThreshold,
        warningCooldownSecs: t.warningCooldownSecs,
        notifyCooldownMs: t.notifyCooldownMs
      })
    } catch (e) {
      failed++
      // eslint-disable-next-line no-console
      console.error('[ConfigView] 保存失败', t.id, e)
    }
  }
  saving.value = false
  if (failed === 0) {
    changes.value = 0
    showToast('success', '所有检测项配置已保存并热加载')
  } else if (failed < thresholds.length) {
    changes.value = failed
    showToast('error', `${failed} 项保存失败，其余已保存并热加载`)
  } else {
    showToast('error', '保存失败，请检查网络或权限')
  }
}

onMounted(() => {
  loadConfig()
  loadReplayConfig()
})
</script>

<template>
  <div class="space-y-4">
    <!-- 检测阈值 -->
    <div class="card">
      <div class="card-header">
        <h3 class="flex items-center gap-2"><ShieldAlert :size="16" style="color: var(--danger-red);"/> 模块检测阈值</h3>
        <div class="flex items-center gap-2 text-caption text-text-secondary">
          <span class="inline-flex items-center gap-1"><span class="w-2 h-2 rounded-full" style="background: var(--warning);"></span>人工审理</span>
          <span class="inline-flex items-center gap-1"><span class="w-2 h-2 rounded-full" style="background: var(--danger-red);"></span>自动封禁</span>
        </div>
      </div>
      <div class="card-body p-0">
        <div class="table-wrap">
          <table class="data-table">
            <thead>
              <tr>
                <th style="width: 180px;">模块</th>
                <th>启用</th>
                <th style="width: 130px;">封禁时长</th>
                <th style="width: 110px;">警告冷却(秒)</th>
                <th>人工审理阈值</th>
                <th>自动封禁阈值</th>
                <th style="width: 200px;">区间可视化</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="t in thresholds" :key="t.id" :class="{ 'opacity-50': !t.enabled }">
                <td>
                  <label class="flex items-center gap-2 cursor-pointer">
                    <input type="checkbox" class="w-4 h-4" v-model="t.enabled" @change="markDirty"/>
                    <span class="text-sm text-text-primary">{{ t.name }}</span>
                  </label>
                </td>
                <td><span class="tag" :class="t.enabled ? 'tag-green' : 'tag-gray'">{{ t.enabled ? '运行中' : '已停用' }}</span></td>
                <td>
                  <select v-model="t.banTime" :disabled="!t.enabled" class="input text-caption py-1" @change="markDirty">
                    <option v-for="opt in BAN_TIME_OPTIONS" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
                  </select>
                </td>
                <td>
                  <input type="number" min="1" max="60" v-model.number="t.warningCooldownSecs" :disabled="!t.enabled" class="input font-mono text-caption py-1 w-20" @input="markDirty"/>
                </td>
                <td>
                  <div class="flex items-center gap-3">
                    <input type="range" min="40" max="100" v-model.number="t.humanReview" :disabled="!t.enabled" class="w-32" @input="markDirty"/>
                    <span class="font-mono text-sm" style="color: var(--warning);">{{ t.humanReview }}</span>
                  </div>
                </td>
                <td>
                  <div class="flex items-center gap-3">
                    <input type="range" min="40" max="100" v-model.number="t.autoBan" :disabled="!t.enabled" class="w-32" @input="markDirty"/>
                    <span class="font-mono text-sm" style="color: var(--danger-red);">{{ t.autoBan }}</span>
                  </div>
                </td>
                <td>
                  <div class="relative h-4 w-full">
                    <div class="absolute inset-x-0 top-1/2 -translate-y-1/2 h-1.5 rounded-full bg-bg-hover overflow-hidden">
                      <div class="h-full rounded-full" style="background: linear-gradient(90deg, #3FB950 0%, #D29922 40%, #FF6D00 70%, #F85149 100%);"></div>
                    </div>
                    <div class="absolute top-1/2 -translate-y-1/2 w-3 h-3 rounded-full border-2 bg-bg-card" style="left: calc(var(--l) * 1%); --l: t.humanReview; border-color: var(--warning);"></div>
                    <div class="absolute top-1/2 -translate-y-1/2 w-3 h-3 rounded-full border-2 bg-bg-card" style="left: calc(var(--r) * 1%); --r: t.autoBan; border-color: var(--danger-red);"></div>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- 回放监视配置（需求 1） -->
    <div class="card">
      <div class="card-header">
        <h3 class="flex items-center gap-2"><Server :size="16" style="color: var(--accent-cyan);"/> 违规回放 · 监视调度</h3>
        <span class="text-caption text-text-secondary">观察者池 {{ replayCfg.observerPoolSize }} 个 · 生效并发 {{ replayCfg.effectiveMaxConcurrent }}</span>
      </div>
      <div class="card-body">
        <div class="grid grid-cols-1 md:grid-cols-3 gap-4">
          <div>
            <label class="input-label">最大并发监视数 (maxConcurrent)</label>
            <input type="number" min="1" v-model.number="replayCfg.maxConcurrent" class="input" @input="markDirty"/>
            <p class="text-caption text-text-muted mt-1">超出按进入顺序排队；物理观察者池固定 {{ replayCfg.observerPoolSize }} 个，调大需增加容器并重启</p>
          </div>
          <div>
            <label class="input-label">队列上限</label>
            <input type="number" min="1" v-model.number="replayCfg.queueMaxSize" class="input" @input="markDirty"/>
          </div>
          <div>
            <label class="input-label">机位模式</label>
            <select v-model="replayCfg.cameraMode" class="input" @change="markDirty">
              <option value="attach">ATTACH（眼位绑定，视角 1:1）</option>
              <option value="shoulder">SHOULDER（过肩，含手持物品）</option>
            </select>
          </div>
        </div>

        <div class="divider"></div>

        <div class="grid grid-cols-1 md:grid-cols-3 gap-4">
          <label class="flex items-center justify-between py-1.5 cursor-pointer">
            <span class="text-sm text-text-primary">启用监视调度</span>
            <input type="checkbox" v-model="replayCfg.surveillanceEnabled" class="w-4 h-4" @change="markDirty"/>
          </label>
          <label class="flex items-center justify-between py-1.5 cursor-pointer">
            <span class="text-sm text-text-primary">玩家进入即调度</span>
            <input type="checkbox" v-model="replayCfg.autoOnJoin" class="w-4 h-4" @change="markDirty"/>
          </label>
          <label class="flex items-center justify-between py-1.5 cursor-pointer">
            <span class="text-sm text-text-primary">违规时自动切过肩取证</span>
            <input type="checkbox" v-model="replayCfg.autoShoulderOnViolation" class="w-4 h-4" @change="markDirty"/>
          </label>
        </div>

        <div class="flex justify-end mt-3">
          <button class="btn btn-primary" :disabled="saving" @click="saveReplayConfig">
            <Save :size="14"/> 保存回放配置
          </button>
        </div>
      </div>
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-2 gap-4">
      <!-- 系统基础配置 -->
      <div class="card">
        <div class="card-header"><h3 class="flex items-center gap-2"><Settings2 :size="16" style="color: var(--accent-blue);"/> 系统基础设置</h3></div>
        <div class="card-body space-y-4">
          <div>
            <label class="input-label">服务器名称</label>
            <input v-model="system.serverName" class="input" @input="markDirty"/>
          </div>
          <div class="grid grid-cols-2 gap-3">
            <div>
              <label class="input-label">数据保留 (天)</label>
              <input type="number" v-model.number="system.dataRetentionDays" class="input" @input="markDirty"/>
            </div>
            <div>
              <label class="input-label">WS 心跳 (秒)</label>
              <input type="number" v-model.number="system.wsHeartbeat" class="input" @input="markDirty"/>
            </div>
          </div>

          <div class="divider"></div>

          <label class="flex items-center justify-between py-1.5 cursor-pointer">
            <span class="flex items-center gap-2"><Lock :size="15" style="color: var(--danger-red);"/> <span class="text-sm text-text-primary">自动封禁 (超过阈值立即封禁)</span></span>
            <input type="checkbox" v-model="system.autoBanEnabled" class="w-4 h-4" @change="markDirty"/>
          </label>
          <label class="flex items-center justify-between py-1.5 cursor-pointer">
            <span class="flex items-center gap-2"><ShieldCheck :size="15" style="color: var(--accent-blue);"/> <span class="text-sm text-text-primary">开启审理队列 (人工复核模式)</span></span>
            <input type="checkbox" v-model="system.reviewQueue" class="w-4 h-4" @change="markDirty"/>
          </label>
          <label class="flex items-center justify-between py-1.5 cursor-pointer">
            <span class="flex items-center gap-2"><Sparkles :size="15" style="color: var(--accent-purple);"/> <span class="text-sm text-text-primary">AI 自动判决 (置信度 ≥ 0.92)</span></span>
            <input type="checkbox" v-model="system.aiVerdictEnabled" class="w-4 h-4" @change="markDirty"/>
          </label>
        </div>
      </div>

      <!-- 通知 & Webhook -->
      <div class="card">
        <div class="card-header"><h3 class="flex items-center gap-2"><Bell :size="16" style="color: var(--warning);"/> 通知渠道</h3></div>
        <div class="card-body space-y-4">
          <label class="flex items-center justify-between py-1.5 cursor-pointer">
            <span class="flex items-center gap-2"><Bell :size="15"/> <span class="text-sm text-text-primary">邮件通知 (高风险告警)</span></span>
            <input type="checkbox" v-model="system.notifyEmail" class="w-4 h-4" @change="markDirty"/>
          </label>
          <label class="flex items-center justify-between py-1.5 cursor-pointer">
            <span class="flex items-center gap-2"><Globe :size="15"/> <span class="text-sm text-text-primary">Webhook (Discord / Slack)</span></span>
            <input type="checkbox" v-model="system.notifyWebhook" class="w-4 h-4" @change="markDirty"/>
          </label>
          <div>
            <label class="input-label">Webhook URL</label>
            <input v-model="system.notifyWebhookUrl" class="input font-mono text-caption" :disabled="!system.notifyWebhook" @input="markDirty"/>
          </div>
          <div class="divider"></div>
          <div class="p-3 rounded-btn" style="background: rgba(63,185,80,0.08); border: 1px solid rgba(63,185,80,0.25);">
            <div class="flex items-start gap-2 mb-1">
              <ShieldCheck :size="15" style="color: var(--success); margin-top: 1px;"/>
              <span class="text-sm font-medium text-text-primary">配置安全</span>
            </div>
            <p class="text-caption text-text-secondary m-0">当前 TLS 端到端加密已启用，敏感字段存储已脱敏。</p>
          </div>
        </div>
      </div>

      <!-- 基础设施 -->
      <div class="card lg:col-span-2">
        <div class="card-header"><h3 class="flex items-center gap-2"><Database :size="16" style="color: var(--accent-cyan);"/> 基础设施</h3></div>
        <div class="card-body grid grid-cols-1 md:grid-cols-3 gap-4">
          <div class="p-4 rounded-card border border-border-line bg-bg-hover space-y-3">
            <div class="flex items-center gap-2 text-text-primary"><Database :size="16" style="color: var(--accent-blue);"/><span class="font-medium">MySQL</span></div>
            <div class="grid grid-cols-2 gap-2 text-caption">
              <span class="text-text-secondary">Host</span><span class="font-mono text-text-primary">{{ system.dbHost }}</span>
              <span class="text-text-secondary">Port</span><span class="font-mono text-text-primary">{{ system.dbPort }}</span>
            </div>
            <div class="flex items-center gap-2 pt-1 text-caption">
              <span class="status-dot online" style="width:6px;height:6px;"></span>
              <span class="text-success">连接正常 · 延迟 2ms</span>
            </div>
          </div>
          <div class="p-4 rounded-card border border-border-line bg-bg-hover space-y-3">
            <div class="flex items-center gap-2 text-text-primary"><Server :size="16" style="color: var(--accent-purple);"/><span class="font-medium">Redis</span></div>
            <div class="grid grid-cols-2 gap-2 text-caption">
              <span class="text-text-secondary">Host</span><span class="font-mono text-text-primary">{{ system.redisHost }}</span>
              <span class="text-text-secondary">模式</span><span class="font-mono text-text-primary">Sentinel</span>
            </div>
            <div class="flex items-center gap-2 pt-1 text-caption">
              <span class="status-dot online" style="width:6px;height:6px;"></span>
              <span class="text-success">连接正常 · 命中率 98.4%</span>
            </div>
          </div>
          <div class="p-4 rounded-card border border-border-line bg-bg-hover space-y-3">
            <div class="flex items-center gap-2 text-text-primary"><Globe :size="16" style="color: var(--accent-cyan);"/><span class="font-medium">WebSocket</span></div>
            <div class="grid grid-cols-2 gap-2 text-caption">
              <span class="text-text-secondary">Port</span><span class="font-mono text-text-primary">{{ system.wsPort }}</span>
              <span class="text-text-secondary">心跳</span><span class="font-mono text-text-primary">{{ system.wsHeartbeat }}s</span>
            </div>
            <div class="flex items-center gap-2 pt-1 text-caption">
              <span class="status-dot watching" style="width:6px;height:6px;"></span>
              <span class="text-text-secondary">客户端 1,284 / 上限 10k</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Toast 提示 -->
    <transition name="fade">
      <div v-if="toast" class="fixed top-4 right-4 z-50 px-4 py-3 rounded-card shadow-lg flex items-center gap-2"
           :style="toast.type === 'success' ? 'background: var(--success); color: #fff;'
                 : toast.type === 'error'   ? 'background: var(--danger-red); color: #fff;'
                                            : 'background: var(--accent-blue); color: #fff;'">
        <ShieldCheck v-if="toast.type === 'success'" :size="16"/>
        <AlertTriangle v-else-if="toast.type === 'error'" :size="16"/>
        <Bell v-else :size="16"/>
        <span class="text-sm font-medium">{{ toast.message }}</span>
      </div>
    </transition>

    <!-- 底部保存 -->
    <div class="sticky bottom-0 card">
      <div class="card-body flex flex-wrap items-center justify-between gap-3">
        <div class="text-caption text-text-secondary">
          <template v-if="loading">
            <span class="inline-block w-3 h-3 rounded-full border-2 border-text-secondary border-t-transparent animate-spin align-middle mr-1"></span>
            正在加载服务器配置...
          </template>
          <template v-else-if="changes > 0">
            <AlertTriangle :size="14" class="inline mr-1" style="color: var(--warning);"/>
            存在 <span class="font-mono text-warning">{{ changes }}</span> 处未保存的修改。
          </template>
          <template v-else>所有修改已保存。</template>
        </div>
        <div class="flex gap-2">
          <button class="btn btn-secondary" :disabled="loading || saving" @click="reset"><RotateCcw :size="14"/> 重置</button>
          <button class="btn btn-primary" :disabled="loading || saving" @click="save">
            <Save :size="14"/>
            <span v-if="saving">保存中...</span>
            <span v-else>保存并热加载</span>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script lang="ts">
import { Sparkles } from 'lucide-vue-next'
export default { components: { Sparkles } }
</script>
