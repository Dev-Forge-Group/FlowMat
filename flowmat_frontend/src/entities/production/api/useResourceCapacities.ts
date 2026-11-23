import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

/** How much energy or water the factory can draw per hour or per day (docs/architecture/adr/ADR-046-resources-first-class.md). */
export interface ResourceCapacity {
  resourceType: 'energy' | 'water'
  unit: string
  amount: number
  per: 'hour' | 'day'
  updatedBy?: string | null
  updatedAt?: string | null
}

export function useResourceCapacities(projectId: string) {
  const client = useQueryClient()
  const key = ['resource-capacities', projectId]
  const path = `/projects/${encodeURIComponent(projectId)}/resource-capacities`
  const query = useQuery<ResourceCapacity[]>({
    queryKey: key,
    enabled: Boolean(projectId),
    retry: false,
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<ResourceCapacity[]>>(path)),
  })
  // The whole list is replaced; a lost reply is safe to send again.
  const save = useMutation({
    retry: false,
    mutationFn: async (capacities: Pick<ResourceCapacity, 'resourceType' | 'unit' | 'amount' | 'per'>[]) =>
      unwrapApiResponse(await httpClient.put<ApiEnvelope<ResourceCapacity[]>>(path, { capacities })),
    onSuccess: (saved) => client.setQueryData(key, saved),
  })
  return { query, save }
}
