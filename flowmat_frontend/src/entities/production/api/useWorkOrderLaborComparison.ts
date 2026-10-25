import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope, ProcessDto } from '../../../shared/types/api'

/** A work order's planned crew time beside what its runs recorded for each person (docs/domain/worker-calendar.md LB6). */
export interface WorkOrderLaborComparisonDto {
  workOrderId: string
  /** The crew's known planned minutes; null when no one's is known. */
  plannedMinutes: number | null
  /** False when someone in the crew has no known planned time. */
  plannedComplete: boolean
  runs: number
  actualMinutes: number
  /** The crew in its order, then anyone else with records (sharePercent null). */
  people: { userId: string; sharePercent: number | null; plannedMinutes: number | null; actualMinutes: number }[]
  /**
   * Actual minutes per process across the order's runs, in process order, then the processes planned but not run yet (LB18, LB23);
   * older answers leave it out.
   */
  steps?: { nodeId: string; nodeName: string | null; sequenceNo: number | null; actualMinutes: number; plannedMinutes?: number | null }[]
  /** Actual minutes that name no step. */
  wholeRunMinutes?: number
  /** The step plan added up (LB23); null without one. */
  stepPlannedMinutes?: number | null
}

/** The work order's planned worker minutes by process, sent whole (LB23); it answers with the comparison. */
export function useSaveStepPlanMutation(workOrderId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (steps: { nodeId: string; plannedMinutes: number }[]) =>
      unwrapApiResponse(await httpClient.put<ApiEnvelope<WorkOrderLaborComparisonDto>>(
        `/work-orders/${encodeURIComponent(workOrderId)}/step-plan`, { steps })),
    onSuccess: (comparison) => client.setQueryData(['work-orders', workOrderId, 'labor-comparison'], comparison),
  })
}

/** The processes of the order's workflow, read when the step plan opens (LB24). */
export function useWorkflowStepsQuery(workflowId: string | undefined, enabled: boolean) {
  return useQuery<ProcessDto[]>({
    queryKey: ['step-plan-processes', workflowId],
    enabled: enabled && Boolean(workflowId),
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<ProcessDto[]>>(
      `/processes?workflowId=${encodeURIComponent(workflowId ?? '')}`)),
  })
}

export function useWorkOrderLaborComparisonQuery(workOrderId: string) {
  return useQuery<WorkOrderLaborComparisonDto>({
    queryKey: ['work-orders', workOrderId, 'labor-comparison'],
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<WorkOrderLaborComparisonDto>>(
      `/work-orders/${encodeURIComponent(workOrderId)}/labor-comparison`)),
    enabled: Boolean(workOrderId),
    retry: false,
    staleTime: 0,
  })
}
