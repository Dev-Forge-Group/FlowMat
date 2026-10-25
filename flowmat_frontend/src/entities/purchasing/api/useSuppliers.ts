import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

/** Someone a project buys from (docs/domain/purchasing.md PO18-PO21). */
export interface SupplierDto {
  supplierId: string
  projectId: string
  supplierName: string
  contact: string | null
  leadTimeDays: number | null
  note: string | null
  active: boolean
  version: number
  createdBy: string
  createdAt: string
  updatedBy: string | null
  updatedAt: string | null
}

export interface SupplierInput {
  supplierName: string
  contact: string | null
  leadTimeDays: number | null
  note: string | null
}

/** The supplier an item is usually bought from (docs/domain/purchasing.md PO29-PO31). */
export interface SupplierItemDto {
  supplierItemId: string
  projectId: string
  itemId: string
  itemCode: string
  supplierId: string
  supplierName: string
  supplierActive: boolean
  supplierItemCode: string | null
  /** The supplier's price per stock unit (PO38). */
  unitPrice?: number | null
  version: number
  updatedBy: string
  updatedAt: string
}

/** Set or, without a supplier, cleared; from the version the form was opened at (null when the item had none). */
export interface SupplierItemInput {
  projectId: string
  itemId: string
  supplierId: string | null
  supplierItemCode: string | null
  unitPrice: number | null
  expectedVersion: number | null
}

const key = (projectId: string) => ['suppliers', projectId]
const itemsKey = (projectId: string) => ['supplier-items', projectId]

export function useSupplierItemsQuery(projectId: string) {
  return useQuery<SupplierItemDto[]>({
    queryKey: itemsKey(projectId),
    enabled: Boolean(projectId),
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<SupplierItemDto[]>>(
      `/supplier-items?projectId=${encodeURIComponent(projectId)}`)),
  })
}

export function useSetSupplierItemMutation(projectId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: SupplierItemInput) =>
      unwrapApiResponse(await httpClient.put<ApiEnvelope<SupplierItemDto | null>>('/supplier-items', input)),
    onSuccess: () => client.invalidateQueries({ queryKey: itemsKey(projectId) }),
  })
}

export function useSuppliersQuery(projectId: string) {
  return useQuery<SupplierDto[]>({
    queryKey: key(projectId),
    enabled: Boolean(projectId),
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<SupplierDto[]>>(
      `/suppliers?projectId=${encodeURIComponent(projectId)}`)),
  })
}

export function useSupplierMutations(projectId: string) {
  const client = useQueryClient()
  const refresh = () => client.invalidateQueries({ queryKey: key(projectId) })
  return {
    create: useMutation({
      mutationFn: async (input: SupplierInput) =>
        unwrapApiResponse(await httpClient.post<ApiEnvelope<SupplierDto>>('/suppliers', { projectId, ...input })),
      onSuccess: refresh,
    }),
    /** Sent whole, from the version the form was opened at; a supplier changed meanwhile is refused (PO19). */
    update: useMutation({
      mutationFn: async ({ supplierId, input, active, expectedVersion }:
        { supplierId: string; input: SupplierInput; active: boolean; expectedVersion: number }) =>
        unwrapApiResponse(await httpClient.put<ApiEnvelope<SupplierDto>>(`/suppliers/${encodeURIComponent(supplierId)}`,
          { ...input, active, expectedVersion })),
      onSuccess: refresh,
    }),
  }
}
