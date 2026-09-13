// ==================== AI 实验室类型 ====================

/** AI 实验室设置快照 */
export interface AILabSettings {
  enabled: boolean
  baselineEnabled: boolean
  forestEnabled: boolean
  supervisedEnabled: boolean
  clusterEnabled: boolean
  learningEnabled: boolean
  weights: { personal: number, global: number, supervised: number }
  watchThreshold: number
  alertThreshold: number
  minLabelsForTraining: number
  minNewLabelsForTraining: number
}

/** 活跃监督模型信息 */
export interface AILabActiveModel {
  exists: boolean
  version?: number
  trainedAt?: number
  sampleCount?: number
  auc?: number
}

/** 系统健康度统计 */
export interface AILabHealth {
  forestReady: boolean
  forestHistory: number
  forestLastRebuildAt: number
  openClusters: number
  labelsTotal: number
  labelsCheat: number
  labelsBenign: number
  labelsNewSinceTrain: number
  trackedPlayers: number
}

/** 在线玩家 AI 评分 */
export interface AILabPlayerScore {
  uuid: string
  name: string
  personal: number
  global: number
  supervised: number
  fused: number
  watchlisted: boolean
}

/** 概览响应 */
export interface AILabOverview {
  enabled: boolean
  settings?: AILabSettings
  activeModel?: AILabActiveModel
  health?: AILabHealth
  topPlayers?: AILabPlayerScore[]
}

/** 单玩家评分明细（可解释性） */
export interface AILabPlayerDetail extends AILabPlayerScore {
  topDeviations?: FeatureContribution[]
  baseline?: {
    warmedUp: boolean
    warmupProgress: number
    warmupTarget: number
    updates: number
  }
}

/** 特征贡献项 */
export interface FeatureContribution {
  dim: number
  name: string
  desc: string
  value?: number
  zDelta?: number
  z?: number
}

/** 异常集群指纹项 */
export interface ClusterFingerprintItem {
  dim: number
  name: string
  desc: string
  delta: number
}

/** 异常集群 */
export interface AILabCluster {
  id: string
  discoveredAt: number
  members: string[]
  memberCount: number
  avgGlobalScore: number
  status: 'open' | 'dismissed' | 'ruled'
  note: string
  fingerprint: ClusterFingerprintItem[]
}

/** 标签样本 */
export interface AILabLabel {
  id: number
  uuid: string
  playerName: string
  label: 0 | 1
  source: string
  hasFeatures: boolean
  timestamp: number
  note: string
}

/** 标签列表响应 */
export interface AILabLabelList {
  items: AILabLabel[]
  total: number
  cheat: number
  benign: number
}

/** 模型版本元数据 */
export interface AILabModelVersion {
  version: number
  trainedAt: number
  sampleCount: number
  auc: number
  active: boolean
}

/** 模拟器结果 */
export interface AILabSimulateResult {
  personal: number
  global: number
  supervised: number
  fused: number
  fromPlayer: boolean
  topFactors: FeatureContribution[]
}

/** 自适应阈值响应 */
export interface AILabThresholds {
  targetFpr: number
  current: Record<string, number>
  history: Record<string, [number, number][]>
}

/** 特征字典项 */
export interface AILabFeatureDictItem {
  index: number
  name: string
  desc: string
  group: string
}
