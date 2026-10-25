import { describe, expect, it } from 'vitest'
import { laborCostPersonText, laborCostText, rateLineText } from './laborCostModel'

describe('labor cost', () => {
  it('says the cost, and when some worked time has no rate', () => {
    expect(laborCostText({ cost: 45, costComplete: true })).toBe('Labor cost: 45')
    expect(laborCostText({ cost: 45, costComplete: false })).toBe('Known labor cost: 45 (some worked time has no rate)')
    expect(laborCostPersonText({ userId: 'kim', workedMinutes: 90, cost: 45, costComplete: true })).toBe('kim: 90 min · 45')
    expect(laborCostPersonText({ userId: 'lee', workedMinutes: 30, cost: 0, costComplete: false })).toBe('lee: 30 min · no rate')
    expect(laborCostPersonText({ userId: 'kim', workedMinutes: 150, cost: 45, costComplete: false }))
      .toBe('kim: 150 min · 45 known, some without a rate')
  })

  it('says what a saved rate is and from when', () => {
    const line = { rateId: 'r1', hourlyRate: 30, effectiveFrom: '2041-09-01', reason: 'Base', setBy: 'demo-owner',
      setAt: '2041-08-30T09:00:00Z', state: 'current' as const, removedBy: null, removedAt: null }
    expect(rateLineText(line)).toBe('30/h from 2041-09-01 (current) · Base · set by demo-owner')
    expect(rateLineText({ ...line, hourlyRate: null, effectiveFrom: null, reason: null, state: 'past' }))
      .toBe('unknown rate from the start (past) · set by demo-owner')
    expect(rateLineText({ ...line, state: 'removed', removedBy: 'demo-owner' }))
      .toBe('30/h from 2041-09-01 (removed by demo-owner) · Base · set by demo-owner')
  })
})
