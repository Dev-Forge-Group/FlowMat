import { describe, expect, it } from 'vitest'
import { MOTION, flowDepths, litAt, motionScene, pointAlong, type MotionEdgeInput, type MotionNodeInput } from './workflowMotion'

const box = (id: string, x = 0): MotionNodeInput => ({ id, x, y: 0, width: 100, height: 60 })
const line = (id: string, source: string, target: string): MotionEdgeInput =>
  ({ id, source, target, points: [{ x: 0, y: 0 }, { x: 100, y: 0 }] })

describe('flowDepths', () => {
  it('counts from where nothing flows in, along the shortest way', () => {
    const depths = flowDepths(['a', 'b', 'c', 'd'], [
      { source: 'a', target: 'b' }, { source: 'b', target: 'c' }, { source: 'a', target: 'c' }, { source: 'c', target: 'd' },
    ])
    expect(Object.fromEntries(depths)).toEqual({ a: 0, b: 1, c: 1, d: 2 })
  })

  it('gives every process a depth when a loop has no way in, and the same depths every time', () => {
    const edges = [{ source: 'x', target: 'y' }, { source: 'y', target: 'z' }, { source: 'z', target: 'x' }]
    expect(Object.fromEntries(flowDepths(['y', 'x', 'z', 'alone'], edges))).toEqual({ alone: 0, y: 0, z: 1, x: 2 })
    expect(Object.fromEntries(flowDepths(['y', 'x', 'z', 'alone'], edges))).toEqual({ alone: 0, y: 0, z: 1, x: 2 })
  })

  it('ignores connections to processes it was not given', () => {
    expect(Object.fromEntries(flowDepths(['a'], [{ source: 'a', target: 'gone' }]))).toEqual({ a: 0 })
  })
})

describe('motionScene', () => {
  it('starts each signal when the flow reaches its source and lights a process when the first signal arrives', () => {
    const scene = motionScene([box('a'), box('b'), box('c'), box('alone')], [line('ab', 'a', 'b'), line('bc', 'b', 'c')])
    expect(scene.edges.map((edge) => [edge.id, edge.delay])).toEqual([['ab', 0], ['bc', MOTION.step]])
    // A process with no connection has nothing flowing through it and does not glow.
    expect(scene.nodes.map((node) => [node.id, node.delay])).toEqual([
      ['a', 0], ['b', MOTION.travel], ['c', MOTION.step + MOTION.travel],
    ])
    expect(scene.period).toBeCloseTo(MOTION.step + MOTION.travel + MOTION.settle)
  })

  it('fits a deep workflow into the clip by shortening the step', () => {
    const ids = Array.from({ length: 30 }, (_, index) => `p${index}`)
    const scene = motionScene(ids.map((id) => box(id)), ids.slice(1).map((id, index) => line(`e${index}`, ids[index], id)))
    const last = Math.max(...scene.edges.map((edge) => edge.delay))
    expect(last + MOTION.travel).toBeLessThanOrEqual(MOTION.seconds)
    expect(scene.period).toBeLessThanOrEqual(MOTION.seconds)
  })

  it('leaves out a connection with no drawn path', () => {
    const scene = motionScene([box('a'), box('b')], [{ ...line('ab', 'a', 'b'), points: [{ x: 0, y: 0 }] }])
    expect(scene.edges).toEqual([])
  })
})

describe('litAt', () => {
  const scene = motionScene([box('a'), box('b'), box('c')], [line('ab', 'a', 'b'), line('bc', 'b', 'c')])

  it('moves a signal along its connection and passes the flow on as it arrives', () => {
    const first = litAt(scene, 0.5)
    expect(first.edges.map((lit) => lit.edge.id)).toEqual(['ab'])
    expect(first.edges[0].tail).toBeLessThan(first.edges[0].head)
    expect(litAt(scene, MOTION.travel - 0.05).edges.map((lit) => lit.edge.id)).toEqual(['ab'])
    expect(litAt(scene, MOTION.travel + 0.05).edges.map((lit) => lit.edge.id)).toEqual(['bc'])
  })

  it('overlaps the signals of a workflow too deep for the clip', () => {
    const ids = Array.from({ length: 6 }, (_, index) => `p${index}`)
    const deep = motionScene(ids.map((id) => box(id)), ids.slice(1).map((id, index) => line(`e${index}`, ids[index], id)))
    expect(deep.edges[1].delay).toBeLessThan(MOTION.travel)
    expect(litAt(deep, deep.edges[1].delay + 0.05).edges.map((lit) => lit.edge.id)).toEqual(['e0', 'e1'])
  })

  it('runs the flow again after the settle', () => {
    expect(litAt(scene, scene.period + 0.1).edges.map((lit) => lit.edge.id)).toEqual(['ab'])
  })

  it('lights a process while a signal reaches it', () => {
    expect(litAt(scene, MOTION.travel + MOTION.pulse / 2).nodes.map((lit) => lit.node.id)).toContain('b')
    expect(litAt(scene, MOTION.travel - 0.2).nodes.map((lit) => lit.node.id)).not.toContain('b')
  })
})

describe('pointAlong', () => {
  it('walks the sampled path', () => {
    const points = [{ x: 0, y: 0 }, { x: 10, y: 0 }, { x: 10, y: 10 }]
    expect(pointAlong(points, 0)).toEqual({ x: 0, y: 0 })
    expect(pointAlong(points, 0.75)).toEqual({ x: 10, y: 5 })
    expect(pointAlong(points, 2)).toEqual({ x: 10, y: 10 })
  })
})
