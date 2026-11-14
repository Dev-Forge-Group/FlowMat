import { describe, expect, it } from 'vitest'
import { reuseUnchangedNodes } from './reuseUnchangedNodes'

const ports: string[] = []
const node = (id: string, over: Record<string, unknown> = {}) => ({
  id,
  type: 'flowmatNode',
  position: { x: 10, y: 20 },
  selected: false,
  measured: { width: 150, height: 72 },
  data: { name: id, inputs: ports, editingByUserId: null as string | null },
  ...over,
})

describe('reuseUnchangedNodes', () => {
  it('returns the previous array when every rebuilt node holds the same values', () => {
    const previous = [node('mix'), node('bake')]

    expect(reuseUnchangedNodes(previous, [node('mix'), node('bake')])).toBe(previous)
  })

  it('keeps the object of an unchanged node beside a changed one', () => {
    const previous = [node('mix'), node('bake')]
    const editing = node('bake', { data: { name: 'bake', inputs: ports, editingByUserId: 'someone' } })

    const nodes = reuseUnchangedNodes(previous, [node('mix'), editing])

    expect(nodes[0]).toBe(previous[0])
    expect(nodes[1]).toBe(editing)
  })

  it.each([
    ['a moved node', { position: { x: 11, y: 20 } }],
    ['a selected node', { selected: true }],
    ['a node measured at another size', { measured: { width: 180, height: 72 } }],
    ['a node that lost its measurement', { measured: undefined }],
    ['a node whose ports were reloaded', { data: { name: 'mix', inputs: ['in'], editingByUserId: null } }],
    ['a node with one more field', { dragging: true }],
  ])('takes the rebuilt object for %s', (_, over) => {
    const previous = [node('mix')]
    const rebuilt = node('mix', over)

    expect(reuseUnchangedNodes(previous, [rebuilt])[0]).toBe(rebuilt)
  })

  it('follows the rebuilt list when nodes were added, removed or reordered', () => {
    const previous = [node('mix'), node('bake')]

    expect(reuseUnchangedNodes(previous, [node('mix')])).toEqual([previous[0]])
    expect(reuseUnchangedNodes(previous, [node('bake'), node('mix')])).toEqual([previous[1], previous[0]])
    const cool = node('cool')
    expect(reuseUnchangedNodes(previous, [node('mix'), node('bake'), cool])).toEqual([previous[0], previous[1], cool])
  })
})
