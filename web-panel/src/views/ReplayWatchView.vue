<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Hls from 'hls.js'
import {
  Play, Pause, SkipBack, SkipForward, Loader2, AlertTriangle,
  ArrowLeft, LogOut, Move3D, Orbit, Layers, Flag, Crosshair, Box,
  Archive as ArchiveIcon, Radio, User, Check, Heart, UtensilsCrossed, Shield, Sparkles,
  Video
} from 'lucide-vue-next'
import JSZip from 'jszip'
import ReplayCanvas from '@/components/replay/ReplayCanvas.vue'
import HudOverlay from '@/components/replay/HudOverlay.vue'
import {
  downloadReplayArchiveBlob
} from '@/api/replays'
import { useAuthStore } from '@/stores/auth'
import { useReplayWS } from '@/composables/useReplayWS'
import type {
  TracePoint,
  ViolationMarker,
  ReplaySession,
  ReplayLayers,
  HudFrame,
  ArchiveHudSample,
  DegradationState
} from '@/types/replay'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

// ==================== 模式判断 ====================
const isRealtime = computed(() => !!route.query.uuid)
const uuid = computed<string>(() => String(route.query.uuid ?? ''))
const archiveName = computed<string>(() => String(route.query.archive ?? ''))

// ==================== 加载状态 ====================
const loading = ref(true)
const loadError = ref('')
const canvasError = ref(false)

// ==================== 会话数据 ====================
const session = ref<ReplaySession | null>(null)
// 独立数组方便增量追加（与 session.points 同步）
const points = ref<TracePoint[]>([])
const violations = ref<ViolationMarker[]>([])

// ==================== HLS 播放 ====================
const videoRef = ref<HTMLVideoElement | null>(null)
const hlsInstance = ref<Hls | null>(null)
const hlsLoading = ref(false)
const hlsError = ref('')

// ==================== 真实画面归档回放（Task 8.5） ====================
const hasVideoArchive = ref(false)
const videoArchiveUrl = ref<string | null>(null)
const videoArchiveRef = ref<HTMLVideoElement | null>(null)
let videoTimeupdateHandler: (() => void) | null = null

// ==================== 归档 HUD 采样（metadata.json hudSampled5Hz） ====================
/** 5Hz HUD 采样帧（按 t 升序），视频归档模式下与 currentTime 同步 */
const archiveHudSamples = ref<ArchiveHudSample[]>([])

// ==================== v3 归档附加元数据（§4.5 取证诚实性） ====================
/** telemetry.jsonl 解析出的 20Hz 全量遥测帧（存在时优先于 5Hz 采样） */
const archiveTelemetryFrames = ref<HudFrame[]>([])
/** video.cameraSegments：机位时间段 [{tMs, mode}]，标注哪几段录到了手持物品 */
const archiveCameraSegments = ref<Array<{ tMs: number; mode: string }>>([])
/** video.showsHeldItem：本归档是否至少有一段录到了手持物品（取证充分性） */
const archiveShowsHeldItem = ref<boolean | null>(null)
/** coverage：归档覆盖情况 {recorded, reason} */
const archiveCoverage = ref<{ recorded: boolean; reason: string | null } | null>(null)

/**
 * 当前视频时刻对应的 HUD 帧（二分查找 t <= currentMs 的最近帧）。
 * 稀疏 inv 为 null 时向前回溯找最近非空帧（物品栏缓存语义）。
 */
const archiveHudFrame = computed<HudFrame | null>(() => {
  // v3：优先使用 telemetry.jsonl（20Hz 全量，含准星/坐标/36 格背包）
  const tele = archiveTelemetryFrames.value
  if (tele.length) {
    const ms = currentMs.value
    let lo = 0
    let hi = tele.length - 1
    let ans = -1
    while (lo <= hi) {
      const mid = (lo + hi) >>> 1
      const t = tele[mid].t ?? 0
      if (t <= ms) { ans = mid; lo = mid + 1 }
      else { hi = mid - 1 }
    }
    if (ans < 0) return null
    const f = tele[ans]
    // 稀疏字段回溯：hotbar / finv 每秒才采一次，向前找最近非空帧
    let hotbar = f.hotbar
    let finv = f.finv
    for (let i = ans; i >= 0 && i >= ans - 100 && (hotbar == null || finv == null); i--) {
      if (hotbar == null && tele[i].hotbar != null) hotbar = tele[i].hotbar
      if (finv == null && tele[i].finv != null) finv = tele[i].finv
    }
    return { ...f, hotbar, finv }
  }

  const s = archiveHudSamples.value
  if (!s.length) return null
  const ms = currentMs.value
  let lo = 0
  let hi = s.length - 1
  let ans = -1
  while (lo <= hi) {
    const mid = (lo + hi) >>> 1
    if (s[mid].t <= ms) { ans = mid; lo = mid + 1 }
    else { hi = mid - 1 }
  }
  if (ans < 0) return null
  const f = s[ans]
  // 稀疏 inv 回溯：最多向前找 100 帧（20s）内的最近非空物品栏
  let inv: Array<string | null> | null = null
  for (let i = ans; i >= 0 && i >= ans - 100; i--) {
    if (s[i].inv != null) { inv = s[i].inv; break }
  }
  const hotbar: HudFrame['hotbar'] = inv
    ? inv.map(id => (id ? { id: 'minecraft:' + id.toLowerCase(), count: 1 } : null))
    : null
  return {
    w: 0,
    health: f.h / 2,
    maxHealth: 20,
    hunger: f.f,
    armor: f.a / 2,
    level: f.lvl,
    xp: f.xp / 100,
    air: null,
    hotbarSlot: f.hs,
    hotbar
  }
})

/** 侧栏 HUD 快照统一数据源：实时用 WS 帧，归档用采样帧 */
const displayHudFrame = computed<HudFrame | null>(() =>
  isRealtime.value ? hudFrame.value : (hasVideoArchive.value ? archiveHudFrame.value : null)
)

/** 当前播放时刻的机位（由 cameraSegments 推断）。ATTACH 段画面不含手持物品（§8.4 红线 4） */
const archiveCameraMode = computed<string | null>(() => {
  const segs = archiveCameraSegments.value
  if (!segs.length) return null
  const ms = currentMs.value
  let mode: string = segs[0].mode
  for (const s of segs) {
    if (s.tMs <= ms) mode = s.mode
    else break
  }
  return mode
})

function setupHls(url: string): void {
  const video = videoRef.value
  if (!video) return
  // 先销毁旧实例
  if (hlsInstance.value) {
    try { hlsInstance.value.destroy() } catch { /* noop */ }
    hlsInstance.value = null
  }
  hlsError.value = ''
  hlsLoading.value = true

  if (Hls.isSupported()) {
    const hls = new Hls({
      lowLatencyMode: true,
      liveSyncDurationCount: 3,
      backBufferLength: 60,
      maxBufferLength: 30,
      enableWorker: true,
    })
    hls.loadSource(url)
    hls.attachMedia(video)
    hls.on(Hls.Events.MANIFEST_PARSED, () => {
      hlsLoading.value = false
      // 自动播放（muted 保证浏览器允许）
      void video.play().catch(() => { /* 忽略：用户没交互时失败，用户再点按钮即可 */ })
    })
    hls.on(Hls.Events.ERROR, (_e, data) => {
      if (data.fatal) {
        switch (data.type) {
          case Hls.ErrorTypes.NETWORK_ERROR:
            // m3u8 连续拿不到（HLS 路由 404 / observer 没写盘）→ 停止无限重试，提示降级
            if (data.details === 'manifestLoadError') {
              hlsError.value = 'HLS 播放列表不可用（manifestLoadError）。观察者可能尚未产出切片、已被释放、或 HLS 路由配置异常。'
              hlsLoading.value = false
              try { hls.destroy() } catch {}
              break
            }
            hlsError.value = 'HLS 网络错误，正在恢复…'
            hls.startLoad()
            break
          case Hls.ErrorTypes.MEDIA_ERROR:
            hlsError.value = 'HLS 媒体错误，正在恢复…'
            hls.recoverMediaError()
            break
          default:
            hlsError.value = `HLS 致命错误：${data.type} ${data.details || ''}`
            try { hls.destroy() } catch {}
            break
        }
      }
    })
    hlsInstance.value = hls
    // 定期读取 hls.js 实测延迟，更新对齐时钟 τ
    stopLatencyPoller()
    latencyPoller = setInterval(() => {
      const h = hlsInstance.value
      const lat = (h as unknown as { latency?: number } | null)?.latency
      if (typeof lat === 'number' && lat > 0) {
        const ms = Math.min(10000, Math.max(500, Math.round(lat * 1000)))
        videoLatencyMs.value = ms
        latencyReporter?.(ms)
      }
    }, 2000)
  } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
    // Safari 原生 HLS
    video.src = url
    video.addEventListener('loadedmetadata', () => {
      hlsLoading.value = false
      void video.play().catch(() => {})
    }, { once: true })
  } else {
    hlsError.value = '当前浏览器不支持 HLS 播放'
    hlsLoading.value = false
  }
}

