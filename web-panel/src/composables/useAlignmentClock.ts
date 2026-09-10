import { ref, type Ref } from 'vue'
import type { HudFrame } from '@/types/replay'

/**
 * 对齐时钟（设计文档 §3.3 — 视角一致性的关键）
 *
 * 视频（LL-HLS）有 1–3s 延迟，遥测（WS）只有 50–100ms。若拿到遥测就立刻渲染，
 * 叠层会显示"未来"的血量盖在"过去"的画面上——这就是"叠层与画面对不上"的根因。
 *
 * 解法：所有遥测帧都带服务端墙钟 `w`，视频当前显示帧对应的世界时间为
 *   target = serverNow() - τ
 * 本时钟只在缓冲中取 `w <= target` 的最新一帧渲染。
 * τ 由 hls.js 的 latency 实时测得；serverNow 需叠加客户端与服务端时钟偏移。
 *
 * 前提：容器时钟与插件时钟需 NTP 同步，并由 /api/replay/clock 提供偏移校正。
 */

export interface UseAlignmentClockOptions {
  /** 遥测缓冲保留时长（毫秒）。需大于最大视频延迟，默认 12s */
  bufferMs?: number
  /** hls.js 实测到延迟之前的兜底值（毫秒），默认 2000 */
  defaultLatencyMs?: number
  /** 对齐 tick 间隔（毫秒），默认 100（10Hz，肉眼无感且开销可忽略） */
  tickMs?: number
}

export interface UseAlignmentClockReturn {
  /** 实测视频延迟 τ（毫秒） */
  videoLatencyMs: Ref<number>
  /** 客户端相对服务端的时钟偏移（毫秒）：serverNow = Date.now() + offset */
  clockOffsetMs: Ref<number>
  /** 按 τ 推迟后的当前帧，渲染叠层应只用这一个数据源 */
  alignedFrame: Ref<HudFrame | null>
  /** 服务端墙钟的本地估算值 */
  serverNow: () => number
  /** 推入一帧遥测（按 w 排序插入并淘汰过期帧） */
  push: (frame: HudFrame) => void
  /** 更新实测视频延迟（由 hls.js latency 驱动） */
  setLatency: (ms: number) => void
  start: () => void
  stop: () => void
  reset: () => void
}

interface BufferedFrame {
  w: number
  frame: HudFrame
}

export function useAlignmentClock(
  options: UseAlignmentClockOptions = {}
): UseAlignmentClockReturn {
  const bufferMs = options.bufferMs ?? 12_000
  const defaultLatencyMs = options.defaultLatencyMs ?? 2_000
  const tickMs = options.tickMs ?? 100

  const videoLatencyMs = ref<number>(defaultLatencyMs)
  const clockOffsetMs = ref<number>(0)
  const alignedFrame = ref<HudFrame | null>(null)

  /** 按 w 升序的环形缓冲。不做整数组重建，避免 20Hz 下的 GC 压力 */
  let buffer: BufferedFrame[] = []
  let timer: ReturnType<typeof setInterval> | null = null

  function serverNow(): number {
    return Date.now() + clockOffsetMs.value
  }

  function push(frame: HudFrame): void {
    const w = typeof frame.w === 'number' ? frame.w : Date.now()
    const buf = buffer

    // 乱序保护：WS 保证按序，但重连续传可能出现已消费过的帧，直接丢弃
    const last = buf.length ? buf[buf.length - 1] : null
    if (last && w < last.w) {
      // 轻微乱序（<1s）插入排序，明显过期则丢弃
      if (w < last.w - 1000) return
      let i = buf.length - 1
      while (i > 0 && buf[i - 1].w > w) i--
      buf.splice(i, 0, { w, frame })
    } else {
      buf.push({ w, frame })
    }

    // 淘汰过期帧
    const cutoff = w - bufferMs
    let drop = 0
    while (drop < buf.length && buf[drop].w < cutoff) drop++
    if (drop > 0) buf.splice(0, drop)
  }

  function tick(): void {
    const buf = buffer
    if (!buf.length) {
      alignedFrame.value = null
      return
    }
    const target = serverNow() - videoLatencyMs.value

    // 二分查找 w <= target 的最近一帧
    let lo = 0
    let hi = buf.length - 1
    let ans = -1
    while (lo <= hi) {
      const mid = (lo + hi) >>> 1
      if (buf[mid].w <= target) {
        ans = mid
        lo = mid + 1
      } else {
        hi = mid - 1
      }
    }
    // 缓冲里全是"未来"的帧（常见于刚连上、τ 还没测准）→ 取最老一帧，避免叠层空白
    if (ans < 0) ans = 0
    alignedFrame.value = buf[ans].frame
  }

  function setLatency(ms: number): void {
    if (!Number.isFinite(ms) || ms <= 0) return
    // 限幅：低于 200ms 不可信（HLS 物理下限），高于 15s 视为异常抖动
    videoLatencyMs.value = Math.min(15_000, Math.max(200, Math.round(ms)))
  }

  function start(): void {
    stop()
    timer = setInterval(tick, tickMs)
    tick()
  }

  function stop(): void {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  function reset(): void {
    buffer = []
    alignedFrame.value = null
    videoLatencyMs.value = defaultLatencyMs
  }

  return {
    videoLatencyMs,
    clockOffsetMs,
    alignedFrame,
    serverNow,
    push,
    setLatency,
    start,
    stop,
    reset
  }
}
