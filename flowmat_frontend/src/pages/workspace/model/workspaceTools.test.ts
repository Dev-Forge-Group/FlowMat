import { describe, expect, it } from 'vitest'
import { getToolLabel, getUndoRedoShortcut, getWorkspaceToolShortcut } from './workspaceTools'
import { createWorkspaceEditorElement, editorElementLabel, normalizeFreehand, readEditorSelectionStyle, toHexColor } from './workspaceEditorElements'

const key = (keyName: string, modifiers: Partial<KeyboardEvent> = {}) =>
  ({ key: keyName, ctrlKey: false, metaKey: false, altKey: false, shiftKey: false, ...modifiers }) as KeyboardEvent

describe('workspace tools', () => {
  it('reads the tool and undo shortcuts', () => {
    expect(getWorkspaceToolShortcut(key('R'))).toBe('editor-rectangle')
    expect(getWorkspaceToolShortcut(key('v'))).toBe('select')
    expect(getWorkspaceToolShortcut(key('r', { ctrlKey: true }))).toBeNull()
    expect(getUndoRedoShortcut(key('z', { ctrlKey: true }))).toBe('undo')
    expect(getUndoRedoShortcut(key('z', { metaKey: true, shiftKey: true }))).toBe('redo')
    expect(getUndoRedoShortcut(key('y', { ctrlKey: true }))).toBe('redo')
    expect(getUndoRedoShortcut(key('z'))).toBeNull()
  })

  it('names tools from the editor, annotation and palette lists', () => {
    expect(getToolLabel('select', [])).toBe('Pointer')
    expect(getToolLabel('editor-ellipse', [])).toBe('Ellipse')
    expect(getToolLabel('annotation-freehand', [])).toBe('Freehand')
  })
})

describe('workspace editor elements', () => {
  it('places a new shape centred on the point with the next free id', () => {
    const document = { elements: [], nextElementSeq: 1 } as never
    const { element, nextElementSeq } = createWorkspaceEditorElement('editor-rectangle', { x: 200, y: 100 }, document)
    expect(element).toMatchObject({ type: 'rectangle', x: 120, y: 52, width: 160, height: 96 })
    expect(nextElementSeq).toBe(2)
    expect(editorElementLabel(element)).toBe('Rectangle')
    expect(readEditorSelectionStyle([element])).toMatchObject({ fill: '#dff7ef', stroke: '#0f766e' })
  })

  it('makes a freehand stroke relative to its box and reads colours safely', () => {
    expect(normalizeFreehand([{ x: 10, y: 20 }, { x: 30, y: 25 }])).toEqual({
      posX: 10, posY: 20, width: 20, height: 8, points: [[0, 0], [20, 5]],
    })
    expect(toHexColor('#AbCdEf', '#000000')).toBe('#AbCdEf')
    expect(toHexColor('red', '#000000')).toBe('#000000')
  })
})