// ==================== 实时模式 WS ====================
let wsReturn: ReturnType<typeof useReplayWS> | null = null
const wsState = ref<'connected' | 'reconnecting' | 'disconnected'>('disconnected')
const latencyMs = ref<number>(0)
const hudFrame = ref<HudFrame | null>(null)
let latencyPoller: ReturnType<typeof setInterval> | null = null

function stopLatencyPoller(): void {
  if (latencyPoller) {
    clearInterval(latencyPoller)
    latencyPoller = null
  }
}

// ==================== 对齐时钟 / 降级状态（统一由 useReplayWS 提供） ====================
// 视频有 ~2s 延迟、遥测只有 ~50ms，直接渲染会让叠层"超前"于画面。
// AlignmentClock 按"实测视频延迟 τ + 服务端时钟偏移"推迟渲染，保证叠层与画面同帧（§3.3）。
const videoLatencyMs = ref<number>(2000)
const clockOffsetMs = ref<number>(0)
const alignedHudFrame = ref<HudFrame | null>(null)
/** 降级等级（§7）：每一级都必须在画面上明示，禁止静默降级 */
const degradation = ref<DegradationState>({
  code: 'L0', level: 'L0', label: '正常', evidenceValue: '完整'
})
/** 把 hls.js 实测延迟回灌给 AlignmentClock 的回调 */
let latencyReporter: ((ms: number) => void) | null = null

const hlsUrl = ref<string | null>(null)
const observerMsg = ref<{ level: 'info' | 'warn' | 'error'; text: string } | null>(null)
const traceDurationMs = ref<number>(0)
const startTime = ref<number | null>(null)
const playerName = ref<string | null>(null)

// 实时跳转提示
const realtimeJumpTip = ref('')
let realtimeJumpTipTimer: ReturnType<typeof setTimeout> | null = null
function showRealtimeJumpTip(msg: string): void {
  realtimeJumpTip.value = msg
  if (realtimeJumpTipTimer) {
    clearTimeout(realtimeJumpTipTimer)
    realtimeJumpTipTimer = null
  }
  realtimeJumpTipTimer = setTimeout(() => {
    realtimeJumpTip.value = ''
    realtimeJumpTipTimer = null
  }, 2000)
}

// WS 断开超时计时（超过 1 分钟显示错误）
let wsDisconnectedAt: number | null = null
const wsDisconnectedTooLong = ref(false)
let wsDisconnectCheckTimer: ReturnType<typeof setInterval> | null = null
function startWsDisconnectCheck(): void {
  stopWsDisconnectCheck()
  wsDisconnectCheckTimer = setInterval(() => {
    if (wsState.value === 'disconnected') {
      if (wsDisconnectedAt == null) wsDisconnectedAt = Date.now()
      else if (Date.now() - wsDisconnectedAt >= 60_000) wsDisconnectedTooLong.value = true
    } else {
      wsDisconnectedAt = null
      wsDisconnectedTooLong.value = false
    }
  }, 2000)
}
function stopWsDisconnectCheck(): void {
  if (wsDisconnectCheckTimer) {
    clearInterval(wsDisconnectCheckTimer)
    wsDisconnectCheckTimer = null
  }
}

function setupRealtimeWS(): void {
  closeRealtimeWS()
  wsReturn = useReplayWS(uuid.value, authStore.token ?? '', {
    appendPoint: (p) => {
      points.value = [...points.value, p]
      // 同步 session
      if (session.value) session.value.points = points.value
    },
    appendViolation: (v) => {
      violations.value = [...violations.value, v]
      if (session.value) session.value.violations = violations.value
    }
  })
  // 绑定 refs 同步
  watch(wsReturn.wsState, (v) => { wsState.value = v })
  watch(wsReturn.latencyMs, (v) => { latencyMs.value = v })
  watch(wsReturn.hudFrame, (v) => { hudFrame.value = v })
  // ★ 对齐后的帧：HUD 叠层渲染只用它，不用原始帧
  watch(wsReturn.alignedHudFrame, (v) => { alignedHudFrame.value = v }, { immediate: true })
  watch(wsReturn.videoLatencyMs, (v) => { videoLatencyMs.value = v })
  watch(wsReturn.clockOffsetMs, (v) => { clockOffsetMs.value = v })
  // 降级等级变化必须可见（§7 禁止静默降级）
  watch(wsReturn.degradation, (v) => { degradation.value = v }, { immediate: true, deep: true })
  // hls.js 测到延迟后回灌给 AlignmentClock
  latencyReporter = wsReturn.reportVideoLatency
  watch(wsReturn.hlsUrl, async (v) => {
    hlsUrl.value = v
    if (v) {
      await nextTick()
      setupHls(v)
    }
  })
  watch(wsReturn.observerMsg, (v) => { observerMsg.value = v })
  watch(wsReturn.traceDurationMs, (v) => { traceDurationMs.value = v })
  watch(wsReturn.startTime, (v) => {
    startTime.value = v
  })
  watch(wsReturn.playerName, (v) => {
    playerName.value = v
    if (v && session.value) session.value.playerName = v
    else if (v && !session.value) {
      // 构造一个空 session 以显示标题
      session.value = {
        playerUuid: uuid.value,
        playerName: v,
        startTime: startTime.value ?? Date.now(),
        endTime: Date.now(),
        points: [],
        violations: []
      }
    }
  })
  startWsDisconnectCheck()
  wsReturn.connect()
}

function closeRealtimeWS(): void {
  stopWsDisconnectCheck()
  wsDisconnectedAt = null
  wsDisconnectedTooLong.value = false
  stopLatencyPoller()
  latencyReporter = null
  alignedHudFrame.value = null
  degradation.value = { code: 'L0', level: 'L0', label: '正常', evidenceValue: '完整' }
  if (wsReturn) {
    wsReturn.close()
    wsReturn = null
  }
  if (hlsInstance.value) {
    try { hlsInstance.value.destroy() } catch { /* noop */ }
    hlsInstance.value = null
  }
  // 清空实时 refs
  wsState.value = 'disconnected'
  latencyMs.value = 0
  hudFrame.value = null
  hlsUrl.value = null
  observerMsg.value = null
  traceDurationMs.value = 0
  startTime.value = null
  playerName.value = null
}

// ==================== 播放状态 ====================
const playing = ref(false)
const speed = ref(1)
const currentMs = ref(0)
const perspective = ref<'first' | 'third' | 'free'>('third')
const layers = ref<ReplayLayers>({
  trail: true, hitbox: true, violation: true, grid: true, terrain: true
})

// 进度条拖拽
const progressBarRef = ref<HTMLDivElement | null>(null)
const dragging = ref(false)

// 播放循环
let rafId = 0
let lastTs = 0

// ==================== 选项 ====================
const perspectives = [
  { id: 'first' as const, label: '第一人称', icon: User },
  { id: 'third' as const, label: '第三人称', icon: Move3D },
  { id: 'free' as const, label: '自由视角', icon: Orbit }
]
const speedOptions = [0.5, 1, 2, 4, 8]

// ==================== 派生 ====================
const totalMs = computed(() => {
  const maxFromPoints = points.value.length ? points.value[points.value.length - 1].t : 0
  const maxFromViolations = violations.value.reduce((m, v) => Math.max(m, v.t), 0)
  const maxFromTrace = traceDurationMs.value
  const base = Math.max(maxFromPoints, maxFromViolations, maxFromTrace)
  // 实时模式 + 有 startTime → 增加 wallClock 已流逝时间作为保底
  if (isRealtime.value && startTime.value != null) {
    const wallElapsed = Date.now() - startTime.value
    return Math.max(0, Math.max(base, wallElapsed))
  }
  return Math.max(0, base)
})

