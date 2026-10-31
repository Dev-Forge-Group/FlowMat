import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { useCurrentUserQuery } from '../../../entities/auth/api/useCurrentUserQuery'
import { httpAttentionKey, httpReviewStates, useHttpExecutionAttention, type HttpReviewState } from '../../../entities/connector/api/useHttpExecutionAttention'
import { errorMessage } from '../../../shared/lib/errorMessage'

const stateLabels: Record<HttpReviewState, string> = {
  ALL: 'All needing review', PENDING: 'Pending', OUTCOME_UNKNOWN: 'Outcome unknown', APPLY_FAILED: 'Apply failed', LATE: 'Late result',
}
const cell = { padding: '8px 6px', textAlign: 'left', overflowWrap: 'anywhere' } as const

export function HttpExecutionAttentionPanel({ projectId }: { projectId: string }) {
  const me = useCurrentUserQuery()
  const userId = !me.isError ? me.data?.userId ?? '' : ''
  return <ReviewList key={`${userId}:${projectId}`} projectId={projectId} userId={userId} />
}

function ReviewList({ projectId, userId }: { projectId: string; userId: string }) {
  const client = useQueryClient()
  const [open, setOpen] = useState(false)
  const [state, setState] = useState<HttpReviewState>('ALL')
  const [cursors, setCursors] = useState<(string | null)[]>([null])
  const cursor = cursors[cursors.length - 1]
  const query = useHttpExecutionAttention(userId, projectId, state, cursor, open)
  const current = open && !query.isError && !query.isFetching ? query.data : undefined
  const items = current?.items ?? []
  const busy = query.isFetching

  async function refresh() {
    if (!open || busy || !userId) return
    const queryKey = [...httpAttentionKey, userId, projectId]
    await client.cancelQueries({ queryKey })
    await client.invalidateQueries({ queryKey, refetchType: 'none' })
    if (cursor !== null) setCursors([null])
    else await query.refetch()
  }

  return <section aria-label="HTTP requests needing review" style={{ display: 'grid', gap: 10, minWidth: 0,
    width: '100%', maxWidth: '100%', boxSizing: 'border-box', marginBottom: 20, padding: 12,
    border: '1px solid var(--border)', borderRadius: 8 }}>
    <h2 style={{ margin: 0 }}>HTTP requests needing review</h2>
    <p style={{ margin: 0 }}>Review pending, uncertain and unapplied results. Opening this list never resends a request.</p>
    <button type="button" aria-expanded={open} onClick={() => setOpen((value) => !value)}>
      {open ? 'Close review list' : 'Open review list'}
    </button>
    {open && <>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10, alignItems: 'end', minWidth: 0 }}>
        <label style={{ display: 'grid', gap: 4, minWidth: 0 }}>HTTP review state
          <select value={state} onChange={(event) => {
            setState(event.target.value as HttpReviewState)
            setCursors([null])
          }} style={{ maxWidth: '100%' }}>
            {httpReviewStates.map((value) => <option key={value} value={value}>{stateLabels[value]}</option>)}
          </select>
        </label>
        <button type="button" disabled={busy || !userId} onClick={() => { void refresh() }}>Refresh review list</button>
      </div>
      {!userId && <p role="alert">Sign in to review this project's HTTP requests.</p>}
      {userId && (query.isPending || busy) && <p role="status">Loading HTTP review list...</p>}
      {query.isError && <p role="alert">{errorMessage(query.error, 'Could not load the HTTP review list.')}</p>}
      {current && items.length === 0 && <p>No HTTP requests need review.</p>}
      {items.length > 0 && <div style={{ overflowX: 'auto', minWidth: 0, maxWidth: '100%' }}>
        <table aria-label="HTTP review results" style={{ borderCollapse: 'collapse', width: '100%', minWidth: 760, tableLayout: 'fixed', fontSize: 13 }}>
          <thead><tr>{['Operation', 'Workflow', 'Run', 'Step', 'Transport', 'Application', 'Started', 'Review']
            .map((label) => <th key={label} scope="col" style={cell}>{label}</th>)}</tr></thead>
          <tbody>{items.map((item) => {
            const params = new URLSearchParams({ view: 'flow-runs', workflowId: item.workflowId, flowRunId: item.flowRunId, stepId: item.stepId })
            return <tr key={item.executionId}>
              <th scope="row" style={cell}>{item.operationId}</th>
              <td style={cell}>{item.workflowId}</td>
              <td style={cell}>{item.flowRunId}<br /><small>{item.runStatus}</small></td>
              <td style={cell}>{item.stepId}<br /><small>{item.stepStatus}</small></td>
              <td style={cell}>{item.transportStatus}{item.httpStatus !== null ? ` · HTTP ${item.httpStatus}` : ''}</td>
              <td style={cell}>{item.applyStatus}</td>
              <td style={cell}>{new Date(item.startedAt).toLocaleString()}</td>
              <td style={cell}><Link to={`/projects/${encodeURIComponent(projectId)}/runs?${params.toString()}`}>Open run</Link></td>
            </tr>
          })}</tbody>
        </table>
      </div>}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <button type="button" disabled={busy || cursors.length === 1} onClick={() => setCursors((values) => values.slice(0, -1))}>Back</button>
        <span>Page {cursors.length}</span>
        <button type="button" disabled={busy || !current?.nextCursor} onClick={() => {
          if (current?.nextCursor) setCursors((values) => [...values, current.nextCursor])
        }}>Next</button>
      </div>
    </>}
  </section>
}
