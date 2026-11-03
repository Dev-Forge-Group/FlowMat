import { Suspense, lazy } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useWorkflowCanvasQuery } from '../../../entities/workflow/api/useWorkflowCanvasQuery'
import { workspaceRouteView } from '../model/workspaceRouteModel'
import { preloadWorkflowCanvasPage } from './workspacePreload'

const WorkflowCanvasPage = lazy(() =>
  preloadWorkflowCanvasPage().then((module) => ({ default: module.WorkflowCanvasPage }))
)

export function WorkspaceRoute() {
  const { projectId = '', workflowId = '' } = useParams<{ projectId: string; workflowId: string }>()
  const { data: canvas, isLoading, isError, isFetching, error, refetch } = useWorkflowCanvasQuery(workflowId)
  const view = workspaceRouteView({ isLoading, hasCanvas: Boolean(canvas) })

  if (view === 'loading') {
    return <div className="workspace-loading">Loading canvas...</div>
  }

  if (view === 'error' || !canvas) {
    const msg = error instanceof Error ? error.message : 'Failed to load canvas.'
    return (
      <div className="workspace-error" style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        <span>{msg}</span>
        <Link to="/" style={{ fontSize: 13, color: 'var(--accent)' }}>
          Back to home
        </Link>
      </div>
    )
  }

  return (
    <Suspense fallback={<div className="workspace-loading">Loading workspace...</div>}>
      {isError && (
        // A failed refresh keeps the editor on what was last loaded; the next refresh or Retry brings it up to date.
        <div role="alert" className="workspace-refresh-error">
          Could not refresh the canvas{error instanceof Error ? `: ${error.message}` : ''}. Showing what was last loaded.{' '}
          <button type="button" disabled={isFetching} onClick={() => void refetch()}>
            {isFetching ? 'Retrying...' : 'Retry'}
          </button>
        </div>
      )}
      <WorkflowCanvasPage canvas={canvas} projectId={projectId} />
    </Suspense>
  )
}
