import { useMutation, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'
import { planConfirmationsKey } from './usePlanActuals'

/** A plan for a target, read only (docs/architecture/adr/ADR-043-plan-backwards-from-a-target.md decisions 5-7). */
export interface PlanTraceStep {
  at: string
  processId: string | null
  rule: string
  from: number | null
  to: number
  factor: number | null
}

export interface PlanRequirement {
  at: string
  processId: string
  itemId: string | null
  itemName: string | null
  needed: number
  available: number | null
  shortage: number | null
  unit: string | null
  trace: PlanTraceStep[]
}

export interface PlanNode {
  processId: string
  batches: number | null
  through: number | null
  unit: string | null
  minutes: number | null
  /** When it runs, in its equipment's working time; absent before the plan placed steps in time. */
  startAt?: string | null
  endAt?: string | null
}

export interface PlanLimit {
  kind: string
  at: string
  processId: string | null
  message: string
}

export interface PlanPreview {
  revisionId: string
  target: number
  targetUnit: string | null
  feasible: boolean
  buildable: number | null
  requirements: PlanRequirement[]
  nodes: PlanNode[]
  limits: PlanLimit[]
  assumptions: { kind: string; at: string; value: string }[]
  basis: { revisionId: string; available: Record<string, number> }
  /** When the last step ends; null when some equipment has too little working time in a year. */
  finishAt?: string | null
}

export interface PlanTarget {
  processIoId: string
  quantity: number
  unit?: string | null
}

export interface PlanConfirmation {
  requestId: string
  revisionId: string
  workOrderId: string
  purchaseOrderIds: string[]
}

/** Confirms a plan seen before into a draft work order and a draft purchase order for its shortages; safe to send again. */
export function usePlanConfirmMutation(workflowId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (input: { target: PlanTarget; basis: PlanPreview['basis']; requestId: string; supplierName?: string | null }) =>
      unwrapApiResponse(await httpClient.post<ApiEnvelope<PlanConfirmation>>(
        `/workflows/${encodeURIComponent(workflowId)}/plan-confirmations`, input)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: planConfirmationsKey(workflowId) }),
  })
}

export function usePlanPreviewMutation(workflowId: string) {
  return useMutation({
    mutationFn: async (target: PlanTarget) => unwrapApiResponse(await httpClient.post<ApiEnvelope<PlanPreview>>(
      `/workflows/${encodeURIComponent(workflowId)}/plan-previews`, { target })),
  })
}
