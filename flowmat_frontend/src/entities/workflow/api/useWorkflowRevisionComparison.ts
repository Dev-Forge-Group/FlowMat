import { useQuery } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

export interface ComparedWorkflowRevision {
  revisionId: string
  revisionNo: number
  schemaVersion: number
  status: 'published' | 'retired'
}

export interface WorkflowRevisionChange {
  section: string
  entityId: string | null
  path: string
  kind: 'added' | 'removed' | 'modified'
  beforeJson?: string | null
  afterJson?: string | null
}

export interface WorkflowRevisionComparison {
  workflowId: string
  fromRevision: ComparedWorkflowRevision
  toRevision: ComparedWorkflowRevision
  totalChanges: number
  changes: WorkflowRevisionChange[]
  nextOffset: number | null
}

export const workflowRevisionComparisonPrefix = ['workflow-revision-comparison'] as const
export const workflowRevisionComparisonKey = (userId: string, projectId: string, workflowId: string,
  fromRevisionId: string, toRevisionId: string, offset: number) =>
  [...workflowRevisionComparisonPrefix, userId, projectId, workflowId, fromRevisionId, toRevisionId, offset, 100] as const

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function revision(value: unknown, id: string): value is ComparedWorkflowRevision {
  return record(value) && value.revisionId === id && Number.isSafeInteger(value.revisionNo) && Number(value.revisionNo) > 0
    && Number.isSafeInteger(value.schemaVersion) && Number(value.schemaVersion) > 0
    && (value.status === 'published' || value.status === 'retired')
}

function change(value: unknown): value is WorkflowRevisionChange {
  return record(value) && typeof value.section === 'string' && (value.entityId === null || typeof value.entityId === 'string')
    && typeof value.path === 'string' && typeof value.kind === 'string' && ['added', 'removed', 'modified'].includes(value.kind)
    && (value.beforeJson == null || typeof value.beforeJson === 'string')
    && (value.afterJson == null || typeof value.afterJson === 'string')
}

export async function fetchWorkflowRevisionComparison(workflowId: string, fromRevisionId: string,
  toRevisionId: string, offset: number): Promise<WorkflowRevisionComparison> {
  const params = new URLSearchParams({ fromRevisionId, toRevisionId, offset: String(offset), limit: '100' })
  const page: unknown = unwrapApiResponse(await httpClient.get<ApiEnvelope<unknown>>(
    `/workflows/${encodeURIComponent(workflowId)}/revisions/compare?${params.toString()}`))
  if (!record(page) || page.workflowId !== workflowId || !revision(page.fromRevision, fromRevisionId)
    || !revision(page.toRevision, toRevisionId) || !Number.isSafeInteger(page.totalChanges) || Number(page.totalChanges) < 0
    || !Array.isArray(page.changes) || page.changes.length > 100 || !page.changes.every(change)
    || !(page.nextOffset === null || (Number.isSafeInteger(page.nextOffset) && Number(page.nextOffset) > offset
      && Number(page.nextOffset) < Number(page.totalChanges) && page.nextOffset === offset + page.changes.length))
    || (page.changes.length > 0 && offset + page.changes.length > Number(page.totalChanges))
    || (page.nextOffset === null && offset + page.changes.length < Number(page.totalChanges))) {
    throw new Error('The revision comparison is unavailable.')
  }
  return page as unknown as WorkflowRevisionComparison
}

/** Every comparison is a read of two published snapshots; opening it never publishes, applies or executes them. */
export function useWorkflowRevisionComparison(userId: string, projectId: string, workflowId: string,
  fromRevisionId: string, toRevisionId: string, offset: number, enabled: boolean) {
  return useQuery({
    queryKey: workflowRevisionComparisonKey(userId, projectId, workflowId, fromRevisionId, toRevisionId, offset),
    queryFn: () => fetchWorkflowRevisionComparison(workflowId, fromRevisionId, toRevisionId, offset),
    enabled: enabled && Boolean(userId && projectId && workflowId && fromRevisionId && toRevisionId),
    staleTime: 0,
    refetchOnMount: 'always',
    retry: false,
  })
}
