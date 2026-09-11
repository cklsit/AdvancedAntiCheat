import { ref, type Ref } from 'vue'
import { WSClient } from '@/utils/ws'
import { getServerClock } from '@/api/replays'
import { useAlignmentClock, type UseAlignmentClockReturn } from './useAlignmentClock'
import type {
  HudFrame,
  TracePoint,
  ViolationMarker,
  ReplayWSMessage,
  ReplayWSMessageType,
  ReplayHelloData,
  DegradationState,
  DegradationCode
} from '@/types/replay'

export interface UseReplayWSOptions {
  appendViolation?: (v: ViolationMarker) => void
  appendPoint?: (p: TracePoint) => void
}

export interface UseReplayWSReturn {
  wsState: Ref<'connected' | 'reconnecting' | 'disconnected'>
  latencyMs: Ref<number>
  /** 最新收到的原始遥测帧（未做延迟对齐，仅供调试/侧栏瞬时值） */
  hudFrame: Ref<HudFrame | null>
  /** ★ 按视频延迟对齐后的帧——渲染 HUD 叠层必须只用这个 */
  alignedHudFrame: Ref<HudFrame | null>
  /** 实测视频延迟 τ（毫秒） */
  videoLatencyMs: Ref<number>
  /** 客户端相对服务端的时钟偏移（毫秒） */
  clockOffsetMs: Ref<number>
  hlsUrl: Ref<string | null>
  observerMsg: Ref<{ level: 'info' | 'warn' | 'error'; text: string } | null>
  /** 当前降级等级（§7），禁止静默降级 */
  degradation: Ref<DegradationState>
  traceDurationMs: Ref<number>
  startTime: Ref<number | null>
  playerName: Ref<string | null>
  /** 服务端能力位 */
  capabilities: Ref<ReplayHelloData['capabilities']>
  /** 观察者就绪纪元：每次 observer_ready 自增，用于驱动 HLS 重建 */
  videoReadyEpoch: Ref<number>
  connect: () => void
  close: () => void
  /** 由 VideoSurface 在 hls.js 测到 latency 时调用 */
  reportVideoLatency: (ms: number) => void
}

/**
 * 推导 WebSocket URL：
 * 从 VITE_API_BASE_URL 或 window.location.origin + '/api' 推导，
 * 把 http(s) 替换为 ws(s)，拼接 /ws/replay/${uuid}?token=${token}
 */
