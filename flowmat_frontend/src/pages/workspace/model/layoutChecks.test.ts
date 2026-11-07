import { describe, expect, it } from 'vitest'
import { layoutWarnings, layoutWarningText, type LayoutBox, type LayoutLine } from './layoutChecks'

const box = (id: string, x: number, y: number): LayoutBox => ({ id, x, y, width: 100, height: 60 })
const line = (id: string, from: string, to: string, ...points: [number, number][]): LayoutLine =>
  ({ id, from, to, points: points.map(([x, y]) => ({ x, y })) })
const names: Record<string, string> = { a: 'Mix', b: 'Bake', c: 'Pack', d: 'Wash', e: 'Cool' }
const nameOf = (id: string) => names[id] ?? id

describe('layout checks', () => {
  it('finds a connection drawn over a process it does not join, but not over its own ends', () => {
    // Mix (0..100) → Pack (400..500) runs straight through Bake (200..300) at y = 30.
    const boxes = [box('a', 0, 0), box('b', 200, 0), box('c', 400, 0)]
    const warnings = layoutWarnings({ boxes, lines: [line('ac', 'a', 'c', [100, 30], [400, 30])] })
    expect(warnings).toEqual([{ code: 'LINE_OVER_PROCESS', connectionId: 'ac', from: 'a', to: 'c', processId: 'b' }])
    expect(layoutWarningText(warnings[0], nameOf)).toBe('The connection from "Mix" to "Pack" passes over "Bake".')
  })

  it('lets a line go around a process or graze its border', () => {
    const boxes = [box('a', 0, 0), box('b', 200, 0), box('c', 400, 0)]
    const around = line('ac', 'a', 'c', [100, 30], [150, 30], [150, 100], [350, 100], [350, 30], [400, 30])
    const grazing = line('ac2', 'a', 'c', [100, 0], [400, 0])
    expect(layoutWarnings({ boxes, lines: [around, grazing] })).toEqual([])
  })

  it('finds two connections that cross, but not two that meet at a process they share', () => {
    const crossing = layoutWarnings({ boxes: [], lines: [
      line('ab', 'a', 'b', [0, 0], [200, 200]), line('cd', 'c', 'd', [0, 200], [200, 0])] })
    expect(crossing).toEqual([{ code: 'LINES_CROSS', connectionIds: ['ab', 'cd'], ends: [['a', 'b'], ['c', 'd']],
      at: { x: 100, y: 100 } }])
    expect(layoutWarningText(crossing[0], nameOf)).toBe('The connections Mix → Bake and Pack → Wash cross.')
    const sharing = layoutWarnings({ boxes: [], lines: [
      line('ab', 'a', 'b', [0, 0], [200, 200]), line('ae', 'a', 'e', [0, 200], [200, 0])] })
    expect(sharing).toEqual([])
  })

  it('does not count lines that only touch or run apart as crossing', () => {
    const touching = layoutWarnings({ boxes: [], lines: [
      line('ab', 'a', 'b', [0, 0], [100, 0]), line('cd', 'c', 'd', [100, 0], [100, 100])] })
    const apart = layoutWarnings({ boxes: [], lines: [
      line('ab', 'a', 'b', [0, 0], [100, 0]), line('cd', 'c', 'd', [0, 50], [100, 50])] })
    expect(touching).toEqual([])
    expect(apart).toEqual([])
  })
})
