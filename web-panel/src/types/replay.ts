// ==================== 回放轨迹点 ====================
export interface TracePoint {
  /** 相对 startTime 的毫秒偏移 */
  t: number
  x: number
  y: number
  z: number
  /** Minecraft yaw: 0=向南(+Z), 顺时针为正 */
  yaw: number
  /** Minecraft pitch: 0=水平, 向下为正 */
  pitch: number
  onGround: boolean
  gameMode: string
  mainHandItemId: string | null
  /** 7×7 方块高度，49 个整数或 null（该帧无地形数据） */
  blockHeights: number[] | null
}

// ==================== 违规标记 ====================
export interface ViolationMarker {
  /** 相对 startTime 的毫秒偏移 */
  t: number
  type: string
  level: string
}

// ==================== 在线玩家（实时列表） ====================
export interface ReplayPlayer {
  uuid: string
  playerName: string
  /** 已在线总时长（秒） */
  onlineSeconds: number
  /** 已累积的可回放时长（秒） */
  replaySeconds: number
  /** 累计违规次数 */
  violationCount: number
  lastViolationType: string | null
  lastViolationLevel: string | null
}

// ==================== 回放会话（一次完整轨迹快照） ====================
export interface ReplaySession {
  playerUuid: string
  playerName: string
  /** epoch ms */
  startTime: number
  /** epoch ms */
  endTime: number
  points: TracePoint[]
  violations: ViolationMarker[]
}

// ==================== 历史存档条目 ====================
export interface ReplayArchive {
  filename: string
  playerName: string
  /** epoch ms */
  startTime: number
  /** 存档时长（毫秒） */
  durationMs: number
  sizeKB: number
  violationCount: number
}

// ==================== 增量拉取结果 ====================
export interface ReplayDelta {
  points: TracePoint[]
  newViolations: ViolationMarker[]
}

// ==================== 回放图层开关（ReplayCanvas 使用） ====================
export interface ReplayLayers {
  /** 运动轨迹线 */
  trail: boolean
  /** 命中盒 */
  hitbox: boolean
  /** 违规时刻标记 */
  violation: boolean
  /** 世界栅格 */
  grid: boolean
  /** 地形高度图 */
  terrain: boolean
}

// ==================== HUD 帧（Task 4 追加字段；live WS 与归档共用长键） ====================
export interface HudFrame {
  /** epoch ms（wallClockMs） */
  w: number
  /** 相对 startTime 的毫秒偏移（live hud 帧携带，归档采样帧为 t） */
  t?: number
  /** 生命值 0~20（对应 10 颗心） */
  health: number
  /** 最大生命值 */
  maxHealth: number
  /** 饥饿值 0~20（10 根鸡腿） */
  hunger: number
  /** 护甲值 0~20（10 件胸甲） */
  armor: number
  /** 经验等级 */
  level: number
  /** 经验条 0~1（小数） */
  xp: number
  /** 正在呼吸的玩家氧气值 0~300（null=未在水中） */
  air: number | null
  /** 当前选中的快捷栏槽位 0~8 */
  hotbarSlot: number
  /** 快捷栏 9 格，每格 {id, count, damage} 或 null；整栏未采样时为 null */
  hotbar: Array<{
    id: string | null
    count: number
    damage?: number
  } | null> | null
  /** 完整 36 格背包物品类型名（稀疏采样，字符串数组，null=未采样） */
  finv?: Array<string | null> | null
  /** 准星目标类别：0=无，1=实体，2=方块 */
  tk?: number
  /** 准星目标实体类型名，如 PLAYER/ZOMBIE */
  tt?: string
  /** 准星目标实体名（玩家名/自定义名/掉落物类型） */
  tn?: string
  /** 准星目标距离（格） */
  td?: number
  /** 准星目标方块类型名 */
  tb?: string
  /** 玩家 XYZ 坐标 */
  x?: number
  y?: number
  z?: number
  /** 视角朝向 */
  yaw?: number
  pitch?: number
}

// TracePoint 已包含部分字段（旧字段保持兼容）
// 这里额外声明用于 HUD overlay 的扩展（可从 HudFrame 同步）

