import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

export type StorageConditionPolicy = 'warn' | 'refuse'

/** A project's inventory settings (docs/domain/storage-location.md SL5-SL7); version is null while it has none. */
export interface InventorySettingDto {
  projectId: string
  storageConditionPolicy: StorageConditionPolicy
  version: number | null
  updatedBy: string | null
  updatedAt: string | null
}

const key = (projectId: string) => ['inventory-settings', projectId]

export function useInventorySettingsQuery(projectId: string) {
  return useQuery<InventorySettingDto>({
    queryKey: key(projectId),
    enabled: Boolean(projectId),
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<InventorySettingDto>>(
      `/inventory-settings?projectId=${encodeURIComponent(projectId)}`)),
  })
}

/** Only the project owner changes them, from the version the form was opened at. */
export function useSaveInventorySettingsMutation(projectId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: { storageConditionPolicy: StorageConditionPolicy; expectedVersion: number | null }) =>
      unwrapApiResponse(await httpClient.put<ApiEnvelope<InventorySettingDto>>('/inventory-settings', { projectId, ...input })),
    onSuccess: () => client.invalidateQueries({ queryKey: key(projectId) }),
  })
}
