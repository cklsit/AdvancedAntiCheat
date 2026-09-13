import type {
  AILabCluster,
  AILabLabelList,
  AILabModelVersion,
  AILabOverview,
  AILabPlayerDetail,
  AILabSettings,
  AILabSimulateResult,
  AILabThresholds,
  AILabFeatureDictItem
} from '@/types/ailab'
import { request } from './request'

/** AI 实验室 REST 客户端（/api/ailab/*） */

export async function getAILabOverview(): Promise<AILabOverview> {
  return request.get<AILabOverview>('/ailab/overview')
}

export async function getAILabPlayerScores(uuid: string): Promise<AILabPlayerDetail> {
  return request.get<AILabPlayerDetail>(`/ailab/players/${uuid}/scores`)
}

export async function resetPlayerBaseline(uuid: string): Promise<{ reset: boolean }> {
  return request.post<{ reset: boolean }>(`/ailab/players/${uuid}/reset-baseline`)
}

export async function getClusters(): Promise<AILabCluster[]> {
  return request.get<AILabCluster[]>('/ailab/clusters')
}

export async function resolveCluster(
  id: string,
  action: 'dismiss' | 'rule',
  note = ''
): Promise<{ resolved: boolean }> {
  return request.post<{ resolved: boolean }>(`/ailab/clusters/${id}/resolve`, { action, note })
}

export async function getLabels(params: {
  label?: number
  source?: string
  name?: string
  page?: number
  size?: number
}): Promise<AILabLabelList> {
  const qs = new URLSearchParams()
  if (params.label !== undefined && params.label >= 0) qs.set('label', String(params.label))
  if (params.source) qs.set('source', params.source)
  if (params.name) qs.set('name', params.name)
  qs.set('page', String(params.page ?? 0))
  qs.set('size', String(params.size ?? 20))
  return request.get<AILabLabelList>(`/ailab/labels?${qs.toString()}`)
}

export async function addLabel(body: {
  uuid: string
  cheat: boolean
  source?: string
  note?: string
}): Promise<{ added: boolean }> {
  return request.post<{ added: boolean }>('/ailab/labels', body)
}

export async function correctLabel(id: number, label: 0 | 1, note = ''): Promise<{ corrected: boolean }> {
  return request.post<{ corrected: boolean }>(`/ailab/labels/${id}/correct`, { label, note })
}

export async function deleteLabel(id: number): Promise<{ deleted: boolean }> {
  return request.delete<{ deleted: boolean }>(`/ailab/labels/${id}`)
}

export async function triggerTrain(): Promise<{ accepted: boolean }> {
  return request.post<{ accepted: boolean }>('/ailab/train')
}

export async function getModels(): Promise<AILabModelVersion[]> {
  return request.get<AILabModelVersion[]>('/ailab/models')
}

export async function rollbackModel(version: number): Promise<AILabModelVersion> {
  return request.post<AILabModelVersion>(`/ailab/models/${version}/rollback`)
}

export async function simulate(body: {
  player?: string
  features?: Record<string, number>
}): Promise<AILabSimulateResult> {
  return request.post<AILabSimulateResult>('/ailab/simulate', body)
}

export async function getAILabSettings(): Promise<AILabSettings> {
  return request.get<AILabSettings>('/ailab/settings')
}

export async function updateAILabSettings(body: Partial<AILabSettings>): Promise<AILabSettings> {
  return request.post<AILabSettings>('/ailab/settings', body)
}

export async function getThresholds(): Promise<AILabThresholds> {
  return request.get<AILabThresholds>('/ailab/thresholds')
}

export async function getFeatureDict(): Promise<AILabFeatureDictItem[]> {
  return request.get<AILabFeatureDictItem[]>('/ailab/features')
}
