import { useEffect, useState, type Dispatch, type SetStateAction } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useWorkflowRevisionComparison, workflowRevisionComparisonPrefix, type WorkflowRevisionChange } from '../../../entities/workflow/api/useWorkflowRevisionComparison'
import { errorMessage, errorStatus } from '../../../shared/lib/errorMessage'
import type { WorkflowRevisionDto } from '../../../shared/types/api'

interface Props {
  userId: string
  projectId: string
  workflowId: string
  revisions: WorkflowRevisionDto[]
  available?: boolean
}

/** Only the current readable workflow can mount a comparison; changing identity discards local selections. */
export function WorkflowRevisionComparisonPanel(props: Props) {
  if (props.available === false || !props.userId || !props.projectId || !props.workflowId || props.revisions.length < 2) return null
  return <Comparison key={JSON.stringify([props.userId, props.projectId, props.workflowId])} {...props} />
}

const wrapped = { minWidth: 0, maxWidth: '100%', overflowWrap: 'anywhere' } as const

function Comparison({ userId, projectId, workflowId, revisions }: Props) {
  const sorted = [...revisions].sort((a, b) => b.revisionNo - a.revisionNo)
  const [open, setOpen] = useState(false)
  const [from, setFrom] = useState(() => sorted[1].workflowRevisionId)
  const [to, setTo] = useState(() => sorted[0].workflowRevisionId)
  const [offsets, setOffsets] = useState([0])
  const [accessDenied, setAccessDenied] = useState(false)
  const valid = revisions.some((value) => value.workflowRevisionId === from)
    && revisions.some((value) => value.workflowRevisionId === to)

  return <section aria-label="Workflow revision comparison" style={{ display: 'grid', gap: 10, ...wrapped,
    width: '100%', boxSizing: 'border-box', marginBottom: 20, padding: 12,
    border: '1px solid var(--border)', borderRadius: 8 }}>
    <h2 style={{ margin: 0 }}>Workflow revision comparison</h2>
    <p style={{ margin: 0 }}>Compare saved workflow revisions, including HTTP bindings and mappings. This view does not change or execute them.</p>
    <button type="button" aria-expanded={open} onClick={() => setOpen((value) => !value)}>
      {open ? 'Close comparison' : 'Compare revisions'}
    </button>
    {open && <>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10, alignItems: 'end', minWidth: 0 }}>
        {([['From revision', from, setFrom], ['To revision', to, setTo]] as const).map(([label, value, update]) =>
          <label key={label} style={{ display: 'grid', gap: 4, minWidth: 0, maxWidth: '100%' }}>{label}
            <select value={value} style={{ minWidth: 0, maxWidth: '100%' }} onChange={(event) => {
              update(event.target.value)
              setOffsets([0])
            }}>
              {!revisions.some((item) => item.workflowRevisionId === value) && <option value={value}>Unavailable revision</option>}
              {sorted.map((item) => <option key={item.workflowRevisionId} value={item.workflowRevisionId}>v{item.revisionNo} · {item.status}</option>)}
            </select>
          </label>)}
        <button type="button" onClick={() => { setFrom(to); setTo(from); setOffsets([0]) }}>Swap revisions</button>
      </div>
      {!valid && <p role="alert">A selected revision is no longer available. Choose revisions again.</p>}
      <ComparisonResults userId={userId} projectId={projectId} workflowId={workflowId} from={from} to={to}
        valid={valid} offsets={offsets} setOffsets={setOffsets} accessDenied={accessDenied} setAccessDenied={setAccessDenied} />
    </>}
  </section>
}

