import { useMemo } from 'react'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { rawFlowRunEventsJson, validatedFlowRunEventPayloadJson, type FlowRunEvent } from '../model/flowRunEventsJson'
import { FlowRunEventTimeline } from './FlowRunEventTimeline'
import { FlowRunJsonValue } from './FlowRunJsonValue'

type EventStep = { stepId: string; nodeId: string; sequenceNo: number }
type EventNode = { processId: string; processName: string }
type EventConnection = { connectionId: string; label: string }

function filteredConnectionDetail(event: FlowRunEvent, connections: readonly EventConnection[]): string {
  if (event.eventType !== 'connection_filtered' || !event.payload
    || typeof event.payload !== 'object' || !('connectionId' in event.payload)
    || typeof event.payload.connectionId !== 'string') return ''
  const id = event.payload.connectionId
  return ` · ${connections.find((connection) => connection.connectionId === id)?.label ?? id.slice(0, 8)} (condition false)`
}

function FlowRunEventPayload({ event }: { event: FlowRunEvent }) {
  let json: string | null | undefined
  try {
    json = validatedFlowRunEventPayloadJson(event.payloadJson)
  } catch {
    return <p>Event payload JSON is invalid. The payload cannot be displayed.</p>
  }
  return <FlowRunJsonValue label="Event payload" json={json} value={event.payload} showAbsent />
}

export function FlowRunEventLog({
  runId, workflowRevisionId, events, steps, nodes, connections,
  eventTypeFilter, eventStepFilter, onEventTypeFilterChange, onEventStepFilterChange,
  isLoading, isError, error,
}: {
  runId: string
  workflowRevisionId: string | undefined
  events: readonly FlowRunEvent[]
  steps: readonly EventStep[]
  nodes: readonly EventNode[]
  connections: readonly EventConnection[]
  eventTypeFilter: string
  eventStepFilter: string
  onEventTypeFilterChange: (value: string) => void
  onEventStepFilterChange: (value: string) => void
  isLoading: boolean
  isError: boolean
  error: unknown
}) {
  const eventTypes = [...new Set(events.map((event) => event.eventType))].sort()
  const selectedEventTypeFilter = eventTypes.includes(eventTypeFilter) ? eventTypeFilter : ''
  const selectedEventStepFilter = steps.some((step) => step.stepId === eventStepFilter) ? eventStepFilter : ''
  const visibleEvents = useMemo(() => events.filter((event) =>
    (!selectedEventTypeFilter || event.eventType === selectedEventTypeFilter)
    && (!selectedEventStepFilter || event.stepId === selectedEventStepFilter)),
  [events, selectedEventTypeFilter, selectedEventStepFilter])
  const { download: eventDownload, error: eventDownloadError } = useMemo(() => {
    try {
      return { download: rawFlowRunEventsJson({
        flowRunId: runId, workflowRevisionId, events: visibleEvents,
      }), error: '' }
    } catch (error) {
      return { download: undefined, error: errorMessage(error, 'Could not prepare the event JSON download.') }
    }
  }, [runId, workflowRevisionId, visibleEvents])

  function downloadVisibleEvents() {
    if (!eventDownload || isError) return
    const url = URL.createObjectURL(new Blob([eventDownload.json], { type: 'application/json' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `flow-run-${runId}-events.json`
    document.body.appendChild(link)
    link.click()
    link.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), 0)
  }

  return <>
    <h3 style={{ marginBottom: 0 }}>Events</h3>
    {isLoading && <p>Loading events...</p>}
    {isError && <p role="alert">{errorMessage(error, 'Could not load events.')}</p>}
    {events.length > 0 && <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
      <label>Event type
        <select value={selectedEventTypeFilter} onChange={(event) => onEventTypeFilterChange(event.target.value)}>
          <option value="">All types</option>
          {eventTypes.map((type) => <option key={type} value={type}>{type}</option>)}
        </select>
      </label>
      <label>Event step
        <select value={selectedEventStepFilter} onChange={(event) => onEventStepFilterChange(event.target.value)}>
          <option value="">All steps</option>
          {steps.map((step) => <option key={step.stepId} value={step.stepId}>
            Step {step.sequenceNo} · {nodes.find((node) => node.processId === step.nodeId)?.processName ?? step.nodeId}
          </option>)}
        </select>
      </label>
      <span role="status">{visibleEvents.length} of {events.length} events</span>
      <button type="button" disabled={visibleEvents.length === 0 || !eventDownload}
        onClick={downloadVisibleEvents}>Download visible events (JSON)</button>
    </div>}
    <FlowRunEventTimeline events={visibleEvents} steps={steps} nodes={nodes} />
    {eventDownloadError && <p role="alert">{eventDownloadError}</p>}
    {eventDownload?.legacy && visibleEvents.length > 0 && <p className="inspector-hint">
      Legacy event JSON response: numeric precision may already be lost in the download.
    </p>}
    <ol style={{ marginTop: 0 }}>
      {visibleEvents.map((event) => (
        <li key={event.eventId}><details>
          <summary>{new Date(event.occurredAt).toLocaleString()} · {event.eventType}
            {filteredConnectionDetail(event, connections)}
            {event.stepId ? ` · step #${steps.find((step) => step.stepId === event.stepId)?.sequenceNo
              ?? event.stepId.slice(0, 8)}` : ''} · {event.actorId}</summary>
          <div>Actor: {event.actorType ?? 'unknown'} · {event.actorId}</div>
          {event.requestId && <div>Request ID: {event.requestId}</div>}
          <FlowRunEventPayload event={event} />
        </details></li>
      ))}
    </ol>
  </>
}
