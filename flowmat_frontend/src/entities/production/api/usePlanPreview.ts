import { useMutation } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

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
}

export interface PlanTarget {
  processIoId: string
  quantity: number
  unit?: string | null
}

export function usePlanPreviewMutation(workflowId: string) {
  return useMutation({
    mutationFn: async (target: PlanTarget) => unwrapApiResponse(await httpClient.post<ApiEnvelope<PlanPreview>>(
      `/workflows/${encodeURIComponent(workflowId)}/plan-previews`, { target })),
  })
}
