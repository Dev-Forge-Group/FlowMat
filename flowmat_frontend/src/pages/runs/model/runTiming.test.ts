import { describe, expect, it } from 'vitest'
import { runTiming, spanText } from './runTiming'

const at = (time: string) => `2026-11-07T${time}:00Z`
const step = (stepId: string, nodeName: string, sequenceNo: number, status: string, startedAt: string | null,
  endedAt: string | null, sourceStepId: string | null = null) => ({ stepId, nodeId: `n-${stepId}`, nodeName, sequenceNo, status,
  sourceStepId, startedAt, endedAt })
const worked = (runLaborId: string, userId: string, stepId: string | null, from: string | null, to: string | null, cancelled = false) =>
  ({ runLaborId, userId, cancelled, stepId, workedFrom: from, workedTo: to })
const now = Date.parse(at('10:30'))

describe('runTiming', () => {
  it('lays the steps out depth first on one axis, the running one open to now and the waiting one without a bar', () => {
    const timing = runTiming([
      step('bake', 'Bake', 2, 'running', at('09:30'), null, 'mix'),
      step('mix', 'Mix', 1, 'completed', at('09:00'), at('09:30')),
      step('cool', 'Cool', 3, 'planned', null, null, 'bake'),
      step('wash', 'Wash', 4, 'failed', at('09:10'), at('09:15')),
    ], [], now)!
    expect(timing.rows.map((row) => [row.label, row.depth, row.length, row.open])).toEqual([
      ['Mix', 0, '30 min', false], ['Bake', 1, '≥ 1 h', true], ['Cool', 2, 'not started', false], ['Wash', 0, '5 min', false],
    ])
    expect(timing.start).toBe(Date.parse(at('09:00')))
    expect(timing.end).toBe(now)
    // 09:00 to 10:30: Mix fills the first third, Bake the rest so far.
    expect(timing.rows[0].from).toBe(0)
    expect(timing.rows[0].to).toBeCloseTo(1 / 3)
    expect(timing.rows[1].from).toBeCloseTo(1 / 3)
    expect(timing.rows[1].to).toBe(1)
    expect(timing.rows[2]).toMatchObject({ from: null, to: null })
    expect(timing.ticks).toEqual([Date.parse(at('09:00')), Date.parse(at('09:45')), now])
  })

  it('puts worked time with clock times under its step and counts what has minutes only', () => {
    const timing = runTiming([step('mix', 'Mix', 1, 'completed', at('09:00'), at('10:00'))], [
      worked('l1', 'kim', 'mix', at('09:05'), at('09:50')),
      worked('l2', 'lee', null, at('09:00'), at('10:00')),
      worked('l3', 'park', 'mix', null, null),
      worked('l4', 'choi', 'mix', at('09:00'), at('09:10'), true),
    ], now)!
    expect(timing.rows.map((row) => [row.label, row.kind, row.depth, row.length])).toEqual([
      ['Mix', 'step', 0, '1 h'], ['kim', 'labor', 1, '45 min'], ['lee (whole run)', 'labor', 0, '1 h'],
    ])
    expect(timing.untimed).toBe(1)
  })

  it('has nothing to draw before anything starts, and survives a loop in the source steps', () => {
    expect(runTiming([step('mix', 'Mix', 1, 'planned', null, null)], [], now)).toBeNull()
    const looped = runTiming([step('a', 'A', 1, 'completed', at('09:00'), at('09:01'), 'b'),
      step('b', 'B', 2, 'completed', at('09:01'), at('09:02'), 'a')], [], now)!
    expect(looped.rows.map((row) => [row.label, row.depth])).toEqual([['A', 0], ['B', 1]])
  })
})

describe('spanText', () => {
  it('says a length in minutes and hours', () => {
    const minutes = (value: number) => value * 60_000
    expect([30_000, minutes(12), minutes(120), minutes(125), minutes(48 * 60), minutes(51 * 60 + 20)].map(spanText))
      .toEqual(['< 1 min', '12 min', '2 h', '2 h 5 min', '2 d', '2 d 3 h'])
  })
})
