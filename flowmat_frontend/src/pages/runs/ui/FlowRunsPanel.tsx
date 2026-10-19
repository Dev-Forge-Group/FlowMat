import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { delayText, heldText } from '../model/delayText'
import type { ApiEnvelope, WorkflowDto, WorkflowRevisionDetailDto, WorkflowRevisionDto } from '../../../shared/types/api'

type FlowRun = {
  flowRunId: string
  workflowRevisionId: string
  productionRunId: string | null
  runType: string
  executionMode: 'manual' | 'graph'
  status: string
  inputPayload: unknown | null
  outputPayload: unknown | null
  startedAt: string
  endedAt: string | null
}

type FlowRunStep = {
  stepId: string
  nodeId: string
  sourceConnectionId: string | null
  sourceStepId: string | null
  sequenceNo: number
  status: string
  scheduledAt: string | null
  errorCode: string | null
  errorMessage: string | null
  inputSnapshot: unknown
  outputSnapshot: unknown
  /** A Join step's branches that can still bring something, 0 once passed; absent for other steps (ADR-007). */
  joinWaiting?: number | null
}

/**
 * A Join step's line (docs/architecture/adr/ADR-007-join-gateway.md decision 11): the steps that arrived and, while it
 * waits, how many branches can still bring something.
 */
function joinLine(step: FlowRunStep, steps: FlowRunStep[]): string {
  const arrivals = (step.inputSnapshot as { arrivals?: { sourceStepId?: string }[] } | null)?.arrivals
  const from = Array.isArray(arrivals) ? arrivals.map((arrival) => {
    const source = steps.find((one) => one.stepId === arrival.sourceStepId)
    return source ? `#${source.sequenceNo}` : (arrival.sourceStepId ?? '').slice(0, 8)
  }) : []
  const joined = `joins step${from.length === 1 ? '' : 's'} ${from.join(', ')}`
  if (step.status !== 'planned') return joined
  return `${joined} · waiting for ${step.joinWaiting} branch${step.joinWaiting === 1 ? '' : 'es'}`
}

type FlowRunStepLineage = {
  step: FlowRunStep
  ancestors: FlowRunStep[]
  descendants: FlowRunStep[]
}

type FlowRunRoutePreview = {
  connectionId: string
  targetNodeId: string
  willRoute: boolean
}

type FlowRunAttempt = {
  attemptId: string
  attemptNo: number
  status: string
  startedAt: string | null
  endedAt: string | null
  retryAt: string | null
  errorCode: string | null
  errorMessage: string | null
  /** When a running attempt times out (docs/domain/flow-run-execution-policy.md EP7). */
  timeoutAt?: string | null
  /** When a production-linked attempt was marked late for passing it; it keeps running (docs/domain/flow-run.md F12). */
  overdueAt?: string | null
}

type FlowRunEvent = {
  eventId: string
  eventType: string
  stepId: string | null
  payload: unknown
  requestId: string | null
  occurredAt: string
  actorType: string
  actorId: string
}

type NodeOption = { processId: string; processName: string }

/**
 * The connection delay that set a step's scheduled time (docs/architecture/adr/ADR-010-connection-delay.md decision 9), as
 * long as nobody rescheduled it since, and whether it was then held to the work order equipment's next working time
 * (docs/architecture/adr/ADR-015-shift-aware-delay.md decision 5).
 */
function delayLine(step: FlowRunStep, events: FlowRunEvent[]): string {
  const scheduled = events.filter((event) => event.eventType === 'step_scheduled' && event.stepId === step.stepId)
  const latest = scheduled[scheduled.length - 1]
  const payload = latest?.payload as
    { reason?: unknown; delaySeconds?: unknown; readyAt?: unknown; equipmentId?: unknown; crew?: unknown } | null | undefined
  if (payload?.reason !== 'connection_delay' || typeof payload.delaySeconds !== 'number' || payload.delaySeconds <= 0) return ''
  return ` · after a ${delayText(payload.delaySeconds)} delay${heldText(payload)}`
}

type ConnectionOption = { connectionId: string; label: string; failurePolicy: string; splitKind: string | null }

/**
 * What the step received when it is not what was sent on: a Split's share (docs/architecture/adr/ADR-008-quantity-split.md
 * decision 11) and what its connection lost on the way (docs/architecture/adr/ADR-009-connection-loss-rate.md decision 10).
 * A Join's arrivals have their own line.
 */
