import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

/** What running a node takes (docs/architecture/adr/ADR-043-plan-backwards-from-a-target.md decision 4). */
export type RequirementKind = 'equipment' | 'crew' | 'skill' | 'compute' | 'time'
export type RequirementPer = 'batch' | 'output_unit'

export interface ProcessRequirementDto {
  processRequirementId: string
  processId: string
  kind: RequirementKind
  ref: string | null
  amount: number
  unit: string | null
  per: RequirementPer
}

export type ProcessRequirementInput = Pick<ProcessRequirementDto, 'kind' | 'ref' | 'amount' | 'unit' | 'per'>

const key = (processId: string) => ['process-requirements', processId] as const
const path = (processId: string) => `/processes/${encodeURIComponent(processId)}/requirements`

export function useProcessRequirementsQuery(processId: string) {
  return useQuery<ProcessRequirementDto[]>({
    queryKey: key(processId),
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<ProcessRequirementDto[]>>(path(processId))),
    enabled: Boolean(processId),
  })
}

/** Replaces the node's whole list; a refusal reads it again. */
export function useSaveProcessRequirementsMutation(processId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (requirements: ProcessRequirementInput[]) =>
      unwrapApiResponse(await httpClient.put<ApiEnvelope<ProcessRequirementDto[]>>(path(processId), { requirements })),
    onSuccess: (saved) => queryClient.setQueryData(key(processId), saved),
    onError: () => void queryClient.invalidateQueries({ queryKey: key(processId) }),
  })
}
