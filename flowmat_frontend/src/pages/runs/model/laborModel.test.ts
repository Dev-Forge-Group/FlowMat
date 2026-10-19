import { describe, expect, it } from 'vitest'
import type { RunLaborPerson } from '../../../entities/production/api/useRunLabor'
import {
  comparisonLine, comparisonSteps, comparisonTotal, firstWorker, laborChoices, laborSummary, stepHint, stepSuggestion, stepSummary, suggestionHint,
} from './laborModel'

const person = (userId: string, sharePercent: number | null, workedMinutes: number, suggestedMinutes: number | null,
  suggestedFrom: RunLaborPerson['suggestedFrom']): RunLaborPerson => ({ userId, sharePercent, workedMinutes, suggestedMinutes, suggestedFrom })

describe('run labor', () => {
  it('offers the crew first with their share, then others with records, then the other workers', () => {
    const people = [person('kim', 50, 0, 60, 'shifts'), person('lee', 100, 0, 120, 'run'), person('owner', null, 30, null, null)]
    expect(laborChoices(people, [{ userId: 'owner' }, { userId: 'kim' }, { userId: 'park' }])).toEqual([
      { userId: 'kim', label: 'kim · crew 50%' },
      { userId: 'lee', label: 'lee · crew 100%' },
      { userId: 'owner', label: 'owner' },
      { userId: 'park', label: 'park' },
    ])
  })

  it('starts with the first of the crew who has time left to record', () => {
    expect(firstWorker([person('kim', 50, 60, null, 'shifts'), person('lee', 100, 0, 120, 'run')])).toBe('lee')
    expect(firstWorker([person('kim', 50, 60, null, 'shifts')])).toBe('kim')
    expect(firstWorker([person('owner', null, 30, null, null)])).toBe('')
  })

  it('says where a suggestion comes from', () => {
    expect(suggestionHint(person('kim', 50, 0, 60, 'shifts'))).toBe("Suggested from kim's shifts during the run at 50%, less what is recorded.")
    expect(suggestionHint(person('lee', 100, 0, 120, 'run'))).toBe("Suggested from the run's time, less what is recorded (lee has no shifts).")
    expect(suggestionHint(person('kim', 50, 60, null, 'shifts'))).toBeNull()
    expect(suggestionHint(undefined)).toBeNull()
  })

  it('sums what counts per person', () => {
    expect(laborSummary({ workedMinutes: 0, people: [person('kim', 50, 0, 60, 'shifts')] })).toBe('No worked time yet')
    expect(laborSummary({ workedMinutes: 75, people: [person('kim', 50, 45, 15, 'shifts'), person('lee', 100, 0, 120, 'run'),
      person('owner', null, 30, null, null)] })).toBe('75 min: kim 45, owner 30')
  })
})

describe('labor on a step', () => {
  const mix = { stepId: 's1', nodeName: 'Mix', sequenceNo: 1, status: 'completed', startedAt: '2026-10-19T08:00:00Z',
    endedAt: '2026-10-19T09:30:00Z', workedMinutes: 30 }
  const line = (userId: string, minutes: number, stepId: string | null, cancelled = false) => ({ runLaborId: `${userId}-${minutes}`,
    userId, workedMinutes: minutes, note: null, recordedBy: 'owner', recordedAt: '2026-10-19T10:00:00Z', cancelled,
    cancelledBy: null, cancelledAt: null, cancelReason: null, stepId })

  it('starts from the step\'s time at the person\'s share, less what counts for them on it', () => {
    const kim = person('kim', 50, 30, 15, 'shifts')
    expect(stepSuggestion(mix, kim, [])).toBe(45)
    expect(stepSuggestion(mix, kim, [line('kim', 30, 's1'), line('kim', 10, null), line('kim', 5, 's1', true)])).toBe(15)
    expect(stepSuggestion(mix, person('owner', null, 0, null, null), [])).toBe(90)
    expect(stepSuggestion(mix, kim, [line('kim', 45, 's1')])).toBeNull()
    expect(stepSuggestion({ ...mix, startedAt: null }, kim, [])).toBeNull()
    expect(stepSuggestion({ ...mix, endedAt: null }, kim, [], new Date('2026-10-19T09:00:00Z'))).toBe(30)
    expect(stepHint(mix, kim)).toBe("Suggested from Mix's time at 50%, less what is recorded.")
  })

  it('sums the minutes per step beside the whole run', () => {
    expect(stepSummary({ lines: [line('kim', 30, 's1'), line('lee', 10, null)], steps: [mix] })).toBe('By step: Mix 30, whole run 10')
    expect(stepSummary({ lines: [line('lee', 10, null)], steps: [mix] })).toBe('')
  })
})

describe('work order labor comparison', () => {
  it('puts each person\'s plan beside what they recorded', () => {
    expect(comparisonLine({ userId: 'kim', sharePercent: 50, plannedMinutes: 480, actualMinutes: 300 }, 1))
      .toBe('kim: planned 480 min at 50% · actual 300 min · 180 min less than planned')
    expect(comparisonLine({ userId: 'kim', sharePercent: 100, plannedMinutes: 480, actualMinutes: 500 }, 2))
      .toBe('kim: planned 480 min · actual 500 min · 20 min more than planned')
    expect(comparisonLine({ userId: 'kim', sharePercent: 100, plannedMinutes: 480, actualMinutes: 480 }, 1))
      .toBe('kim: planned 480 min · actual 480 min · as planned')
    expect(comparisonLine({ userId: 'lee', sharePercent: 100, plannedMinutes: null, actualMinutes: 0 }, 1))
      .toBe('lee: no planned time · actual 0 min')
    expect(comparisonLine({ userId: 'owner', sharePercent: null, plannedMinutes: null, actualMinutes: 20 }, 1))
      .toBe('owner: not in the crew · actual 20 min')
    expect(comparisonLine({ userId: 'kim', sharePercent: 50, plannedMinutes: 480, actualMinutes: 0 }, 0))
      .toBe('kim: planned 480 min at 50% · no run yet')
  })

  it('adds the actual time up by process beside the whole run', () => {
    expect(comparisonSteps({ steps: [{ nodeId: 'p1', nodeName: 'Mix', sequenceNo: 1, actualMinutes: 300 },
      { nodeId: 'p2', nodeName: 'Bake', sequenceNo: 2, actualMinutes: 120 }], wholeRunMinutes: 20 }))
      .toBe('By step: Mix 300, Bake 120, whole run 20')
    expect(comparisonSteps({ steps: [], wholeRunMinutes: 20 })).toBe('')
    expect(comparisonSteps({})).toBe('')
  })

  it('sums the crew', () => {
    expect(comparisonTotal({ plannedMinutes: 480, plannedComplete: false, runs: 1, actualMinutes: 320 }))
      .toBe('All: planned 480 min known so far · actual 320 min over 1 run')
    expect(comparisonTotal({ plannedMinutes: null, plannedComplete: true, runs: 0, actualMinutes: 0 }))
      .toBe('All: no planned time · no run yet')
    expect(comparisonTotal({ plannedMinutes: 60, plannedComplete: true, runs: 2, actualMinutes: 70 }))
      .toBe('All: planned 60 min · actual 70 min over 2 runs')
  })
})
