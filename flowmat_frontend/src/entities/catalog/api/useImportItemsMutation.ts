import { useMutation, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope, ItemImportResultDto, ItemImportRowDto } from '../../../shared/types/api'

/**
 * Checks (dry run) or saves items from a spreadsheet (docs/domain/item-import.md); the item list refreshes when saved.
 * {@code skipRows} names the rows to leave out, from 1: they are not checked and not saved.
 */
export function useImportItemsMutation(projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ rows, dryRun, skipRows }: { rows: ItemImportRowDto[]; dryRun: boolean; skipRows?: number[] }) =>
      unwrapApiResponse(
        await httpClient.post<ApiEnvelope<ItemImportResultDto>>('/items/import',
          { projectId, dryRun, rows, ...(skipRows && skipRows.length > 0 ? { skipRows } : {}) }),
      ),
    onSuccess: (result) => {
      if (result.applied) {
        void queryClient.invalidateQueries({ queryKey: ['items', projectId] })
      }
    },
  })
}
