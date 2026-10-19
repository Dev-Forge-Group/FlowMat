import { describe, expect, it } from 'vitest'
import { delayText, heldText } from './delayText'

describe('a connection\'s delay in words (ADR-010)', () => {
  it('says hours, minutes and seconds that are there', () => {
    expect(delayText(7200)).toBe('2 h')
    expect(delayText(5400)).toBe('1 h 30 min')
    expect(delayText(3661)).toBe('1 h 1 min 1 s')
    expect(delayText(45)).toBe('45 s')
    expect(delayText(0.5)).toBe('0.5 s')
    expect(delayText(0)).toBe('0 s')
  })
})

describe('what a delayed step was held for (ADR-015, ADR-016)', () => {
  const readyAt = '2041-09-06T09:00:00Z'

  it('names the equipment, the crew or both, and nothing when the step was not held', () => {
    expect(heldText({ readyAt, equipmentId: 'press' })).toBe(", then the equipment's next working time")
    expect(heldText({ readyAt, crew: ['kim'] })).toBe(", then the crew's next working time")
    expect(heldText({ readyAt, equipmentId: 'press', crew: ['kim'] })).toBe(', then the next time the equipment and crew work')
    expect(heldText({ equipmentId: 'press', crew: ['kim'] })).toBe('')
  })
})
