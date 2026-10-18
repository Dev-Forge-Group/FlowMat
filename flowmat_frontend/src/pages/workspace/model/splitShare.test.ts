import { describe, expect, it } from 'vitest'
import { splitInput } from './splitShare'

describe('a Split gateway\'s share (ADR-008)', () => {
  it('sends a percent, a fixed quantity, the rest or nothing', () => {
    expect(splitInput('share', '70')).toEqual({ splitKind: 'share', splitValue: 70 })
    expect(splitInput('fixed', ' 0.5 ')).toEqual({ splitKind: 'fixed', splitValue: 0.5 })
    expect(splitInput('rest', '12')).toEqual({ splitKind: 'rest' })
    expect(splitInput('', '')).toEqual({ clearSplit: true })
  })

  it('refuses what the server would refuse', () => {
    expect(splitInput('share', '')).toEqual({ error: 'Enter the percent.' })
    expect(splitInput('share', '0')).toEqual({ error: 'A percent share is above 0 and at most 100.' })
    expect(splitInput('share', '100.5')).toEqual({ error: 'A percent share is above 0 and at most 100.' })
    expect(splitInput('fixed', 'lots')).toEqual({ error: 'Enter the quantity.' })
    expect(splitInput('fixed', '-1')).toEqual({ error: 'A fixed share is above 0.' })
  })
})
