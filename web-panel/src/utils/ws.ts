/**
 * WebSocket 客户端封装
 *
 * - 自动重连: 固定间隔（默认）或 **指数退避 + 抖动**（v3 推荐）
 * - 心跳: 旧式字符串 `__PING__` 或 v3 JSON `{"op":"ping"}`
 * - 对外暴露 onopen / onmessage / onerror / onclose 回调
 * - 状态回调 onStateChange: 'connected' | 'reconnecting' | 'disconnected'
 * - 全部重连失败后派发 window 事件 'app:ws-failed'
 */

export type WSState = 'connected' | 'reconnecting' | 'disconnected'
export type WSEventHandler = (event: Event) => void
export type WSMessageHandler = (data: unknown) => void
export type WSStateHandler = (state: WSState, latencyMs?: number) => void

export interface WSClientOptions {
  /**
   * v3 协议心跳：发送 JSON `{"op":"ping"}`，并识别 v3 信封中的 `pong` 消息来测延迟。
   * 关闭时沿用旧式字符串 `__PING__` / `__PONG__`（旧端点兼容）。
   * @default false
   */
  jsonPing?: boolean
  /**
   * 指数退避 + 抖动重连：`min(30s, 1s × 2^n) ± 20%`。
   * 抖动用于避免多标签页同时重连造成惊群。
   * @default false
   */
  exponentialBackoff?: boolean
  /** 心跳间隔（毫秒）。v3 规范要求 5s。@default 15000 */
  pingIntervalMs?: number
  /** 最大重连次数，超出后判定为彻底断开。@default 12 */
  maxReconnectAttempts?: number
  /** 指数退避的单次上限（毫秒）。@default 30000 */
  maxBackoffMs?: number
}

export class WSClient {
  private url: string
  private options: Required<WSClientOptions>
  private ws: WebSocket | null = null

  // 自动重连配置
  private autoReconnect = true
  private reconnectTimer: number | null = null
  private reconnectAttempts = 0

  // Event hooks
  public onopen: WSEventHandler | null = null
  public onmessage: WSMessageHandler | null = null
  public onerror: WSEventHandler | null = null
  public onclose: WSEventHandler | null = null
  public onStateChange: WSStateHandler | null = null

  // Latency probe
  private lastPingAt = 0
  private latencyMs = 0
  private pingTimer: ReturnType<typeof setInterval> | null = null

  constructor(url: string, options: WSClientOptions = {}) {
    this.url = url
    this.options = {
      jsonPing: options.jsonPing ?? false,
      exponentialBackoff: options.exponentialBackoff ?? false,
      pingIntervalMs: options.pingIntervalMs ?? 15_000,
      maxReconnectAttempts: options.maxReconnectAttempts ?? 12,
      maxBackoffMs: options.maxBackoffMs ?? 30_000,
    }
  }

