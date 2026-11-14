import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import { createStockCommand } from '../../inventory/api/stockCommand'
import type { ApiEnvelope, ProductionRunItemDto } from '../../../shared/types/api'

export interface RecordRunItemInput {
  productionRunId: string
  itemId: string
  direction: 'input' | 'output'
  plannedQty: number
  actualQty?: number
  unit: string
  inventoryId?: string
  processId?: string
  processIoId?: string
}

async function recordRunItem({ productionRunId, ...payload }: RecordRunItemInput & { requestId: string }): Promise<ProductionRunItemDto> {
  const envelope = await httpClient.post<ApiEnvelope<ProductionRunItemDto>>(
    `/production-runs/${encodeURIComponent(productionRunId)}/items`,
    payload,
  )
  return unwrapApiResponse(envelope)
}

/**
 * Records an input or output of a run. A recording whose reply was lost is sent again with the same request key, so the
 * server answers with what it already recorded instead of moving the stock twice (docs/domain/production-run-recording.md).
 */
export function useRecordRunItemMutation(productionRunId: string, projectId: string) {
  const queryClient = useQueryClient()
  const [command] = useState(() => createStockCommand(recordRunItem))
  return useMutation({
    mutationFn: command,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['production-run-items', productionRunId] })
      // Linked inventory rows are adjusted server-side when an item is recorded.
      void queryClient.invalidateQueries({ queryKey: ['inventories', projectId] })
    },
  })
}
