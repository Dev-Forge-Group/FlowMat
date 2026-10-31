import { useQuery } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

export const httpReviewStates = ['ALL', 'PENDING', 'OUTCOME_UNKNOWN', 'APPLY_FAILED', 'LATE'] as const
export type HttpReviewState = typeof httpReviewStates[number]
export const httpAttentionKey = ['http-execution-attention'] as const

export interface HttpExecutionAttentionItem {
  executionId: string
  flowRunId: string
  workflowId: string
  runStatus: string
  stepId: string
  nodeId: string
  stepStatus: string
  attemptId: string
  operationId: string
  transportStatus: string
  applyStatus: string
  httpStatus: number | null
  errorCode: string | null
  startedAt: string
  completedAt: string | null
}

export interface HttpExecutionAttentionPage {
  items: HttpExecutionAttentionItem[]
  nextCursor: string | null
}

export const httpAttentionPageKey = (userId: string, projectId: string, state: HttpReviewState, cursor: string | null) =>
  [...httpAttentionKey, userId, projectId, state, cursor] as const

export async function fetchHttpAttentionPage(projectId: string, state: HttpReviewState, cursor: string | null): Promise<HttpExecutionAttentionPage> {
  const params = new URLSearchParams({ limit: '25', state })
  if (cursor !== null) params.set('cursor', cursor)
  const page = unwrapApiResponse(await httpClient.get<ApiEnvelope<HttpExecutionAttentionPage>>(
    `/projects/${encodeURIComponent(projectId)}/http-executions/attention?${params.toString()}`))
  if (!page || !Array.isArray(page.items) || !(page.nextCursor === null || typeof page.nextCursor === 'string')) {
    throw new Error('The HTTP review list is unavailable.')
  }
  return page
}

/** Loading the summary is explicit; it neither previews payloads nor sends HTTP requests. */
export function useHttpExecutionAttention(userId: string, projectId: string, state: HttpReviewState, cursor: string | null, open: boolean) {
  return useQuery({
    queryKey: httpAttentionPageKey(userId, projectId, state, cursor),
    queryFn: () => fetchHttpAttentionPage(projectId, state, cursor),
    enabled: open && Boolean(userId && projectId),
    staleTime: 0,
    retry: false,
  })
}