const hasPoints = computed(() => points.value.length > 0 || hasVideoArchive.value)

/** 当前帧（二分查找 t <= currentMs 的最近点） */
const currentPoint = computed<TracePoint | null>(() => {
  const pts = points.value
  if (!pts.length) return null
  const ms = currentMs.value
  let lo = 0
  let hi = pts.length - 1
  let ans = 0
  while (lo <= hi) {
    const mid = (lo + hi) >>> 1
    if (pts[mid].t <= ms) { ans = mid; lo = mid + 1 }
    else { hi = mid - 1 }
  }
  return pts[ans]
})

const currentPercent = computed(() => {
  if (totalMs.value <= 0) return 0
  return Math.min(100, Math.max(0, (currentMs.value / totalMs.value) * 100))
})

/** 违规时刻在进度条上的位置列表（多个） */
const violationPercentList = computed(() => {
  if (totalMs.value <= 0) return []
  return violations.value.map(v => ({
    t: v.t,
    percent: Math.min(100, Math.max(0, (v.t / totalMs.value) * 100)),
    type: v.type,
    level: v.level
  }))
})

/** 当前违规跳转高亮索引 */
const activeViolationIdx = computed(() => {
  const vios = violations.value
  if (!vios.length) return -1
  let idx = -1
  for (let i = 0; i < vios.length; i++) {
    if (vios[i].t <= currentMs.value) idx = i
    else break
  }
  return idx
})

const currentText = computed(() => formatMs(currentMs.value))
const durationText = computed(() => formatMs(totalMs.value))

