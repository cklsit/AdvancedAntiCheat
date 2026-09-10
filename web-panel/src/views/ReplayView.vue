<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  PlaySquare, RefreshCw, Trash2, Users, Archive, AlertTriangle,
  Loader2, CircleDot, Clock, Flag, HardDrive
} from 'lucide-vue-next'
import {
  getReplayPlayers,
  getReplayArchives,
  deleteReplayArchive
} from '@/api/replays'
import type { ReplayPlayer, ReplayArchive } from '@/types/replay'
import { formatDate, formatDuration } from '@/utils/format'

const route = useRoute()
const router = useRouter()

// ==================== 是否在子路由（播放器） ====================
const inWatch = computed(() => route.name === 'ReplayWatch')

// ==================== 状态 ====================
const players = ref<ReplayPlayer[]>([])
const archives = ref<ReplayArchive[]>([])
const playersLoading = ref(false)
const archivesLoading = ref(false)
const playersError = ref('')
const archivesError = ref('')
const deleting = ref<string | null>(null)

// 自动轮询
let pollTimer = 0

// ==================== 统计 ====================
const onlineCount = computed(() => players.value.length)
const archiveCount = computed(() => archives.value.length)

// ==================== 轮询加载 ====================
async function loadAll(): Promise<void> {
  await Promise.allSettled([loadPlayers(), loadArchives()])
}

async function loadPlayers(): Promise<void> {
  playersLoading.value = true
  playersError.value = ''
  try {
    players.value = await getReplayPlayers()
  } catch {
    playersError.value = '在线玩家列表加载失败'
  } finally {
    playersLoading.value = false
  }
}

async function loadArchives(): Promise<void> {
  archivesLoading.value = true
  archivesError.value = ''
  try {
    archives.value = await getReplayArchives()
  } catch {
    archivesError.value = '历史存档加载失败'
  } finally {
    archivesLoading.value = false
  }
}

function startPolling(): void {
  stopPolling()
  // 每 2 秒刷一次在线玩家（频繁变化），存档每 10 秒刷一次（低频）
  pollTimer = window.setInterval(() => {
    if (document.visibilityState !== 'visible') return
    void loadPlayers()
    // 每 5 轮刷一次存档
    pollCounter++
    if (pollCounter % 5 === 0) void loadArchives()
  }, 2000)
}
let pollCounter = 0

function stopPolling(): void {
  if (pollTimer) {
    window.clearInterval(pollTimer)
    pollTimer = 0
  }
}

