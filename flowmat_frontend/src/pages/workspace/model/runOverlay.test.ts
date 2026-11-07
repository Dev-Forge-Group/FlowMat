import { describe, expect, it } from 'vitest'
import { runOverlay, runSummary } from './runOverlay'

const step = (nodeId: string, status: string, sequenceNo: number) => ({ nodeId, status, sequenceNo })
const known = new Set(['mix', 'bake', 'cool', 'pack'])

describe('runOverlay', () => {
  it('marks each process by its latest step and counts processes no longer on the canvas', () => {
    const overlay = runOverlay([step('mix', 'completed', 1), step('bake', 'running', 3), step('bake', 'failed', 2),
      step('cool', 'planned', 4), step('old', 'completed', 5), step('pack', 'mystery', 6)], known)
    expect([...overlay.marks]).toEqual([['mix', 'completed'], ['bake', 'running'], ['cool', 'waiting'], ['pack', 'waiting']])
    expect(overlay.missing).toBe(1)
  })

  it('says how the run stands', () => {
    const overlay = runOverlay([step('mix', 'completed', 1), step('bake', 'failed', 2), step('cool', 'skipped', 3),
      step('old', 'completed', 4), step('older', 'running', 5)], known)
    expect(runSummary('R-0001', 'running', overlay))
      .toBe('Run R-0001 (running): 1 completed, 1 failed, 1 skipped; 2 processes no longer on the canvas')
    expect(runSummary('R-0002', 'running', runOverlay([], known))).toBe('Run R-0002 (running): no steps yet')
  })
})
