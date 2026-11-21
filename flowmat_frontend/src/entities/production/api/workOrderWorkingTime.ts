import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

/**
 * When a work order's equipment and its full-share crew with shifts work together in a window
 * (docs/domain/worker-calendar.md WC68, docs/architecture/adr/ADR-041-try-it-time.md decision 5): what its production runs
 * wait for and count working-time delays by. Times are ISO with the offset of `from`.
 */
export interface WorkOrderWorkingTimeDto {
  workOrderId: string
  from: string
  until: string
  /** Nothing fixes the time: every delay counts all time and no step waits. */
  allTime: boolean
  equipmentId: string | null
  equipmentCode: string | null
  crew: string[]
  stretches: [string, string][]
}

/** The work order's working time from `from` (ISO with an offset) for seven days, or until `until`. */
export async function fetchWorkOrderWorkingTime(workOrderId: string, from: string, until?: string): Promise<WorkOrderWorkingTimeDto> {
  const query = new URLSearchParams({ from })
  if (until) query.set('until', until)
  return unwrapApiResponse(await httpClient.get<ApiEnvelope<WorkOrderWorkingTimeDto>>(
    `/work-orders/${encodeURIComponent(workOrderId)}/working-time?${query}`))
}
