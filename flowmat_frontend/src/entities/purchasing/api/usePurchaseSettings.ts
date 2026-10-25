import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

/** A project's purchasing settings (docs/domain/purchasing.md PO33); version is null while the project has none. */
export interface PurchaseSettingDto {
  projectId: string
  approvalLimit: number | null
  version: number | null
  updatedBy: string | null
  updatedAt: string | null
  /** The members who approve from a value up, lowest first; canApprove false once they cannot edit the project (PO50). */
  approvalSteps?: { fromValue: number; approverId: string; canApprove: boolean }[]
}

const key = (projectId: string) => ['purchase-settings', projectId]

export function usePurchaseSettingsQuery(projectId: string) {
  return useQuery<PurchaseSettingDto>({
    queryKey: key(projectId),
    enabled: Boolean(projectId),
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<PurchaseSettingDto>>(
      `/purchase-settings?projectId=${encodeURIComponent(projectId)}`)),
  })
}

/** Only the project owner changes them, from the version the form was opened at. */
export function useSavePurchaseSettingsMutation(projectId: string) {
  const client = useQueryClient()
  return useMutation({
    // Steps left out stay as they are (PO50).
    mutationFn: async (input: { approvalLimit: number | null; expectedVersion: number | null; approvalSteps?: { fromValue: number; approverId: string }[] }) =>
      unwrapApiResponse(await httpClient.put<ApiEnvelope<PurchaseSettingDto>>('/purchase-settings', { projectId, ...input })),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: key(projectId) })
      // Whether drafts need approval follows the limit.
      void client.invalidateQueries({ queryKey: ['purchase-orders', projectId] })
    },
  })
}