function receivedLine(step: FlowRunStep, connections: ConnectionOption[], events: FlowRunEvent[]): string | null {
  if (step.joinWaiting != null) return null
  const loss = events.find((event) => event.eventType === 'transfer_loss' && event.stepId === step.sourceStepId
    && (event.payload as { connectionId?: unknown } | null)?.connectionId === step.sourceConnectionId)
    ?.payload as { lost?: unknown } | undefined
  const split = connections.find((connection) => connection.connectionId === step.sourceConnectionId)?.splitKind
  if (!split && !loss) return null
  const input = step.inputSnapshot as { quantity?: unknown; unit?: unknown } | null
  if (typeof input?.quantity !== 'number') return null
  const unit = typeof input.unit === 'string' ? ` ${input.unit}` : ''
  return `received ${input.quantity}${unit}${typeof loss?.lost === 'number' ? ` · lost ${loss.lost}${unit}` : ''}`
}

function revisionNodes(snapshot: unknown): NodeOption[] {
  if (!snapshot || typeof snapshot !== 'object' || !('processes' in snapshot)) return []
  const processes = snapshot.processes
  if (!Array.isArray(processes)) return []
  return processes.flatMap((value: unknown) => {
    if (!value || typeof value !== 'object' || !('processId' in value) || typeof value.processId !== 'string') return []
    const name = 'processName' in value && typeof value.processName === 'string' ? value.processName : value.processId
    return [{ processId: value.processId, processName: name }]
  })
}

function revisionConnections(snapshot: unknown): ConnectionOption[] {
  if (!snapshot || typeof snapshot !== 'object' || !('connections' in snapshot)) return []
  if (!Array.isArray(snapshot.connections)) return []
  return snapshot.connections.flatMap((value: unknown) => {
    if (!value || typeof value !== 'object' || !('connectionId' in value) || typeof value.connectionId !== 'string') return []
    const label = 'connectionLabel' in value && typeof value.connectionLabel === 'string' && value.connectionLabel.trim()
      ? value.connectionLabel : value.connectionId.slice(0, 8)
    const failurePolicy = 'failurePolicy' in value && typeof value.failurePolicy === 'string'
      ? value.failurePolicy : 'stop'
    const splitKind = 'splitKind' in value && typeof value.splitKind === 'string' ? value.splitKind : null
    return [{ connectionId: value.connectionId, label, failurePolicy, splitKind }]
  })
}

function filteredConnectionDetail(event: FlowRunEvent, connections: ConnectionOption[]): string {
  if (event.eventType !== 'connection_filtered' || !event.payload
    || typeof event.payload !== 'object' || !('connectionId' in event.payload)
    || typeof event.payload.connectionId !== 'string') return ''
  const id = event.payload.connectionId
  return ` · ${connections.find((connection) => connection.connectionId === id)?.label ?? id.slice(0, 8)} (condition false)`
}

async function getData<T>(path: string): Promise<T> {
  return unwrapApiResponse(await httpClient.get<ApiEnvelope<T>>(path))
}

async function postData<T>(path: string, body?: unknown): Promise<T> {
  return unwrapApiResponse(await httpClient.post<ApiEnvelope<T>>(path, body))
}

async function putData<T>(path: string, body: unknown): Promise<T> {
  return unwrapApiResponse(await httpClient.put<ApiEnvelope<T>>(path, body))
}

function localSchedule(value: string | null): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
}

function scheduledAtIso(value: string): string | null {
  if (!value) return null
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) throw new Error('Enter a valid scheduled start.')
  return date.toISOString()
}

