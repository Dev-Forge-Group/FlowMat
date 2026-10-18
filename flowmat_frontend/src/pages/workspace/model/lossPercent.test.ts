import { describe, expect, it } from 'vitest'
import { lossPercentText, lossRateInput } from './lossPercent'

describe('a connection\'s loss as a percent (ADR-009)', () => {
  it('shows the stored fraction as a percent', () => {
    expect(lossPercentText(0.02)).toBe('2')
    expect(lossPercentText(0.0007)).toBe('0.07')
    expect(lossPercentText(0.9999)).toBe('99.99')
    expect(lossPercentText(0)).toBe('0')
    expect(lossPercentText(null)).toBe('')
  })

  it('sends the percent as a fraction with at most 4 decimals', () => {
    expect(lossRateInput('2')).toEqual({ lossRate: 0.02 })
    expect(lossRateInput(' 0.07 ')).toEqual({ lossRate: 0.0007 })
    expect(lossRateInput('99.99')).toEqual({ lossRate: 0.9999 })
    expect(lossRateInput('')).toEqual({ lossRate: null })
  })

  it('refuses what the server would refuse', () => {
    expect(lossRateInput('100')).toEqual({ error: 'Loss % is 0 or more and below 100.' })
    expect(lossRateInput('-1')).toEqual({ error: 'Loss % is 0 or more and below 100.' })
    expect(lossRateInput('lots')).toEqual({ error: 'Loss % is 0 or more and below 100.' })
    expect(lossRateInput('12.345')).toEqual({ error: 'Loss % takes up to 2 decimals.' })
  })
})
