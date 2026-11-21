import { describe, expect, it } from 'vitest'
import { calculateSnapGuides, snapGuidesFor, SNAP_THRESHOLD } from './snapGuides'

const box = (x: number, y: number, w = 100, h = 50) => ({ x, y, w, h })

describe('calculateSnapGuides', () => {
  it('has none when nothing comes within the threshold', () => {
    expect(calculateSnapGuides(box(0, 0), [{ id: 'far', ...box(500, 500) }])).toEqual([])
  })

  it('lines up left edges with a vertical guide spanning both boxes', () => {
    expect(calculateSnapGuides(box(3, 200), [{ id: 'mix', ...box(0, 0) }])).toEqual([
      { type: 'vertical', position: 0, start: 0, end: 250 },
    ])
  })

  it('gives a vertical and a horizontal guide at once; among equally near pairs the first (left, top) wins', () => {
    // Left, centre and right are each 2 away from the other box's, as are top, middle and bottom.
    const guides = calculateSnapGuides(box(2, 2), [{ id: 'mix', ...box(0, 0) }])
    expect(guides).toEqual([
      { type: 'vertical', position: 0, start: 0, end: 52 },
      { type: 'horizontal', position: 0, start: 0, end: 102 },
    ])
  })

  it('snaps only strictly inside the threshold', () => {
    expect(calculateSnapGuides(box(SNAP_THRESHOLD, 300), [{ id: 'mix', ...box(0, 0) }])).toEqual([])
    expect(calculateSnapGuides(box(SNAP_THRESHOLD - 1, 300), [{ id: 'mix', ...box(0, 0) }])).toHaveLength(1)
  })
})

describe('snapGuidesFor', () => {
  const node = (id: string, x: number, y: number) => ({ id, position: { x, y }, width: 100, height: 50 })

  it('measures the dragged process at its new position against the others', () => {
    const nodes = [node('mix', 0, 0), node('bake', 400, 400)]
    expect(snapGuidesFor('bake', { x: 4, y: 300 }, nodes)).toEqual([
      { type: 'vertical', position: 0, start: 0, end: 350 },
    ])
  })

  it('has none for a process not on the canvas', () => {
    expect(snapGuidesFor('gone', { x: 0, y: 0 }, [node('mix', 0, 0)])).toEqual([])
  })
})
