import { useQuery } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

/** One work order on the board: what it needs and how much of that falls in the window. */
export interface EquipmentLoadOrderDto {
  workOrderId: string
  workOrderNumber: string
  workOrderTitle: string
  workOrderStatus: string
  targetItemId: string | null
  targetItemCode: string | null
  plannedStartAt: string
  plannedEndAt: string
  remainingQuantity: number | null
  /** Changeover from the order before it on the equipment. */
  changeoverMinutes: number | null
  /** Production time plus changeover; null without a quantity or a capacity per hour. */
  neededHours: number | null
  hoursInWindow: number | null
  /** Whole-order setup at the current hourly rate, separate from actual and material cost. */
  setupCostEstimate?: number | null
}

export interface EquipmentLoadRowDto {
  equipmentId: string
  equipmentCode: string | null
  equipmentName: string
  equipmentStatus: string
  capacityPerHour: number | null
  /** Null for 100 (docs/domain/equipment.md E8); needed hours are at capacity times this. */
  efficiencyPercent?: number | null
  /** Runs several work orders at once (docs/domain/equipment.md E12): never overloaded, and overlaps do not clash. */
  concurrentWorkOrders?: boolean
  /** At most this many side by side (E16, E18): load is over the hours times this, overlaps clash above it. */
  concurrentLimit?: number | null
  calendarSet: boolean
  availableHours: number
  downtimeHours: number
  /** Approved and running orders. */
  plannedHours: number
  draftHours: number
  /** Null when the equipment has no time in the window. */
  loadPercent: number | null
  overloaded: boolean
  unplannedOrders: number
  unmeasuredOrders: number
  orders: EquipmentLoadOrderDto[]
  /**
   * The window's changeovers in planned order and an order of the same work orders with fewer (docs/domain/equipment-load.md
   * "전환 순서 제안"); null without two orders with items or any changeover time.
   */
  changeovers?: { plannedMinutes: number; suggestedMinutes: number | null; suggestedOrder: string[] | null } | null
}

/** Each equipment's available hours against what its work orders need (docs/domain/equipment-load.md). */
export interface EquipmentLoadDto {
  from: string
  to: string
  equipment: EquipmentLoadRowDto[]
}

export function useEquipmentLoadQuery(projectId: string, from: string, to: string, enabled: boolean) {
  return useQuery<EquipmentLoadDto>({
    queryKey: ['equipment-load', projectId, from, to],
    queryFn: async () =>
      unwrapApiResponse(
        await httpClient.get<ApiEnvelope<EquipmentLoadDto>>(
          `/equipment-load?projectId=${encodeURIComponent(projectId)}&from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
        ),
      ),
    enabled: Boolean(projectId) && enabled,
    staleTime: 0,
  })
}
