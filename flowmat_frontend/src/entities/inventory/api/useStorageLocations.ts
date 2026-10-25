import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse, unwrapApiVoidResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

export type StorageLocationType = 'site' | 'warehouse' | 'zone' | 'location' | 'bin'

/** A place the project keeps stock (docs/domain/storage-location.md). */
export interface StorageLocationDto {
  locationId: string
  projectId: string
  parentLocationId: string | null
  locationCode: string
  locationName: string | null
  locationType: StorageLocationType
  active: boolean
  note: string | null
  /** The codes from the top-level place down to this one, joined with " / ". */
  path: string
  depth: number
  /** Stock records here that hold stock (on hand or reserved), and their different items. */
  stockRecords: number
  itemCount: number
  /** How the place keeps stock, free text; places inside without one take the nearest outer place's (SL1). */
  storageCondition?: string | null
}

export interface StorageLocationCreateInput {
  projectId: string
  locationCode: string
  locationName: string | null
  locationType: StorageLocationType
  parentLocationId: string | null
  note: string | null
  storageCondition: string | null
}

/** Only the fields given change; an empty name or note clears it. */
export interface StorageLocationUpdateInput {
  locationCode?: string
  locationName?: string
  locationType?: StorageLocationType
  parentLocationId?: string
  clearParent?: boolean
  active?: boolean
  note?: string
  /** An empty one clears it. */
  storageCondition?: string
}

export const storageLocationsKey = (projectId: string) => ['storage-locations', projectId] as const

export function useStorageLocationsQuery(projectId: string) {
  return useQuery<StorageLocationDto[]>({
    queryKey: storageLocationsKey(projectId),
    queryFn: async () =>
      unwrapApiResponse(
        await httpClient.get<ApiEnvelope<StorageLocationDto[]>>(`/storage-locations?projectId=${encodeURIComponent(projectId)}`),
      ),
    enabled: Boolean(projectId),
  })
}

/** Adding, changing, switching off, deleting and adopting places; each refreshes the list. */
export function useStorageLocationMutations(projectId: string) {
  const queryClient = useQueryClient()
  const refresh = async (queryKeys: readonly (readonly unknown[])[]) => {
    // Cancel reads started before the change before refetching, including stock and tasks moved by a rename.
    await Promise.all(queryKeys.map((queryKey) => queryClient.cancelQueries({ queryKey })))
    await Promise.all(queryKeys.map((queryKey) => queryClient.invalidateQueries({ queryKey })))
  }
  const onSuccess = () => refresh([storageLocationsKey(projectId)])
  const create = useMutation({
    mutationFn: async (input: StorageLocationCreateInput) =>
      unwrapApiResponse(await httpClient.post<ApiEnvelope<StorageLocationDto>>('/storage-locations', input)),
    onSuccess,
  })
  const update = useMutation({
    mutationFn: async ({ locationId, input }: { locationId: string; input: StorageLocationUpdateInput }) =>
      unwrapApiResponse(
        await httpClient.put<ApiEnvelope<StorageLocationDto>>(`/storage-locations/${encodeURIComponent(locationId)}`, input),
      ),
    onSuccess: () => refresh([
      storageLocationsKey(projectId), ['inventories', projectId], ['warehouse-tasks', projectId], ['inventory-transactions'],
    ]),
  })
  const toggle = useMutation({
    mutationFn: async ({ locationId, active }: { locationId: string; active: boolean }) =>
      unwrapApiResponse(
        await httpClient.put<ApiEnvelope<StorageLocationDto>>(`/storage-locations/${encodeURIComponent(locationId)}`, { active }),
      ),
    onSuccess,
  })
  const remove = useMutation({
    mutationFn: async (locationId: string) =>
      unwrapApiVoidResponse(await httpClient.delete<ApiEnvelope<null>>(`/storage-locations/${encodeURIComponent(locationId)}`)),
    onSuccess,
  })
  const adopt = useMutation({
    mutationFn: async () =>
      unwrapApiResponse(
        await httpClient.post<ApiEnvelope<StorageLocationDto[]>>(
          `/storage-locations/adopt-used?projectId=${encodeURIComponent(projectId)}`,
          {},
        ),
      ),
    onSuccess,
  })
  return { create, update, toggle, remove, adopt }
}
