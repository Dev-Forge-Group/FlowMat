import { describe, expect, it } from 'vitest'
import {
  createEmptyEditorDocument,
  createRectangleElement,
  toElementId,
  type EditorDocument,
  type EditorElement,
} from '../../../lib/flowmat-editor'
import type { CanvasAnnotationViewModel } from '../../../entities/workflow/model/types'
import {
  addConnector,
  buildAnnotationZIndexPatch,
  createBackendHistorySnapshot,
  createInteractionDocuments,
  draggedDocuments,
  duplicateEditorElements,
  filterStable,
  keepsUnsavedEdit,
  recordHistory,
  redoHistory,
  reorderEditorElements,
  toggleSelectedUnit,
  undoHistory,
} from './workspaceEditorDocument'

describe('filterStable', () => {
  it('returns the same array reference when nothing is filtered out', () => {
    const list = ['a', 'b', 'c']
    const result = filterStable(list, () => true)

    expect(result).toBe(list)
  })

  it('returns the same reference for a valid selection cleanup (all ids still exist)', () => {
    const selectedIds = ['el-1', 'el-2']
    const editableIds = new Set(['el-1', 'el-2', 'el-3'])

    const result = filterStable(selectedIds, (id) => editableIds.has(id))

    expect(result).toBe(selectedIds)
  })

  it('returns a new array only when an item is actually removed', () => {
    const list = ['a', 'b', 'c']
    const result = filterStable(list, (item) => item !== 'b')

    expect(result).not.toBe(list)
    expect(result).toEqual(['a', 'c'])
  })

  it('repeated calls against an unchanged predicate settle on the same reference (no update loop)', () => {
    // Simulates the WorkspaceEditorLayer bug: `editableIds` used to get a new Set
    // identity every render while the editor-document query was pending, but the
    // selection itself never actually changed. filterStable must converge to a
    // stable reference so the driving useEffect stops re-firing.
    const selectedIds: readonly string[] = []
    const first = filterStable(selectedIds, () => true)
    const second = filterStable(first, () => true)

    expect(first).toBe(selectedIds)
    expect(second).toBe(selectedIds)
  })
})

describe('keepsUnsavedEdit', () => {
  it('keeps the edit when the save failed for a passing reason', () => {
    expect(keepsUnsavedEdit(null)).toBe(true)
    expect(keepsUnsavedEdit(429)).toBe(true)
    expect(keepsUnsavedEdit(503)).toBe(true)
  })

  it('drops it for a conflict or a refused document, so the server version shows', () => {
    expect(keepsUnsavedEdit(409)).toBe(false)
    expect(keepsUnsavedEdit(400)).toBe(false)
    expect(keepsUnsavedEdit(403)).toBe(false)
  })
})

const id = toElementId
const box = (name: string, x: number, order: number) => ({
  ...createRectangleElement({ id: id(name), x, y: 0, width: 20, height: 20 }), order,
})
const doc = (...elements: EditorElement[]): EditorDocument => ({ ...createEmptyEditorDocument({ nextElementSeq: 1 }), elements })

describe('reorderEditorElements', () => {
  const layers = doc(box('a', 0, 1), box('b', 0, 2), box('c', 0, 3))
  const order = (document: EditorDocument) =>
    [...document.elements].sort((x, y) => x.order - y.order).map((element) => String(element.id))

  it('brings the selection to the front or sends it to the back, keeping its own order', () => {
    expect(order(reorderEditorElements(layers, [id('a'), id('b')], 'front'))).toEqual(['c', 'a', 'b'])
    expect(order(reorderEditorElements(layers, [id('c')], 'back'))).toEqual(['c', 'a', 'b'])
  })
})

describe('duplicateEditorElements', () => {
  it('copies the selection on top under the next free ids, and selects the copies', () => {
    const taken = { ...doc(box('a', 0, 1), box('shape-1', 50, 2)), nextElementSeq: 1 }
    const result = duplicateEditorElements(taken, [id('a')])
    expect(result.selectedIds.map(String)).toEqual(['shape-2'])
    const copy = result.document.elements.find((element) => element.id === id('shape-2'))!
    expect(copy).toMatchObject({ order: 3 })
    expect(result.document.nextElementSeq).toBe(3)
  })
})

