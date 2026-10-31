import { useMutation, useQuery } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

/**
 * Suggested planned start and end on the order's equipment (docs/domain/equipment-schedule.md "계획 기간 제안"), or without
 * equipment in its crew's time by its step plan (docs/domain/worker-calendar.md LB25).
 */
export interface WorkOrderPlanSuggestionDto {
  workOrderId: string
  /** Null when the crew's time decided (LB25). */
  equipmentId: string | null
  /** Where the search began. */
  from: string
  plannedStartAt: string
  plannedEndAt: string
  remainingQuantity: number | null
  capacityPerHour: number | null
  /** The equipment's efficiency, null for 100 (docs/domain/equipment.md E8); productionHours is at capacity times this. */
  efficiencyPercent?: number | null
  /** Null without equipment. */
  productionHours: number | null
  changeoverHours: number
  /** The longer of production and labor, plus changeover: the available time between the start and the end. */
  neededHours: number
  /** The order whose item this one changes over from; null without a changeover. */
  changeoverFrom: string | null
  /** Approved or running orders on the same equipment the suggestion was moved after, earliest first. */
  movedPast: string[]
  /** The order's crew and their shift hours in the suggested window (docs/domain/worker-calendar.md WC14). */
  crew?: { userId: string; calendarSet: boolean; hours: number; sharePercent?: number }[]
  /** Crew members with shifts who were not working when the equipment alone could start (worker-calendar.md WC25). */
  waitedForCrew?: string[]
  /** Approved or running orders holding some of the crew that the suggestion was moved after (WC25). */
  crewMovedPast?: { workOrderNumber: string; userIds: string[] }[]
  /** What is left of the step plan in worker minutes, and that shared by the crew with shifts (LB25); null when not known. */
  laborMinutes?: number | null
  laborHours?: number | null
  /** Who works on which process when, for a step plan split among people (LB35); empty or left out otherwise. */
  schedule?: { nodeId: string; nodeName: string | null; userId: string; from: string; to: string }[]
}

/** Suggestions for several orders, in the order asked (docs/domain/equipment-schedule.md P8-P11). Nothing is saved. */
export interface WorkOrderBatchPlanDto {
  /** The requested start of every search; null when each order started from its own default. */
  from: string | null
  lines: {
    workOrderId: string
    workOrderNumber: string
    workOrderStatus: string
    /** Placed after the orders before it in the request; null with a problem. */
    suggestion: WorkOrderPlanSuggestionDto | null
    problem: string | null
  }[]
}

/** Read only on the server; a mutation because the orders go in the body. */
export function useWorkOrderBatchPlanMutation() {
  return useMutation({
    mutationFn: async (body: { projectId: string; workOrderIds: string[]; from: string }) =>
      unwrapApiResponse(await httpClient.post<ApiEnvelope<WorkOrderBatchPlanDto>>('/work-orders/plan-suggestions', body)),
  })
}

/** The order with the fewest changeovers to plan several orders in (docs/domain/equipment-schedule.md P13-P16). */
export interface WorkOrderPlanOrderDto {
  /** Every requested order: each equipment's in the suggested order, then those without equipment as given. */
  workOrderIds: string[]
  changeoverMinutes: number
  /** The changeover minutes of the order as ticked. */
  givenChangeoverMinutes: number
  groups: { equipmentId: string; after: string | null; workOrderIds: string[]; changeoverMinutes: number; givenChangeoverMinutes: number }[]
}

export function useWorkOrderPlanOrderMutation() {
  return useMutation({
    mutationFn: async (body: { projectId: string; workOrderIds: string[]; from?: string }) =>
      unwrapApiResponse(await httpClient.post<ApiEnvelope<WorkOrderPlanOrderDto>>('/work-orders/plan-order', body)),
  })
}

/** Fetched on demand: {@code from} stays null until the user asks, then it is the instant the search starts from. */
export function useWorkOrderPlanSuggestionQuery(workOrderId: string, from: string | null) {
  return useQuery<WorkOrderPlanSuggestionDto>({
    queryKey: ['work-order-plan', workOrderId, from],
    queryFn: async () =>
      unwrapApiResponse(
        await httpClient.get<ApiEnvelope<WorkOrderPlanSuggestionDto>>(
          `/work-orders/${encodeURIComponent(workOrderId)}/plan-suggestion?from=${encodeURIComponent(from ?? '')}`,
        ),
      ),
    enabled: Boolean(workOrderId && from),
    staleTime: 0,
    // A refusal (no capacity, no quantity, not enough time) is an answer, not a hiccup.
    retry: false,
  })
}
