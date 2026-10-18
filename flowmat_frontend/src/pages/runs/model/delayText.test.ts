import { describe, expect, it } from 'vitest'
import { delayText } from './delayText'

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