describe('addConnector', () => {
  const shapes = doc(box('a', 0, 1), box('b', 100, 2))

  it('binds a line to both shapes on top, under the next free id', () => {
    const added = addConnector(shapes, { elementId: id('a'), anchor: 'right' }, { elementId: id('b'), anchor: 'left' })!
    const line = added.document.elements.find((element) => element.id === added.id)!
    expect(String(added.id)).toBe('shape-1')
    // From a's right middle (20, 10) to b's left middle (100, 10): the line sits at its start, its ends relative to it.
    expect(line).toMatchObject({ type: 'line', order: 3, x: 20, y: 10, start: { x: 0, y: 0 }, end: { x: 80, y: 0 },
      startBinding: { elementId: id('a'), anchor: 'right' }, endBinding: { elementId: id('b'), anchor: 'left' } })
    expect(added.document.nextElementSeq).toBe(2)
  })

  it('draws nothing from a shape to itself or to one that is not there', () => {
    expect(addConnector(shapes, { elementId: id('a'), anchor: 'right' }, { elementId: id('a'), anchor: 'left' })).toBeNull()
    expect(addConnector(shapes, { elementId: id('a'), anchor: 'right' }, { elementId: id('gone'), anchor: 'left' })).toBeNull()
  })
})

describe('toggleSelectedUnit', () => {
  it('adds a unit that is not wholly selected and takes away one that is', () => {
    expect(toggleSelectedUnit([id('a')], [id('b'), id('c')]).map(String)).toEqual(['a', 'b', 'c'])
    expect(toggleSelectedUnit([id('a'), id('b'), id('c')], [id('b'), id('c')]).map(String)).toEqual(['a'])
    expect(toggleSelectedUnit([id('a'), id('b')], [id('b'), id('c')]).map(String)).toEqual(['a', 'b', 'c'])
  })
})

describe('buildAnnotationZIndexPatch', () => {
  const note = (annotationId: string, zIndex: string) => ({ annotationId, zIndex }) as CanvasAnnotationViewModel
  const notes = [note('n1', '1'), note('n2', '2'), note('n3', '3')]

  it('puts the selection above the highest or below the lowest, in its own order', () => {
    expect([...buildAnnotationZIndexPatch(notes, [id('n2'), id('n1')], 'front')]).toEqual([[id('n1'), '4'], [id('n2'), '5']])
    expect([...buildAnnotationZIndexPatch(notes, [id('n3')], 'back')]).toEqual([[id('n3'), '0']])
  })

  it('has nothing for a selection with no annotation', () => {
    expect(buildAnnotationZIndexPatch(notes, [id('shape')], 'front').size).toBe(0)
  })
})

describe('draggedDocuments', () => {
  const base = createInteractionDocuments(doc(box('a', 0, 1), box('b', 100, 2)), doc(), [id('a')])

  it('moves the selection by the snapped distance from where the drag started, and nothing else', () => {
    const moved = draggedDocuments({ type: 'move', start: { x: 0, y: 0 }, base }, { x: 13, y: 7 }, { x: 16, y: 8 })
    const element = (name: string) => moved.backendDocument!.elements.find((item) => item.id === id(name))
    expect(element('a')).toMatchObject({ x: 16, y: 8 })
    expect(element('b')).toMatchObject({ x: 100, y: 0 })
    expect(moved.annotationDocument).toBeNull()
  })

  it('resizes the selection within its box from the dragged handle', () => {
    const resized = draggedDocuments({ type: 'resize', handle: 'se', base, baseBounds: { x: 0, y: 0, width: 20, height: 20 } },
      { x: 41, y: 39 }, { x: 40, y: 40 })
    expect(resized.backendDocument!.elements.find((item) => item.id === id('a'))).toMatchObject({ width: 40, height: 40 })
  })
})

describe('the editor document\'s undo history', () => {
  const at = (x: number) => createBackendHistorySnapshot(doc(box('a', x, 1)), [id('a')])
  const xOf = (snapshot: { document: EditorDocument }) => (snapshot.document.elements[0] as { x: number }).x

  it('records before a change, dropping what could be redone and keeping only the newest', () => {
    const history = recordHistory({ past: [at(0)], future: [at(9)] }, at(1), 2)
    expect(history.future).toEqual([])
    expect(recordHistory(history, at(2), 2).past.map(xOf)).toEqual([1, 2])
  })

  it('undoes to the last snapshot and redoes back to the present', () => {
    const undone = undoHistory({ past: [at(0), at(1)], future: [] }, at(2))!
    expect(xOf(undone.target)).toBe(1)
    expect(undone.history.past.map(xOf)).toEqual([0])
    expect(undone.history.future.map(xOf)).toEqual([2])
    const redone = redoHistory(undone.history, at(1))!
    expect(xOf(redone.target)).toBe(2)
    expect(redone.history.past.map(xOf)).toEqual([0, 1])
    expect(redone.history.future).toEqual([])
  })

  it('does nothing with nothing to undo or redo', () => {
    expect(undoHistory({ past: [], future: [] }, at(0))).toBeNull()
    expect(redoHistory({ past: [], future: [] }, at(0))).toBeNull()
  })
})
