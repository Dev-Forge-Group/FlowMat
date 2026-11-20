import { describe, expect, it } from 'vitest'
import { lockDrag, lockedMove } from './axisLock'

describe('Shift axis lock', () => {
  it('keeps the move to the axis it went further along', () => {
    expect(lockedMove({ x: 100, y: 100 }, { x: 180, y: 130 })).toEqual({ x: 80, y: 0 })
    expect(lockedMove({ x: 100, y: 100 }, { x: 90, y: 20 })).toEqual({ x: 0, y: -80 })
    // An even move goes across.
    expect(lockedMove({ x: 0, y: 0 }, { x: 40, y: 40 })).toEqual({ x: 40, y: 0 })
  })

  it('moves every dragged process the way the lead one moved, from its own start', () => {
    const starts = new Map([['a', { x: 0, y: 0 }], ['b', { x: 200, y: 50 }]])
    const placed = lockDrag(starts, [{ id: 'a', position: { x: 64, y: 16 } }, { id: 'b', position: { x: 264, y: 66 } }])
    expect(Object.fromEntries(placed)).toEqual({ a: { x: 64, y: 0 }, b: { x: 264, y: 50 } })
  })

  it('leaves a process it has no start for where the drag put it', () => {
    const placed = lockDrag(new Map([['a', { x: 0, y: 0 }]]), [{ id: 'a', position: { x: 0, y: 30 } }, { id: 'c', position: { x: 5, y: 5 } }])
    expect(Object.fromEntries(placed)).toEqual({ a: { x: 0, y: 30 }, c: { x: 5, y: 5 } })
    expect(lockDrag(new Map(), [{ id: 'c', position: { x: 5, y: 5 } }]).size).toBe(0)
  })
})
