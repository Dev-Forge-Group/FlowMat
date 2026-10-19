import { useQuery } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

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
  /** Actual minutes per process across the order's runs, in process order (LB18); older answers leave it out. */
  steps?: { nodeId: string; nodeName: string | null; sequenceNo: number | null; actualMinutes: number }[]
  /** Actual minutes that name no step. */
  wholeRunMinutes?: number
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
