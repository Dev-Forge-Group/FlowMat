import { describe, expect, it } from 'vitest'
import { eventKind, eventTimeline } from './eventTimeline'

const base = Date.parse('2030-01-01T09:00:00Z')
const at = (seconds: number) => new Date(base + seconds * 1000).toISOString()
const event = (eventId: string, eventType: string, stepId: string | null, seconds: number) =>
  ({ eventId, eventType, stepId, occurredAt: at(seconds) })
const steps = [{ stepId: 's1', nodeId: 'mix', sequenceNo: 1 }, { stepId: 's2', nodeId: 'bake', sequenceNo: 2 }]
const nodes = [{ processId: 'mix', processName: 'Mix' }, { processId: 'bake', processName: 'Bake' }]
const twoDays = 2 * 24 * 60 * 60

describe('eventTimeline', () => {
  it('puts the run and each step in a lane on one axis, folding a long quiet stretch', () => {
    const timeline = eventTimeline([
      event('e1', 'run_started', null, 0), event('e2', 'step_started', 's1', 10), event('e3', 'step_failed', 's1', 20),
      event('e4', 'step_retry_scheduled', 's1', 30), event('e5', 'step_started', 's2', 30 + twoDays),
      event('e6', 'run_finished', null, 40 + twoDays),
    ], steps, nodes)!
    expect(timeline.lanes.map((lane) => lane.label)).toEqual(['Run', 'Step 1 · Mix', 'Step 2 · Bake'])
    // 40 s are kept across 96% of the axis; the two days take 4%.
    const [run, mix, bake] = timeline.lanes
    expect(run.marks.map((mark) => mark.at)).toEqual([0, 1])
    expect(mix.marks.map((mark) => [mark.eventType, mark.kind])).toEqual([
      ['step_started', 'change'], ['step_failed', 'alert'], ['step_retry_scheduled', 'recovery']])
    expect(mix.marks[2].at).toBeCloseTo(0.72)
    expect(bake.marks[0].at).toBeCloseTo(0.76)
    expect(timeline.breaks).toEqual([{ at: expect.closeTo(0.74), label: '≈ 2 d' }])
  })

  it('stacks marks that would overlap and keeps an even run unfolded', () => {
    const timeline = eventTimeline([event('a', 'step_created', 's1', 0), event('b', 'step_started', 's1', 0),
      event('c', 'step_completed', 's1', 60)], steps, nodes)!
    expect(timeline.lanes[0].marks.map((mark) => mark.stack)).toEqual([0, 1, 0])
    expect(timeline.breaks).toEqual([])
  })

  it('has nothing to place without a time, and puts a single moment in the middle', () => {
    expect(eventTimeline([{ eventId: 'x', eventType: 'run_started', stepId: null, occurredAt: 'not a time' }], steps, nodes))
      .toBeNull()
    expect(eventTimeline([event('a', 'run_started', null, 0)], steps, nodes)!.lanes[0].marks[0].at).toBe(0.5)
  })
})

describe('eventKind', () => {
  it('tells problems and retries from other changes', () => {
    expect(['step_timed_out', 'step_overdue', 'steps_unavailable', 'step_retry_scheduled', 'gateway_routed', 'transfer_loss']
      .map(eventKind)).toEqual(['alert', 'alert', 'alert', 'recovery', 'change', 'change'])
  })
})
