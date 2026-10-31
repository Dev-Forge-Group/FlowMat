import { useMemo, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useWorkflowsQuery } from '../../../entities/workflow/api/useWorkflowsQuery'
import { usePublishWorkflowRevisionMutation, useWorkflowRevisionsQuery } from '../../../entities/workflow/api/useWorkflowRevisions'
import { useItemsQuery } from '../../../entities/catalog/api/useItemsQuery'
import { useProductionRunsQuery } from '../../../entities/production/api/useProductionRunsQuery'
import { useStartProductionRunMutation } from '../../../entities/production/api/useStartProductionRunMutation'
import { StartReadiness } from './StartReadiness'
import { RevisionList } from './RevisionList'
import { useWorkOrdersQuery } from '../../../entities/production/api/useWorkOrders'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { runnableWorkOrders } from '../model/workOrderActions'
import { useBomsQuery } from '../../../entities/bom/api/useBoms'
import { approvedRevision, zoneDay } from '../../inventory/model/bomModel'
import { useProjectTimeZone } from '../../../entities/project/api/useProjectTimeZone'
import { RunStatusBadge, formatQty } from './runDisplay'
import { WorkOrdersPanel } from './WorkOrdersPanel'
import { FlowRunsPanel } from './FlowRunsPanel'
import { HttpExecutionAttentionPanel } from './HttpExecutionAttentionPanel'
import { WorkflowRevisionComparisonPanel } from './WorkflowRevisionComparisonPanel'
import { useCurrentUserQuery } from '../../../entities/auth/api/useCurrentUserQuery'

const RUN_TYPES = ['actual', 'simulation']

const cell = { padding: '8px 6px' } as const

