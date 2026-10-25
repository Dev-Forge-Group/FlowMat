import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

/** One stretch at work (docs/domain/worker-calendar.md AT1-AT5). */
export interface WorkerAttendanceLineDto {
  attendanceId: string
  /** The project date of the clock-in. */
  workDate: string
  clockIn: string
  clockOut: string | null
  /** Whole minutes; null while open. */
  minutes: number | null
  note: string | null
  recordedBy: string
  recordedAt: string
  cancelled: boolean
  cancelledBy: string | null
  cancelledAt: string | null
  cancelReason: string | null
}

export interface WorkerAttendanceDto {
  projectId: string
  userId: string
  from: string
  to: string
  today: string
  /** The stretch still clocked in, whenever it began. */
  open: WorkerAttendanceLineDto | null
  days: { date: string; attendedMinutes: number }[]
  lines: WorkerAttendanceLineDto[]
}

const key = (projectId: string, userId: string) => ['worker-attendance', projectId, userId]
const path = (projectId: string, userId: string) =>
  `/projects/${encodeURIComponent(projectId)}/workers/${encodeURIComponent(userId)}/attendance`

/** The worker's own and the project owner's only (ADR-026); the last 7 project days. */
export function useWorkerAttendanceQuery(projectId: string, userId: string, enabled: boolean) {
  return useQuery<WorkerAttendanceDto>({
    queryKey: key(projectId, userId),
    enabled: enabled && Boolean(projectId) && Boolean(userId),
    retry: false,
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<WorkerAttendanceDto>>(path(projectId, userId))),
  })
}

/** The worker's attendance over a period of project days, at most 62 (AT5), read once for a download (AT9). */
export async function fetchWorkerAttendance(projectId: string, userId: string, from: string, to: string): Promise<WorkerAttendanceDto> {
  return unwrapApiResponse(await httpClient.get<ApiEnvelope<WorkerAttendanceDto>>(
    `${path(projectId, userId)}?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`))
}

/** Each change answers with the attendance; the comparison is read again. */
export function useWorkerAttendanceMutations(projectId: string, userId: string) {
  const client = useQueryClient()
  const onSuccess = (attendance: WorkerAttendanceDto) => {
    client.setQueryData(key(projectId, userId), attendance)
    void client.invalidateQueries({ queryKey: ['attendance-comparison', projectId, userId] })
  }
  return {
    record: useMutation({
      mutationFn: async (input: { requestId: string; clockIn: string; clockOut?: string; note?: string }) =>
        unwrapApiResponse(await httpClient.post<ApiEnvelope<WorkerAttendanceDto>>(path(projectId, userId), input)),
      onSuccess,
    }),
    clockOut: useMutation({
      mutationFn: async ({ attendanceId, clockOut }: { attendanceId: string; clockOut?: string }) =>
        unwrapApiResponse(await httpClient.post<ApiEnvelope<WorkerAttendanceDto>>(
          `${path(projectId, userId)}/${encodeURIComponent(attendanceId)}/clock-out`, clockOut ? { clockOut } : {})),
      onSuccess,
    }),
    cancel: useMutation({
      mutationFn: async ({ attendanceId, reason }: { attendanceId: string; reason: string }) =>
        unwrapApiResponse(await httpClient.post<ApiEnvelope<WorkerAttendanceDto>>(
          `${path(projectId, userId)}/${encodeURIComponent(attendanceId)}/cancel`, { reason })),
      onSuccess,
    }),
  }
}