function ComparisonResults({ userId, projectId, workflowId, from, to, valid, offsets, setOffsets, accessDenied, setAccessDenied }: {
  userId: string; projectId: string; workflowId: string; from: string; to: string; valid: boolean;
  offsets: number[]; setOffsets: Dispatch<SetStateAction<number[]>>;
  accessDenied: boolean; setAccessDenied: Dispatch<SetStateAction<boolean>>
}) {
  const client = useQueryClient()
  const [refreshing, setRefreshing] = useState(false)
  const offset = offsets[offsets.length - 1]
  const query = useWorkflowRevisionComparison(userId, projectId, workflowId, from, to, offset, valid && !accessDenied)
  const refusal = [401, 403, 404].includes(errorStatus(query.error) ?? 0)
  const busy = query.isFetching || refreshing
  const current = valid && !accessDenied && !refusal && !query.isError && !busy ? query.data : undefined

  useEffect(() => {
    if (refusal) setAccessDenied(true)
  }, [refusal, setAccessDenied])

  async function refresh() {
    if (busy || !valid) return
    setRefreshing(true)
    try {
      const queryKey = [...workflowRevisionComparisonPrefix, userId, projectId, workflowId]
      await client.cancelQueries({ queryKey })
      await client.invalidateQueries({ queryKey, refetchType: 'none' })
      const result = await query.refetch()
      if (result.isSuccess) setAccessDenied(false)
    } finally {
      setRefreshing(false)
    }
  }

  return <>
    <button type="button" disabled={busy || !valid} onClick={() => { void refresh() }}>Refresh comparison</button>
    {busy && <p role="status">Loading revision comparison...</p>}
    {accessDenied && !query.isError && <p role="alert">The revision comparison is unavailable. Refresh comparison to check access again.</p>}
    {query.isError && <p role="alert">{errorMessage(query.error, 'Could not load the revision comparison.')}</p>}
    {current && <>
      <p role="status">{current.totalChanges} changes</p>
      {current.totalChanges === 0 && <p>No differences between these revisions.</p>}
      <div aria-label="Revision changes" style={{ display: 'grid', gap: 12, minWidth: 0 }}>
        {current.changes.map((item, index) => <Change key={`${offset + index}:${item.section}:${item.entityId}:${item.path}`} item={item} />)}
      </div>
    </>}
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center', minWidth: 0 }}>
      <button type="button" disabled={busy || accessDenied || offsets.length === 1} onClick={() => setOffsets((values) => values.slice(0, -1))}>Previous changes</button>
      <span>Page {offsets.length}</span>
      <button type="button" disabled={busy || !current || current.nextOffset === null} onClick={() => {
        const next = current?.nextOffset
        if (next != null) setOffsets((values) => [...values, next])
      }}>Next changes</button>
    </div>
  </>
}

function Change({ item }: { item: WorkflowRevisionChange }) {
  const exact = (item.kind === 'added' || typeof item.beforeJson === 'string')
    && (item.kind === 'removed' || typeof item.afterJson === 'string')
  return <article aria-label={`${item.kind} ${item.section} ${item.entityId ?? ''} ${item.path}`} style={{ display: 'grid', gap: 6,
    ...wrapped, padding: 10, boxSizing: 'border-box', border: '1px solid var(--border)', borderRadius: 6 }}>
    <h3 style={{ margin: 0, ...wrapped }}>{item.kind} · {item.section}{item.entityId === null ? '' : ` · ${item.entityId}`}</h3>
    <code style={wrapped}>{item.path || '(whole value)'}</code>
    {!exact && <p role="alert">Exact JSON is unavailable for this change.</p>}
    {exact && <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 16rem), 1fr))', gap: 10, minWidth: 0 }}>
      {([['Before', item.kind === 'added' ? 'Absent' : item.beforeJson], ['After', item.kind === 'removed' ? 'Absent' : item.afterJson]] as const)
        .map(([label, json]) => <div key={label} style={wrapped}>
          <h4 style={{ margin: '0 0 4px' }}>{label}</h4>
          <pre aria-label={label} style={{ margin: 0, whiteSpace: 'pre-wrap', ...wrapped }}>{json}</pre>
        </div>)}
    </div>}
  </article>
}
