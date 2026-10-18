import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

/** One process step of a production run (docs/architecture/adr/ADR-011-production-steps.md). */
export interface RunStepDto {
  stepId: string
  nodeId: string
  nodeName: string | null
  sequenceNo: number
  status: string
  /** A step the server passes itself (Exclusive, Join, Split). */
  gateway: boolean
  sourceStepId: string | null
  sourceConnectionId: string | null
  scheduledAt: string | null
  startedAt: string | null
  endedAt: string | null
  errorCode: string | null
  errorMessage: string | null
  inputSnapshot: unknown
  outputSnapshot: unknown
  joinWaiting: number | null
  /** When the latest attempt passes its process's time limit; null without one. */
  timeoutAt?: string | null
  /** When a running step was marked late for passing it; it keeps running (docs/domain/flow-run.md F12). */
  overdueAt?: string | null
  /** The process's required work instruction steps and how many are confirmed. */
  instructionRequired: number
  instructionDone: number
}

const stepsKey = (runId: string) => ['production-run-steps', runId]

export function useRunStepsQuery(runId: string) {
  return useQuery<RunStepDto[]>({
    queryKey: stepsKey(runId),
    queryFn: async () => unwrapApiResponse(
      await httpClient.get<ApiEnvelope<RunStepDto[]>>(`/production-runs/${encodeURIComponent(runId)}/steps`)),
    enabled: Boolean(runId),
  })
}

/** Start and complete a step; the list and the run's checklist are read again either way. */
export function useRunStepActions(runId: string) {
  const queryClient = useQueryClient()
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: stepsKey(runId) })
    void queryClient.invalidateQueries({ queryKey: ['run-instruction', runId] })
  }
  const act = (action: 'start' | 'complete') => async (stepId: string) => unwrapApiResponse(
    await httpClient.post<ApiEnvelope<RunStepDto>>(
      `/production-runs/${encodeURIComponent(runId)}/steps/${encodeURIComponent(stepId)}/${action}`, {}))
  return {
    start: useMutation({ mutationFn: act('start'), onSettled: refresh }),
    complete: useMutation({ mutationFn: act('complete'), onSettled: refresh }),
  }
}
