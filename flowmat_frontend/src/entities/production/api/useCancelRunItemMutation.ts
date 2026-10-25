import { useMutation, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope, ProductionRunItemDto } from '../../../shared/types/api'

/**
 * Cancels a recorded item of an open run. The server reverses its stock movement (refusing if that stock was already
 * used) and rebuilds the run's LOT genealogy, so stock, LOT and run views all need a refresh. With {@code reallocate} the
 * stock an input gives back is allocated to the run's work order again (docs/domain/stock-allocation.md R4).
 */
export function useCancelRunItemMutation(runId: string, projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ productionRunItemId, reason, reallocate = false }: { productionRunItemId: string; reason: string; reallocate?: boolean }) =>
      unwrapApiResponse(
        await httpClient.post<ApiEnvelope<ProductionRunItemDto>>(
          `/production-runs/${encodeURIComponent(runId)}/items/${encodeURIComponent(productionRunItemId)}/cancel`,
          reallocate ? { reason, reallocate } : { reason },
        ),
      ),
    onSuccess: (_item, { reallocate }) => {
      if (reallocate) {
        void queryClient.invalidateQueries({ queryKey: ['work-order-allocations'] })
        void queryClient.invalidateQueries({ queryKey: ['work-order-readiness'] })
      }
      void queryClient.invalidateQueries({ queryKey: ['production-run-items', runId] })
      void queryClient.invalidateQueries({ queryKey: ['inventories', projectId] })
      void queryClient.invalidateQueries({ queryKey: ['lots', projectId] })
      void queryClient.invalidateQueries({ queryKey: ['inventory-transactions'] })
    },
  })
}
