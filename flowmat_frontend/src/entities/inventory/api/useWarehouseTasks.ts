import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

export type WarehouseTaskStatus = 'open' | 'done' | 'cancelled'

/** A planned move of part of one stock record to a place (docs/domain/warehouse-task.md). */
export interface WarehouseTaskDto {
  taskId: string
  projectId: string
  taskNo: string
  taskType: 'putaway' | 'pick'
  status: WarehouseTaskStatus
  inventoryId: string
  itemId: string
  itemCode: string | null
  itemName: string | null
  lotId: string | null
  lotNo: string | null
  quantity: number
  fromLocation: string | null
  toLocation: string
  workOrderId: string | null
  workOrderNumber: string | null
  note: string | null
  createdBy: string
  createdAt: string
  finishedBy: string | null
  finishedAt: string | null
  transferId: string | null
  cancelReason: string | null
  /** Who should do the task (docs/domain/warehouse-task.md W7); null while no one is named. */
  assignedTo: string | null
  /** Reserved pick tasks keep this allocation when moved (V54). */
  allocationId?: string | null
}

export interface PutawayInput {
  inventoryId: string
  quantity: number
  toLocation: string
  note: string | null
}

export interface WarehouseTaskCompletionInput {
  taskId: string
  quantity?: number
  expectedToLocation?: string
  /** Remaining task quantity observed before this completion; omitted only by legacy callers. */
  expectedQuantity?: number
}

/** A work order, or lines in each item's stock unit. */
export interface PickListInput {
  stagingLocation: string
  workOrderId?: string
  quantity?: number
  lines?: { itemId: string; quantity: number }[]
  note: string | null
}

export interface PickListLine {
  itemId: string
  itemCode: string
  required: number
  atStaging: number
  alreadyPlanned: number
  plannedNow: number
  shortage: number
}

export interface PickListResult {
  tasks: WarehouseTaskDto[]
  lines: PickListLine[]
}

export const warehouseTasksKey = (projectId: string) => ['warehouse-tasks', projectId] as const

/** Newest first; one status only when given. */
export function useWarehouseTasksQuery(projectId: string, status: WarehouseTaskStatus | null) {
  return useQuery<WarehouseTaskDto[]>({
    queryKey: [...warehouseTasksKey(projectId), status],
    queryFn: async () => {
      const params = new URLSearchParams({ projectId })
      if (status) params.set('status', status)
      return unwrapApiResponse(await httpClient.get<ApiEnvelope<WarehouseTaskDto[]>>(`/warehouse-tasks?${params.toString()}`))
    },
    enabled: Boolean(projectId),
  })
}

/** Planning refreshes the task lists; doing a task moves stock, so the stock views refresh too. */
export function useWarehouseTaskMutations(projectId: string) {
  const queryClient = useQueryClient()
  const refreshTasks = async () => {
    // A first load still in flight may have read the list before this change; TanStack would fold the refetch into it.
    await queryClient.cancelQueries({ queryKey: warehouseTasksKey(projectId) })
    void queryClient.invalidateQueries({ queryKey: warehouseTasksKey(projectId) })
  }
  const refreshStock = async () => {
    await refreshTasks()
    void queryClient.invalidateQueries({ queryKey: ['inventories', projectId] })
    void queryClient.invalidateQueries({ queryKey: ['lots', projectId] })
    void queryClient.invalidateQueries({ queryKey: ['inventory-transactions'] })
    void queryClient.invalidateQueries({ queryKey: ['storage-locations', projectId] })
  }
  const task = (id: string) => `/warehouse-tasks/${encodeURIComponent(id)}`
  const create = useMutation({
    mutationFn: async (input: PutawayInput) =>
      unwrapApiResponse(await httpClient.post<ApiEnvelope<WarehouseTaskDto>>('/warehouse-tasks', { projectId, taskType: 'putaway', ...input })),
    onSuccess: refreshTasks,
  })
  const pickList = useMutation({
    mutationFn: async (input: PickListInput) =>
      unwrapApiResponse(await httpClient.post<ApiEnvelope<PickListResult>>('/warehouse-tasks/pick-list', { projectId, ...input })),
    onSuccess: refreshTasks,
  })
  /** Without a quantity the whole task; with a smaller one only that much moves and the rest stays open. */
  const complete = useMutation({
    mutationFn: async ({ taskId, quantity, expectedToLocation, expectedQuantity }: WarehouseTaskCompletionInput) =>
      unwrapApiResponse(await httpClient.post<ApiEnvelope<WarehouseTaskDto>>(`${task(taskId)}/complete`,
        { quantity, expectedToLocation, expectedQuantity })),
    onSuccess: async (result) => {
      await refreshStock()
      if (result.workOrderId) {
        void queryClient.invalidateQueries({ queryKey: ['work-order-allocations', result.workOrderId] })
        void queryClient.invalidateQueries({ queryKey: ['work-order-readiness', result.workOrderId] })
      }
    },
    // A failed response can still follow a committed move. Reconcile stock as well as the remaining task.
    onError: refreshStock,
  })
  const cancel = useMutation({
    mutationFn: async ({ taskId, reason }: { taskId: string; reason: string }) =>
      unwrapApiResponse(await httpClient.post<ApiEnvelope<WarehouseTaskDto>>(`${task(taskId)}/cancel`, { reason })),
    onSuccess: refreshTasks,
  })
  /** Null or blank leaves the task to no one. */
  const assign = useMutation({
    mutationFn: async ({ taskId, assignedTo }: { taskId: string; assignedTo: string | null }) =>
      unwrapApiResponse(await httpClient.put<ApiEnvelope<WarehouseTaskDto>>(`${task(taskId)}/assignee`, { assignedTo })),
    onSuccess: refreshTasks,
  })
  return { create, pickList, complete, cancel, assign }
}
