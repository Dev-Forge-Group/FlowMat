import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

/** One person's time on a run (docs/domain/worker-calendar.md LB4). */
export type RunLaborPerson = {
  userId: string
  /** Their share in the work order's crew; null outside it. */
  sharePercent: number | null
  workedMinutes: number
  /** For the crew: their share of their working time during the run less what counts; null when nothing is left. */
  suggestedMinutes: number | null
  /** shifts, or run when they have no shifts; null outside the crew. */
  suggestedFrom: 'shifts' | 'run' | null
}

/** A process step of the run, with the worked minutes that count on it (docs/domain/worker-calendar.md LB15). */
export type RunLaborStep = {
  stepId: string
  nodeName: string | null
  sequenceNo: number | null
  status: string
  startedAt: string | null
  endedAt: string | null
  workedMinutes: number
}

/** Who worked on a run and for how long; no cost, as there are no worker rates (LB4-LB5). */
export type RunLabor = {
  productionRunId: string
  workedMinutes: number
  /** The work order's crew in its order, then anyone else with records. */
  people: RunLaborPerson[]
  lines: {
    runLaborId: string; userId: string; workedMinutes: number; note: string | null; recordedBy: string; recordedAt: string
    cancelled: boolean; cancelledBy: string | null; cancelledAt: string | null; cancelReason: string | null
    /** The process step it was worked on; null or left out for the whole run (LB13). */
    stepId?: string | null; stepName?: string | null
    /** When it started and ended; null or left out when only its minutes were recorded (LB31). */
    workedFrom?: string | null; workedTo?: string | null
  }[]
  /** The run's process steps in order; older answers leave it out. */
  steps?: RunLaborStep[]
}

export type RunLaborInput = {
  requestId: string; userId?: string; workedMinutes: number; note?: string; stepId?: string
  /** Its clock times, both or neither (LB31). */
  workedFrom?: string; workedTo?: string
}

const key = (runId: string) => ['run-labor', runId]
const path = (runId: string) => `/production-runs/${encodeURIComponent(runId)}/labor`

export function useRunLaborQuery(runId: string) {
  return useQuery<RunLabor>({
    queryKey: key(runId),
    enabled: Boolean(runId),
    retry: false,
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<RunLabor>>(path(runId))),
  })
}

/** Records worked time; the caller keeps the requestId for a retry of the same input after a lost reply (LB2). */
export function useRecordRunLaborMutation(runId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: RunLaborInput) => unwrapApiResponse(await httpClient.post<ApiEnvelope<RunLabor>>(path(runId), input)),
    onSuccess: (result) => {
      client.setQueryData(key(runId), result)
      void client.invalidateQueries({ queryKey: ['labor-cost'] })
    },
  })
}

export function useCancelRunLaborMutation(runId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async ({ runLaborId, reason }: { runLaborId: string; reason: string }) =>
      unwrapApiResponse(await httpClient.post<ApiEnvelope<RunLabor>>(
        `${path(runId)}/${encodeURIComponent(runLaborId)}/cancel`, { reason })),
    onSuccess: (result) => {
      client.setQueryData(key(runId), result)
      void client.invalidateQueries({ queryKey: ['labor-cost'] })
    },
  })
}
