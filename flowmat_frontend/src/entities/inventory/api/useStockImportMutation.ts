import { useMutation, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope, StockImportResultDto, StockImportRowDto } from '../../../shared/types/api'

/**
 * Checks (dry run) or receives stock from a spreadsheet (docs/domain/stock-import.md). {@code skipRows} names the rows to
 * leave out, from 1: they are not checked and not received.
 */
export function useStockImportMutation(projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ rows, dryRun, note, skipRows }: { rows: StockImportRowDto[]; dryRun: boolean; note?: string; skipRows?: number[] }) =>
      unwrapApiResponse(
        await httpClient.post<ApiEnvelope<StockImportResultDto>>('/inventories/import',
          { projectId, dryRun, note, rows, ...(skipRows && skipRows.length > 0 ? { skipRows } : {}) }),
      ),
    onSuccess: (result) => {
      if (!result.applied) return
      void queryClient.invalidateQueries({ queryKey: ['inventories', projectId] })
      void queryClient.invalidateQueries({ queryKey: ['lots', projectId] })
      void queryClient.invalidateQueries({ queryKey: ['inventory-transactions'] })
    },
  })
}