// ==================== 工具 ====================
/** h:mm:ss 格式化 */
function formatHms(seconds: number): string {
  if (seconds < 0) seconds = 0
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  const mm = m.toString().padStart(2, '0')
  const ss = s.toString().padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${m}:${ss}`
}

/** 时长 → H:MM（存档用） */
function formatDurationHm(seconds: number): string {
  if (seconds < 0) seconds = 0
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  return `${h}:${m.toString().padStart(2, '0')}`
}

/** 违规等级 → tag class */
function levelTagClass(level: string | null): string {
  switch ((level ?? '').toUpperCase()) {
    case 'CRITICAL': return 'tag tag-red'
    case 'HIGH': return 'tag tag-orange'
    case 'MEDIUM': return 'tag tag-yellow'
    case 'LOW': return 'tag tag-blue'
    default: return 'tag tag-blue'
  }
}

/** 玩家头像渐变（根据 uuid hash 取 6 种之一） */
function avatarGradient(uuid: string): string {
  const palettes = [
    'linear-gradient(135deg,#1f6feb,#00e5ff)',
    'linear-gradient(135deg,#f85149,#ff6d00)',
    'linear-gradient(135deg,#8b5cf6,#ec4899)',
    'linear-gradient(135deg,#22c55e,#00e5ff)',
    'linear-gradient(135deg,#f59e0b,#ef4444)',
    'linear-gradient(135deg,#0ea5e9,#8b5cf6)'
  ]
  let hash = 0
  for (let i = 0; i < uuid.length; i++) hash = (hash * 31 + uuid.charCodeAt(i)) >>> 0
  return palettes[hash % palettes.length]
}

// ==================== 跳转播放器 ====================
function openWatchWithUuid(uuid: string): void {
  router.push({ name: 'ReplayWatch', query: { uuid } })
}

function openWatchWithArchive(filename: string): void {
  router.push({ name: 'ReplayWatch', query: { archive: filename } })
}

async function handleDeleteArchive(filename: string): Promise<void> {
  if (deleting.value) return
  if (!window.confirm(`确认删除存档 ${filename}？删除后不可恢复。`)) return
  deleting.value = filename
  try {
    await deleteReplayArchive(filename)
    archives.value = archives.value.filter(a => a.filename !== filename)
  } catch {
    archivesError.value = '删除存档失败'
  } finally {
    deleting.value = null
  }
}

// ==================== 生命周期 ====================
onMounted(() => {
  void loadAll()
  startPolling()
})

onBeforeUnmount(() => {
  stopPolling()
})
</script>

<template>
  <!-- 子路由（播放器） -->
  <router-view v-if="inWatch" />

  <!-- 默认列表页 -->
  <div v-else class="space-y-4">

    <!-- ========== 顶部控制栏 ========== -->
    <div class="card">
      <div class="card-header">
        <h3 class="flex items-center gap-2">
          <PlaySquare :size="16" style="color: var(--accent-cyan);" />
          回放监控中心
        </h3>
        <div class="flex items-center gap-2">
          <button class="btn btn-ghost btn-sm" :disabled="playersLoading || archivesLoading" @click="loadAll">
            <RefreshCw :size="14" :class="{ 'animate-spin': playersLoading || archivesLoading }" />
            刷新
          </button>
        </div>
      </div>
      <div class="card-body flex flex-wrap items-center gap-4 text-caption">
        <span class="flex items-center gap-1.5 text-text-secondary">
          <Users :size="14" style="color: var(--accent-cyan);" />
          在线 <span class="font-mono text-text-primary">{{ onlineCount }}</span>
        </span>
        <span class="flex items-center gap-1.5 text-text-secondary">
          <Archive :size="14" style="color: var(--accent-blue);" />
          存档 <span class="font-mono text-text-primary">{{ archiveCount }}</span>
        </span>
        <span class="text-text-muted flex items-center gap-1.5">
          <Loader2 v-if="playersLoading" :size="12" class="animate-spin" />
          {{ playersLoading ? '实时同步中…' : '2 秒自动刷新' }}
        </span>
        <span v-if="playersError" class="text-text-muted" style="color: var(--danger-red);">{{ playersError }}</span>
      </div>
    </div>

    <!-- ========== 在线玩家卡片网格 ========== -->
    <div class="card">
      <div class="card-header">
        <h3 class="flex items-center gap-2">
          <CircleDot :size="16" style="color: var(--accent-cyan);" />
          在线玩家（持续录制中）
        </h3>
        <span class="text-caption text-text-muted font-normal">共 {{ onlineCount }} 位</span>
      </div>
      <div class="card-body">

        <!-- 空状态 -->
        <div v-if="!playersLoading && players.length === 0" class="py-10 text-center">
          <Users :size="42" class="mx-auto mb-3 opacity-40" style="color: var(--text-muted);" />
          <div class="text-sm text-text-muted">暂无玩家正在录制轨迹</div>
          <div class="text-caption text-text-muted mt-1 opacity-70">当玩家在线时，这里会自动出现</div>
        </div>

        <!-- 加载中 -->
        <div v-else-if="playersLoading && players.length === 0" class="py-10 text-center text-text-muted">
          <Loader2 :size="28" class="mx-auto mb-3 animate-spin" style="color: var(--accent-cyan);" />
          加载中…
        </div>

        <!-- 卡片网格 -->
        <div v-else class="player-grid">
          <div
            v-for="p in players"
            :key="p.uuid"
            class="player-card"
            @click="openWatchWithUuid(p.uuid)"
          >
            <!-- 圆形头像 -->
            <div class="avatar" :style="{ background: avatarGradient(p.uuid) }">
              {{ p.playerName.charAt(0).toUpperCase() }}
            </div>

            <!-- 违规红点徽章 -->
            <div v-if="p.violationCount > 0" class="violation-badge" :title="`累计 ${p.violationCount} 次违规`">
              {{ p.violationCount > 99 ? '99+' : p.violationCount }}
            </div>

            <div class="p-name" :title="p.playerName">{{ p.playerName }}</div>

            <div class="p-times">
              <span class="time-item" :title="'在线时长'">
                <Clock :size="12" />
                {{ formatHms(p.onlineSeconds) }}
              </span>
              <span class="time-sep">·</span>
              <span class="time-item" :title="'可回放时长'">
                {{ formatDurationHm(p.replaySeconds) }}
              </span>
            </div>

            <!-- 最后违规类型 tag -->
            <div v-if="p.lastViolationType" class="p-violation-row">
              <span :class="levelTagClass(p.lastViolationLevel)">{{ p.lastViolationLevel }}</span>
              <span class="tag tag-red font-mono">{{ p.lastViolationType }}</span>
            </div>
            <div v-else class="p-violation-row text-text-muted text-caption">
              暂无违规
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ========== 历史存档表格 ========== -->
    <div class="card">
      <div class="card-header">
        <h3 class="flex items-center gap-2">
          <Archive :size="16" style="color: var(--accent-blue);" />
          历史存档
        </h3>
        <span class="text-caption text-text-muted font-normal">共 {{ archiveCount }} 份</span>
      </div>
      <div class="card-body">

        <!-- 空状态 -->
        <div v-if="!archivesLoading && archives.length === 0" class="py-10 text-center">
          <HardDrive :size="42" class="mx-auto mb-3 opacity-40" style="color: var(--text-muted);" />
          <div class="text-sm text-text-muted">暂无历史存档</div>
          <div class="text-caption text-text-muted mt-1 opacity-70">有违规案件结案后会自动存档</div>
        </div>

        <!-- 加载中 -->
        <div v-else-if="archivesLoading && archives.length === 0" class="py-10 text-center text-text-muted">
          <Loader2 :size="28" class="mx-auto mb-3 animate-spin" style="color: var(--accent-cyan);" />
          加载中…
        </div>

        <!-- 表格 -->
        <table v-else class="archive-table">
          <thead>
            <tr>
              <th>文件名</th>
              <th>玩家</th>
              <th>时长</th>
              <th>大小</th>
              <th>违规数</th>
              <th>日期</th>
              <th class="text-right">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in archives" :key="a.filename">
              <td class="font-mono text-text-secondary">{{ a.filename }}</td>
              <td class="text-text-primary">{{ a.playerName }}</td>
              <td class="font-mono text-caption">{{ formatDuration(a.durationMs / 1000) }}</td>
              <td class="font-mono text-caption">{{ a.sizeKB }} KB</td>
              <td>
                <span v-if="a.violationCount > 0" class="tag tag-red">{{ a.violationCount }}</span>
                <span v-else class="tag tag-blue">0</span>
              </td>
              <td class="font-mono text-caption">{{ formatDate(a.startTime, 'full') }}</td>
              <td class="text-right">
                <button class="btn btn-primary btn-sm" @click="openWatchWithArchive(a.filename)">
                  <PlaySquare :size="13" />
                  加载回放
                </button>
                <button
                  class="btn btn-ghost btn-sm btn-danger-ghost"
                  :disabled="deleting === a.filename"
                  @click="handleDeleteArchive(a.filename)"
                >
                  <Trash2 :size="13" :class="{ 'animate-spin': deleting === a.filename }" />
                  删除
                </button>
              </td>
            </tr>
          </tbody>
        </table>

        <div v-if="archivesError" class="text-caption mt-2" style="color: var(--danger-red);">{{ archivesError }}</div>
      </div>
    </div>

  </div>
</template>

<style scoped>
/* ==================== 玩家卡片网格 ==================== */
.player-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 14px;
}

.player-card {
  position: relative;
  padding: 16px;
  border: 1px solid var(--border-line);
  border-radius: var(--radius-card);
  background: linear-gradient(135deg, rgba(20, 28, 38, 0.85), rgba(14, 20, 28, 0.9));
  cursor: pointer;
  transition: transform 0.15s, border-color 0.15s, box-shadow 0.15s;
  overflow: hidden;
}
.player-card::before {
  content: '';
  position: absolute;
  inset: 0;
  background: radial-gradient(200px 120px at 100% 0%, rgba(0, 229, 255, 0.08), transparent 70%);
  pointer-events: none;
}
.player-card:hover {
  border-color: rgba(0, 229, 255, 0.5);
  transform: translateY(-2px);
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.4), 0 0 0 1px rgba(0, 229, 255, 0.15);
}

/* 头像 */
.avatar {
  width: 46px;
  height: 46px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-weight: 600;
  font-size: 18px;
  letter-spacing: 0.5px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.35);
  margin-bottom: 10px;
}

/* 违规红点徽章 */
.violation-badge {
  position: absolute;
  top: 12px;
  right: 12px;
  min-width: 22px;
  height: 22px;
  padding: 0 6px;
  border-radius: 11px;
  background: var(--danger-red);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  font-family: var(--font-mono);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 0 0 2px var(--bg-card), 0 0 8px rgba(248, 81, 73, 0.6);
}

.p-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.p-times {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--text-muted);
  font-family: var(--font-mono);
  margin-bottom: 8px;
}
.time-item { display: inline-flex; align-items: center; gap: 3px; }
.time-sep { opacity: 0.4; }

.p-violation-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

/* ==================== 存档表格 ==================== */
.archive-table {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  font-size: 13px;
}
.archive-table thead th {
  text-align: left;
  padding: 10px 12px;
  font-size: 12px;
  font-weight: 500;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  border-bottom: 1px solid var(--border-line);
  background: transparent;
}
.archive-table tbody td {
  padding: 10px 12px;
  border-bottom: 1px solid var(--border-line);
  color: var(--text-secondary);
}
.archive-table tbody tr {
  transition: background 0.15s;
}
.archive-table tbody tr:hover td {
  background: rgba(0, 229, 255, 0.03);
}
.archive-table tbody tr:last-child td {
  border-bottom: none;
}

.btn-danger-ghost {
  color: var(--danger-red);
  border: 1px solid transparent;
}
.btn-danger-ghost:hover:not(:disabled) {
  background: rgba(248, 81, 73, 0.1);
  border-color: rgba(248, 81, 73, 0.4);
}
</style>
