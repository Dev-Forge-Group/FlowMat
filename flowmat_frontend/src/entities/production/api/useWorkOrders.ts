import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope, WorkOrderDto } from '../../../shared/types/api'

/** Editable fields of a draft work order (mirrors WorkOrderCreateRequest / WorkOrderUpdateRequest). */
export interface WorkOrderInput {
  workOrderTitle: string
  workflowId?: string
  targetItemId?: string
  targetQuantity?: number
  priority?: string
  plannedStartAt?: string
  plannedEndAt?: string
  instruction?: string
  assignedTo?: string
  /** BOM revision runs of this order plan their materials from; must be approved before the order is. */
  bomId?: string
  /** Link to the written work instruction (http or https); left out on an update, it is cleared. */
  instructionUrl?: string
  /** Optional BOM materials the order takes, by item (ADR-033); left out on an update, they stay as they are. */
  optionalItemIds?: string[]
}

export type WorkOrderTransition = 'approve' | 'cancel' | 'complete'

async function fetchWorkOrders(projectId: string): Promise<WorkOrderDto[]> {
  return unwrapApiResponse(
    await httpClient.get<ApiEnvelope<WorkOrderDto[]>>(`/work-orders?projectId=${encodeURIComponent(projectId)}`),
  )
}

export function useWorkOrdersQuery(projectId: string) {
  return useQuery<WorkOrderDto[]>({
    queryKey: ['work-orders', projectId],
    queryFn: () => fetchWorkOrders(projectId),
    enabled: Boolean(projectId),
  })
}

function useInvalidateWorkOrders(projectId: string) {
  const queryClient = useQueryClient()
  return () => void queryClient.invalidateQueries({ queryKey: ['work-orders', projectId] })
}

export function useSaveWorkOrderMutation(projectId: string) {
  const onSuccess = useInvalidateWorkOrders(projectId)
  return useMutation({
    mutationFn: async ({ workOrderId, ...input }: WorkOrderInput & { workOrderId?: string }) =>
      unwrapApiResponse(
        workOrderId
          ? await httpClient.put<ApiEnvelope<WorkOrderDto>>(`/work-orders/${encodeURIComponent(workOrderId)}`, input)
          : await httpClient.post<ApiEnvelope<WorkOrderDto>>('/work-orders', { projectId, ...input }),
      ),
    onSuccess,
  })
}

/** approve/cancel need project owner access; complete needs every run of the order to be finished. */
export function useWorkOrderTransitionMutation(projectId: string) {
  const onSuccess = useInvalidateWorkOrders(projectId)
  return useMutation({
    mutationFn: async ({ workOrderId, action }: { workOrderId: string; action: WorkOrderTransition }) =>
      unwrapApiResponse(
        await httpClient.post<ApiEnvelope<WorkOrderDto>>(`/work-orders/${encodeURIComponent(workOrderId)}/${action}`, {}),
      ),
    onSuccess,
  })
}
