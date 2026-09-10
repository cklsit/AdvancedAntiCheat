<script setup lang="ts">
import { ref, computed, onMounted, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Ban, Shield, ShieldAlert, Globe, Wifi, Clock, Monitor, Link2, AlertTriangle } from 'lucide-vue-next'
import type { Player } from '@/types'
import { getPlayerDetail, kickPlayer, banPlayer, setPlayerGameMode } from '@/api/players'
import { formatDate, formatDuration, formatNumber, formatIp } from '@/utils/format'
import { playerStatusMeta, scoreToHexColor, levelToCssClass, levelToLabel } from '@/utils/risk'

const route = useRoute()
const router = useRouter()
const loading = ref(true)
const player = ref<Player | null>(null)
const notFound = ref(false)

type ToastKind = 'success' | 'error' | 'info'
interface ToastPayload { type: ToastKind; message: string; durationMs?: number }
function toast(p: ToastPayload): void {
  if (typeof window === 'undefined') return
  const w = window as typeof window & { __anticheatToast?: (p: ToastPayload) => void }
  if (typeof w.__anticheatToast === 'function') { w.__anticheatToast(p); return }
  // fallback：dispatch app:toast（App.vue也监听这个）
  window.dispatchEvent(new CustomEvent('app:toast', { detail: p }))
}
function nativeConfirm(msg: string): boolean {
  return typeof window !== 'undefined' ? window.confirm(msg) : true
}

// —— 观察模式 / 踢出 / 封禁 ——
const actionBusy = reactive({ observe: false, kick: false, ban: false })

async function onObserve(): Promise<void> {
  if (!player.value || actionBusy.observe) return
  if (!nativeConfirm(`确认将玩家「${player.value.name}」切换到观察模式 (Spectator)？`)) return
  actionBusy.observe = true
  try {
    await setPlayerGameMode(player.value.uuid, 'OBSERVER')
    toast({ type: 'success', message: `已将 ${player.value.name} 切换为观察模式` })
    // 刷新详情
    try {
      const r = await getPlayerDetail(player.value.uuid)
      player.value = r.data
    } catch { /* ignore */ }
  } catch (e) {
    toast({ type: 'error', message: `切换观察模式失败：${(e as Error).message || '请检查权限或玩家是否在线'}` })
  } finally { actionBusy.observe = false }
}

async function onKick(): Promise<void> {
  if (!player.value || actionBusy.kick) return
  const reason = (typeof window !== 'undefined')
    ? (window.prompt(`输入踢出「${player.value.name}」的原因（可留空）：`, '管理员踢出') ?? '')
    : '管理员踢出'
  if (reason === null) return
  if (!nativeConfirm(`确认立即踢出玩家「${player.value.name}」？`)) return
  actionBusy.kick = true
  try {
    await kickPlayer(player.value.uuid, { reason })
    toast({ type: 'success', message: `已踢出 ${player.value.name}` })
  } catch (e) {
    toast({ type: 'error', message: `踢出失败：${(e as Error).message || '请检查权限或玩家是否在线'}` })
  } finally { actionBusy.kick = false }
}

// —— 简单的封禁 Modal（内置，不依赖外部UI库）
const banDialog = reactive<{
  open: boolean
  duration: string
  durationLabel: string
  reason: string
  busy: boolean
}>({
  open: false,
  duration: '1d',
  durationLabel: '1 天',
  reason: 'Web 面板封禁',
  busy: false
})
const DURATIONS = [
  { val: '30m',   label: '30 分钟' },
  { val: '1h',    label: '1 小时' },
  { val: '12h',   label: '12 小时' },
  { val: '1d',    label: '1 天' },
  { val: '7d',    label: '7 天' },
  { val: '30d',   label: '30 天' },
  { val: 'permanent', label: '永久' }
]
function openBanDialog(): void {
  if (!player.value || actionBusy.ban) return
  banDialog.open = true
  banDialog.busy = false
  banDialog.duration = '1d'
  banDialog.durationLabel = '1 天'
  banDialog.reason = 'Web 面板封禁'
}
function closeBanDialog(): void {
  if (banDialog.busy) return
  banDialog.open = false
}
function pickDuration(val: string, label: string): void {
  banDialog.duration = val
  banDialog.durationLabel = label
}
async function confirmBan(): Promise<void> {
  if (!player.value || banDialog.busy) return
  if (!nativeConfirm(`确认封禁「${player.value!.name}」 ${banDialog.durationLabel}？封禁后立即踢出。`)) return
  banDialog.busy = true
  try {
    await banPlayer(player.value.uuid, { duration: banDialog.duration, reason: banDialog.reason })
    toast({ type: 'success', message: `已封禁 ${player.value.name}（${banDialog.durationLabel}）` })
    banDialog.open = false
    // 刷新详情
    try {
      const r = await getPlayerDetail(player.value.uuid)
      player.value = r.data
    } catch { /* ignore */ }
  } catch (e) {
    toast({ type: 'error', message: `封禁失败：${(e as Error).message || '请检查权限'}` })
  } finally { banDialog.busy = false }
}

