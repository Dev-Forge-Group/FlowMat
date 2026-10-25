import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

export interface CountPlanLine {
  lineId: string; inventoryId: string; itemId: string; lotId: string | null; location: string | null
  baselineQuantity: number | null; checkpointQuantity: number | null; countedQuantity: number | null
  requiresRecount: boolean; countedBy: string | null; countedAt: string | null; entryVersion: number
}
export interface CountPlan {
  planId: string; projectId: string; blind: boolean; status: 'open' | 'recount_required' | 'submitted'
  note: string | null; createdBy: string; createdAt: string; submittedBy: string | null
  submittedAt: string | null; countId: string | null; lines: CountPlanLine[]
  /** Who should count it (docs/domain/stock-count.md P9). */
  assignedTo?: string | null
}
export type CountPlanCommand =
  | { kind: 'create'; input: { projectId: string; requestId: string; blind: boolean; note: string; inventoryIds: string[] } }
  | { kind: 'record'; planId: string; lineId: string; input: { countedQuantity: number; expectedEntryVersion: number } }
  | { kind: 'recount'; planId: string; lineId: string }
  | { kind: 'submit'; planId: string }
  | { kind: 'assign'; planId: string; input: { assignedTo: string | null } }

export const countPlansKey = (projectId: string) => ['count-plans', projectId]
export function useInventoryCountPlans(projectId: string) {
  return useQuery({ queryKey: countPlansKey(projectId), enabled: Boolean(projectId),
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<CountPlan[]>>(
      `/inventory-count-plans?projectId=${encodeURIComponent(projectId)}`)) })
}
export function useCountPlanCommand(projectId: string) {
  const cache = useQueryClient()
  return useMutation({
    mutationFn: async (command: CountPlanCommand) => {
      const path = '/inventory-count-plans'
      const response = command.kind === 'create'
        ? await httpClient.post<ApiEnvelope<CountPlan>>(path, command.input)
        : command.kind === 'record'
          ? await httpClient.put<ApiEnvelope<CountPlan>>(`${path}/${command.planId}/lines/${command.lineId}`, command.input)
          : command.kind === 'assign'
            ? await httpClient.post<ApiEnvelope<CountPlan>>(`${path}/${command.planId}/assignee`, command.input)
            : await httpClient.post<ApiEnvelope<CountPlan>>(`${path}/${command.planId}/${command.kind === 'submit' ? 'submit' : `lines/${command.lineId}/recount`}`, {})
      return unwrapApiResponse(response)
    },
    onSuccess: (plan) => {
      cache.setQueryData<CountPlan[]>(countPlansKey(projectId), (previous) =>
        [plan, ...(previous ?? []).filter((entry) => entry.planId !== plan.planId)])
      void cache.invalidateQueries({ queryKey: countPlansKey(projectId) })
      if (plan.status === 'submitted') {
        void cache.invalidateQueries({ queryKey: ['inventories', projectId] })
        void cache.invalidateQueries({ queryKey: ['inventory-transactions'] })
        void cache.invalidateQueries({ queryKey: ['lots', projectId] })
      }
    },
    onError: () => { void cache.invalidateQueries({ queryKey: countPlansKey(projectId) }) },
  })
}
