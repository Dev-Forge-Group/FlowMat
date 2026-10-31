import { describe, expect, it } from 'vitest'
import { rawFlowRunEventsJson } from './flowRunEventsJson'

const event = (overrides: Record<string, unknown> = {}) => ({
  eventId: 'event-1', eventType: 'step_completed', stepId: 'step-1',
  payload: { quantity: 9007199254740992 }, requestId: 'request-1',
  occurredAt: '2040-01-02T03:04:05Z', actorType: 'user', actorId: 'actor-1', ...overrides,
})

const download = (events: ReturnType<typeof event>[]) => rawFlowRunEventsJson({
  flowRunId: 'run-1', workflowRevisionId: 'revision-1', events,
})

describe('Flow Run event JSON download', () => {
  it('inserts exact raw payload tokens without rounding or changing their JSON type', () => {
    const raw = '{"quantity":9007199254740993.123456789,"tiny":1e-1000,"huge":1e1000,"quantity":7.0000}'
    const result = download([event({ payloadJson: raw })])

    expect(result.json).toContain(`"payload":${raw}`)
    expect(result.json).not.toContain('9007199254740992')
    expect(result.json).not.toContain('payloadJson')
    expect(result.legacy).toBe(false)
    expect(JSON.parse(result.json)).toMatchObject({
      flowRunId: 'run-1', workflowRevisionId: 'revision-1',
      events: [{ eventId: 'event-1', actorId: 'actor-1' }],
    })
  })

  it.each(['null', 'false', '0', '"raw string"', '[9007199254740993]', '{"nested":{"value":-0}}'])(
    'retains a valid top-level JSON payload: %s', (raw) => {
      const result = download([event({ payloadJson: raw })])
      expect(result.json).toContain(`"payload":${raw}`)
      expect(result.legacy).toBe(false)
      expect(JSON.parse(result.json).events[0].payload).toEqual(JSON.parse(raw))
    },
  )

  it('exports an absent stored value as null without using a stale parsed payload', () => {
    const result = download([event({ payloadJson: null, payload: { stale: true } })])
    expect(JSON.parse(result.json).events[0].payload).toBeNull()
    expect(result.legacy).toBe(false)
  })

  it('escapes envelope and event metadata without changing raw payload text', () => {
    const raw = '{"message":"line\\nnext","amount":9007199254740993}'
    const flowRunId = 'run"\\\nname'
    const actorId = 'actor"\\\nname'
    const result = rawFlowRunEventsJson({ flowRunId, workflowRevisionId: undefined,
      events: [event({ actorId, payloadJson: raw })] })

    expect(result.json).toContain(`"payload":${raw}`)
    const parsed = JSON.parse(result.json)
    expect(parsed.flowRunId).toBe(flowRunId)
    expect(parsed).not.toHaveProperty('workflowRevisionId')
    expect(parsed.events[0].actorId).toBe(actorId)
  })

  it('exports only the supplied visible events in their existing order and preserves metadata', () => {
    const result = download([
      event({ eventId: 'third', payloadJson: '{"x":3}', futureMetadata: { tag: 'retained' } }),
      event({ eventId: 'first', payloadJson: '{"x":1}', requestId: null }),
    ])
    const parsed = JSON.parse(result.json)
    expect(Object.keys(parsed)).toEqual(['flowRunId', 'workflowRevisionId', 'events'])
    expect(parsed.events.map((item: { eventId: string }) => item.eventId)).toEqual(['third', 'first'])
    expect(parsed.events[0].futureMetadata).toEqual({ tag: 'retained' })
    expect(parsed.events[1].requestId).toBeNull()
    expect(Object.keys(parsed.events[0])).toEqual([
      'eventId', 'eventType', 'stepId', 'payload', 'requestId', 'occurredAt', 'actorType', 'actorId', 'futureMetadata',
    ])
  })

  it('keeps the legacy export compatible while explicitly marking potentially rounded payloads', () => {
    const values = [event({ payload: { quantity: 2 }, optional: undefined }), event({ payload: null })]
    const result = download(values)
    expect(JSON.parse(result.json)).toEqual({ flowRunId: 'run-1', workflowRevisionId: 'revision-1', events: values })
    expect(result.legacy).toBe(true)
    expect(download([event({ payloadJson: '{"quantity":3}' }), values[0]]).legacy).toBe(true)
  })

  it('preserves the old omission of an undefined legacy payload', () => {
    const result = download([event({ payload: undefined })])
    expect(JSON.parse(result.json).events[0]).not.toHaveProperty('payload')
    expect(result.legacy).toBe(true)
  })

  it('exports an empty visible list without inventing events or a precision warning', () => {
    const result = download([])
    expect(JSON.parse(result.json).events).toEqual([])
    expect(result.legacy).toBe(false)
  })

  it.each(['', ' ', '{', 'undefined', '{"value":1,}', '{} {}', 'NaN'])(
    'rejects invalid exact JSON instead of downloading a rounded fallback: %s', (raw) => {
      expect(() => download([event({ payloadJson: raw })])).toThrow('Event payload JSON is invalid. Download is unavailable.')
    },
  )

  it('rejects an invalid raw-field type rather than treating it as an older response', () => {
    expect(() => download([event({ payloadJson: 3 })]))
      .toThrow('Event payload JSON is invalid. Download is unavailable.')
  })
})