// ==================== 工具 ====================
function formatMs(ms: number): string {
  const v = Math.max(0, Math.floor(ms))
  const m = Math.floor(v / 60000)
  const s = Math.floor((v % 60000) / 1000)
  return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`
}

function levelTagClass(level: string | null): string {
  switch ((level ?? '').toUpperCase()) {
    case 'CRITICAL': return 'tag tag-red'
    case 'HIGH': return 'tag tag-orange'
    case 'MEDIUM': return 'tag tag-yellow'
    case 'LOW': return 'tag tag-blue'
    default: return 'tag tag-blue'
  }
}

function seekToMs(ms: number): void {
  currentMs.value = Math.max(0, Math.min(totalMs.value, ms))
  // 真实画面归档模式：同步视频播放器
  if (!isRealtime.value && hasVideoArchive.value && videoArchiveRef.value) {
    const v = videoArchiveRef.value
    const targetSec = currentMs.value / 1000
    if (Number.isFinite(targetSec) && Math.abs(v.currentTime - targetSec) > 0.05) {
      try { v.currentTime = targetSec } catch { /* noop */ }
    }
  }
}
function seekBySeconds(sec: number): void { seekToMs(currentMs.value + sec * 1000) }
function togglePlay(): void {
  if (!hasPoints.value || totalMs.value <= 0) return
  if (!playing.value && currentMs.value >= totalMs.value) currentMs.value = 0
  playing.value = !playing.value
  // 视频归档：真实触发 <video> 的 play/pause
  if (!isRealtime.value && hasVideoArchive.value && videoArchiveRef.value) {
    const v = videoArchiveRef.value
    if (playing.value) { void v.play().catch(() => {}) }
    else { v.pause() }
  }
}
function jumpToViolation(t: number): void {
  if (isRealtime.value) {
    // 实时模式：只 seek 进度条（HLS 直播不支持回跳）
    seekToMs(t)
    showRealtimeJumpTip('实时流不支持回跳，已定位至历史 HUD 快照')
  } else {
    seekToMs(t)
    // 真实画面归档：直接跳到该秒，并确保暂停以便用户观察
    if (hasVideoArchive.value && videoArchiveRef.value) {
      const v = videoArchiveRef.value
      const targetSec = t / 1000
      if (Number.isFinite(targetSec)) {
        try {
          v.currentTime = targetSec
        } catch { /* noop */ }
      }
    }
  }
}
function setSpeed(s: number): void { speed.value = s }
function exit(): void { router.push({ name: 'Replay' }) }

// ==================== 播放循环 ====================
function startLoop(): void {
  if (rafId) return
  lastTs = performance.now()
  const tick = (now: number): void => {
    rafId = requestAnimationFrame(tick)
    const dt = Math.min(now - lastTs, 100)
    lastTs = now
    if (!playing.value) return
    // 视频归档模式：currentMs 由 <video> 的 timeupdate 事件驱动，raf 只做 HUD 插值等副作用
    if (!isRealtime.value && hasVideoArchive.value) return
    currentMs.value += dt * speed.value
    if (currentMs.value >= totalMs.value) {
      // 实时模式：允许自动跟随增量继续（不暂停）
      if (isRealtime.value) {
        currentMs.value = totalMs.value
      } else {
        currentMs.value = totalMs.value
        playing.value = false
        stopLoop()
      }
    }
  }
  rafId = requestAnimationFrame(tick)
}
function stopLoop(): void {
  if (rafId) { cancelAnimationFrame(rafId); rafId = 0 }
}
watch(playing, (p) => {
  if (p) startLoop(); else stopLoop()
})

// 视频归档：把 speed.value 映射到 video.playbackRate（1/2/4/8x）
watch(speed, (s) => {
  if (!isRealtime.value && hasVideoArchive.value && videoArchiveRef.value) {
    try { videoArchiveRef.value.playbackRate = s } catch { /* noop */ }
  }
})

// 视频归档：监听 videoArchiveUrl → 绑定/解绑 <video> 事件
watch([videoArchiveUrl, hasVideoArchive], async ([url, hv]) => {
  // 先解绑旧的
  if (videoArchiveRef.value && videoTimeupdateHandler) {
    videoArchiveRef.value.removeEventListener('timeupdate', videoTimeupdateHandler)
    videoTimeupdateHandler = null
  }
  if (!url || !hv) return
  // nextTick 确保 ref 绑定完成
  await nextTick()
  const v = videoArchiveRef.value
  if (!v) return
  // 初始化播放速率
  try { v.playbackRate = speed.value } catch { /* noop */ }
  // 绑定 timeupdate：把 video.currentTime 同步成 currentMs (毫秒)
  videoTimeupdateHandler = () => {
    const ctMs = Math.round(v.currentTime * 1000)
    if (Math.abs(ctMs - currentMs.value) >= 30) { // 节流：差异 >30ms 才赋值，避免频繁 trigger
      currentMs.value = ctMs
    }
    // 播放结束同步
    if (v.ended && playing.value) {
      playing.value = false
      stopLoop()
    }
  }
  v.addEventListener('timeupdate', videoTimeupdateHandler)
  // 同步 pause/play 状态
  v.addEventListener('pause', () => { if (playing.value) playing.value = false })
  v.addEventListener('play', () => { if (!playing.value) playing.value = true })
  v.addEventListener('ended', () => { playing.value = false; stopLoop() })
})

// ==================== 进度条拖拽 ====================
function seekFromClientX(clientX: number): void {
  const bar = progressBarRef.value
  if (!bar) return
  const rect = bar.getBoundingClientRect()
  const ratio = Math.max(0, Math.min(1, (clientX - rect.left) / Math.max(1, rect.width)))
  seekToMs(ratio * totalMs.value)
}
function onProgressPointerDown(e: PointerEvent): void {
  if (!hasPoints.value || totalMs.value <= 0) return
  dragging.value = true
  playing.value = false
  progressBarRef.value?.setPointerCapture(e.pointerId)
  seekFromClientX(e.clientX)
}
function onProgressPointerMove(e: PointerEvent): void {
  if (!dragging.value) return
  seekFromClientX(e.clientX)
}
function onProgressPointerUp(e: PointerEvent): void {
  if (!dragging.value) return
  dragging.value = false
  progressBarRef.value?.releasePointerCapture(e.pointerId)
}

// ==================== 数据加载 ====================
async function loadRealtime(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    // 清空历史数据
    session.value = null
    points.value = []
    violations.value = []
    currentMs.value = 0
    // 初始化 WS，连接建立后会有 init 消息推送 meta 数据
    setupRealtimeWS()
    // 小幅等待首个消息到达
    await new Promise<void>((resolve) => {
      let resolved = false
      const done = () => { if (!resolved) { resolved = true; resolve() } }
      // 最多等 2 秒，超时也显示（HUD 稍后会到）
      setTimeout(done, 2000)
      // 任一数据到达即 resolve
      const unw = watch(
        () => [hudFrame.value, points.value.length, session.value],
        () => {
          if (hudFrame.value || points.value.length > 0 || session.value) {
            unw()
            done()
          }
        }
      )
    })
    loading.value = false
  } catch (e) {
    const msg = e instanceof Error ? e.message : '未知错误'
    loadError.value = `连接实时流失败：${msg}`
    loading.value = false
  }
}

async function loadArchive(): Promise<void> {
  loading.value = true
  loadError.value = ''
  // 重置视频归档状态
  hasVideoArchive.value = false
  archiveHudSamples.value = []
  archiveTelemetryFrames.value = []
  archiveCameraSegments.value = []
  archiveShowsHeldItem.value = null
  archiveCoverage.value = null
  if (videoArchiveUrl.value) {
    try { URL.revokeObjectURL(videoArchiveUrl.value) } catch { /* noop */ }
    videoArchiveUrl.value = null
  }
  try {
    const blob = await downloadReplayArchiveBlob(archiveName.value)
    const zip = await JSZip.loadAsync(blob)

    // ========== A) 新格式：ZIP 内含 video.mp4 → 真实画面回放 ==========
    if (zip.files['video.mp4']) {
      // 读取 metadata.json
      const metaEntry = zip.file('metadata.json')
      let meta: any = null
      if (metaEntry) {
        try {
          const metaStr = await metaEntry.async('string')
          meta = JSON.parse(metaStr)
        } catch (e) {
          const msg = e instanceof Error ? e.message : 'metadata.json 解析失败'
          loadError.value = `加载归档元数据失败：${msg}`
          loading.value = false
          return
        }
      }
      // 读取 video.mp4 为 blob 并创建本地 URL
      const videoBlob = await zip.files['video.mp4'].async('blob')
      const videoUrl = URL.createObjectURL(videoBlob)
      videoArchiveUrl.value = videoUrl
      hasVideoArchive.value = true

      // HUD 采样（5Hz）：供归档模式 HUD overlay 与视频 currentTime 同步
      const hudRaw: unknown = meta?.hudSampled5Hz
      if (Array.isArray(hudRaw)) {
        archiveHudSamples.value = (hudRaw as ArchiveHudSample[])
          .filter(s => s && typeof s.t === 'number' && Number.isFinite(s.t))
          .sort((a, b) => a.t - b.t)
      }

      // ========== v3：telemetry.jsonl（20Hz 全量遥测，含准星/坐标/36 格背包） ==========
      const teleEntry = zip.file('telemetry.jsonl')
      if (teleEntry) {
        try {
          const teleStr = await teleEntry.async('string')
          const frames: HudFrame[] = []
          for (const line of teleStr.split('\n')) {
            const l = line.trim()
            if (!l) continue
            try {
              const f = JSON.parse(l) as HudFrame
              if (f && typeof f.t === 'number' && Number.isFinite(f.t)) frames.push(f)
            } catch { /* 跳过坏行 */ }
          }
          frames.sort((a, b) => (a.t ?? 0) - (b.t ?? 0))
          archiveTelemetryFrames.value = frames
        } catch {
          // telemetry.jsonl 损坏不阻断回放：静默降级到 hudSampled5Hz
        }
      }

      // ========== v3：机位时间段 / 取证充分性 / 覆盖情况（§4.5） ==========
      const videoMeta = meta?.video
      if (videoMeta && typeof videoMeta === 'object') {
        if (Array.isArray(videoMeta.cameraSegments)) {
          archiveCameraSegments.value = videoMeta.cameraSegments
            .filter((s: any) => s && typeof s.tMs === 'number')
            .map((s: any) => ({ tMs: Number(s.tMs), mode: String(s.mode ?? 'ATTACH') }))
        }
        if (typeof videoMeta.showsHeldItem === 'boolean') {
          archiveShowsHeldItem.value = videoMeta.showsHeldItem
        }
      }
      if (meta?.coverage && typeof meta.coverage === 'object') {
        archiveCoverage.value = {
          recorded: meta.coverage.recorded !== false,
          reason: typeof meta.coverage.reason === 'string' ? meta.coverage.reason : null
        }
      }

      // 从 metadata 派生 session / violations / totalMs
      const durationMs = meta?.durationMs ?? 0
      const playerUuid = meta?.playerUuid ?? ''
      const playerName = meta?.playerName ?? archiveName.value
      const startTime = meta?.startTimeMs ?? 0
      const endTime = meta?.endTimeMs ?? (startTime + durationMs)
      const metaViolations: any[] = meta?.violations ?? []

      // violations: 把 tMs 映射成 ViolationMarker（相对 startTime 的 t）
      const vios: ViolationMarker[] = metaViolations.map((v: any) => ({
        t: Number(v.tMs ?? 0),
        type: String(v.type ?? 'UNKNOWN'),
        level: String(v.level ?? '')
      }))
      violations.value = vios

      // session: points 留空（不做 Canvas 3D 轨迹渲染）
      session.value = {
        playerUuid,
        playerName,
        startTime,
        endTime,
        points: [],
        violations: vios
      }
      points.value = []

      // 注入 totalMs：把 meta.durationMs 作为归档时长
      // 通过覆盖一个 session-level 的字段不可行，改为单独 ref 或
      // 利用 traceDurationMs（非实时模式 totalMs 也会使用 traceDurationMs）
      traceDurationMs.value = durationMs
      startTime.value = startTime
      playerName.value = playerName
      currentMs.value = 0

      loading.value = false
      return
    }

    // ========== B) 老格式：找 replay.json → Canvas 3D 轨迹回放 ==========
    const replayJsonFile = zip.file('replay.json')
    if (!replayJsonFile) throw new Error('存档内未找到 replay.json（非回放 ZIP）')
    const jsonText = await replayJsonFile.async('string')
    const sess: ReplaySession = JSON.parse(jsonText)
    session.value = sess
    points.value = sess.points
    violations.value = sess.violations
    currentMs.value = 0
    loading.value = false
  } catch (e) {
    const msg = e instanceof Error ? e.message : '未知错误'
    loadError.value = `加载历史存档失败：${msg}`
    loading.value = false
  }
}

// ==================== 路由 query 变化 → 重新加载 ====================
watch(
  () => [route.query.uuid, route.query.archive],
  () => {
    closeRealtimeWS()
    stopLoop()
    playing.value = false
    currentMs.value = 0
    session.value = null
    points.value = []
    violations.value = []
    archiveHudSamples.value = []
    if (isRealtime.value) void loadRealtime()
    else if (archiveName.value) void loadArchive()
    else loadError.value = '缺少 uuid 或 archive 参数'
  }
)

// ==================== Canvas 事件 ====================
function onCanvasReady(): void { canvasError.value = false }
function onCanvasErr(): void { canvasError.value = true }

// ==================== 标题派生 ====================
const titleText = computed(() => {
  if (playerName.value) return `${playerName.value} 的直播`
  if (session.value) return `${session.value.playerName} 的轨迹`
  if (isRealtime.value) return '实时直播回放'
  if (archiveName.value) return archiveName.value
  return '回放播放'
})
const subText = computed(() => {
  if (isRealtime.value) return '直播 · 真实画面 · 观察者模式'
  if (hasVideoArchive.value) return '历史存档 · 真实画面回放'
  if (archiveName.value) return '历史存档 · 3D 轨迹'
  return ''
})

// ==================== 实时模式加载中判断 ====================
const realtimeStillLoading = computed(() => {
  if (!isRealtime.value) return false
  if (!hlsUrl.value) return true
  if (hlsLoading.value) return true
  return false
})

// ==================== 生命周期 ====================
onMounted(() => {
  if (isRealtime.value) void loadRealtime()
  else if (archiveName.value) void loadArchive()
  else loadError.value = '缺少 uuid 或 archive 参数'
})

onBeforeUnmount(() => {
  stopLoop()
  closeRealtimeWS()
  if (realtimeJumpTipTimer) {
    clearTimeout(realtimeJumpTipTimer)
    realtimeJumpTipTimer = null
  }
  // 视频归档：解绑事件 + 释放 blob URL
  if (videoArchiveRef.value && videoTimeupdateHandler) {
    try { videoArchiveRef.value.removeEventListener('timeupdate', videoTimeupdateHandler) } catch { /* noop */ }
    videoTimeupdateHandler = null
  }
  if (videoArchiveUrl.value) {
    try { URL.revokeObjectURL(videoArchiveUrl.value) } catch { /* noop */ }
    videoArchiveUrl.value = null
  }
  hasVideoArchive.value = false
})
</script>

<template>
  <div class="replay-watch">

    <!-- ========== 顶部导航栏 ========== -->
    <div class="watch-header">
      <button class="btn btn-ghost btn-sm" @click="exit">
        <ArrowLeft :size="15" />
        返回列表
      </button>
      <div class="flex items-center gap-2 min-w-0">
        <span v-if="isRealtime" class="tag tag-cyan font-mono">
          <Radio :size="11" class="inline-block mr-1 animate-pulse" />
          直播 · 真实画面
        </span>
        <span v-else class="tag tag-blue font-mono">
          <ArchiveIcon :size="11" class="inline-block mr-1" />
          历史
        </span>
        <span class="title-text" :title="titleText">{{ titleText }}</span>
        <span v-if="subText" class="sub-text">{{ subText }}</span>
      </div>
      <button class="btn btn-ghost btn-sm btn-danger-ghost" @click="exit">
        <LogOut :size="15" />
        退出
      </button>
    </div>

    <!-- ========== 主体：Canvas/Video + 侧栏 ========== -->
    <div class="watch-body">

      <!-- 左侧主区 -->
      <div class="main-area">
        <!-- 舞台 -->
        <div class="replay-stage relative w-full" style="aspect-ratio: 16 / 9;">

          <!-- ============ 实时模式：HLS + HUD overlay ============ -->
          <template v-if="isRealtime">
            <!-- Video -->
            <video
              ref="videoRef"
              muted
              playsinline
              autoplay
              style="width:100%;height:100%;object-fit:contain;background:#000;display:block;"
            ></video>

            <!-- HUD overlay -->
            <HudOverlay :hud="alignedHudFrame" :show="true" />

            <!-- 降级状态条（§7）：任何降级都必须明示，禁止静默 -->
            <div
              v-if="degradation.code !== 'L0'"
              class="degradation-bar"
              :class="`deg-${degradation.code.toLowerCase()}`"
              :title="degradation.reason ? `reason=${degradation.reason}` : ''"
            >
              <AlertTriangle :size="13" class="shrink-0" />
              <span class="deg-level">{{ degradation.level }}</span>
              <span class="deg-label">{{ degradation.label }}</span>
              <span class="deg-evidence">取证价值 {{ degradation.evidenceValue }}</span>
              <span v-if="degradation.message" class="deg-msg">{{ degradation.message }}</span>
            </div>

            <!-- 对齐时钟诊断：视频延迟 τ 与服务端时钟偏移 -->
            <div v-if="!loading && !realtimeStillLoading" class="align-clock-readout">
              <span>τ {{ (videoLatencyMs / 1000).toFixed(1) }}s</span>
              <span>Δ {{ clockOffsetMs >= 0 ? '+' : '' }}{{ clockOffsetMs }}ms</span>
            </div>

            <!-- Observer banner (top) -->
            <div
              v-if="observerMsg"
              class="observer-banner"
              :class="observerMsg.level"
            >
              <AlertTriangle v-if="observerMsg.level !== 'info'" :size="14" class="mr-1.5 inline-block" />
              <Check v-else :size="14" class="mr-1.5 inline-block" />
              {{ observerMsg.text }}
            </div>

            <!-- 实时模式跳转提示 -->
            <div v-if="realtimeJumpTip" class="realtime-jump-tip">
              {{ realtimeJumpTip }}
            </div>

            <!-- Loading overlay -->
            <div v-if="loading || realtimeStillLoading" class="absolute inset-0 flex flex-col items-center justify-center bg-black/70 z-20 pointer-events-none">
              <Loader2 :size="36" class="animate-spin mb-3" style="color: var(--accent-cyan);" />
              <div class="text-caption text-text-secondary">
                {{ loading ? '正在连接观察者…' : (hlsLoading ? '直播流初始化中…' : '等待画面就绪…') }}
              </div>
            </div>

            <!-- Error overlay -->
            <div v-else-if="hlsError || wsDisconnectedTooLong || loadError" class="absolute inset-0 flex flex-col items-center justify-center bg-black/70 z-20">
              <AlertTriangle :size="36" class="mb-3" style="color: var(--danger-red);" />
              <div class="text-sm text-text-secondary text-center max-w-md px-4 space-y-1">
                <div v-if="loadError">{{ loadError }}</div>
                <div v-else-if="wsDisconnectedTooLong">WebSocket 已断开超过 1 分钟，请检查网络或刷新重试</div>
                <div v-else-if="hlsError">{{ hlsError }}</div>
              </div>
            </div>

            <!-- 空数据（WS 未到数据，但没 error） -->
            <div v-else-if="!hasPoints && !hudFrame" class="absolute inset-0 flex flex-col items-center justify-center bg-black/40 z-10 pointer-events-none">
              <Crosshair :size="42" class="mx-auto mb-3 opacity-50" />
              <div class="text-sm text-text-muted">等待直播数据…</div>
            </div>

            <!-- 右上角状态标签（实时模式） -->
            <div v-if="!loading && !realtimeStillLoading" class="absolute top-3 right-3 flex flex-col items-end gap-1.5 pointer-events-none z-20">
              <span v-if="hlsError" class="tag tag-red">HLS 异常</span>
              <span v-else-if="wsState === 'reconnecting'" class="tag tag-yellow">WS 重连中…</span>
              <span v-else-if="wsState === 'disconnected'" class="tag tag-red">WS 断开</span>
              <span v-else-if="wsState === 'connected'" class="tag tag-green">WS {{ latencyMs }}ms</span>
              <span class="tag tag-cyan font-mono">×{{ speed }}</span>
              <span class="tag tag-blue font-mono">{{ currentText }} / {{ durationText }}</span>
            </div>
          </template>

          <!-- ============ 历史模式：真实画面回放（video archive） ============ -->
          <template v-else-if="hasVideoArchive">
            <!-- 真实画面 MP4 -->
            <video
              ref="videoArchiveRef"
              :src="videoArchiveUrl ?? undefined"
              controls
              playsinline
              preload="metadata"
              style="width:100%;height:100%;background:#000;object-fit:contain;display:block;"
            ></video>

            <!-- HUD overlay：按 telemetry.jsonl / metadata.hudSampled5Hz 与 video.currentTime 同步（二分查找最近帧） -->
            <HudOverlay :hud="archiveHudFrame" :show="true" />

            <!-- 取证充分性标注（§8.4 红线 4）：当前段画面是否录到手持物品 -->
            <div v-if="archiveCameraMode" class="archive-camera-badge" :class="{ attach: archiveCameraMode === 'ATTACH' }">
              <Video :size="12" />
              <span v-if="archiveCameraMode === 'ATTACH'">ATTACH · 本段画面不含手持物品</span>
              <span v-else>SHOULDER · 本段画面含手持物品</span>
            </div>

            <!-- Loading overlay -->
            <div v-if="loading" class="absolute inset-0 flex flex-col items-center justify-center bg-black/70 z-20 pointer-events-none">
              <Loader2 :size="36" class="animate-spin mb-3" style="color: var(--accent-cyan);" />
              <div class="text-caption text-text-secondary">正在解压真实画面归档…</div>
            </div>

            <!-- Error overlay -->
            <div v-else-if="loadError" class="absolute inset-0 flex flex-col items-center justify-center bg-black/70 z-20">
              <AlertTriangle :size="36" class="mb-3" style="color: var(--danger-red);" />
              <div class="text-sm text-text-secondary text-center max-w-md px-4">
                {{ loadError }}
              </div>
            </div>

            <!-- 右上角状态（视频归档） -->
            <div v-if="!loading" class="absolute top-3 right-3 flex flex-col items-end gap-1.5 pointer-events-none z-20">
              <span class="tag tag-green">真实画面</span>
              <span v-if="archiveShowsHeldItem === true" class="tag tag-blue" title="本归档至少有一段录到手持物品">含手持物品</span>
              <span v-else-if="archiveShowsHeldItem === false" class="tag tag-orange" title="整段归档均未录到手持物品，该维度证据不充分">无手持物品记录</span>
              <span v-if="archiveCoverage && !archiveCoverage.recorded" class="tag tag-red" :title="archiveCoverage.reason ?? ''">未录到视频</span>
              <span class="tag tag-cyan font-mono">×{{ speed }}</span>
              <span class="tag tag-blue font-mono">{{ currentText }} / {{ durationText }}</span>
            </div>
          </template>

          <!-- ============ 历史模式：Three.js（旧 Canvas 3D 轨迹） ============ -->
          <template v-else>
            <!-- Three.js -->
            <ReplayCanvas
              v-if="hasPoints"
              :points="points"
              :current-ms="currentMs"
              :violations="violations"
              :perspective="perspective"
              :layers="layers"
              :player-name="session?.playerName ?? ''"
              @ready="onCanvasReady"
              @error="onCanvasErr"
            />

            <!-- 加载中 / 错误 / 空 -->
            <div v-else class="absolute inset-0 flex items-center justify-center bg-bg-base/60">
              <Loader2 v-if="loading" :size="34" class="animate-spin" style="color: var(--accent-cyan);" />
              <div v-else-if="loadError" class="text-center">
                <AlertTriangle :size="34" class="mx-auto mb-2" style="color: var(--danger-red);" />
                <div class="text-sm text-text-secondary">{{ loadError }}</div>
              </div>
              <div v-else class="text-center text-text-muted">
                <Crosshair :size="42" class="mx-auto mb-3 opacity-50" />
                <div class="text-sm">暂无轨迹数据</div>
              </div>
            </div>

            <!-- 右上角状态 -->
            <div v-if="hasPoints" class="absolute top-3 right-3 flex flex-col items-end gap-1.5 pointer-events-none">
              <span v-if="canvasError" class="tag tag-red">3D 渲染失败</span>
              <span class="tag tag-cyan font-mono">×{{ speed }}</span>
              <span class="tag tag-blue font-mono">{{ currentPoint ? `${currentPoint.t}ms` : '--' }}</span>
            </div>

            <!-- 右下 HUD：主手物品（历史模式） -->
            <div v-if="currentPoint?.mainHandItemId" class="hud-item">
              <div class="hud-item-icon" style="background: rgba(0,229,255,0.12);">
                <Box :size="16" style="color: var(--accent-cyan);" />
              </div>
              <div class="hud-item-info">
                <div class="hud-item-label">主手物品</div>
                <div class="hud-item-name font-mono">{{ currentPoint.mainHandItemId }}</div>
              </div>
            </div>
          </template>

        </div>

        <!-- ========== 底部播放控件 ========== -->
        <div class="replay-controls">
          <!-- 进度条 -->
          <div
            ref="progressBarRef"
            class="progress-track"
            :class="{ disabled: !hasPoints || totalMs <= 0 }"
            @pointerdown="onProgressPointerDown"
            @pointermove="onProgressPointerMove"
            @pointerup="onProgressPointerUp"
            @pointercancel="onProgressPointerUp"
          >
            <div class="progress-rail">
              <!-- 已播放 -->
              <div class="progress-fill" :style="{ width: currentPercent + '%' }"></div>
              <!-- 违规红标（绝对定位，可点击跳转） -->
              <div
                v-for="v in violationPercentList"
                :key="v.t"
                class="violation-marker"
                :style="{ left: `calc(${v.percent}% - 4px)` }"
                :title="`${v.type} · ${v.level}`"
                @click.stop="jumpToViolation(v.t)"
              ></div>
              <!-- 播放头 -->
              <div class="progress-thumb" :style="{ left: `calc(${currentPercent}% - 6px)` }"></div>
            </div>
          </div>

          <!-- 按钮行 -->
          <div class="flex flex-wrap items-center gap-2">
            <button
              class="btn btn-primary btn-icon !w-10 !h-10"
              :disabled="!hasPoints || totalMs <= 0"
              :title="playing ? '暂停' : '播放'"
              @click="togglePlay"
            >
              <component :is="playing ? Pause : Play" :size="17" />
            </button>
            <button class="btn btn-ghost btn-icon" :disabled="!hasPoints" title="后退 1 秒" @click="seekBySeconds(-1)">
              <SkipBack :size="16" />
            </button>
            <button class="btn btn-ghost btn-icon" :disabled="!hasPoints" title="前进 1 秒" @click="seekBySeconds(1)">
              <SkipForward :size="16" />
            </button>

            <span class="font-mono text-caption text-text-secondary ml-1 tabular-nums">
              {{ currentText }} / {{ durationText }}
            </span>

            <div class="ml-auto flex items-center gap-2">
              <div class="speed-group">
                <button
                  v-for="s in speedOptions"
                  :key="s"
                  class="speed-btn"
                  :class="{ active: speed === s }"
                  @click="setSpeed(s)"
                >{{ s }}×</button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 右侧栏 -->
      <div class="side-panel space-y-4">

        <!-- 违规列表（通用） -->
        <div class="card">
          <div class="card-header">
            <h3 class="flex items-center gap-2">
              <Flag :size="16" style="color: var(--danger-red);" />
              违规标记
            </h3>
            <span class="text-caption text-text-muted font-mono">{{ violations.length }}</span>
          </div>
          <div class="card-body">
            <div v-if="!violations.length" class="py-4 text-center text-text-muted text-sm">
              <Check :size="20" class="mx-auto mb-1 opacity-40" />
              暂无违规记录
            </div>
            <div v-else class="violation-list">
              <div
                v-for="(v, idx) in violations"
                :key="idx"
                class="violation-item"
                :class="{ active: idx === activeViolationIdx }"
                @click="jumpToViolation(v.t)"
              >
                <div class="violation-time font-mono">{{ formatMs(v.t) }}</div>
                <div class="violation-type">{{ v.type }}</div>
                <span :class="levelTagClass(v.level)">{{ v.level }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 视角切换（仅旧 Canvas 3D 轨迹历史模式） -->
        <div v-if="!isRealtime && !hasVideoArchive" class="card">
          <div class="card-header">
            <h3 class="flex items-center gap-2">
              <Move3D :size="16" style="color: var(--accent-blue);" />
              视角
            </h3>
          </div>
          <div class="card-body">
            <div class="grid grid-cols-3 gap-2">
              <button
                v-for="p in perspectives"
                :key="p.id"
                class="persp-btn"
                :class="{ active: perspective === p.id }"
                @click="perspective = p.id"
              >
                <component :is="p.icon" :size="15" />
                <span>{{ p.label }}</span>
              </button>
            </div>
            <div v-if="perspective === 'free'" class="text-caption text-text-muted mt-2">
              鼠标左键旋转 / 滚轮缩放 / 右键平移
            </div>
          </div>
        </div>

        <!-- 图层开关（仅旧 Canvas 3D 轨迹历史模式） -->
        <div v-if="!isRealtime && !hasVideoArchive" class="card">
          <div class="card-header">
            <h3 class="flex items-center gap-2">
              <Layers :size="16" style="color: var(--accent-purple);" />
              图层
            </h3>
          </div>
          <div class="card-body space-y-1">
            <label class="layer-row">
              <span class="text-sm text-text-primary">运动轨迹</span>
              <input type="checkbox" class="layer-check trail" v-model="layers.trail" />
            </label>
            <label class="layer-row">
              <span class="text-sm text-text-primary">命中盒</span>
              <input type="checkbox" class="layer-check hitbox" v-model="layers.hitbox" />
            </label>
            <label class="layer-row">
              <span class="text-sm text-text-primary">违规标记</span>
              <input type="checkbox" class="layer-check violation" v-model="layers.violation" />
            </label>
            <label class="layer-row">
              <span class="text-sm text-text-primary">世界栅格</span>
              <input type="checkbox" class="layer-check grid" v-model="layers.grid" />
            </label>
            <label class="layer-row">
              <span class="text-sm text-text-primary">地形高度图</span>
              <input type="checkbox" class="layer-check terrain" v-model="layers.terrain" />
            </label>
          </div>
        </div>

        <!-- 当前帧 / HUD 快照 -->
        <div class="card">
          <div class="card-header">
            <h3 class="flex items-center gap-2">
              <Crosshair :size="16" style="color: var(--accent-cyan);" />
              {{ (isRealtime || hasVideoArchive) ? 'HUD 快照' : '当前帧' }}
            </h3>
          </div>
          <div class="card-body">
            <!-- 实时模式 / 视频归档模式：显示 HUD 快照 -->
            <template v-if="isRealtime || hasVideoArchive">
              <div v-if="!displayHudFrame" class="py-3 text-center text-text-muted text-sm">
                {{ isRealtime ? '尚未接收 HUD 数据' : '该存档无 HUD 采样数据' }}
              </div>
              <div v-else class="font-mono text-caption leading-relaxed space-y-1">
                <div class="frame-row">
                  <Heart :size="11" class="inline-block mr-1.5" style="color:#e74c3c;" />
                  生命
                  <span class="text-text-primary ml-1 tabular-nums">{{ displayHudFrame.health }}/{{ displayHudFrame.maxHealth }}</span>
                  <span class="hud-bar ml-2">
                    <span class="bar-fill bar-red" :style="{ width: (displayHudFrame.health / Math.max(1, displayHudFrame.maxHealth || 20) * 100) + '%' }"></span>
                  </span>
                </div>
                <div class="frame-row">
                  <UtensilsCrossed :size="11" class="inline-block mr-1.5" style="color:#b45309;" />
                  饥饿
                  <span class="text-text-primary ml-1 tabular-nums">{{ displayHudFrame.hunger }}/20</span>
                  <span class="hud-bar ml-2">
                    <span class="bar-fill bar-orange" :style="{ width: (displayHudFrame.hunger / 20 * 100) + '%' }"></span>
                  </span>
                </div>
                <div class="frame-row">
                  <Shield :size="11" class="inline-block mr-1.5" style="color:#94a3b8;" />
                  护甲
                  <span class="text-text-primary ml-1 tabular-nums">{{ displayHudFrame.armor }}/20</span>
                  <span class="hud-bar ml-2">
                    <span class="bar-fill bar-slate" :style="{ width: (displayHudFrame.armor / 20 * 100) + '%' }"></span>
                  </span>
                </div>
                <div class="frame-row">
                  <Sparkles :size="11" class="inline-block mr-1.5" style="color:#22d3ee;" />
                  经验
                  <span class="text-text-primary ml-1 tabular-nums">Lv {{ displayHudFrame.level }}</span>
                  <span class="hud-bar ml-2">
                    <span class="bar-fill bar-xp" :style="{ width: (displayHudFrame.xp * 100) + '%' }"></span>
                  </span>
                </div>
                <div class="frame-row">
                  💧 氧气
                  <span class="text-text-primary ml-1 tabular-nums">{{ displayHudFrame.air == null ? '未在水中' : `${displayHudFrame.air}/300` }}</span>
                </div>
                <div class="frame-row">
                  ⌨ 快捷栏槽位
                  <span class="text-text-primary ml-1 tabular-nums">第 {{ displayHudFrame.hotbarSlot + 1 }} 格</span>
                </div>
                <div v-if="!isRealtime && displayHudFrame.hotbar" class="frame-row">
                  🎒 手持
                  <span class="text-text-primary ml-1 font-mono">
                    {{ displayHudFrame.hotbar[displayHudFrame.hotbarSlot]?.id?.replace(/^minecraft:/, '') ?? '空手' }}
                  </span>
                </div>
              </div>
            </template>

            <!-- 历史模式：显示 TracePoint 信息 -->
            <template v-else>
              <div v-if="!currentPoint" class="py-3 text-center text-text-muted text-sm">未播放</div>
              <div v-else class="font-mono text-caption leading-relaxed">
                <div class="frame-row"><Box :size="11" class="inline-block mr-1.5 opacity-60" />XYZ
                  <span class="text-text-primary ml-1">{{ currentPoint.x.toFixed(2) }} / {{ currentPoint.y.toFixed(2) }} / {{ currentPoint.z.toFixed(2) }}</span>
                </div>
                <div class="frame-row">Yaw/Pitch
                  <span class="text-text-primary ml-1">{{ currentPoint.yaw.toFixed(1) }}° / {{ currentPoint.pitch.toFixed(1) }}°</span>
                </div>
                <div class="frame-row">状态
                  <span class="text-text-primary ml-1">{{ currentPoint.onGround ? '着地' : '空中' }} · {{ currentPoint.gameMode }}</span>
                </div>
                <div class="frame-row">物品
                  <span class="text-text-primary ml-1">{{ currentPoint.mainHandItemId ?? '空手' }}</span>
                </div>
                <div class="frame-row">地形
                  <span class="text-text-primary ml-1">{{ currentPoint.blockHeights ? '7×7 已采集' : '无数据' }}</span>
                </div>
              </div>
            </template>
          </div>
        </div>

      </div>
    </div>
  </div>
</template>

<script lang="ts">
// 提前引入 Check 图标（lucide 中没有）
import { Check as CheckIcon } from 'lucide-vue-next'
export default {
  components: { CheckIcon }
}
</script>

<style scoped>
/* ==================== 整体布局 ==================== */
.replay-watch {
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
}

.watch-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  border: 1px solid var(--border-line);
  border-radius: var(--radius-card);
  background: var(--bg-card);
}
.title-text {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sub-text {
  font-size: 11px;
  color: var(--text-muted);
}
.watch-header > *:last-child { margin-left: auto; }

.watch-body {
  display: grid;
  grid-template-columns: 1fr 340px;
  gap: 12px;
  flex: 1;
  min-height: 0;
}

.main-area {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}

.side-panel {
  min-height: 0;
  overflow-y: auto;
  padding-right: 2px;
}

/* ==================== HUD 物品栏（历史模式） ==================== */
.hud-item {
  position: absolute;
  right: 14px;
  bottom: 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 6px;
  background: rgba(8, 14, 22, 0.72);
  border: 1px solid rgba(0, 229, 255, 0.35);
  backdrop-filter: blur(6px);
  font-size: 11px;
  pointer-events: none;
}
.hud-item-icon {
  width: 28px; height: 28px;
  display: flex; align-items: center; justify-content: center;
  border-radius: 4px;
}
.hud-item-label { color: var(--text-muted); font-size: 10px; }
.hud-item-name { color: var(--accent-cyan); font-size: 12px; }

/* ==================== 3D / Video 画布区 ==================== */
.replay-stage {
  background: #0a0e14;
  overflow: hidden;
  border-radius: var(--radius-card);
  border: 1px solid var(--border-line);
  position: relative;
}

/* ==================== Observer Banner (Top) ==================== */
.observer-banner {
  position: absolute;
  top: 10px;
  left: 50%;
  transform: translateX(-50%);
  padding: 6px 14px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 600;
  z-index: 30;
  backdrop-filter: blur(6px);
  box-shadow: 0 4px 14px rgba(0,0,0,0.4);
  pointer-events: none;
  border: 1px solid rgba(255,255,255,0.1);
}
.observer-banner.info {
  background: rgba(14, 165, 233, 0.85);
  color: #ecfeff;
}
.observer-banner.warn {
  background: rgba(234, 88, 12, 0.88);
  color: #fff7ed;
}
.observer-banner.error {
  background: rgba(220, 38, 38, 0.9);
  color: #fff;
}

/* ==================== 实时跳转 tip ==================== */
.realtime-jump-tip {
  position: absolute;
  bottom: 130px;
  left: 50%;
  transform: translateX(-50%);
  padding: 8px 18px;
  background: rgba(0, 0, 0, 0.8);
  color: #fde68a;
  border: 1px solid rgba(250, 204, 21, 0.5);
  border-radius: 6px;
  font-size: 12px;
  font-weight: 600;
  z-index: 30;
  pointer-events: none;
  animation: fadeInOut 2s ease-in-out;
}
@keyframes fadeInOut {
  0% { opacity: 0; transform: translate(-50%, 8px); }
  15% { opacity: 1; transform: translate(-50%, 0); }
  85% { opacity: 1; transform: translate(-50%, 0); }
  100% { opacity: 0; transform: translate(-50%, -4px); }
}

/* ==================== 归档机位标注（§8.4 取证充分性） ==================== */
.archive-camera-badge {
  position: absolute;
  top: 12px;
  left: 12px;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
  z-index: 15;
  pointer-events: none;
  background: rgba(34, 197, 94, 0.82);
  color: #052e16;
  backdrop-filter: blur(4px);
}
.archive-camera-badge.attach {
  background: rgba(234, 179, 8, 0.82);
  color: #422006;
}

/* ==================== 降级状态条（§7 禁止静默降级） ==================== */
.degradation-bar {
  position: absolute;
  top: 44px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  max-width: calc(100% - 24px);
  padding: 5px 12px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 600;
  z-index: 30;
  backdrop-filter: blur(6px);
  border: 1px solid rgba(255, 255, 255, 0.14);
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.4);
  pointer-events: none;
  flex-wrap: wrap;
}
.deg-l1  { background: rgba(234, 179, 8, 0.88);  color: #422006; }
.deg-l1e { background: rgba(34, 197, 94, 0.90);  color: #052e16; }
.deg-l15 { background: rgba(249, 115, 22, 0.90); color: #431407; }
.deg-l2  { background: rgba(220, 38, 38, 0.90);  color: #fff; }
.deg-l3  { background: rgba(168, 85, 247, 0.90); color: #fff; }
.deg-l4  { background: rgba(100, 116, 139, 0.90); color: #fff; }
.deg-l5  { background: rgba(127, 29, 29, 0.92);  color: #fff; }
.deg-level { font-family: var(--font-mono); opacity: 0.85; }
.deg-evidence {
  padding: 1px 6px;
  border-radius: 3px;
  background: rgba(0, 0, 0, 0.25);
  font-weight: 700;
}
.deg-msg { opacity: 0.9; font-weight: 500; }

/* ==================== 对齐时钟读数（τ / 时钟偏移） ==================== */
.align-clock-readout {
  position: absolute;
  bottom: 96px;
  left: 12px;
  display: flex;
  gap: 8px;
  font-family: var(--font-mono);
  font-size: 10px;
  color: #9fe1cb;
  background: rgba(0, 0, 0, 0.5);
  padding: 3px 8px;
  border-radius: 3px;
  pointer-events: none;
  z-index: 12;
  opacity: 0.75;
}

/* ==================== 侧栏内 mini 进度条 ==================== */
.hud-bar {
  display: inline-block;
  width: 60px;
  height: 6px;
  background: var(--bg-hover);
  border-radius: 3px;
  overflow: hidden;
  vertical-align: middle;
}
.bar-fill {
  display: block;
  height: 100%;
  border-radius: 3px;
  transition: width 0.1s linear;
}
.bar-red    { background: linear-gradient(90deg, #7f1d1d, #e74c3c); }
.bar-orange { background: linear-gradient(90deg, #78350f, #f59e0b); }
.bar-slate  { background: linear-gradient(90deg, #334155, #94a3b8); }
.bar-xp     { background: linear-gradient(90deg, #16a34a, #eab308, #22d3ee); }

/* ==================== tag 颜色扩展 ==================== */
.tag-green {
  background: rgba(34, 197, 94, 0.15);
  color: #22c55e;
  border-color: rgba(34, 197, 94, 0.4);
}
.tag-yellow {
  background: rgba(234, 179, 8, 0.15);
  color: #facc15;
  border-color: rgba(234, 179, 8, 0.45);
}

/* ==================== 播放控件 ==================== */
.replay-controls {
  border: 1px solid var(--border-line);
  border-radius: var(--radius-card);
  background: var(--bg-card);
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.progress-track {
  height: 22px;
  display: flex;
  align-items: center;
  cursor: pointer;
  touch-action: none;
  user-select: none;
}
.progress-track.disabled { cursor: default; opacity: 0.5; }
.progress-rail {
  position: relative;
  width: 100%;
  height: 6px;
  border-radius: 3px;
  background: var(--bg-hover);
}
.progress-fill {
  position: absolute;
  left: 0; top: 0; bottom: 0;
  border-radius: 3px;
  background: linear-gradient(90deg, var(--accent-blue), var(--accent-cyan));
  box-shadow: 0 0 8px rgba(0, 229, 255, 0.35);
  transition: width 0.05s linear;
}
.progress-thumb {
  position: absolute;
  top: 50%;
  width: 12px; height: 12px;
  border-radius: 50%;
  background: var(--accent-cyan);
  border: 2px solid #0a0e14;
  transform: translateY(-50%);
  box-shadow: 0 0 6px rgba(0, 229, 255, 0.6);
  transition: left 0.05s linear;
}
.progress-track:not(.disabled):hover .progress-thumb {
  transform: translateY(-50%) scale(1.2);
}
.violation-marker {
  position: absolute;
  top: 50%;
  width: 8px; height: 8px;
  border-radius: 50%;
  background: var(--danger-red);
  border: 2px solid #0a0e14;
  transform: translateY(-50%);
  box-shadow: 0 0 8px rgba(248, 81, 73, 0.7);
  cursor: pointer;
  z-index: 2;
}
.violation-marker:hover { transform: translateY(-50%) scale(1.3); }

.speed-group {
  display: flex;
  border: 1px solid var(--border-line);
  border-radius: var(--radius-btn);
  overflow: hidden;
}
.speed-btn {
  padding: 0 10px;
  height: 32px;
  font-size: 12px;
  color: var(--text-secondary);
  background: transparent;
  border: none;
  cursor: pointer;
  transition: all 0.15s;
  font-family: var(--font-mono);
}
.speed-btn + .speed-btn { border-left: 1px solid var(--border-line); }
.speed-btn:hover { background: var(--bg-hover); color: var(--text-primary); }
.speed-btn.active {
  background: rgba(56, 139, 253, 0.18);
  color: var(--accent-blue);
}

/* ==================== 违规列表 ==================== */
.violation-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 280px;
  overflow-y: auto;
}
.violation-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 6px;
  border: 1px solid var(--border-line);
  background: var(--bg-input);
  cursor: pointer;
  transition: all 0.12s;
  font-size: 12px;
}
.violation-item:hover {
  border-color: rgba(248, 81, 73, 0.6);
  background: rgba(248, 81, 73, 0.08);
}
.violation-item.active {
  border-color: var(--danger-red);
  background: rgba(248, 81, 73, 0.15);
  box-shadow: 0 0 0 1px rgba(248, 81, 73, 0.3);
}
.violation-time {
  color: var(--accent-cyan);
  flex-shrink: 0;
  min-width: 40px;
}
.violation-type {
  flex: 1;
  color: var(--text-primary);
  font-family: var(--font-mono);
  font-size: 11px;
}

/* ==================== 视角按钮 ==================== */
.persp-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 10px 4px;
  border: 1px solid var(--border-line);
  border-radius: var(--radius-btn);
  background: transparent;
  color: var(--text-secondary);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
}
.persp-btn:hover { background: var(--bg-hover); color: var(--text-primary); }
.persp-btn.active {
  background: rgba(56, 139, 253, 0.15);
  color: var(--accent-blue);
  border-color: rgba(56, 139, 253, 0.5);
  box-shadow: 0 0 0 1px rgba(56, 139, 253, 0.2);
}

/* ==================== 图层开关 ==================== */
.layer-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 7px 0;
  cursor: pointer;
}
.layer-check {
  appearance: none;
  width: 16px; height: 16px;
  border: 1px solid var(--border-line);
  border-radius: 4px;
  background: var(--bg-input);
  cursor: pointer;
  position: relative;
  transition: all 0.15s;
}
.layer-check:hover { border-color: var(--accent-cyan); }
.layer-check:checked { background: var(--accent-blue); border-color: var(--accent-blue); }
.layer-check:checked::after {
  content: '';
  position: absolute;
  left: 5px; top: 1.5px;
  width: 4px; height: 8px;
  border: solid #fff;
  border-width: 0 2px 2px 0;
  transform: rotate(45deg);
}
.layer-check.trail:checked { background: var(--accent-cyan); border-color: var(--accent-cyan); }
.layer-check.hitbox:checked { background: var(--danger-red); border-color: var(--danger-red); }
.layer-check.violation:checked { background: var(--danger-orange); border-color: var(--danger-orange); }
.layer-check.grid:checked { background: var(--accent-purple); border-color: var(--accent-purple); }
.layer-check.terrain:checked { background: #22c55e; border-color: #22c55e; }

.frame-row {
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--text-muted);
  padding: 3px 0;
  flex-wrap: wrap;
}

.btn-danger-ghost { color: var(--danger-red); border: 1px solid transparent; }
.btn-danger-ghost:hover:not(:disabled) {
  background: rgba(248, 81, 73, 0.1);
  border-color: rgba(248, 81, 73, 0.4);
}
</style>
