import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope, RunCorrectionDto, RunCorrectionLineRequest } from '../../../shared/types/api'

const base = (runId: string) => `/production-runs/${encodeURIComponent(runId)}/corrections`

/** Corrections of a finished run, newest first (docs/domain/production-run-correction.md). */
export function useRunCorrectionsQuery(runId: string, enabled = true) {
  return useQuery<RunCorrectionDto[]>({
    queryKey: ['run-corrections', runId],
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<RunCorrectionDto[]>>(base(runId))),
    enabled: Boolean(runId) && enabled,
  })
}

export function useRequestRunCorrectionMutation(runId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (body: { reason: string; lines: RunCorrectionLineRequest[] }) =>
      unwrapApiResponse(await httpClient.post<ApiEnvelope<RunCorrectionDto>>(base(runId), body)),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['run-corrections', runId] })
    },
  })
}

/**
 * Approving applies the correction on the server (stock, recordings, output quantity and LOT genealogy together), so
 * every view of those needs a refresh. Rejecting only changes the correction.
 */
export function useDecideRunCorrectionMutation(runId: string, projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ correctionId, decision, note }: { correctionId: string; decision: 'approve' | 'reject'; note?: string }) =>
      unwrapApiResponse(
        await httpClient.post<ApiEnvelope<RunCorrectionDto>>(
          `${base(runId)}/${encodeURIComponent(correctionId)}/${decision}`,
          decision === 'reject' ? { note } : {},
        ),
      ),
    onSuccess: (_data, { decision }) => {
      void queryClient.invalidateQueries({ queryKey: ['run-corrections', runId] })
      if (decision === 'approve') {
        void queryClient.invalidateQueries({ queryKey: ['production-run', runId] })
        void queryClient.invalidateQueries({ queryKey: ['production-run-items', runId] })
        void queryClient.invalidateQueries({ queryKey: ['inventories', projectId] })
        void queryClient.invalidateQueries({ queryKey: ['lots', projectId] })
        void queryClient.invalidateQueries({ queryKey: ['inventory-transactions'] })
        void queryClient.invalidateQueries({ queryKey: ['work-orders'] })
        void queryClient.invalidateQueries({ queryKey: ['run-setups', runId] })
        void queryClient.invalidateQueries({ queryKey: ['run-equipment-time', runId] })
        void queryClient.invalidateQueries({ queryKey: ['run-labor', runId] })
      }
    },
  })
}
