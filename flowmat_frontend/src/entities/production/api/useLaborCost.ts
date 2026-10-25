import { useQuery } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

/** One person's worked time and its known cost (docs/architecture/adr/ADR-025-worker-labor-cost.md). */
export interface LaborCostPerson {
  userId: string
  workedMinutes: number
  /** The known amounts; costComplete is false when some of their time has no rate on its day. */
  cost: number
  costComplete: boolean
}

export interface RunLaborCostDto {
  productionRunId: string
  workedMinutes: number
  cost: number
  costComplete: boolean
  people: LaborCostPerson[]
  lines: {
    runLaborId: string; userId: string; workedMinutes: number; rateDate: string
    /** recorded: the day it was recorded; run_end: added by a correction, the run's original end. */
    rateBasis: 'recorded' | 'run_end'
    hourlyRate: number | null; cost: number | null
  }[]
}

export interface WorkOrderLaborCostDto {
  workOrderId: string
  runs: number
  workedMinutes: number
  cost: number
  costComplete: boolean
  people: LaborCostPerson[]
}

/** The project owner's only (ADR-025); ask for it only for them. */
export function useRunLaborCostQuery(runId: string, enabled: boolean) {
  return useQuery<RunLaborCostDto>({
    queryKey: ['labor-cost', 'run', runId],
    enabled: enabled && Boolean(runId),
    retry: false,
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<RunLaborCostDto>>(
      `/production-runs/${encodeURIComponent(runId)}/labor-cost`)),
  })
}

/** The project owner's only (ADR-025); ask for it only for them. */
export function useWorkOrderLaborCostQuery(workOrderId: string, enabled: boolean) {
  return useQuery<WorkOrderLaborCostDto>({
    queryKey: ['labor-cost', 'work-order', workOrderId],
    enabled: enabled && Boolean(workOrderId),
    retry: false,
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<WorkOrderLaborCostDto>>(
      `/work-orders/${encodeURIComponent(workOrderId)}/labor-cost`)),
  })
}