function buildWsUrl(uuid: string, token: string): string {
  const baseRaw = (import.meta.env.VITE_API_BASE_URL as string | undefined) || ''
  let httpBase: string
  if (baseRaw) {
    if (baseRaw.startsWith('http://') || baseRaw.startsWith('https://')) {
      httpBase = baseRaw
    } else {
      httpBase = window.location.origin + baseRaw
    }
  } else {
    httpBase = window.location.origin + '/api'
  }
  httpBase = httpBase.replace(/\/+$/, '')
  const wsBase = httpBase.replace(/^http:\/\//, 'ws://').replace(/^https:\/\//, 'wss://')
  const wsPathBase = wsBase.replace(/\/api$/, '/ws') || wsBase + '/ws'
  const qs = token ? `?token=${encodeURIComponent(token)}` : ''
  return `${wsPathBase}/replay/${encodeURIComponent(uuid)}${qs}`
}

/** 降级等级元数据（设计文档 §7 阶梯表） */
const DEGRADATION_META: Record<DegradationCode, Omit<DegradationState, 'code' | 'reason' | 'message'>> = {
  L0:  { level: 'L0',   label: '正常',           evidenceValue: '完整' },
  L1:  { level: 'L1',   label: '降质',           evidenceValue: '完整' },
  L1E: { level: 'L1-E', label: '取证锁',         evidenceValue: '完整' },
  L15: { level: 'L1.5', label: '世界空洞',       evidenceValue: '中' },
  L2:  { level: 'L2',   label: '无视频',         evidenceValue: '高' },
  L3:  { level: 'L3',   label: '未获槽位',       evidenceValue: '中' },
  L4:  { level: 'L4',   label: '遥测降级',       evidenceValue: '低' },
  L5:  { level: 'L5',   label: '全失败',         evidenceValue: '极低' },
}

function makeDegradation(
  code: DegradationCode,
  reason?: string,
  message?: string
): DegradationState {
  return { ...DEGRADATION_META[code], code, reason, message }
}

let observerMsgTimer: ReturnType<typeof setTimeout> | null = null

export function useReplayWS(
  uuid: string,
  token: string,
  options: UseReplayWSOptions = {}
): UseReplayWSReturn {
  const wsState = ref<'connected' | 'reconnecting' | 'disconnected'>('disconnected')
  const latencyMs = ref<number>(0)
  const hudFrame = ref<HudFrame | null>(null)
  const hlsUrl = ref<string | null>(null)
  const observerMsg = ref<{ level: 'info' | 'warn' | 'error'; text: string } | null>(null)
  const traceDurationMs = ref<number>(0)
  const startTime = ref<number | null>(null)
  const playerName = ref<string | null>(null)
  const capabilities = ref<ReplayHelloData['capabilities']>(undefined)
  const degradation = ref<DegradationState>(makeDegradation('L0'))
  /**
   * 观察者就绪"纪元"：每次收到 observer_ready 自增，用来驱动 HLS 重建。
   * <p>不能只依赖 hlsUrl 值变化——hello 帧在连接瞬间就会带上 hlsUrl
   * （早于 observer 分配、ffmpeg 产出切片），后续 observer_ready 携带的
   * hlsUrl 字符串与之相同，watch 不会触发，于是一次打空后画面会永久
   * 卡在"直播流初始化中"。
   */
  const videoReadyEpoch = ref(0)

  // ★ 对齐时钟：视频延迟 τ + 服务端时钟偏移
  const clock: UseAlignmentClockReturn = useAlignmentClock()

  let client: WSClient | null = null
  /** 已收到的最大 seq，重连时用于续传 */
  let lastSeq = 0
  let clockSyncTimer: ReturnType<typeof setInterval> | null = null

  function setObserverMsg(level: 'info' | 'warn' | 'error', text: string): void {
    observerMsg.value = { level, text }
    if (observerMsgTimer) {
      clearTimeout(observerMsgTimer)
      observerMsgTimer = null
    }
    observerMsgTimer = setTimeout(() => {
      observerMsg.value = null
      observerMsgTimer = null
    }, 2500)
  }

  /** 按事件名映射降级等级（§7：每一级都必须明确告知前端，禁止静默降级） */
  function applyEventDegradation(kind: string, data: Record<string, unknown>): void {
    const reason = typeof data.reason === 'string' ? data.reason : undefined
    switch (kind) {
      case 'observer_ready':
        degradation.value = makeDegradation('L0')
        break
      case 'slot_queued':
        degradation.value = makeDegradation('L3', 'queued',
          typeof data.message === 'string' ? data.message : '排队等待观察者槽位')
        break
      case 'slot_acquired':
        // 拿到槽位但视频尚未就绪 → 仍属 L3
        degradation.value = makeDegradation('L3', 'awaiting_video', '已获槽位，等待画面就绪')
        break
      case 'observer_lost':
      case 'observer_error':
      case 'observer_offline':
      case 'target_offline':
      case 'offline':
        degradation.value = makeDegradation('L2', kind,
          typeof data.message === 'string' ? data.message : '观察者离线，画面不可用')
        break
      case 'camera_switch_failed':
        // 机位切换失败：画面不含手持物品，须显式标注（§8.4 红线 4）
        degradation.value = makeDegradation('L15', 'camera_switch_failed',
          '过肩机位切换失败，本段证据未录到手持物品')
        break
      case 'degraded': {
        // 服务端可显式下发 level；缺省按 reason 推断
        const lvl = typeof data.level === 'string' ? data.level.toUpperCase() : ''
        if (lvl === 'L1-E' || lvl === 'L1E') {
          degradation.value = makeDegradation('L1E', reason, String(data.message ?? '取证锁：画质已锁定'))
        } else if (lvl === 'L4') {
          degradation.value = makeDegradation('L4', reason, String(data.message ?? '遥测采样降级'))
        } else if (lvl === 'L5') {
          degradation.value = makeDegradation('L5', reason, String(data.message ?? '回放模块异常'))
        } else if (reason === 'chunk_loading') {
          degradation.value = makeDegradation('L15', 'chunk_loading',
            String(data.message ?? '观察者区块未加载，画面可能出现空洞'))
        } else {
          degradation.value = makeDegradation('L1', reason,
            String(data.message ?? '画面降质（清晰度保持不变）'))
        }
        break
      }
      default:
        break
    }
  }

  function handleHello(data: ReplayHelloData): void {
    if (data?.target?.name != null) playerName.value = String(data.target.name)
    if (data?.hlsUrl) hlsUrl.value = String(data.hlsUrl)
    else if (data?.video?.url) hlsUrl.value = String(data.video.url)
    if (data?.capabilities) capabilities.value = data.capabilities
    if (data?.t0WallClockMs != null) startTime.value = Number(data.t0WallClockMs)

    // ★ 续传结果：false 表示缺口已滚出 60s 缓冲，必须清空重画，不静默显示旧数据
    if (data?.resumed === false) {
      hudFrame.value = null
      clock.reset()
    }
    if (data?.lastHud) {
      hudFrame.value = data.lastHud as HudFrame
      clock.push(data.lastHud as HudFrame)
    }
  }

  function handleMessage(raw: unknown): void {
    if (!raw || typeof raw !== 'object') return
    const msg = raw as ReplayWSMessage
    const type = msg.type as ReplayWSMessageType
    const data = msg.data

    // 记录 seq：重连时据此续传（TCP 内不会丢帧，gap 只发生在跨连接场景）
    if (typeof msg.seq === 'number' && msg.seq > lastSeq) lastSeq = msg.seq

    switch (type) {
      case 'hello':
        handleHello(data as ReplayHelloData)
        break

      case 'hud': {
        if (data) {
          const f = data as HudFrame
          hudFrame.value = f
          // 推入对齐时钟缓冲：渲染走 alignedHudFrame，不直接用原始帧
          clock.push(f)
          if (typeof f.t === 'number' && f.t > traceDurationMs.value) {
            traceDurationMs.value = f.t
          }
        }
        break
      }

      case 'violation': {
        const v = data as { t: number; type: string; level: string }
        if (v && typeof v.t === 'number') {
          options.appendViolation?.({ t: v.t, type: v.type, level: v.level })
        }
        break
      }

      case 'event': {
        const kind = String((data?.event ?? data?.kind ?? '') as string)
        if (kind === 'observer_ready') {
          if (data?.hlsUrl) {
            hlsUrl.value = String(data.hlsUrl)
          }
          // 无论 hlsUrl 是否变化都递增：通知视图重建 HLS（此时切片已落盘）
          videoReadyEpoch.value += 1
          setObserverMsg('info', '观察者就绪，直播画面已连接')
        } else if (kind === 'observer_error') {
          setObserverMsg('error', data?.message ? String(data.message) : '观察者错误')
        } else if (kind === 'observer_offline' || kind === 'offline') {
          setObserverMsg('warn', data?.message ? String(data.message) : '观察者离线')
        } else if (kind === 'slot_queued') {
          const pos = data?.queuePosition != null ? `#${data.queuePosition} ` : ''
          const eta = data?.etaSec != null ? `预计 ${data.etaSec}s` : ''
          setObserverMsg('info', `排队等待观察者 ${pos}${eta}`.trim())
        }
        applyEventDegradation(kind, (data ?? {}) as Record<string, unknown>)
        break
      }

      case 'error': {
        const e = data as { code?: string; message?: string; retryable?: boolean }
        setObserverMsg('error', e?.message ?? '回放通道错误')
        break
      }

      case 'pong': {
        // 心跳响应：serverTimeMs 可用于持续校正时钟（主校正走 HTTP /api/replay/clock）
        const st = (data as { serverTimeMs?: number })?.serverTimeMs
        if (typeof st === 'number' && st > 0) {
          const observed = st - Date.now()
          // 平滑收敛，避免单次抖动导致叠层跳变
          clock.clockOffsetMs.value =
            Math.round(clock.clockOffsetMs.value * 0.7 + observed * 0.3)
        }
        break
      }
    }
  }

  /**
   * 时钟偏移校正（§3.3）。三点法：t0 → 请求 → t1。
   * offset ≈ serverTime + rtt/2 - t1
   */
  async function syncClock(): Promise<void> {
    try {
      const t0 = Date.now()
      const c = await getServerClock()
      const t1 = Date.now()
      const rtt = t1 - t0
      // RTT 过大说明网络异常或代理缓存，此时估算不可信，保留上一轮结果
      if (rtt >= 0 && rtt < 3000) {
        clock.clockOffsetMs.value = Math.round(c.serverTimeMs + rtt / 2 - t1)
      }
    } catch {
      // 静默失败：校正失败只影响对齐精度，不影响可用性
    }
  }

  function connect(): void {
    close()
    const url = buildWsUrl(uuid, token)
    client = new WSClient(url, {
      // v3 协议：JSON 心跳 + 指数退避（避免多标签页同时重连造成惊群）
      jsonPing: true,
      exponentialBackoff: true,
      pingIntervalMs: 5_000,
      maxReconnectAttempts: 20,
    })
    client.onStateChange = (state, lat) => {
      wsState.value = state
      if (typeof lat === 'number') latencyMs.value = lat
    }
    client.onmessage = handleMessage

    // ★ 断线重连续传：lastSeq > 0 说明是重连，请求补发缺口帧
    client.onopen = () => {
      if (lastSeq > 0 && client) {
        try {
          client.sendJSON({ op: 'subscribe', lastSeq })
        } catch {
          // 发送失败会在下一个心跳周期自然重试
        }
      }
      void syncClock()
    }

    client.connect()
    clock.start()
    void syncClock()
    // 时钟会漂移，每 60s 重新校正一次
    clockSyncTimer = setInterval(() => { void syncClock() }, 60_000)
  }

  function close(): void {
    if (client) {
      client.close()
      client = null
    }
    if (clockSyncTimer) {
      clearInterval(clockSyncTimer)
      clockSyncTimer = null
    }
    if (observerMsgTimer) {
      clearTimeout(observerMsgTimer)
      observerMsgTimer = null
    }
    clock.stop()
    wsState.value = 'disconnected'
  }

  return {
    wsState,
    latencyMs,
    hudFrame,
    alignedHudFrame: clock.alignedFrame,
    videoLatencyMs: clock.videoLatencyMs,
    clockOffsetMs: clock.clockOffsetMs,
    hlsUrl,
    observerMsg,
    degradation,
    videoReadyEpoch,
    traceDurationMs,
    startTime,
    playerName,
    capabilities,
    connect,
    close,
    reportVideoLatency: clock.setLatency
  }
}
