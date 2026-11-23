import { useQuery } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

/** One confirmed plan of a workflow, newest first (ADR-043 decision 7). */
export interface PlanConfirmationSummary {
  requestId: string
  revisionId: string
  targetPortId: string | null
  targetConnectionId: string | null
  targetQuantity: number
  workOrderId: string
  purchaseOrderIds: string[]
  confirmedAt: string
}

/** A variance is (actual - planned) / planned in percent; null when there is nothing to compare yet. */
export interface PlanActualMaterial {
  itemId: string
  itemName: string
  unit: string | null
  plannedForTarget: number
  /** The plan for what the finished runs made. */
  planned: number | null
  used: number | null
  variancePercent: number | null
  within: boolean | null
}

export interface PlanActualStep {
  processId: string
  processName: string | null
  plannedForTarget: number | null
  planned: number | null
  actual: number | null
  measured: number
  variancePercent: number | null
  within: boolean | null
}

/** A confirmed plan against its work order's finished runs (docs/domain/plan-vs-actual.md). */
export interface PlanActual {
  workOrderId: string
  workOrderStatus: string
  workflowId: string
  revisionId: string
  confirmedAt: string
  target: number
  targetUnit: string | null
  output: number
  finishedRuns: number
  openRuns: number
  materialTolerancePercent: number
  timeTolerancePercent: number
  materials: PlanActualMaterial[]
  steps: PlanActualStep[]
  complete: boolean
  notes: string[]
}

export const planConfirmationsKey = (workflowId: string) => ['plan-confirmations', workflowId] as const

export function usePlanConfirmationsQuery(workflowId: string) {
  return useQuery<PlanConfirmationSummary[]>({
    queryKey: planConfirmationsKey(workflowId),
    enabled: Boolean(workflowId),
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<PlanConfirmationSummary[]>>(
      `/workflows/${encodeURIComponent(workflowId)}/plan-confirmations`)),
  })
}

export function usePlanActualQuery(workOrderId: string | null) {
  return useQuery<PlanActual>({
    queryKey: ['plan-actuals', workOrderId],
    enabled: Boolean(workOrderId),
    retry: false,
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<PlanActual>>(
      `/work-orders/${encodeURIComponent(workOrderId ?? '')}/plan-actuals`)),
  })
}
