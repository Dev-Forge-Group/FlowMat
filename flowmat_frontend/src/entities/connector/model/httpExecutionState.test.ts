import { describe, expect, it } from 'vitest'
import { blocksHttpTransmission, canApplyHttpExecution, executionSummary, isUnresolved, pinnedHttpBinding, savedRequestId } from './httpExecutionState'

describe('explicit HTTP execution safety', () => {
  it('reuses a saved request ID after an uncertain browser response', () => {
    const values = new Map<string, string>()
    const storage = { getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => { values.set(key, value) } }
    const first = savedRequestId(storage, 'project/run/step', () => 'first-id')
    expect(savedRequestId(storage, 'project/run/step', () => 'new-id')).toBe(first)
    expect(savedRequestId(storage, 'other/run/step', () => 'other-id')).toBe('other-id')
  })

  it('does not send when request ID persistence fails', () => {
    expect(() => savedRequestId({ getItem: () => null, setItem: () => { throw new Error('blocked') } },
      'step', () => 'id')).toThrow('blocked')
  })

  it.each(['PENDING', 'OUTCOME_UNKNOWN'])('holds another transmission for %s', (transportStatus) => {
    expect(isUnresolved({ transportStatus })).toBe(true)
    expect(executionSummary({ transportStatus, applyStatus: 'PENDING' })).toMatch(/pending|unknown/i)
  })

  it('distinguishes successful external transmission from failed local application', () => {
    expect(executionSummary({ transportStatus: 'SUCCEEDED', applyStatus: 'APPLY_FAILED', httpStatus: 200, outputJson: '{"ok":true}' }))
      .toBe('HTTP 200 accepted. Applying the result failed; it can be applied again without another HTTP request.')
    expect(isUnresolved({ transportStatus: 'SUCCEEDED' })).toBe(false)
  })

  it('does not offer an impossible local replay when response mapping saved no output', () => {
    const result = { transportStatus: 'SUCCEEDED', applyStatus: 'APPLY_FAILED', httpStatus: 200,
      errorCode: 'RESPONSE_MAPPING_FAILED', outputJson: null }
    expect(executionSummary(result)).toBe('HTTP 200 accepted, but its response could not be mapped. No output was saved, so it cannot be applied again.')
    expect(canApplyHttpExecution(result)).toBe(false)
    expect(canApplyHttpExecution({ ...result, outputJson: '{"ok":true}', errorCode: 'LOCAL_APPLY_FAILED' })).toBe(true)
  })

  it.each(['HTTP_REJECTED', 'RESPONSE_REJECTED', 'TRANSPORT_FAILED'])('can apply a resolved %s failure without a success output', (transportStatus) => {
    expect(canApplyHttpExecution({ transportStatus, applyStatus: 'APPLY_FAILED', outputJson: null })).toBe(true)
  })

  it('only recognizes the selected process binding from the published snapshot', () => {
    expect(pinnedHttpBinding({ nodeBindings: [{ processId: 'a', operationId: 'send', profileVersionId: 'v' }] }, 'b')).toBeNull()
    expect(pinnedHttpBinding({ nodeBindings: [{ processId: 'a', operationId: 'send', profileVersionId: 'v' }] }, 'a'))
      .toMatchObject({ operationId: 'send' })
    expect(pinnedHttpBinding({ nodeBindings: 'bad' }, 'a')).toBeNull()
  })
})


describe('HTTP transmission barriers match durable server evidence', () => {
  it.each(['PENDING', 'OUTCOME_UNKNOWN'])('blocks %s independently of local application status', (transportStatus) => {
    for (const applyStatus of ['PENDING', 'APPLY_FAILED', 'LATE', 'APPLIED']) {
      expect(blocksHttpTransmission({ transportStatus, applyStatus })).toBe(true)
    }
  })

  it.each(['SUCCEEDED', 'HTTP_REJECTED', 'RESPONSE_REJECTED'])('blocks unapplied %s evidence from any attempt', (transportStatus) => {
    for (const applyStatus of ['PENDING', 'APPLY_FAILED', 'LATE']) {
      expect(blocksHttpTransmission({ transportStatus, applyStatus })).toBe(true)
    }
    expect(blocksHttpTransmission({ transportStatus, applyStatus: 'APPLIED' })).toBe(false)
  })

  it('does not turn a known unsent transport failure into a previous-transmission barrier', () => {
    for (const applyStatus of ['PENDING', 'APPLY_FAILED', 'LATE', 'APPLIED']) {
      expect(blocksHttpTransmission({ transportStatus: 'TRANSPORT_FAILED', applyStatus })).toBe(false)
    }
  })
})
