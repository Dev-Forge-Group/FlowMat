import { useState } from 'react'
import { Link } from 'react-router-dom'
import {
  usePublishWorkflowRevisionMutation,
  useWorkflowRevisionsQuery,
} from '../../../entities/workflow/api/useWorkflowRevisions'
import { errorMessage } from '../../../shared/lib/errorMessage'
import type { WorkflowRevisionDetailDto } from '../../../shared/types/api'
import { latestPublished, revisionLine } from '../../runs/model/revisionModel'
import { t } from '../../../shared/i18n/t'

/**
 * Publishes the workflow from the editor (docs/domain/workflow-revision.md V8): what is saved becomes the next revision once
 * the server's check passes (V2). A refusal opens the workflow check so its issues can be picked on the canvas.
 */
export function EditorPublish({ workflowId, projectId, editable, onRefused }: {
  workflowId: string
  projectId: string
  /** Editors and owners publish; viewers only see the latest revision. */
  editable: boolean
  /** Show the workflow check, which lists what stopped the publish. */
  onRefused: () => void
}) {
  const revisionsQuery = useWorkflowRevisionsQuery(workflowId)
  const publish = usePublishWorkflowRevisionMutation(workflowId)
  const [published, setPublished] = useState<WorkflowRevisionDetailDto | null>(null)
  const latest = latestPublished(revisionsQuery.data ?? [])

  return (
    <span style={{ display: 'inline-flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', marginLeft: 8 }}>
      <button type="button" disabled={!editable || publish.isPending}
        title={editable ? t('Publish what is saved as a new revision; runs start from published revisions') : t('Viewers cannot publish')}
        onClick={() => {
          setPublished(null)
          publish.mutate(undefined, { onSuccess: setPublished, onError: onRefused })
        }}>
        {publish.isPending ? t('Publishing...') : t('Publish revision')}
      </button>
      <span className="inspector-hint" style={{ fontSize: 12 }}>
        {latest ? t('Latest: v{no} {line}', { no: latest.revisionNo, line: revisionLine(latest) }) : t('Not published yet')}
      </span>
      {published && (
        <span role="status" style={{ fontSize: 12, color: '#047857' }}>
          {t('Published v{no}.', { no: published.revisionNo })}{' '}
          <Link to={`/projects/${encodeURIComponent(projectId)}/runs?workflowId=${encodeURIComponent(workflowId)}`}>{t('Start a run')}</Link>
        </span>
      )}
      {publish.isError && (
        <span role="alert" style={{ fontSize: 12, color: '#dc2626', flexBasis: '100%' }}>{errorMessage(publish.error)}</span>
      )}
    </span>
  )
}
