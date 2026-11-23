import { describe, expect, it } from 'vitest'
import type { KernelWalk } from '../../../shared/lib/flowKernel'
import { amount, connectionFlows, duration, kernelTime, shownTime, startTime, summarizeWalk, timeNotes, timeSummary, tryInput, walkRows } from './tryFlow'

const nameOf = (id: string) => ({ in: 'Receive', sp: 'Divide', a: 'Line A', b: 'Line B', jn: 'Gather' } as Record<string, string>)[id] ?? id

const finished: KernelWalk = {
  status: 'finished',
  steps: [
    { nodeId: 'in', status: 'completed', input: { quantity: 10, unit: 'kg' }, output: { quantity: 10, unit: 'kg' } },
    { nodeId: 'sp', status: 'completed', source: 0, connectionId: 'c1', input: { quantity: 10, unit: 'kg' } },
    { nodeId: 'a', status: 'completed', source: 1, connectionId: 'c2', input: { quantity: 6, unit: 'kg' } },
    { nodeId: 'b', status: 'completed', source: 1, connectionId: 'c3', input: { quantity: 4, unit: 'kg' } },
    { nodeId: 'jn', status: 'completed', source: 2, connectionId: 'c4', input: { arrivals: [
      { connectionId: 'c4', sourceStepId: 2, quantity: 5.4, unit: 'kg' },
      { connectionId: 'c5', sourceStepId: 3, quantity: 4, unit: 'kg' }], quantity: 9.4, unit: 'kg' } },
  ],
  events: [{ step: 2, type: 'transfer_loss', payload: { connectionId: 'c4', lost: 0.6, unit: 'kg' } }],
}

describe('a tried flow', () => {
  it('says how it ended, with what was lost', () => {
    expect(summarizeWalk(finished, nameOf)).toEqual({ tone: 'finished', headline: 'Reaches the end in 5 steps, losing 0.6 kg on the way' })
    expect(summarizeWalk({ status: 'running', events: [], steps: [{ nodeId: 'a', status: 'refused', input: {},
      refusal: { status: 409, message: 'Connection c9 capacity exceeded.' } }] }, nameOf))
      .toEqual({ tone: 'stopped', headline: 'Stops at Line A: Connection c9 capacity exceeded.' })
    expect(summarizeWalk({ status: 'failed', events: [], failure: { code: 'NO_ROUTE', message: 'no route' },
      steps: [{ nodeId: 'sp', status: 'failed', input: {}, error: { code: 'NO_ROUTE', message: 'no route' } }] }, nameOf))
      .toEqual({ tone: 'failed', headline: 'Fails at Divide: no route' })
    expect(summarizeWalk({ error: { code: 'CONFLICT', message: 'Published revision loop through a has no way out.' } }, nameOf))
      .toEqual({ tone: 'unreadable', headline: 'Cannot run: Published revision loop through a has no way out.' })
  })

  it('lists each step and puts what arrived on each connection', () => {
    expect(walkRows(finished, nameOf).map((row) => `${row.name} ${row.received} ${row.status}`))
      .toEqual(['Receive 10 kg completed', 'Divide 10 kg completed', 'Line A 6 kg completed', 'Line B 4 kg completed',
        'Gather 9.4 kg completed'])
    expect(connectionFlows(finished)).toEqual({ c1: '10 kg', c2: '6 kg', c3: '4 kg', c4: '5.4 kg', c5: '4 kg' })
    expect(amount({ unit: 'kg' })).toBe('nothing measured')
  })

  it('goes round a loop and says when it stopped at the pass limit', () => {
    const looped: KernelWalk = {
      status: 'finished',
      steps: [
        { nodeId: 'in', status: 'completed', input: { quantity: 10, unit: 'kg' } },
        { nodeId: 'cut', status: 'completed', source: 0, connectionId: 'c1', input: { quantity: 10, unit: 'kg' } },
        { nodeId: 'cut', status: 'completed', source: 1, connectionId: 'c3', input: { quantity: 2, unit: 'kg' } },
        { nodeId: 'cut', status: 'completed', source: 2, connectionId: 'c3', input: { quantity: 0.4, unit: 'kg' } },
      ],
      events: [{ step: 3, type: 'connection_filtered', payload: { connectionId: 'c3', targetNodeId: 'cut', reason: 'loop_limit', quantity: 0.08 } }],
    }
    expect(summarizeWalk(looped, (id) => ({ cut: 'Cut' } as Record<string, string>)[id] ?? id)).toEqual({ tone: 'finished',
      headline: 'Reaches the end in 4 steps; the loop back to Cut stopped at its pass limit' })
    expect(connectionFlows(looped)).toEqual({ c1: '10 kg', c3: '2 times, last 0.4 kg' })
  })

  it('reads the input as typed', () => {
    expect(tryInput('10', ' kg ', '{"lot":"A"}')).toEqual({ input: { quantity: 10, unit: 'kg', attrs: { lot: 'A' } } })
    expect(tryInput('', '', '')).toEqual({ input: {} })
    expect(tryInput('ten', '', '')).toEqual({ problem: 'The quantity is not a number.' })
    expect(tryInput('1', '', '[1]')).toEqual({ problem: 'The attributes are a JSON object, like {"lot": "A"}.' })
  })
})

