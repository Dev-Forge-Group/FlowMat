import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useWorkflowRevisionsQuery } from '../../../entities/workflow/api/useWorkflowRevisions'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope, WorkflowRevisionDetailDto } from '../../../shared/types/api'
import { changesSummary, revisionChanges, type DraftGraph } from './revisionChanges'

/**
 * The workflow's published revisions, newest first, and what the draft changed since the one shown (`revisionId`, from the
 * address): a mark per process and connection and a one-line summary. View only. The revision list is the one the publish
 * bar already reads; a revision's snapshot is read only once it is shown.
 */
export function useRevisionChanges(workflowId: string, revisionId: string | null, draft: DraftGraph) {
  const revisions = useWorkflowRevisionsQuery(workflowId)
  const revision = revisionId ? revisions.data?.find((candidate) => candidate.workflowRevisionId === revisionId) ?? null : null
  const detail = useQuery({
    queryKey: ['workflow-revision', workflowId, revisionId],
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<WorkflowRevisionDetailDto>>(
      `/workflows/${encodeURIComponent(workflowId)}/revisions/${encodeURIComponent(revisionId ?? '')}`)),
    enabled: Boolean(workflowId && revision),
  })
  const changes = useMemo(() => (revision && detail.data ? revisionChanges(draft, detail.data.snapshot) : null),
    [revision, detail.data, draft])
  const newestFirst = useMemo(() => [...(revisions.data ?? [])].sort((left, right) => right.revisionNo - left.revisionNo),
    [revisions.data])

  let summary: string | null = null
  if (revision) {
    if (detail.isError) summary = `Could not read v${revision.revisionNo}.`
    else if (!detail.data) summary = `Reading v${revision.revisionNo}…`
    else summary = changes ? changesSummary(revision.revisionNo, changes) : `v${revision.revisionNo} cannot be compared here.`
  } else if (revisionId && revisions.isSuccess) {
    summary = 'The linked revision is not one of this workflow\'s revisions.'
  }

  return { revisions: newestFirst, loading: !revisions.data && revisions.isFetching, revision, changes, summary }
}

export type RevisionChangesView = ReturnType<typeof useRevisionChanges>
