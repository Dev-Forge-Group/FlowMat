import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import { createStockCommand } from '../../inventory/api/stockCommand'
import type { ApiEnvelope, ProductionRunItemDto } from '../../../shared/types/api'

export interface AllocateRunInputInput {
  productionRunId: string
  itemId: string
  quantity: number
  unit: string
  processId?: string
}

/**
 * Records one input over the item's LOTs, first-expiring first, as one recording per LOT; all or nothing
 * (docs/domain/lot-expiry.md "여러 LOT에 나눠 투입"). A split whose reply was lost is sent again with the same request key and
 * answered with the same recordings (docs/domain/production-run-recording.md RR3).
 */
export function useAllocateRunInputMutation(productionRunId: string, projectId: string) {
  const queryClient = useQueryClient()
  const [command] = useState(() => createStockCommand(
    async ({ productionRunId: runId, ...payload }: AllocateRunInputInput & { requestId: string }) =>
      unwrapApiResponse(
        await httpClient.post<ApiEnvelope<ProductionRunItemDto[]>>(`/production-runs/${encodeURIComponent(runId)}/inputs/fefo`, payload),
      ),
  ))
  return useMutation({
    mutationFn: command,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['production-run-items', productionRunId] })
      void queryClient.invalidateQueries({ queryKey: ['inventories', projectId] })
      void queryClient.invalidateQueries({ queryKey: ['lots', projectId] })
    },
  })
}
