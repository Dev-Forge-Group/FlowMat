import { describe, expect, it } from 'vitest'
import { flowRunJsonDisplay, rawFlowRunJsonBody } from './flowRunJson'

describe('manual Flow Run JSON request bodies', () => {
  it.each(['inputSnapshot', 'outputSnapshot', 'outputPayload'] as const)(
    'preserves numeric tokens and duplicate keys in %s', (field) => {
      const raw = ' {"quantity":9007199254740993.123456789,"tiny":1e-1000,"huge":1e1000,"quantity":7.0000} '
      expect(rawFlowRunJsonBody(field, raw)).toBe(`{"${field}":${raw}}`)
    },
  )

  it('escapes step metadata without parsing or rewriting its input snapshot', () => {
    const nodeId = 'node"\\\nname'
    const raw = '{"nested":{"value":9007199254740993},"text":"\\n"}'
    const body = rawFlowRunJsonBody('inputSnapshot', raw, { nodeId, scheduledAt: null })

    expect(body).toBe(`{"nodeId":${JSON.stringify(nodeId)},"scheduledAt":null,"inputSnapshot":${raw}}`)
    expect(JSON.parse(body).nodeId).toBe(nodeId)
    expect(JSON.parse(body).scheduledAt).toBeNull()
  })

  it('includes a scheduled start unchanged alongside the raw JSON', () => {
    const scheduledAt = '2040-01-02T03:04:05.000Z'
    expect(rawFlowRunJsonBody('inputSnapshot', 'null', { nodeId: 'node-1', scheduledAt }))
      .toBe(`{"nodeId":"node-1","scheduledAt":"${scheduledAt}","inputSnapshot":null}`)
  })

  it.each(['null', 'false', '0', '"raw string"', '[9007199254740993]'])(
    'retains a valid top-level JSON value: %s', (raw) => {
      expect(rawFlowRunJsonBody('outputPayload', raw)).toBe(`{"outputPayload":${raw}}`)
    },
  )

  it.each(['', ' ', '{', 'undefined', '{"value":1,}', '{} {}', 'NaN'])(
    'rejects invalid JSON before creating a request: %s', (raw) => {
      expect(() => rawFlowRunJsonBody('outputSnapshot', raw)).toThrow('Enter valid JSON.')
    },
  )
})

describe('Flow Run JSON response display', () => {
  it('uses the exact JSON string even when the parsed legacy value was rounded', () => {
    const exact = '{"quantity":9007199254740993.123456789,"tiny":1e-1000}'
    expect(flowRunJsonDisplay(exact, { quantity: 9007199254740992, tiny: 0 }))
      .toEqual({ present: true, text: exact, legacy: false })
  })

  it('distinguishes JSON null from an absent stored payload', () => {
    expect(flowRunJsonDisplay('null', null)).toEqual({ present: true, text: 'null', legacy: false })
    expect(flowRunJsonDisplay(null, { stale: true })).toEqual({ present: false, text: '', legacy: false })
  })

  it('marks a missing exact field as a legacy fallback instead of claiming exact precision', () => {
    expect(flowRunJsonDisplay(undefined, { quantity: 2 }))
      .toEqual({ present: true, text: '{\n  "quantity": 2\n}', legacy: true })
  })

  it('does not invent an absent legacy payload', () => {
    expect(flowRunJsonDisplay(undefined, null)).toEqual({ present: false, text: '', legacy: true })
    expect(flowRunJsonDisplay(undefined, undefined)).toEqual({ present: false, text: '', legacy: true })
  })
})