export function RunsRoute() {
  const { projectId = '' } = useParams<{ projectId: string }>()
  const [searchParams, setSearchParams] = useSearchParams()
  const navigate = useNavigate()

  const me = useCurrentUserQuery()
  const userId = !me.isError ? me.data?.userId ?? '' : ''
  const workflowsQuery = useWorkflowsQuery(projectId)
  const itemsQuery = useItemsQuery(projectId)
  const workflows = !workflowsQuery.isError ? workflowsQuery.data ?? [] : []
  const workflowId = searchParams.get('workflowId') ?? workflows[0]?.workflowId ?? ''
  const requestedView = searchParams.get('view')
  const view = requestedView === 'work-orders' || requestedView === 'flow-runs' ? requestedView : 'runs'

  const runsQuery = useProductionRunsQuery(workflowId)
  const startMutation = useStartProductionRunMutation(workflowId)
  const revisionsQuery = useWorkflowRevisionsQuery(workflowId)
  const publishRevision = usePublishWorkflowRevisionMutation(workflowId)
  const revisions = revisionsQuery.data ?? []
  const publishedRevisions = revisions.filter((revision) => revision.status === 'published')
  const revisionNumberById = new Map(revisions.map((revision) => [revision.workflowRevisionId, revision.revisionNo]))
  const workOrders = useWorkOrdersQuery(projectId).data ?? []
  const workOrderNumber = new Map(workOrders.map((order) => [order.workOrderId, order.workOrderNumber]))
  const selectableOrders = runnableWorkOrders(workOrders, workflowId)

  const [form, setForm] = useState({ workOrderId: '', targetItemId: '', plannedOutputQty: '', runType: 'actual', useBom: true, workflowRevisionId: '' })
  const selectedRevisionId = publishedRevisions.some((revision) => revision.workflowRevisionId === form.workflowRevisionId)
    ? form.workflowRevisionId
    : publishedRevisions[0]?.workflowRevisionId ?? ''
  const boms = useBomsQuery(projectId).data ?? []
  // An order with a BOM always plans from it (the server uses the order's BOM), so the checkbox only applies without one.
  const orderBomId = workOrders.find((order) => order.workOrderId === form.workOrderId)?.bomId ?? null
  const orderBom = orderBomId ? boms.find((candidate) => candidate.bomId === orderBomId) : undefined
  // A run without an order plans from the revision effective on the project's today, as the server does.
  const timeZone = useProjectTimeZone(projectId ?? '').query.data?.timeZone
  const bom = !orderBomId && form.targetItemId ? approvedRevision(boms, form.targetItemId, zoneDay(timeZone)) : undefined

  function setView(next: 'runs' | 'work-orders' | 'flow-runs') {
    const params: Record<string, string> = {}
    if (searchParams.get('workflowId')) params.workflowId = searchParams.get('workflowId') as string
    if (next !== 'runs') params.view = next
    setSearchParams(params, { replace: true })
  }

  /** Choosing an order pre-fills its target item and the quantity still to produce. */
  function selectWorkOrder(workOrderId: string) {
    const order = workOrders.find((candidate) => candidate.workOrderId === workOrderId)
    const remaining = order?.targetQuantity ? Math.max(0, order.targetQuantity - Number(order.producedQuantity)) : null
    setForm((f) => ({
      ...f,
      workOrderId,
      targetItemId: order?.targetItemId ?? f.targetItemId,
      plannedOutputQty: remaining !== null ? String(remaining) : f.plannedOutputQty,
    }))
  }

  const itemLabel = useMemo(
    () => new Map((itemsQuery.data ?? []).map((item) => [item.itemId, `${item.itemCode} · ${item.itemName}`])),
    [itemsQuery.data],
  )
  const runs = runsQuery.data ?? []

  function selectWorkflow(nextWorkflowId: string) {
    setSearchParams(view === 'runs' ? { workflowId: nextWorkflowId }
      : { workflowId: nextWorkflowId, view }, { replace: true })
    setForm((f) => ({ ...f, workOrderId: '', workflowRevisionId: '' }))
    startMutation.reset()
    publishRevision.reset()
  }

  async function handlePublish() {
    try {
      const revision = await publishRevision.mutateAsync()
      setForm((current) => ({ ...current, workflowRevisionId: revision.workflowRevisionId }))
    } catch {
      // The mutation error is shown next to the publish control.
    }
  }

  async function handleStart(e: FormEvent) {
    e.preventDefault()
    try {
      const run = await startMutation.mutateAsync({
        projectId,
        workflowId,
        workflowRevisionId: selectedRevisionId,
        targetItemId: form.targetItemId || undefined,
        plannedOutputQty: Number(form.plannedOutputQty),
        runType: form.runType,
        workOrderId: form.workOrderId || undefined,
        bomId: form.useBom && bom ? bom.bomId : undefined,
      })
      navigate(`/projects/${projectId}/runs/${run.productionRunId}`)
    } catch {
      // Surfaced through the mutation error state below the form (includes rule-engine rejections).
    }
  }

  return (
    <div style={{ padding: 32, width: '100%', minWidth: 0, maxWidth: 1120, boxSizing: 'border-box', margin: '0 auto' }}>
      <Link to="/" style={{ fontSize: 13, color: 'var(--accent)' }}>Back to home</Link>
      <h1>Production</h1>

      <div role="tablist" style={{ display: 'flex', flexWrap: 'wrap', gap: 4, minWidth: 0,
        borderBottom: '1px solid var(--border)', marginBottom: 20 }}>
        {(['runs', 'flow-runs', 'work-orders'] as const).map((name) => (
          <button
            key={name}
            type="button"
            role="tab"
            aria-selected={view === name}
            onClick={() => setView(name)}
            style={{
              background: 'transparent',
              border: 'none',
              borderBottom: view === name ? '2px solid var(--accent)' : '2px solid transparent',
              borderRadius: 0,
              padding: '8px 14px',
              fontWeight: view === name ? 600 : 400,
              opacity: view === name ? 1 : 0.65,
            }}
          >
            {name === 'runs' ? 'Runs' : name === 'flow-runs' ? 'Flow executions' : `Work Orders (${workOrders.length})`}
          </button>
        ))}
      </div>

      {view !== 'work-orders' && userId && !workflowsQuery.isError
        && workflows.some((workflow) => workflow.workflowId === workflowId)
        && revisionsQuery.isSuccess && !revisionsQuery.isError && (
          <WorkflowRevisionComparisonPanel key={`${userId}:${projectId}:${workflowId}`}
            userId={userId} projectId={projectId} workflowId={workflowId} revisions={revisions} />
        )}

      {view === 'work-orders' && (
        <WorkOrdersPanel projectId={projectId} workflows={workflows} items={itemsQuery.data ?? []} />
      )}

      {view === 'flow-runs' && (
        <>
          <HttpExecutionAttentionPanel key={projectId} projectId={projectId} />
          {!workflowsQuery.isPending && !workflowsQuery.isError && searchParams.has('workflowId')
            && !workflows.some((workflow) => workflow.workflowId === workflowId)
            ? <p role="alert">Requested workflow is unavailable.</p>
            : <FlowRunsPanel key={`${projectId}:${workflowId}`} projectId={projectId} workflowId={workflowId}
              workflows={workflows} revisions={revisions} onSelectWorkflow={selectWorkflow} />}
        </>
      )}

      {view === 'runs' && workflowsQuery.isLoading && <p>Loading workflows...</p>}
      {workflowsQuery.isError && (
        <p style={{ color: '#dc2626' }}>{errorMessage(workflowsQuery.error, 'Failed to load workflows.')}</p>
      )}
      {view === 'runs' && !workflowsQuery.isLoading && workflows.length === 0 && (
        <p className="inspector-hint">This project has no workflows yet. Create one from the home screen to start runs.</p>
      )}

      {view === 'runs' && workflows.length > 0 && (
        <>
          <label style={{ display: 'inline-flex', gap: 8, alignItems: 'center', marginBottom: 16, fontSize: 13 }}>
            <span>Workflow</span>
            <select value={workflowId} onChange={(e) => selectWorkflow(e.target.value)}>
              {workflows.map((wf) => <option key={wf.workflowId} value={wf.workflowId}>{wf.workflowName}</option>)}
            </select>
            <Link to={`/projects/${projectId}/workflows/${workflowId}`} style={{ color: 'var(--accent)' }}>
              Open canvas
            </Link>
          </label>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 320px', gap: 24, alignItems: 'start' }}>
            <section>
              {runsQuery.isLoading && <p>Loading runs...</p>}
              {runsQuery.isError && (
                <p style={{ color: '#dc2626' }}>{errorMessage(runsQuery.error, 'Failed to load runs.')}</p>
              )}
              {!runsQuery.isLoading && runs.length === 0 && (
                <p className="inspector-hint">No runs for this workflow yet. Start one from the form on the right.</p>
              )}
              {runs.length > 0 && (
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                  <thead>
                    <tr style={{ borderBottom: '2px solid var(--border)', textAlign: 'left' }}>
                      <th style={cell}>Run</th>
                      <th style={cell}>Workflow revision</th>
                      <th style={cell}>Status</th>
                      <th style={cell}>Type</th>
                      <th style={cell}>Target item</th>
                      <th style={{ ...cell, textAlign: 'right' }}>Planned</th>
                      <th style={{ ...cell, textAlign: 'right' }}>Actual</th>
                    </tr>
                  </thead>
                  <tbody>
                    {runs.map((run) => (
                      <tr key={run.productionRunId} style={{ borderBottom: '1px solid var(--border)' }}>
                        <td style={cell}>
                          <Link
                            to={`/projects/${projectId}/runs/${run.productionRunId}`}
                            style={{ color: 'var(--accent)' }}
                          >
                            <code>{run.runNumber}</code>
                          </Link>
                          {run.workOrderId && (
                            <div style={{ fontSize: 11, opacity: 0.6 }}>
                              {workOrderNumber.get(run.workOrderId) ?? run.workOrderId}
                            </div>
                          )}
                        </td>
                        <td style={cell}>
                          {run.workflowRevisionId
                            ? `v${revisionNumberById.get(run.workflowRevisionId) ?? '?'}`
                            : 'Legacy draft'}
                        </td>
                        <td style={cell}><RunStatusBadge status={run.runStatus} /></td>
                        <td style={{ ...cell, opacity: 0.7 }}>{run.runType ?? '-'}</td>
                        <td style={{ ...cell, opacity: 0.7 }}>
                          {run.targetItemId ? itemLabel.get(run.targetItemId) ?? run.targetItemId : '-'}
                        </td>
                        <td style={{ ...cell, textAlign: 'right' }}>{formatQty(run.plannedOutputQty)}</td>
                        <td style={{ ...cell, textAlign: 'right' }}>{formatQty(run.actualOutputQty)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </section>

            <section style={{ border: '1px solid var(--border)', borderRadius: 12, padding: 18 }}>
              <h3 style={{ marginTop: 0 }}>Start Run</h3>
              <form onSubmit={(e) => void handleStart(e)} style={{ display: 'grid', gap: 10 }}>
                <label style={{ display: 'grid', gap: 4 }}>
                  <span>Workflow revision *</span>
                  <select
                    value={selectedRevisionId}
                    onChange={(e) => setForm((current) => ({ ...current, workflowRevisionId: e.target.value }))}
                    disabled={publishedRevisions.length === 0}
                  >
                    {publishedRevisions.length === 0 && <option value="">No published revision</option>}
                    {publishedRevisions.map((revision) => (
                      <option key={revision.workflowRevisionId} value={revision.workflowRevisionId}>
                        v{revision.revisionNo} · {new Date(revision.publishedAt).toLocaleString()}
                      </option>
                    ))}
                  </select>
                </label>
                <button type="button" onClick={() => void handlePublish()} disabled={!workflowId || publishRevision.isPending}>
                  {publishRevision.isPending ? 'Publishing...' : 'Publish current workflow'}
                </button>
                <RevisionList workflowId={workflowId} revisions={revisions} />
                {revisionsQuery.isError && (
                  <p style={{ color: '#dc2626', fontSize: 12, margin: 0 }}>
                    {errorMessage(revisionsQuery.error, 'Failed to load workflow revisions.')}
                  </p>
                )}
                {publishRevision.isError && (
                  <p style={{ color: '#dc2626', fontSize: 12, margin: 0 }}>
                    {errorMessage(publishRevision.error, 'Failed to publish workflow.')}
                  </p>
                )}
                {!revisionsQuery.isLoading && publishedRevisions.length === 0 && (
                  <p className="inspector-hint" style={{ fontSize: 12, margin: 0 }}>
                    Publish this workflow before starting a versioned run.
                  </p>
                )}
                <label style={{ display: 'grid', gap: 4 }}>
                  <span>Work order</span>
                  <select value={form.workOrderId} onChange={(e) => selectWorkOrder(e.target.value)}>
                    <option value="">None</option>
                    {selectableOrders.map((order) => (
                      <option key={order.workOrderId} value={order.workOrderId}>
                        {order.workOrderNumber} · {order.workOrderTitle}
                      </option>
                    ))}
                  </select>
                  {selectableOrders.length === 0 && (
                    <span style={{ fontSize: 11, opacity: 0.6 }}>No approved work orders for this workflow.</span>
                  )}
                </label>
                {form.workOrderId && <StartReadiness workOrderId={form.workOrderId} />}
                <label style={{ display: 'grid', gap: 4 }}>
                  <span>Target item</span>
                  <select
                    value={form.targetItemId}
                    onChange={(e) => setForm((f) => ({ ...f, targetItemId: e.target.value }))}
                  >
                    <option value="">None</option>
                    {(itemsQuery.data ?? []).map((item) => (
                      <option key={item.itemId} value={item.itemId}>{item.itemCode} · {item.itemName}</option>
                    ))}
                  </select>
                </label>
                <label style={{ display: 'grid', gap: 4 }}>
                  <span>Planned output qty *</span>
                  <input
                    type="number"
                    min="0"
                    step="any"
                    value={form.plannedOutputQty}
                    onChange={(e) => setForm((f) => ({ ...f, plannedOutputQty: e.target.value }))}
                    required
                  />
                </label>
                {orderBomId && (
                  <span style={{ fontSize: 13 }}>
                    Materials are planned from the work order&apos;s BOM
                    {orderBom ? <> <code>{orderBom.bomName} v{orderBom.bomVersion}</code></> : ''}.
                  </span>
                )}
                {bom && (
                  <label style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 13 }}>
                    <input
                      type="checkbox"
                      checked={form.useBom}
                      onChange={(e) => setForm((f) => ({ ...f, useBom: e.target.checked }))}
                    />
                    <span>
                      Plan materials from BOM <code>{bom.bomName} v{bom.bomVersion}</code>
                    </span>
                  </label>
                )}
                <label style={{ display: 'grid', gap: 4 }}>
                  <span>Run type</span>
                  <select value={form.runType} onChange={(e) => setForm((f) => ({ ...f, runType: e.target.value }))}>
                    {RUN_TYPES.map((type) => <option key={type} value={type}>{type}</option>)}
                  </select>
                </label>
                <button type="submit" disabled={startMutation.isPending || !selectedRevisionId} style={{ marginTop: 4 }}>
                  {startMutation.isPending ? 'Starting...' : 'Start'}
                </button>
                {startMutation.isError && (
                  <p style={{ color: '#dc2626', fontSize: 12, margin: 0 }}>
                    {errorMessage(startMutation.error, 'Failed to start run.')}
                  </p>
                )}
              </form>
              <p className="inspector-hint" style={{ fontSize: 12, marginBottom: 0 }}>
                Starting a run checks <Link to={`/projects/${projectId}/rules`} style={{ color: 'var(--accent)' }}>flow rules</Link>{' '}
                for this project, workflow, and target item.
              </p>
            </section>
          </div>
        </>
      )}
    </div>
  )
}
