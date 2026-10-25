import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

/** One saved hourly rate of a worker in a project (docs/architecture/adr/ADR-025-worker-labor-cost.md). */
export interface WorkerLaborRateLineDto {
  rateId: string
  /** Per hour; null is unknown. */
  hourlyRate: number | null
  /** YYYY-MM-DD; null from the start. */
  effectiveFrom: string | null
  reason: string | null
  setBy: string
  setAt: string
  /** past: a later start took over; replaced: saved again for the same start. */
  state: 'current' | 'upcoming' | 'past' | 'replaced' | 'removed'
  removedBy: string | null
  removedAt: string | null
}

export interface WorkerLaborRatesDto {
  projectId: string
  userId: string
  /** The project's date. */
  today: string
  current: WorkerLaborRateLineDto | null
  /** Newest save first, removed ones included. */
  lines: WorkerLaborRateLineDto[]
}

const key = (projectId: string, userId: string) => ['worker-labor-rates', projectId, userId]
const path = (projectId: string, userId: string) =>
  `/projects/${encodeURIComponent(projectId)}/workers/${encodeURIComponent(userId)}/labor-rates`

/** The project owner's only (ADR-025); ask for it only for them. */
export function useWorkerLaborRatesQuery(projectId: string, userId: string, enabled: boolean) {
  return useQuery<WorkerLaborRatesDto>({
    queryKey: key(projectId, userId),
    enabled: enabled && Boolean(projectId) && Boolean(userId),
    retry: false,
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<WorkerLaborRatesDto>>(path(projectId, userId))),
  })
}

/** Each change answers with the rates; labor cost is read again. */
export function useWorkerLaborRateMutations(projectId: string, userId: string) {
  const client = useQueryClient()
  const onSuccess = (rates: WorkerLaborRatesDto) => {
    client.setQueryData(key(projectId, userId), rates)
    void client.invalidateQueries({ queryKey: ['labor-cost'] })
  }
  return {
    set: useMutation({
      mutationFn: async (input: { hourlyRate: number | null; effectiveFrom?: string; reason?: string; fromStart?: boolean }) =>
        unwrapApiResponse(await httpClient.put<ApiEnvelope<WorkerLaborRatesDto>>(path(projectId, userId), input)),
      onSuccess,
    }),
    remove: useMutation({
      mutationFn: async (rateId: string) => unwrapApiResponse(await httpClient.delete<ApiEnvelope<WorkerLaborRatesDto>>(
        `${path(projectId, userId)}/${encodeURIComponent(rateId)}`)),
      onSuccess,
    }),
  }
}
