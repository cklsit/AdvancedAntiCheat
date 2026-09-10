// ==================== 角色枚举 ====================
export enum Role {
  SUPER_ADMIN = 0,
  ADMIN = 1,
  REVIEWER = 2,
  OBSERVER = 3
}

// ==================== 玩家状态 ====================
export type PlayerStatus = 'online' | 'offline' | 'banned' | 'watching'

// ==================== 风险等级 ====================
export enum RiskLevel {
  LOW = 0,      // 0-49
  MEDIUM = 1,   // 50-69
  HIGH = 2,     // 70-89
  EXTREME = 3   // 90-100
}

// ==================== 玩家实体 ====================
export interface Player {
  uuid: string
  name: string
  avatar: string
  status: PlayerStatus
  riskScore: number
  riskLevel: RiskLevel
  lastTrigger: string
  onlineDuration: number  // seconds
  ip: string
  firstJoin: string
  lastJoin: string
  violationsCount: number
  ping: number
  gameMode: string
  world: string
  /** 玩家在世界中的 X 坐标（实时地图用） */
  locationX: number
  /** 玩家在世界中的 Z 坐标（实时地图用） */
  locationZ: number
  version: string
  country: string
  hardwareId?: string
  violationHistory: ViolationRecord[]
  linkedAccounts: LinkedAccount[]
}

export interface ViolationRecord {
  id: string
  module: string
  type: string
  score: number
  time: string
  server: string
  details?: string
}

export interface LinkedAccount {
  uuid: string
  name: string
  relation: string
  ipMatch: boolean
  hardwareMatch: boolean
}

// ==================== 案件状态 ====================
export type CaseStatus = 'pending' | 'reviewing' | 'completed'

// ==================== 案件实体 ====================
export interface CaseEntity {
  id: string
  playerName: string
  playerUuid: string
  topModule: string
  topScore: number
  riskLevel: RiskLevel
  evidenceCount: number
  age: number  // hours since created
  status: CaseStatus
  createdAt: string
  assignedTo?: string
  verdict?: 'guilty' | 'innocent' | 'watched'
  modules: ModuleStat[]
  evidenceSummary: EvidenceSummary[]
  /** 关联的违规回放片段 id（后端填充；可能为空） */
  replayId?: number | null
}

export interface EvidenceSummary {
  type: string
  count: number
  peakScore: number
  lastTime: string
}

export interface ModuleStat {
  name: string
  triggerCount: number
  avgScore: number
  peakScore: number
}

// ==================== 通知级别 ====================
export type NotificationLevel = 'info' | 'warning' | 'danger' | 'success'

// ==================== 通知条目 ====================
export interface NotificationItem {
  id: string
  level: NotificationLevel
  title: string
  content: string
  playerName?: string
  playerUuid?: string
  time: string
  read: boolean
  category: string
}

// ==================== 审计条目 ====================
export interface AuditItem {
  id: string
  time: string
  operator: string
  operatorRole: Role
  type: AuditType
  target: string
  ip: string
  result: AuditResult
  detail?: string
}

export type AuditType =
  | 'login'
  | 'logout'
  | 'ban'
  | 'unban'
  | 'config_change'
  | 'case_verdict'
  | 'report_generate'
  | 'permission_change'
  | 'system_restart'

export type AuditResult = 'success' | 'failed' | 'warning'

// ==================== 告警条目 ====================
export interface AlertItem {
  id: string
  level: 'critical' | 'high' | 'medium' | 'low'
  title: string
  message: string
  playerName?: string
  time: string
  module: string
  score: number
  /** 关联的违规回放片段 id（后端填充；可能为空） */
  replayId?: number | null
}

// ==================== Dashboard 统计 ====================
export interface DashboardStats {
  onlinePlayers: number
  totalPlayers: number
  activeCases: number
  todayViolations: number
  todayBans: number
  riskDistribution: { level: RiskLevel; count: number; percent: number }[]
  moduleTriggers: { module: string; count: number; trend: number }[]
  hourlyTrend: { hour: string; violations: number; bans: number }[]
  serverStatus: ServerStatus[]
}

export interface ServerStatus {
  id: string
  name: string
  online: number
  tps: number
  memory: number
  region: string
  status: 'healthy' | 'warning' | 'critical'
}

// ==================== 通用接口 ====================
export interface ApiResp<T = unknown> {
  code: number
  message: string
  data: T
}

export interface Page<T> {
  list: T[]
  total: number
  page: number
  pageSize: number
  pages: number
}

// ==================== 检测模块配置 ====================
/**
 * 与后端 ConfigModuleDTO 对齐。
 * 真实配置字段：与 config.yml 中 detection.<id>.* 一一对应。
 * 派生字段：autoBan / humanReview 仅为旧 UI 兼容。
 */
export interface ConfigModule {
  id: string
  name: string
  module: string
  enabled: boolean
  maxViolations: number
  /** 封禁时长字符串："30m" / "1h" / "1d" / "permanent" */
  banTime: string
  /** 达到该违规数时踢出 */
  kickThreshold: number
  /** 达到该违规数时升级人工审核 */
  humanReviewThreshold: number
  /** 警告消息冷却（秒） */
  warningCooldownSecs: number
  /** 检测通知冷却（毫秒） */
  notifyCooldownMs: number
  // 派生字段（旧 UI 兼容）
  autoBan: number
  humanReview: number
}

// ==================== 登录相关 ====================
export interface LoginPayload {
  username: string
  password: string
  /** 8 位动态验证码 (TOTP / Google Authenticator) */
  totp?: string
  captcha?: string
}

export interface LoginResult {
  token: string
  user: UserInfo
}

export interface UserInfo {
  id: string
  username: string
  nickname: string
  role: Role
  avatar?: string
  lastLogin: string
  lastIp: string
  permissions: string[]
}

// ==================== 联盟图谱 ====================
export interface AllianceNode {
  id: string
  label: string
  type: 'player' | 'hardware' | 'ip' | 'cluster'
  score: number
  x: number
  y: number
  ip?: string
  world?: string
}

export interface AllianceEdge {
  source: string
  target: string
  kind: 'behavior' | 'ip' | 'hardware'
  weight: number
}

export interface AllianceGroup {
  members: string[]
  suspicionScore: number
  density: number
}

export interface AllianceGraph {
  nodes: AllianceNode[]
  edges: AllianceEdge[]
  groups: AllianceGroup[]
  stats: { nodeCount: number; edgeCount: number; groupCount: number }
}

// ==================== 违规回放 ====================
export type {
  TracePoint,
  ViolationMarker,
  ReplayPlayer,
  ReplaySession,
  ReplayArchive,
  ReplayDelta,
  ReplayLayers
} from './replay'
