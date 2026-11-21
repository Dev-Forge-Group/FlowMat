import { describe, expect, it } from 'vitest'
import { deleteConfirmation, deletePlan } from './canvasDeletion'

const edge = (id: string, fromProcessId: string, toProcessId: string) => ({ id, fromProcessId, toProcessId })
// mix -> bake -> pack, and a loop on bake; idle has none.
const edges = [edge('c1', 'mix', 'bake'), edge('c2', 'bake', 'pack'), edge('c3', 'bake', 'bake')]

describe('deleteConfirmation', () => {
  it('asks nothing for connections alone or a process with no connections', () => {
    expect(deleteConfirmation({ nodeIds: [], edgeIds: ['c1'] }, edges)).toBeNull()
    expect(deleteConfirmation({ nodeIds: ['idle'], edgeIds: [] }, edges)).toBeNull()
  })

  it('counts the connections a process takes with it, a loop once', () => {
    expect(deleteConfirmation({ nodeIds: ['bake'], edgeIds: [] }, edges))
      .toBe('Deleting this node will also remove 3 connected connections. Continue?')
    expect(deleteConfirmation({ nodeIds: ['mix'], edgeIds: [] }, edges))
      .toBe('Deleting this node will also remove 1 connected connection. Continue?')
  })

  it('names the implicit ones for several processes, and leaves the note out when all were selected', () => {
    expect(deleteConfirmation({ nodeIds: ['mix', 'pack'], edgeIds: ['c1'] }, edges))
      .toBe('Deleting 2 nodes will also remove 2 connected connections (1 implicit). Continue?')
    expect(deleteConfirmation({ nodeIds: ['mix', 'pack'], edgeIds: ['c1', 'c2'] }, edges))
      .toBe('Deleting 2 nodes will also remove 2 connected connections. Continue?')
  })
})

describe('deletePlan', () => {
  it('does nothing for an empty selection', () => {
    expect(deletePlan({ nodeIds: [], edgeIds: [] }, edges)).toEqual({ kind: 'nothing' })
  })

  it('deletes one connection on its own, several together', () => {
    expect(deletePlan({ nodeIds: [], edgeIds: ['c2'] }, edges)).toEqual({ kind: 'connection', connectionId: 'c2' })
    expect(deletePlan({ nodeIds: [], edgeIds: ['c1', 'c2'] }, edges)).toEqual({ kind: 'elements' })
  })

  it('deletes one process on its own when the selected connections are its own', () => {
    expect(deletePlan({ nodeIds: ['bake'], edgeIds: [] }, edges)).toEqual({ kind: 'process', processId: 'bake' })
    expect(deletePlan({ nodeIds: ['bake'], edgeIds: ['c1', 'c3'] }, edges)).toEqual({ kind: 'process', processId: 'bake' })
  })

  it('deletes together when a selected connection is not the process\'s, or there are several processes', () => {
    expect(deletePlan({ nodeIds: ['mix'], edgeIds: ['c2'] }, edges)).toEqual({ kind: 'elements' })
    expect(deletePlan({ nodeIds: ['mix', 'pack'], edgeIds: [] }, edges)).toEqual({ kind: 'elements' })
  })
})