describe('a tried flow\'s times', () => {
  const timed: KernelWalk = {
    status: 'finished',
    steps: [
      { nodeId: 'mix', status: 'completed', input: { quantity: 10 } },
      { nodeId: 'bake', status: 'completed', source: 0, connectionId: 'c1', input: { quantity: 10 } },
      { nodeId: 'pack', status: 'completed', source: 1, connectionId: 'c2', input: { quantity: 10 } },
    ],
    events: [],
    times: [
      { nodeId: 'mix', endedAt: '2026-12-21T09:00:00+09:00' },
      { nodeId: 'bake', readyAt: '2026-12-21T12:15:00+09:00', scheduledAt: '2026-12-21T13:00:00+09:00',
        endedAt: '2026-12-21T13:00:00+09:00', held: { equipmentId: 'eq-1', crew: ['kim'] } },
      { nodeId: 'pack', readyAt: '2026-12-21T13:30:30+09:00', scheduledAt: '2026-12-21T13:30:30+09:00',
        endedAt: '2026-12-21T13:30:30+09:00' },
    ],
  }

  it('starts at the typed time with the browser\'s offset', () => {
    expect(startTime('2026-12-21T09:00', 540)).toBe('2026-12-21T09:00:00+09:00')
    expect(startTime('2026-12-21T09:00:30', -300)).toBe('2026-12-21T09:00:30-05:00')
    expect(startTime('2026-12-21T09:00', 0)).toBe('2026-12-21T09:00:00+00:00')
    expect(startTime('2026-12-21T09:00', 330)).toBe('2026-12-21T09:00:00+05:30')
    expect(startTime('tomorrow', 0)).toBeNull()
  })

  it('takes the work order\'s stretches, or none when nothing fixes its time', () => {
    const working = { workOrderId: 'wo', from: 'f', until: 'u', allTime: false, equipmentId: 'eq-1', equipmentCode: 'PRESS',
      crew: ['kim'], stretches: [['a', 'b']] as [string, string][] }
    expect(kernelTime('s', working)).toEqual({ start: 's', stretches: [['a', 'b']], equipmentId: 'eq-1', crew: ['kim'] })
    expect(kernelTime('s', { ...working, allTime: true, equipmentId: null, crew: [], stretches: [] }).stretches).toEqual([])
  })

  it('says when each step could start and what it waited for, the equipment by its code', () => {
    expect(timeNotes(timed, 'PRESS')).toEqual([null, 'from 2026-12-21 13:00 (waits for PRESS, kim)', 'from 2026-12-21 13:30:30'])
    expect(timeNotes(timed, null)[1]).toBe('from 2026-12-21 13:00 (waits for eq-1, kim)')
    expect(timeNotes({ ...timed, times: undefined }, 'PRESS')).toEqual([])
  })

  it('says when the flow ends and how long it takes', () => {
    expect(timeSummary(timed, '2026-12-21T09:00:00+09:00')).toBe('Ends 2026-12-21 13:30:30, 4 h 31 min after the start')
    expect(timeSummary({ ...timed, times: undefined }, '2026-12-21T09:00:00+09:00')).toBeNull()
  })

  it('shows times and lengths as people read them', () => {
    expect(shownTime('2026-12-21T13:00:00.5+09:00')).toBe('2026-12-21 13:00')
    expect(duration('2026-12-21T09:00:00Z', '2026-12-21T09:00:45Z')).toBe('45 s')
    expect(duration('2026-12-21T09:00:00Z', '2026-12-21T09:02:00Z')).toBe('2 min')
    expect(duration('2026-12-21T09:00:00Z', '2026-12-21T12:00:00Z')).toBe('3 h')
    expect(duration('2026-12-21T09:00:00Z', '2026-12-24T12:00:00Z')).toBe('3 d 3 h')
  })
})
