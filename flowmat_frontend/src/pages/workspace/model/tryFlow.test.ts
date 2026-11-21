import { describe, expect, it } from 'vitest'
import type { KernelWalk } from '../../../shared/lib/flowKernel'
import { amount, connectionFlows, summarizeWalk, tryInput, walkRows } from './tryFlow'

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
    expect(summarizeWalk({ error: { code: 'CONFLICT', message: 'Graph revisions with cycles cannot execute.' } }, nameOf))
      .toEqual({ tone: 'unreadable', headline: 'Cannot run: Graph revisions with cycles cannot execute.' })
  })

  it('lists each step and puts what arrived on each connection', () => {
    expect(walkRows(finished, nameOf).map((row) => `${row.name} ${row.received} ${row.status}`))
      .toEqual(['Receive 10 kg completed', 'Divide 10 kg completed', 'Line A 6 kg completed', 'Line B 4 kg completed',
        'Gather 9.4 kg completed'])
    expect(connectionFlows(finished)).toEqual({ c1: '10 kg', c2: '6 kg', c3: '4 kg', c4: '5.4 kg', c5: '4 kg' })
    expect(amount({ unit: 'kg' })).toBe('nothing measured')
  })

  it('reads the input as typed', () => {
    expect(tryInput('10', ' kg ', '{"lot":"A"}')).toEqual({ input: { quantity: 10, unit: 'kg', attrs: { lot: 'A' } } })
    expect(tryInput('', '', '')).toEqual({ input: {} })
    expect(tryInput('ten', '', '')).toEqual({ problem: 'The quantity is not a number.' })
    expect(tryInput('1', '', '[1]')).toEqual({ problem: 'The attributes are a JSON object, like {"lot": "A"}.' })
  })
})
