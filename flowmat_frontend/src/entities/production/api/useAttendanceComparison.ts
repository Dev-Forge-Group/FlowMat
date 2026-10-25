import { useQuery } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

/** A worker's attendance beside the worked time recorded on runs (docs/domain/worker-calendar.md AT7). */
export interface AttendanceComparisonDto {
  projectId: string
  userId: string
  from: string
  to: string
  attendedMinutes: number
  laborMinutes: number
  /** Every date of the range; differenceMinutes is attended less recorded. */
  days: { date: string; attendedMinutes: number; laborMinutes: number; differenceMinutes: number }[]
}

/** The worker's own and the project owner's only (ADR-026); the last 7 project days. */
export function useAttendanceComparisonQuery(projectId: string, userId: string, enabled: boolean) {
  return useQuery<AttendanceComparisonDto>({
    queryKey: ['attendance-comparison', projectId, userId],
    enabled: enabled && Boolean(projectId) && Boolean(userId),
    retry: false,
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<AttendanceComparisonDto>>(
      `/projects/${encodeURIComponent(projectId)}/workers/${encodeURIComponent(userId)}/attendance-comparison`)),
  })
}
