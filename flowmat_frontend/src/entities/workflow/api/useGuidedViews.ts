import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse, unwrapApiVoidResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

/** One chapter of a workflow's guided views (GET /workflows/{id}/guided-views, docs/architecture/adr/ADR-037). */
export interface GuidedViewDto {
  guidedViewId: string
  workflowId: string
  sortOrder: number
  label: string
  note: string | null
  /** The processes it shows that the workflow still has, in reading order. */
  focusProcessIds: string[]
  version: number
}

export interface GuidedViewInput {
  label: string
  note: string | null
  focusProcessIds: string[]
}

const key = (workflowId: string) => ['guided-views', workflowId] as const
const path = (workflowId: string) => `/workflows/${encodeURIComponent(workflowId)}/guided-views`

export function useGuidedViewsQuery(workflowId: string) {
  return useQuery({
    queryKey: key(workflowId),
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<GuidedViewDto[]>>(path(workflowId))),
    enabled: Boolean(workflowId),
  })
}

/** Making, changing, ordering and deleting chapters; each refreshes the list. A change sends the version it was made on. */
export function useGuidedViewMutations(workflowId: string) {
  const queryClient = useQueryClient()
  const onSuccess = () => queryClient.invalidateQueries({ queryKey: key(workflowId) })
  const create = useMutation({
    mutationFn: async (input: GuidedViewInput) =>
      unwrapApiResponse(await httpClient.post<ApiEnvelope<GuidedViewDto>>(path(workflowId), input)),
    onSuccess,
  })
  const update = useMutation({
    mutationFn: async ({ guidedViewId, ...input }: GuidedViewInput & { guidedViewId: string; version: number }) =>
      unwrapApiResponse(await httpClient.put<ApiEnvelope<GuidedViewDto>>(
        `${path(workflowId)}/${encodeURIComponent(guidedViewId)}`, input)),
    onSuccess,
  })
  const remove = useMutation({
    mutationFn: async (guidedViewId: string) =>
      unwrapApiVoidResponse(await httpClient.delete<ApiEnvelope<null>>(`${path(workflowId)}/${encodeURIComponent(guidedViewId)}`)),
    onSuccess,
  })
  const reorder = useMutation({
    mutationFn: async (guidedViewIds: string[]) =>
      unwrapApiResponse(await httpClient.put<ApiEnvelope<GuidedViewDto[]>>(`${path(workflowId)}/order`, { guidedViewIds })),
    onSuccess,
  })
  return { create, update, remove, reorder }
}