onMounted(async () => {
  const id = route.params.id as string
  try {
    const resp = await getPlayerDetail(id)
    player.value = resp.data
  } catch {
    notFound.value = true
  } finally {
    loading.value = false
  }
})

function goBack(): void {
  router.back()
}
function goLinked(uuid: string): void {
  router.push(`/players/${uuid}`).catch(() => void 0)
}
const pl = computed(() => player.value as Player)
</script>

<template>
  <div v-if="loading" class="h-[50vh] flex items-center justify-center text-text-secondary text-sm">加载玩家画像中…</div>
  <div v-else-if="notFound" class="card">
    <div class="card-body p-10 text-center">
      <ShieldAlert :size="40" class="mx-auto mb-4" style="color: var(--danger-red);"/>
      <h3 class="text-card-title text-text-primary mb-2">未找到该玩家画像</h3>
      <p class="text-caption text-text-secondary mb-5">UUID 可能无效或数据已被清理。</p>
      <button class="btn btn-secondary" @click="router.replace('/players')">返回玩家列表</button>
    </div>
  </div>

  <div v-else class="space-y-4">
    <!-- 顶部返回 + 玩家卡 -->
    <div class="card">
      <div class="card-body p-5 flex flex-wrap gap-5">
        <button class="btn btn-secondary shrink-0" @click="goBack"><ArrowLeft :size="16"/>返回列表</button>

        <div class="flex items-center gap-4 flex-1 min-w-[300px]">
          <div class="w-16 h-16 rounded-card flex items-center justify-center text-2xl font-bold text-white shrink-0"
               style="background: linear-gradient(135deg, var(--accent-blue), var(--accent-purple));">
            {{ pl.avatar }}
          </div>
          <div class="min-w-0 flex-1">
            <div class="flex flex-wrap items-center gap-3 mb-1">
              <h2 class="m-0 text-[20px] font-semibold text-text-primary leading-tight">{{ pl.name }}</h2>
              <span class="tag" :class="playerStatusMeta(pl.status).cls">
                <span class="status-dot" :class="playerStatusMeta(pl.status).dot" style="width:6px;height:6px;"></span>
                {{ playerStatusMeta(pl.status).label }}
              </span>
              <span class="tag" :class="levelToCssClass(pl.riskLevel)">{{ levelToLabel(pl.riskLevel) }} · {{ pl.riskScore }}</span>
            </div>
            <div class="text-caption text-text-secondary font-mono space-x-4">
              <span>UUID: {{ pl.uuid }}</span>
            </div>
            <div class="mt-2 flex flex-wrap gap-x-6 gap-y-1 text-caption text-text-secondary">
              <span class="inline-flex items-center gap-1.5"><Globe :size="13"/> 国家：{{ pl.country }}</span>
              <span class="inline-flex items-center gap-1.5"><Wifi :size="13"/> Ping：<span class="font-mono text-text-primary">{{ pl.ping }}ms</span></span>
              <span class="inline-flex items-center gap-1.5"><Monitor :size="13"/> 版本：{{ pl.version }}</span>
              <span class="inline-flex items-center gap-1.5"><Globe :size="13"/> 世界：{{ pl.world }}</span>
              <span class="inline-flex items-center gap-1.5">模式：{{ pl.gameMode }}</span>
            </div>
          </div>
        </div>

        <!-- 大数字指标 -->
        <div class="grid grid-cols-4 gap-4 w-full md:w-auto md:min-w-[480px]">
          <div class="text-center p-3 rounded-card border border-border-line bg-bg-hover">
            <div class="text-2xl font-semibold text-text-primary leading-none">{{ formatNumber(pl.violationsCount) }}</div>
            <div class="text-caption text-text-secondary mt-1.5">累计违规</div>
          </div>
          <div class="text-center p-3 rounded-card border border-border-line bg-bg-hover">
            <div class="text-2xl font-semibold leading-none font-mono" :style="{ color: scoreToHexColor(pl.riskScore) }">{{ pl.riskScore }}</div>
            <div class="text-caption text-text-secondary mt-1.5">当前风险分</div>
          </div>
          <div class="text-center p-3 rounded-card border border-border-line bg-bg-hover">
            <div class="text-2xl font-semibold text-text-primary leading-none font-mono">{{ formatIp(pl.ip, true) }}</div>
            <div class="text-caption text-text-secondary mt-1.5">最近 IP</div>
          </div>
          <div class="text-center p-3 rounded-card border border-border-line bg-bg-hover">
            <div class="text-2xl font-semibold text-text-primary leading-none">{{ formatDuration(pl.onlineDuration) }}</div>
            <div class="text-caption text-text-secondary mt-1.5">本次在线</div>
          </div>
        </div>
      </div>

      <div class="card-footer flex flex-wrap items-center justify-end gap-2 border-t border-border-line">
        <button class="btn btn-secondary" :disabled="actionBusy.observe" @click="onObserve"><Shield :size="14"/>{{ actionBusy.observe ? '处理中…' : '观察模式' }}</button>
        <button class="btn btn-outline-cyan" :disabled="actionBusy.kick" @click="onKick">{{ actionBusy.kick ? '处理中…' : '踢出玩家' }}</button>
        <button class="btn btn-danger" :disabled="actionBusy.ban" @click="openBanDialog"><Ban :size="14"/>{{ actionBusy.ban ? '处理中…' : '执行封禁' }}</button>
      </div>
    </div>

    <div class="grid grid-cols-1 xl:grid-cols-3 gap-4">
      <!-- 违规历史 -->
      <div class="card xl:col-span-2">
        <div class="card-header"><h3 class="flex items-center gap-2"><AlertTriangle :size="16" style="color: var(--warning);"/> 违规历史</h3></div>
        <div class="card-body p-0">
          <div class="table-wrap">
            <table class="data-table">
              <thead><tr><th>时间</th><th>模块</th><th>类型</th><th>得分</th><th>服务器</th></tr></thead>
              <tbody>
                <tr v-if="pl.violationHistory.length === 0">
                  <td colspan="5" class="py-10 text-center text-text-secondary">暂无违规记录</td>
                </tr>
                <tr v-for="v in pl.violationHistory" :key="v.id">
                  <td class="font-mono text-caption text-text-secondary">{{ formatDate(v.time, 'date') }}</td>
                  <td class="text-text-primary">{{ v.module }}</td>
                  <td class="text-text-secondary">{{ v.type }}</td>
                  <td>
                    <div class="flex items-center gap-2">
                      <div class="risk-bar w-24"><div class="risk-bar-fill" :style="{ width: v.score + '%', background: scoreToHexColor(v.score) }"></div></div>
                      <span class="font-mono text-sm" :style="{ color: scoreToHexColor(v.score) }">{{ v.score }}</span>
                    </div>
                  </td>
                  <td class="text-caption text-text-secondary font-mono">{{ v.server }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>

      <!-- 右侧：基础 + 关联账号 -->
      <div class="space-y-4">
        <div class="card">
          <div class="card-header"><h3 class="flex items-center gap-2"><Clock :size="16" style="color: var(--accent-cyan);"/> 时间线</h3></div>
          <div class="card-body space-y-3 text-sm">
            <div class="flex justify-between"><span class="text-text-secondary">首次进入</span><span class="font-mono text-text-primary">{{ pl.firstJoin }}</span></div>
            <div class="flex justify-between"><span class="text-text-secondary">最近登录</span><span class="font-mono text-text-primary">{{ formatDate(pl.lastJoin, 'full') }}</span></div>
            <div class="flex justify-between"><span class="text-text-secondary">最近触发</span><span class="font-mono text-text-primary">{{ formatDate(pl.lastTrigger, 'relative') }}</span></div>
            <div v-if="pl.hardwareId" class="flex justify-between"><span class="text-text-secondary">硬件指纹</span><span class="font-mono text-text-primary">{{ pl.hardwareId.slice(0, 16) }}…</span></div>
          </div>
        </div>

        <div class="card">
          <div class="card-header"><h3 class="flex items-center gap-2"><Link2 :size="16" style="color: var(--accent-purple);"/> 关联账号 ({{ pl.linkedAccounts.length }})</h3></div>
          <div class="card-body p-0">
            <div v-if="pl.linkedAccounts.length === 0" class="p-6 text-center text-text-secondary text-sm">未检测到关联账号</div>
            <div
              v-for="la in pl.linkedAccounts" :key="la.uuid"
              class="px-4 py-3 border-b last:border-b-0 border-border-line flex items-center gap-3 cursor-pointer hover:bg-bg-hover"
              @click="goLinked(la.uuid)"
            >
              <div class="avatar avatar-sm">{{ la.name.charAt(0) }}</div>
              <div class="min-w-0 flex-1">
                <div class="text-sm text-text-primary truncate">{{ la.name }}</div>
                <div class="text-caption text-text-secondary">{{ la.relation }}</div>
              </div>
              <div class="flex flex-col items-end gap-1">
                <span v-if="la.ipMatch" class="tag tag-red text-[10px]">IP 匹配</span>
                <span v-if="la.hardwareMatch" class="tag tag-orange text-[10px]">硬件匹配</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 封禁 Dialog -->
    <Transition name="fade">
      <div v-if="banDialog.open" class="ban-dialog-mask" @click.self="closeBanDialog">
        <div class="ban-dialog card" role="dialog" aria-modal="true" aria-labelledby="ban-dialog-title">
          <div class="card-header flex items-center justify-between">
            <h3 id="ban-dialog-title" class="flex items-center gap-2 m-0">
              <Ban :size="18" style="color: var(--danger-red);"/>
              执行封禁 — <span class="text-text-secondary font-normal">{{ player?.name }}</span>
            </h3>
            <button class="icon-btn" @click="closeBanDialog" :disabled="banDialog.busy" aria-label="关闭">✕</button>
          </div>

          <div class="card-body space-y-5">
            <div>
              <label class="form-label">封禁时长</label>
              <div class="dur-grid">
                <button
                  v-for="d in DURATIONS"
                  :key="d.val"
                  type="button"
                  :class="['dur-chip', { active: d.val === banDialog.duration }]"
                  @click="pickDuration(d.val, d.label)"
                  :disabled="banDialog.busy"
                >{{ d.label }}</button>
              </div>
            </div>
            <div>
              <label class="form-label" for="ban-reason">封禁原因</label>
              <textarea
                id="ban-reason"
                v-model="banDialog.reason"
                :disabled="banDialog.busy"
                rows="3"
                placeholder="请输入封禁原因（会显示给被封玩家）"
                class="form-input form-textarea"
              ></textarea>
            </div>
          </div>

          <div class="card-footer flex justify-end gap-2 border-t border-border-line">
            <button class="btn btn-secondary" @click="closeBanDialog" :disabled="banDialog.busy">取消</button>
            <button class="btn btn-danger" @click="confirmBan" :disabled="banDialog.busy">
              {{ banDialog.busy ? '执行中…' : `确认封禁 · ${banDialog.durationLabel}` }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.ban-dialog-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9990;
  padding: 24px;
}
.ban-dialog {
  width: 100%;
  max-width: 520px;
}
.icon-btn {
  width: 30px; height: 30px;
  display: inline-flex; align-items: center; justify-content: center;
  background: transparent;
  border: 1px solid var(--border-line);
  color: var(--text-secondary);
  border-radius: 8px;
  cursor: pointer;
}
.icon-btn:hover:not(:disabled) { background: var(--bg-hover); color: var(--text-primary); }
.icon-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.form-label {
  display: block;
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 8px;
}
.form-input, .form-textarea {
  width: 100%;
  padding: 10px 12px;
  background: var(--bg-card);
  border: 1px solid var(--border-line);
  color: var(--text-primary);
  border-radius: 10px;
  font-size: 14px;
  outline: none;
  transition: border-color 150ms ease;
  font-family: inherit;
}
.form-input:focus, .form-textarea:focus {
  border-color: var(--accent-cyan);
}
.form-textarea { resize: vertical; min-height: 80px; }

.dur-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}
.dur-chip {
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid var(--border-line);
  background: var(--bg-card);
  color: var(--text-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: all 150ms ease;
}
.dur-chip:hover:not(:disabled) { border-color: rgba(100,160,255,0.4); color: var(--text-primary); }
.dur-chip.active {
  border-color: var(--danger-red);
  color: var(--text-primary);
  background: rgba(240, 82, 82, 0.08);
  font-weight: 600;
}
.dur-chip:disabled { opacity: 0.5; cursor: not-allowed; }

@media (max-width: 640px) { .dur-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }

.fade-enter-from, .fade-leave-to { opacity: 0; }
.fade-enter-active, .fade-leave-active { transition: opacity 200ms ease; }
.fade-enter-active .ban-dialog, .fade-leave-active .ban-dialog { transition: transform 220ms cubic-bezier(.2,.7,.2,1); }
.fade-enter-from .ban-dialog, .fade-leave-to .ban-dialog { transform: translateY(-12px) scale(0.98); }
</style>
