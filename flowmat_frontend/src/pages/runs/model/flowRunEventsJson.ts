export type FlowRunEvent = {
  eventId: string
  eventType: string
  stepId: string | null
  payload: unknown
  payloadJson?: string | null
  requestId: string | null
  occurredAt: string
  actorType: string
  actorId: string
}

type EventJsonSource = { payload: unknown; payloadJson?: unknown }

type FlowRunEventsJsonSource = {
  flowRunId: string
  workflowRevisionId: string | undefined
  events: readonly EventJsonSource[]
}

const invalidPayloadMessage = 'Event payload JSON is invalid. Download is unavailable.'

export function validatedFlowRunEventPayloadJson(value: unknown): string | null | undefined {
  if (value === undefined || value === null) return value
  if (typeof value !== 'string') throw new Error(invalidPayloadMessage)
  try {
    JSON.parse(value)
  } catch {
    throw new Error(invalidPayloadMessage)
  }
  return value
}

export function rawFlowRunEventsJson({ flowRunId, workflowRevisionId, events }: FlowRunEventsJsonSource): {
  json: string
  legacy: boolean
} {
  let legacy = false
  const serializedEvents = events.map((event) => {
    const exact = validatedFlowRunEventPayloadJson(event.payloadJson)
    if (exact === undefined) legacy = true
    const payload = exact === undefined ? JSON.stringify(event.payload) : exact === null ? 'null' : exact
    let payloadIncluded = false
    const fields = Object.entries(event).flatMap(([key, value]) => {
      if (key === 'payloadJson') return []
      if (key === 'payload') payloadIncluded = true
      const json = key === 'payload' ? payload : JSON.stringify(value)
      return json === undefined ? [] : [`${JSON.stringify(key)}:${json}`]
    })
    if (!payloadIncluded && payload !== undefined) fields.push(`"payload":${payload}`)
    return `{${fields.join(',')}}`
  })
  const metadata = JSON.stringify({ flowRunId, workflowRevisionId }).slice(1, -1)
  return { json: `{${metadata},"events":[${serializedEvents.join(',')}]}`, legacy }
}