export function FlowRunsPanel({
  projectId,
  workflowId,
  workflows,
  revisions,
  onSelectWorkflow,
}: {
  projectId: string
  workflowId: string
  workflows: WorkflowDto[]
  revisions: WorkflowRevisionDto[]
  onSelectWorkflow: (workflowId: string) => void
}) {
  const queryClient = useQueryClient()
  const [runId, setRunId] = useState('')
  const [revisionId, setRevisionId] = useState('')
  const [runType, setRunType] = useState('test')
  const [executionMode, setExecutionMode] = useState<'manual' | 'graph'>('manual')
  const [runInputJson, setRunInputJson] = useState('{}')
  const [nodeId, setNodeId] = useState('')
  const [inputJson, setInputJson] = useState('{}')
  const [stepScheduledAt, setStepScheduledAt] = useState('')
  const [scheduleEditStepId, setScheduleEditStepId] = useState('')
  const [scheduleEditValue, setScheduleEditValue] = useState('')
  const [outputJsonByStep, setOutputJsonByStep] = useState<Record<string, string>>({})
  const [runOutputJsonByRun, setRunOutputJsonByRun] = useState<Record<string, string>>({})
  const [errorCodeByStep, setErrorCodeByStep] = useState<Record<string, string>>({})
  const [errorMessageByStep, setErrorMessageByStep] = useState<Record<string, string>>({})
  const [runErrorCode, setRunErrorCode] = useState('MANUAL_FAILURE')
  const [runStopReason, setRunStopReason] = useState('')
  const [formError, setFormError] = useState('')
  const [previewPending, setPreviewPending] = useState(false)
  const [routePreview, setRoutePreview] = useState<{
    runId: string; stepId: string; outputJson: string; decisions: FlowRunRoutePreview[]
  } | null>(null)
  const [eventTypeFilter, setEventTypeFilter] = useState('')
  const [eventStepFilter, setEventStepFilter] = useState('')

  const published = revisions.filter((revision) => revision.status === 'published')
  const selectedRevisionId = published.some((revision) => revision.workflowRevisionId === revisionId)
    ? revisionId : published[0]?.workflowRevisionId ?? ''
  const runsQuery = useQuery({
    queryKey: ['flow-runs', workflowId],
    queryFn: () => getData<FlowRun[]>(`/flow-runs?workflowId=${encodeURIComponent(workflowId)}`),
    enabled: Boolean(workflowId),
  })
  const runs = runsQuery.data ?? []
  const selectedRunId = runs.some((run) => run.flowRunId === runId) ? runId : runs[0]?.flowRunId ?? ''
  const selectedRun = runs.find((run) => run.flowRunId === selectedRunId)
  const basePath = `/flow-runs/${encodeURIComponent(selectedRunId)}`
  const stepsQuery = useQuery({
    queryKey: ['flow-run-steps', selectedRunId],
    queryFn: () => getData<FlowRunStep[]>(`${basePath}/steps`),
    enabled: Boolean(selectedRunId),
  })
  const eventsQuery = useQuery({
    queryKey: ['flow-run-events', selectedRunId],
    queryFn: () => getData<FlowRunEvent[]>(`${basePath}/events`),
    enabled: Boolean(selectedRunId),
  })
  const revisionQuery = useQuery({
    queryKey: ['workflow-revision', workflowId, selectedRun?.workflowRevisionId],
    queryFn: () => getData<WorkflowRevisionDetailDto>(
      `/workflows/${encodeURIComponent(workflowId)}/revisions/${encodeURIComponent(selectedRun!.workflowRevisionId)}`,
    ),
    enabled: Boolean(workflowId && selectedRun?.workflowRevisionId),
  })
  const nodes = revisionNodes(revisionQuery.data?.snapshot)
  const connections = revisionConnections(revisionQuery.data?.snapshot)
  const selectedNodeId = nodes.some((node) => node.processId === nodeId) ? nodeId : nodes[0]?.processId ?? ''
  const steps = stepsQuery.data ?? []
  const events = eventsQuery.data ?? []
  const eventTypes = [...new Set(events.map((event) => event.eventType))].sort()
  const selectedEventTypeFilter = eventTypes.includes(eventTypeFilter) ? eventTypeFilter : ''
  const selectedEventStepFilter = steps.some((step) => step.stepId === eventStepFilter) ? eventStepFilter : ''
  const visibleEvents = events.filter((event) =>
    (!selectedEventTypeFilter || event.eventType === selectedEventTypeFilter)
    && (!selectedEventStepFilter || event.stepId === selectedEventStepFilter))
  const outputForStep = (stepId: string) => outputJsonByStep[`${selectedRunId}:${stepId}`] ?? '{}'
  const outputForRun = runOutputJsonByRun[selectedRunId] ?? '{}'
  const errorCodeForStep = (stepId: string) => errorCodeByStep[`${selectedRunId}:${stepId}`] ?? 'MANUAL_FAILURE'
  const errorMessageForStep = (stepId: string) => errorMessageByStep[`${selectedRunId}:${stepId}`] ?? ''
  const [expandedStepId, setExpandedStepId] = useState('')
  const attemptsQuery = useQuery({
    queryKey: ['flow-run-attempts', selectedRunId, expandedStepId],
    queryFn: () => getData<FlowRunAttempt[]>(`${basePath}/steps/${encodeURIComponent(expandedStepId)}/attempts`),
    enabled: Boolean(selectedRunId && expandedStepId),
  })
  const lineageQuery = useQuery({
    queryKey: ['flow-run-lineage', selectedRunId, expandedStepId],
    queryFn: () => getData<FlowRunStepLineage>(`${basePath}/steps/${encodeURIComponent(expandedStepId)}/lineage`),
    enabled: Boolean(selectedRunId && expandedStepId && selectedRun?.executionMode === 'graph'),
  })

  async function refreshRun(id: string) {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['flow-runs', workflowId] }),
      queryClient.invalidateQueries({ queryKey: ['flow-run-steps', id] }),
      queryClient.invalidateQueries({ queryKey: ['flow-run-events', id] }),
      queryClient.invalidateQueries({ queryKey: ['flow-run-attempts', id] }),
      queryClient.invalidateQueries({ queryKey: ['flow-run-lineage', id] }),
    ])
  }

  const startMutation = useMutation({
    mutationFn: () => postData<FlowRun>(executionMode === 'graph' ? '/flow-runs/graph' : '/flow-runs', {
      workflowId, workflowRevisionId: selectedRevisionId, runType,
      inputPayload: parseJson(runInputJson),
    }),
    onSuccess: async (run) => {
      setRunId(run.flowRunId)
      setScheduleEditStepId('')
      setEventTypeFilter('')
      setEventStepFilter('')
      await refreshRun(run.flowRunId)
    },
  })
  const actionMutation = useMutation({
    mutationFn: ({ path, body }: { path: string; body?: unknown }) => postData<unknown>(path, body),
    onSuccess: async () => { await refreshRun(selectedRunId) },
  })
  const scheduleMutation = useMutation({
    mutationFn: ({ runId, stepId, scheduledAt }: { runId: string; stepId: string; scheduledAt: string | null }) =>
      putData<FlowRunStep>(`/flow-runs/${encodeURIComponent(runId)}/steps/${encodeURIComponent(stepId)}/schedule`,
        { scheduledAt }),
    onSuccess: async (_, variables) => {
      setScheduleEditStepId('')
      await refreshRun(variables.runId)
    },
  })

  function parseJson(value: string): unknown {
    try {
      return JSON.parse(value) as unknown
    } catch {
      throw new Error('Enter valid JSON.')
    }
  }

  async function createStep(event: FormEvent) {
    event.preventDefault()
    setFormError('')
    try {
      const inputSnapshot = parseJson(inputJson)
      await actionMutation.mutateAsync({ path: `${basePath}/steps`, body: {
        nodeId: selectedNodeId, inputSnapshot, scheduledAt: scheduledAtIso(stepScheduledAt),
      } })
      setInputJson('{}')
      setStepScheduledAt('')
    } catch (error) {
      setFormError(errorMessage(error, 'Could not create the step.'))
    }
  }

  function saveSchedule(stepId: string) {
    setFormError('')
    try {
      scheduleMutation.mutate({ runId: selectedRunId, stepId, scheduledAt: scheduledAtIso(scheduleEditValue) })
    } catch (error) {
      setFormError(errorMessage(error, 'Could not save the schedule.'))
    }
  }

  async function act(path: string, body?: unknown) {
    setFormError('')
    setRoutePreview(null)
    try {
      await actionMutation.mutateAsync({ path, body })
    } catch (error) {
      setFormError(errorMessage(error, 'Could not update the run.'))
    }
  }

  async function completeStep(stepId: string) {
    try {
      await act(`${basePath}/steps/${encodeURIComponent(stepId)}/complete`,
        { outputSnapshot: parseJson(outputForStep(stepId)) })
    } catch (error) {
      setFormError(errorMessage(error, 'Enter valid JSON before completing the step.'))
    }
  }

  async function finishRun() {
    try {
      await act(`${basePath}/finish`, { outputPayload: parseJson(outputForRun) })
    } catch (error) {
      setFormError(errorMessage(error, 'Enter valid JSON before finishing the run.'))
    }
  }

  function downloadVisibleEvents() {
    const content = JSON.stringify({
      flowRunId: selectedRunId,
      workflowRevisionId: selectedRun?.workflowRevisionId,
      events: visibleEvents,
    }, null, 2)
    const url = URL.createObjectURL(new Blob([content], { type: 'application/json' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `flow-run-${selectedRunId}-events.json`
    document.body.appendChild(link)
    link.click()
    link.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), 0)
  }

  async function previewStep(stepId: string) {
    setFormError('')
    setRoutePreview(null)
    setPreviewPending(true)
    try {
      const value = outputForStep(stepId)
      const decisions = await postData<FlowRunRoutePreview[]>(
        `${basePath}/steps/${encodeURIComponent(stepId)}/preview`,
        { outputSnapshot: parseJson(value) },
      )
      setRoutePreview({ runId: selectedRunId, stepId, outputJson: value, decisions })
    } catch (error) {
      setFormError(errorMessage(error, 'Could not preview the route.'))
    } finally {
      setPreviewPending(false)
    }
  }

  if (workflows.length === 0) return <p className="inspector-hint">Create a workflow before starting a Flow Run.</p>

  return (
    <section style={{ display: 'grid', gap: 18 }}>
      <label style={{ display: 'grid', gap: 4, maxWidth: 360 }}>
        Workflow
        <select value={workflowId} onChange={(event) => onSelectWorkflow(event.target.value)}>
          {workflows.map((workflow) => (
            <option key={workflow.workflowId} value={workflow.workflowId}>{workflow.workflowName}</option>
          ))}
        </select>
      </label>

      <form onSubmit={(event) => { event.preventDefault(); void startMutation.mutateAsync().catch(() => undefined) }}
        style={{ display: 'flex', gap: 10, alignItems: 'end', flexWrap: 'wrap' }}>
        <label style={{ display: 'grid', gap: 4 }}>Published revision
          <select value={selectedRevisionId} onChange={(event) => setRevisionId(event.target.value)}>
            {published.length === 0 && <option value="">None</option>}
            {published.map((revision) => (
              <option key={revision.workflowRevisionId} value={revision.workflowRevisionId}>v{revision.revisionNo}</option>
            ))}
          </select>
        </label>
        <label style={{ display: 'grid', gap: 4 }}>Run type
          <select value={runType} onChange={(event) => setRunType(event.target.value)}>
            {['test', 'simulation', 'dry_run', 'actual'].map((type) => <option key={type} value={type}>{type}</option>)}
          </select>
        </label>
        <label style={{ display: 'grid', gap: 4 }}>Execution mode
          <select value={executionMode} onChange={(event) => setExecutionMode(event.target.value as 'manual' | 'graph')}>
            <option value="graph">Graph (route from published revision)</option>
            <option value="manual">Manual (record steps yourself)</option>
          </select>
        </label>
        <label style={{ display: 'grid', gap: 4, minWidth: 240 }}>
          Run input {executionMode === 'graph' ? '(JSON object)' : '(JSON)'}
          <textarea value={runInputJson} onChange={(event) => setRunInputJson(event.target.value)} rows={2} />
        </label>
        <button type="submit" disabled={!selectedRevisionId || startMutation.isPending}>Start Flow Run</button>
      </form>
      {startMutation.isError && <p role="alert">{errorMessage(startMutation.error, 'Could not start the run.')}</p>}

      {runsQuery.isLoading && <p>Loading Flow Runs...</p>}
      {runsQuery.isError && <p role="alert">{errorMessage(runsQuery.error, 'Could not load Flow Runs.')}</p>}
      {runs.length === 0 && !runsQuery.isLoading && <p className="inspector-hint">No Flow Runs for this workflow.</p>}
      {runs.length > 0 && (
        <label style={{ display: 'grid', gap: 4, maxWidth: 480 }}>Flow Run
          <select value={selectedRunId} onChange={(event) => {
            setRunId(event.target.value); setExpandedStepId(''); setRoutePreview(null)
            setEventTypeFilter(''); setEventStepFilter('')
            setScheduleEditStepId('')
          }}>
            {runs.map((run) => (
              <option key={run.flowRunId} value={run.flowRunId}>
                {run.flowRunId.slice(0, 8)} · {run.executionMode ?? 'manual'} · {run.runType} · {run.status} · {new Date(run.startedAt).toLocaleString()}
              </option>
            ))}
          </select>
        </label>
      )}

      {selectedRun && (
        <>
          <div>Revision v{revisions.find((revision) => revision.workflowRevisionId === selectedRun.workflowRevisionId)?.revisionNo ?? '?'}
            {' · '}Mode: {selectedRun.executionMode ?? 'manual'} · Status: {selectedRun.status}
            {selectedRun.productionRunId && (
              <> · <Link to={`/projects/${projectId}/runs/${selectedRun.productionRunId}`}>Open production run</Link></>
            )}
          </div>
          {selectedRun.inputPayload != null && <div>
            <strong>Run input</strong>
            <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
              {JSON.stringify(selectedRun.inputPayload, null, 2)}
            </pre>
          </div>}
          {selectedRun.outputPayload != null && <div>
            <strong>Final run output</strong>
            <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
              {JSON.stringify(selectedRun.outputPayload, null, 2)}
            </pre>
          </div>}
          {selectedRun.status === 'running' && !selectedRun.productionRunId && selectedRun.executionMode !== 'graph' && (
            <>
              <form onSubmit={(event) => void createStep(event)} style={{ display: 'grid', gap: 8, maxWidth: 560 }}>
                <label>Node in pinned revision
                  <select value={selectedNodeId} onChange={(event) => setNodeId(event.target.value)}>
                    {nodes.map((node) => <option key={node.processId} value={node.processId}>{node.processName}</option>)}
                  </select>
                </label>
                <label>Input snapshot (JSON)
                  <textarea value={inputJson} onChange={(event) => setInputJson(event.target.value)} rows={2} />
                </label>
                <label>Scheduled start (optional)
                  <input type="datetime-local" value={stepScheduledAt}
                    onChange={(event) => setStepScheduledAt(event.target.value)} />
                </label>
                <button type="submit" disabled={!selectedNodeId || actionMutation.isPending}>Add planned step</button>
              </form>
              {revisionQuery.isError && <p role="alert">{errorMessage(revisionQuery.error, 'Could not load revision nodes.')}</p>}
              {nodes.length === 0 && !revisionQuery.isLoading && <p className="inspector-hint">This revision has no process nodes.</p>}
            </>
          )}

          <h3 style={{ marginBottom: 0 }}>Steps</h3>
          {stepsQuery.isLoading && <p>Loading steps...</p>}
          {stepsQuery.isError && <p role="alert">{errorMessage(stepsQuery.error, 'Could not load steps.')}</p>}
          {steps.length === 0 && !stepsQuery.isLoading && <p className="inspector-hint">No steps recorded.</p>}
          {steps.map((step) => (
            <div key={step.stepId} style={{ border: '1px solid var(--border)', borderRadius: 8, padding: 12 }}>
              <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
                <strong>#{step.sequenceNo} {nodes.find((node) => node.processId === step.nodeId)?.processName ?? step.nodeId}</strong>
                <span>{step.status}</span>
                {/* A planned step that keeps its last failure waits to retry (docs/domain/flow-run-execution-policy.md EP10). */}
                {step.status === 'planned' && step.errorCode && <span>waiting to retry</span>}
                {step.scheduledAt && <span>Scheduled: {new Date(step.scheduledAt).toLocaleString()}{delayLine(step, events)}</span>}
                {step.joinWaiting != null && <span>{joinLine(step, steps)}</span>}
                {step.sourceStepId && step.joinWaiting == null && <span title={step.sourceStepId}>
                  from step #{steps.find((source) => source.stepId === step.sourceStepId)?.sequenceNo
                    ?? step.sourceStepId.slice(0, 8)}
                </span>}
                {step.sourceConnectionId && step.joinWaiting == null && <span>
                  via {connections.find((connection) => connection.connectionId === step.sourceConnectionId)?.label
                    ?? step.sourceConnectionId.slice(0, 8)}
                  {' · '}on failure: {connections.find((connection) => connection.connectionId === step.sourceConnectionId)?.failurePolicy
                    ?? 'stop'}
                </span>}
                {receivedLine(step, connections, events) && <span>{receivedLine(step, connections, events)}</span>}
                {step.errorCode && <span role="alert">{step.errorCode}</span>}
                <button type="button" onClick={() => setExpandedStepId(expandedStepId === step.stepId ? '' : step.stepId)}>
                  {expandedStepId === step.stepId ? 'Hide attempts' : 'Show attempts'}
                </button>
                {selectedRun.status === 'running' && !selectedRun.productionRunId && (
                  <>
                    {step.status === 'planned' && step.joinWaiting == null && <button type="button" disabled={actionMutation.isPending}
                      onClick={() => void act(`${basePath}/steps/${encodeURIComponent(step.stepId)}/start`)}>Start</button>}
                    {step.status === 'planned' && step.joinWaiting == null && <button type="button" disabled={scheduleMutation.isPending}
                      onClick={() => {
                        scheduleMutation.reset()
                        setScheduleEditStepId(step.stepId)
                        setScheduleEditValue(localSchedule(step.scheduledAt))
                      }}>Edit schedule</button>}
                    {step.status === 'failed' && selectedRun.executionMode !== 'graph' && <button type="button" disabled={actionMutation.isPending}
                      onClick={() => void act(`${basePath}/steps/${encodeURIComponent(step.stepId)}/retry`)}>Retry</button>}
                    {step.status === 'running' && <>
                      {selectedRun.executionMode === 'graph' && <button type="button"
                        disabled={actionMutation.isPending || previewPending}
                        onClick={() => void previewStep(step.stepId)}>Preview routes</button>}
                      <button type="button" disabled={actionMutation.isPending} onClick={() => void completeStep(step.stepId)}>Complete</button>
                      <button type="button" disabled={actionMutation.isPending || !errorCodeForStep(step.stepId).trim()}
                        onClick={() => void act(`${basePath}/steps/${encodeURIComponent(step.stepId)}/fail`,
                          { errorCode: errorCodeForStep(step.stepId),
                            errorMessage: errorMessageForStep(step.stepId) || null })}>Fail</button>
                    </>}
                  </>
                )}
              </div>
              {step.status === 'planned' && scheduleEditStepId === step.stepId
                && <div style={{ display: 'grid', gap: 6, maxWidth: 560, marginTop: 8 }}>
                <label>Scheduled start for step #{step.sequenceNo}
                  <input type="datetime-local" value={scheduleEditValue}
                    onChange={(event) => setScheduleEditValue(event.target.value)} />
                </label>
                <div style={{ display: 'flex', gap: 8 }}>
                  <button type="button" disabled={scheduleMutation.isPending}
                    onClick={() => saveSchedule(step.stepId)}>Save schedule</button>
                  <button type="button" onClick={() => setScheduleEditStepId('')}>Cancel edit</button>
                </div>
                {scheduleMutation.isError && <p role="alert">
                  {errorMessage(scheduleMutation.error, 'Could not save the schedule.')}
                </p>}
              </div>}
              {selectedRun.status === 'running' && !selectedRun.productionRunId && step.status === 'running' && <div
                style={{ display: 'grid', gap: 6, maxWidth: 560, marginTop: 8 }}>
                <label>Output snapshot for step #{step.sequenceNo} (JSON)
                  <textarea value={outputForStep(step.stepId)} onChange={(event) => {
                    setOutputJsonByStep((current) => ({
                      ...current, [`${selectedRunId}:${step.stepId}`]: event.target.value,
                    }))
                    if (routePreview?.stepId === step.stepId) setRoutePreview(null)
                  }} rows={2} />
                </label>
                <label>Failure code for step #{step.sequenceNo}
                  <input value={errorCodeForStep(step.stepId)} onChange={(event) => setErrorCodeByStep((current) => ({
                    ...current, [`${selectedRunId}:${step.stepId}`]: event.target.value,
                  }))} maxLength={100} />
                </label>
                <label>Failure reason for step #{step.sequenceNo}
                  <textarea value={errorMessageForStep(step.stepId)} onChange={(event) => setErrorMessageByStep((current) => ({
                    ...current, [`${selectedRunId}:${step.stepId}`]: event.target.value,
                  }))} maxLength={4000} rows={2} />
                </label>
                {selectedRun.executionMode === 'graph' && <p className="inspector-hint" style={{ margin: 0 }}>
                  Routing reads quantity, unit, item and attrs from this object. Preview uses the pinned revision;
                  completion checks it again.
                </p>}
              </div>}
              {routePreview?.runId === selectedRunId && routePreview.stepId === step.stepId
                && routePreview.outputJson === outputForStep(step.stepId) && <div role="status" style={{ marginTop: 8 }}>
                  <strong>Route preview</strong>
                  {routePreview.decisions.length === 0 && <div>No outgoing connections.</div>}
                  {routePreview.decisions.map((decision) => <div key={decision.connectionId}>
                    {decision.willRoute ? 'Will route' : 'Condition false'}: {' '}
                    {connections.find((connection) => connection.connectionId === decision.connectionId)?.label
                      ?? decision.connectionId.slice(0, 8)} → {' '}
                    {nodes.find((node) => node.processId === decision.targetNodeId)?.processName
                      ?? decision.targetNodeId}
                  </div>)}
                </div>}
              {expandedStepId === step.stepId && (
                <div style={{ marginTop: 8, fontSize: 13 }}>
                  <div>Input snapshot</div>
                  <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
                    {JSON.stringify(step.inputSnapshot ?? null, null, 2)}
                  </pre>
                  {step.outputSnapshot != null && <>
                    <div>Output snapshot</div>
                    <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
                      {JSON.stringify(step.outputSnapshot, null, 2)}
                    </pre>
                  </>}
                  {step.errorMessage && <p role="alert">{step.errorMessage}</p>}
                  {selectedRun.executionMode === 'graph' && <div>
                    <strong>Execution path</strong>
                    {lineageQuery.isLoading && <span> Loading path...</span>}
                    {lineageQuery.isError && <p role="alert">
                      {errorMessage(lineageQuery.error, 'Could not load the execution path.')}
                    </p>}
                    {lineageQuery.data && <>
                      <div>Upstream: {lineageQuery.data.ancestors.length === 0 ? 'none' :
                        lineageQuery.data.ancestors.map((ancestor) => `#${ancestor.sequenceNo}`).join(' → ')}</div>
                      <div>Downstream: {lineageQuery.data.descendants.length === 0 ? 'none' :
                        lineageQuery.data.descendants.map((descendant) => `#${descendant.sequenceNo}`).join(', ')}</div>
                    </>}
                  </div>}
                  {attemptsQuery.isLoading && <span>Loading attempts...</span>}
                  {(attemptsQuery.data ?? []).map((attempt) => (
                    <div key={attempt.attemptId} style={{ marginTop: 6 }}>
                      <strong>Attempt {attempt.attemptNo}: {attempt.status}{attempt.errorCode ? ` (${attempt.errorCode})` : ''}</strong>
                      {attempt.startedAt && <div>Started: {new Date(attempt.startedAt).toLocaleString()}</div>}
                      {attempt.endedAt && <div>Ended: {new Date(attempt.endedAt).toLocaleString()}</div>}
                      {attempt.status === 'running' && attempt.timeoutAt
                        && <div>{selectedRun.productionRunId ? (attempt.overdueAt ? 'Late since' : 'Due by') : 'Times out'}:{' '}
                          {new Date(attempt.timeoutAt).toLocaleString()}</div>}
                      {attempt.retryAt && <div>Retry scheduled: {new Date(attempt.retryAt).toLocaleString()}</div>}
                      {attempt.errorMessage && <div>Reason: {attempt.errorMessage}</div>}
                    </div>
                  ))}
                </div>
              )}
            </div>
          ))}
          {selectedRun.status === 'running' && !selectedRun.productionRunId && (
            <div style={{ display: 'grid', gap: 8, maxWidth: 560 }}>
              <label>Final run output (JSON)
                <textarea value={outputForRun} onChange={(event) => setRunOutputJsonByRun((current) => ({
                  ...current, [selectedRunId]: event.target.value,
                }))} rows={2} />
              </label>
              <label>Flow Run failure code
                <input value={runErrorCode} onChange={(event) => setRunErrorCode(event.target.value)} maxLength={100} />
              </label>
              <button type="button" disabled={actionMutation.isPending || stepsQuery.isLoading || stepsQuery.isError
                || steps.some((step) => step.status !== 'completed' && step.status !== 'skipped')}
                onClick={() => void finishRun()}>Finish Flow Run</button>
              <label>Reason for ending this run
                <textarea value={runStopReason} onChange={(event) => setRunStopReason(event.target.value)}
                  maxLength={4000} rows={2} />
              </label>
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                <button type="button" disabled={actionMutation.isPending || !runStopReason.trim()}
                  onClick={() => void act(`${basePath}/cancel`, { reason: runStopReason })}>Cancel Flow Run</button>
                <button type="button" disabled={actionMutation.isPending || !runStopReason.trim() || !runErrorCode.trim()}
                  onClick={() => void act(`${basePath}/fail`, { errorCode: runErrorCode, errorMessage: runStopReason })}>
                  Mark Flow Run failed
                </button>
              </div>
            </div>
          )}
          {formError && <p role="alert">{formError}</p>}

          <h3 style={{ marginBottom: 0 }}>Events</h3>
          {eventsQuery.isLoading && <p>Loading events...</p>}
          {eventsQuery.isError && <p role="alert">{errorMessage(eventsQuery.error, 'Could not load events.')}</p>}
          {events.length > 0 && <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            <label>Event type
              <select value={selectedEventTypeFilter} onChange={(event) => setEventTypeFilter(event.target.value)}>
                <option value="">All types</option>
                {eventTypes.map((type) => <option key={type} value={type}>{type}</option>)}
              </select>
            </label>
            <label>Event step
              <select value={selectedEventStepFilter} onChange={(event) => setEventStepFilter(event.target.value)}>
                <option value="">All steps</option>
                {steps.map((step) => <option key={step.stepId} value={step.stepId}>
                  Step {step.sequenceNo} · {nodes.find((node) => node.processId === step.nodeId)?.processName ?? step.nodeId}
                </option>)}
              </select>
            </label>
            <span role="status">{visibleEvents.length} of {events.length} events</span>
            <button type="button" disabled={visibleEvents.length === 0}
              onClick={downloadVisibleEvents}>Download visible events (JSON)</button>
          </div>}
          <ol style={{ marginTop: 0 }}>
            {visibleEvents.map((event) => (
              <li key={event.eventId}><details>
                <summary>{new Date(event.occurredAt).toLocaleString()} · {event.eventType}
                  {filteredConnectionDetail(event, connections)}
                  {event.stepId ? ` · step #${steps.find((step) => step.stepId === event.stepId)?.sequenceNo
                    ?? event.stepId.slice(0, 8)}` : ''} · {event.actorId}</summary>
                <div>Actor: {event.actorType ?? 'unknown'} · {event.actorId}</div>
                {event.requestId && <div>Request ID: {event.requestId}</div>}
                <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
                  {JSON.stringify(event.payload ?? null, null, 2)}
                </pre>
              </details></li>
            ))}
          </ol>
        </>
      )}
    </section>
  )
}