// ==================== 归档 HUD 采样（metadata.json 的 hudSampled5Hz） ====================
export interface ArchiveHudSample {
  /** 相对视频起点（startTimeMs）的毫秒偏移 */
  t: number
  /** 半心 0~40（health = h/2） */
  h: number
  /** 饥饿 0~20 */
  f: number
  /** 半护甲 0~40（armor = a/2） */
  a: number
  /** 经验等级 */
  lvl: number
  /** 本等级经验百分比 0~100 */
  xp: number
  /** 选中快捷栏槽位 0~8 */
  hs: number
  /** 热栏 9 格物品类型名（稀疏采样：null 表示沿用最近非空帧） */
  inv: Array<string | null> | null
}

// ==================== WebSocket 消息（/ws/replay/:targetUuid，v3 统一信封） ====================
export type ReplayWSMessageType =
  | 'hello'          // 初始握手 / 续传基线 → data: HelloData
  | 'hud'            // HUD 帧推送（20Hz） → data: HudFrame
  | 'violation'      // 违规事件 → data: { t:number, type:string, level:string, details?, confidence? }
  | 'event'          // 观察者/调度事件 → data: { event:string, message?, hlsUrl?, queuePosition?, etaSec?, level? }
  | 'error'          // 错误 → data: { code:string, message:string, retryable:boolean }
  | 'pong'           // 心跳响应 → data: { serverTimeMs:number }

/** hello 帧的 data 结构（v3） */
export interface ReplayHelloData {
  sessionId?: string
  target?: { uuid: string; name: string }
  /** 会话起点（服务端墙钟 ms） */
  t0WallClockMs?: number
  /** 服务端当前墙钟，用于时钟偏移校正（§3.3） */
  serverTimeMs?: number
  hlsUrl?: string
  video?: {
    mode?: 'llhls'
    url?: string
    targetLatencyMs?: number
    qualities?: Array<{ name: string; width: number; height: number; bitrateKbps: number }>
  }
  telemetry?: { rateHz?: number; fields?: string[] }
  capabilities?: {
    spectatorBound?: boolean
    crosshair?: boolean
    fullInventory?: boolean
    /** 服务端是否支持 lastSeq 续传 */
    resume?: boolean
  }
  lastHud?: HudFrame
  lastInventory?: Array<string | null>
  violations?: Array<{ t: number; wallClockMs?: number; type: string; level: string; details?: string }>
  /**
   * 断线续传结果：
   * - false = 缺口已滚出 60s 缓冲（或首次连接），前端必须清空重画，不得静默显示旧数据
   * - true  = 已按序补齐缺口帧
   */
  resumed?: boolean
}

export interface ReplayWSMessage {
  /** 协议版本 */
  v: number
  type: ReplayWSMessageType
  /** 服务端墙钟 ms */
  ts: number
  /** 按目标递增的单调序号（用于丢帧/乱序检测） */
  seq: number
  data: any
}

// ==================== 降级阶梯（设计文档 §7） ====================

/**
 * L0 正常 | L1 降质 | L1E 取证锁 | L15 世界空洞（L1.5）
 * L2 无视频 | L3 未获槽 | L4 遥测降级 | L5 全失败
 */
export type DegradationCode = 'L0' | 'L1' | 'L1E' | 'L15' | 'L2' | 'L3' | 'L4' | 'L5'

export interface DegradationState {
  code: DegradationCode
  /** 展示用等级名，如 'L1.5' */
  level: string
  label: string
  /** 该等级下证据的可信度 */
  evidenceValue: '完整' | '中' | '高' | '低' | '极低'
  reason?: string
  message?: string
}

// ==================== 服务端时钟（GET /api/replay/clock，§3.3） ====================
export interface ServerClock {
  /** 服务端权威墙钟 ms */
  serverTimeMs: number
  /** JVM 启动至今 ms（前端据此判断服务端是否重启过时基失效） */
  uptimeMs: number
  /** WS 续传窗口 ms */
  retentionMs: number
  ntpSynced: boolean
}

// ==================== 观察者/调度事件名 ====================
export type ReplayEventKind =
  | 'slot_queued'
  | 'slot_acquired'
  | 'observer_ready'
  | 'observer_error'
  | 'observer_offline'
  | 'offline'
  | 'degraded'

// ==================== 观察者池状态（/api/replay/observer-status） ====================
export interface ObserverStatus {
  id: string
  name: string
  baseUrl: string
  status: 'READY' | 'BUSY' | 'ERROR' | 'OFFLINE'
  busyTarget: string | null
  hlsUrl: string | null
  lastError: string | null
}
export interface ObserverPoolSnapshot {
  observers: ObserverStatus[]
  subscribersPerTarget: Record<string, number>
  pendingRetryTargets: string[]
  updatedAt: number
}