  public get currentState(): WSState {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) return 'connected'
    if (this.reconnectTimer) return 'reconnecting'
    return 'disconnected'
  }

  public get latency(): number {
    return this.latencyMs
  }

  public connect(): void {
    this.autoReconnect = true
    this.reconnectAttempts = 0
    this.doConnect()
  }

  public close(code = 1000, reason = 'manual_close'): void {
    this.autoReconnect = false  // 手动关闭不重连
    this.clearPingTimer()
    this.clearReconnectTimer()
    if (this.ws) {
      try {
        this.ws.onopen = null
        this.ws.onmessage = null
        this.ws.onerror = null
        this.ws.onclose = null
        this.ws.close(code, reason)
      } catch { /* noop */ }
      this.ws = null
    }
    this.notifyState('disconnected')
  }

  public send(data: string | ArrayBufferLike | Blob | ArrayBufferView): void {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      throw new Error('WebSocket is not connected')
    }
    this.ws.send(data)
  }

  /** 发送 JSON 数据 */
  public sendJSON<T = unknown>(payload: T): void {
    this.send(JSON.stringify(payload))
  }

  // ---------- internal ----------

  private doConnect(): void {
    this.clearReconnectTimer()

    if (!('WebSocket' in window)) {
      this.notifyState('disconnected')
      return
    }

    if (this.reconnectAttempts > 0) this.notifyState('reconnecting')

    try {
      this.ws = new WebSocket(this.url)
    } catch {
      this.scheduleReconnect()
      return
    }

    this.ws.onopen = (ev) => {
      this.reconnectAttempts = 0  // 重连成功后重置计数
      this.notifyState('connected')
      this.startPingTimer()
      this.onopen?.(ev)
    }

    this.ws.onmessage = (ev) => {
      let parsed: unknown = ev.data
      if (typeof ev.data === 'string') {
        try { parsed = JSON.parse(ev.data) } catch { parsed = ev.data }
      }

      // ---- 心跳 PONG：优先 v3 信封，其次旧式字符串 ----
      if (this.options.jsonPing && parsed && typeof parsed === 'object') {
        const env = parsed as { type?: string; data?: { serverTimeMs?: number } }
        if (env.type === 'pong') {
          if (this.lastPingAt > 0) {
            this.latencyMs = Date.now() - this.lastPingAt
            this.lastPingAt = 0
            this.onStateChange?.('connected', this.latencyMs)
          }
          // 继续下发：上层需要读 data.serverTimeMs 做时钟偏移校正
          this.onmessage?.(parsed)
          return
        }
      } else if (typeof ev.data === 'string' && ev.data.startsWith('__PONG__')) {
        if (this.lastPingAt > 0) {
          this.latencyMs = Date.now() - this.lastPingAt
          this.lastPingAt = 0
          this.onStateChange?.('connected', this.latencyMs)
        }
        return
      }

      this.onmessage?.(parsed)
    }

    this.ws.onerror = (ev) => {
      this.onerror?.(ev)
    }

    this.ws.onclose = (ev) => {
      this.clearPingTimer()
      this.onclose?.(ev)
      this.ws = null
      if (this.autoReconnect) {
        this.scheduleReconnect()
      } else {
        this.notifyState('disconnected')
      }
    }
  }

  private scheduleReconnect(): void {
    if (!this.autoReconnect) return

    if (this.reconnectAttempts >= this.options.maxReconnectAttempts) {
      // 全部失败，通知 UI
      this.notifyState('disconnected')
      if (typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent('app:ws-failed'))
      }
      return
    }

    this.reconnectAttempts++
    this.notifyState('reconnecting')

    const delay = this.options.exponentialBackoff
      ? this.backoffDelay(this.reconnectAttempts)
      : 5000

    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null
      this.doConnect()
    }, delay)
  }

  /**
   * 指数退避 + ±20% 抖动：min(maxBackoffMs, 1s × 2^(n-1))。
   * 抖动避免多标签页同时重连造成惊群。
   */
  private backoffDelay(attempt: number): number {
    const raw = Math.min(
      this.options.maxBackoffMs,
      1000 * Math.pow(2, Math.max(0, attempt - 1))
    )
    const jitter = raw * 0.2
    return Math.round(raw - jitter + Math.random() * jitter * 2)
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
  }

  private startPingTimer(): void {
    this.clearPingTimer()
    this.pingTimer = setInterval(() => {
      if (this.ws && this.ws.readyState === WebSocket.OPEN) {
        this.lastPingAt = Date.now()
        try {
          if (this.options.jsonPing) {
            this.ws.send(JSON.stringify({ op: 'ping' }))
          } else {
            this.ws.send('__PING__')
          }
        } catch { /* noop */ }
      }
    }, this.options.pingIntervalMs)
  }

  private clearPingTimer(): void {
    if (this.pingTimer) {
      clearInterval(this.pingTimer)
      this.pingTimer = null
    }
  }

  private notifyState(state: WSState): void {
    this.onStateChange?.(state, this.latencyMs)
  }
}
