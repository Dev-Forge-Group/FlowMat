import { describe, expect, it } from 'vitest'
import { stepDeadline, stepQuantityText, stepStartsFrom } from './runStepsModel'

describe('a process step\'s quantity (ADR-011)', () => {
  it('says what a completed step made and what the others get', () => {
    expect(stepQuantityText({ status: 'completed', inputSnapshot: {}, outputSnapshot: { quantity: 98, unit: 'kg' } })).toBe('made 98 kg')
    expect(stepQuantityText({ status: 'planned', inputSnapshot: { quantity: 98, unit: 'kg' }, outputSnapshot: null })).toBe('gets 98 kg')
    expect(stepQuantityText({ status: 'running', inputSnapshot: { quantity: 3 }, outputSnapshot: null })).toBe('gets 3')
    expect(stepQuantityText({ status: 'completed', inputSnapshot: {}, outputSnapshot: {} })).toBe('')
  })
})

describe('a running step\'s time limit (F12)', () => {
  const now = new Date('2026-10-18T09:00:00Z')

  it('is due until the limit passes, then late', () => {
    expect(stepDeadline({ status: 'running', timeoutAt: '2026-10-18T09:30:00Z' }, now)).toEqual({ at: '2026-10-18T09:30:00Z', late: false })
    expect(stepDeadline({ status: 'running', timeoutAt: '2026-10-18T08:30:00Z' }, now)).toEqual({ at: '2026-10-18T08:30:00Z', late: true })
  })

  it('takes the server\'s mark even before the local clock agrees', () => {
    expect(stepDeadline({ status: 'running', timeoutAt: '2026-10-18T09:30:00Z', overdueAt: '2026-10-18T09:30:02Z' }, now)?.late).toBe(true)
  })

  it('says nothing without a limit or once the step ended', () => {
    expect(stepDeadline({ status: 'running', timeoutAt: null }, now)).toBeNull()
    expect(stepDeadline({ status: 'completed', timeoutAt: '2026-10-18T08:30:00Z', overdueAt: '2026-10-18T08:30:05Z' }, now)).toBeNull()
  })
})

describe('when a planned step can start (ADR-010, ADR-015)', () => {
  const now = new Date('2041-09-06T09:00:00Z')

  it('shows a start still ahead and nothing once it has come or the step is not planned', () => {
    expect(stepStartsFrom({ status: 'planned', scheduledAt: '2041-09-08T23:00:00Z' }, now)).toBe('2041-09-08T23:00:00Z')
    expect(stepStartsFrom({ status: 'planned', scheduledAt: '2041-09-06T08:59:00Z' }, now)).toBeNull()
    expect(stepStartsFrom({ status: 'planned', scheduledAt: null }, now)).toBeNull()
    expect(stepStartsFrom({ status: 'running', scheduledAt: '2041-09-08T23:00:00Z' }, now)).toBeNull()
  })
})
