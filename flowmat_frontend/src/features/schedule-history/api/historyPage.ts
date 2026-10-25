import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

/** One page of a history search, newest first; nextCursor is null at the end. */
export interface HistoryPage<T> {
  entries: T[]
  nextCursor: string | null
}

/** The 100 entries saved before {@code cursor}, an entry already shown (the history search APIs). */
export async function fetchOlderHistory<T>(searchPath: string, cursor: string): Promise<HistoryPage<T>> {
  return unwrapApiResponse(await httpClient.get<ApiEnvelope<HistoryPage<T>>>(
    `${searchPath}?cursor=${encodeURIComponent(cursor)}&limit=100`))
}
