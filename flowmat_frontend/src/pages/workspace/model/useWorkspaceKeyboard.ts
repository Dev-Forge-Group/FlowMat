import { useEffect, useRef, type Dispatch, type MutableRefObject, type SetStateAction } from 'react'
import { isWorkflowEditorTool, type WorkflowPaletteTool } from '../../../entities/workflow/model/nodeCatalog'
import { CANVAS_ACTIONS, type CanvasActionContext } from './canvasActions'
import { getUndoRedoShortcut, getWorkspaceToolShortcut } from './workspaceTools'
import { nextPreset, type CanvasPreset } from '../ui/StylePresetMenu'
import type { WorkspaceEditorCommandApi, WorkspaceEditorSelectionSnapshot } from '../ui/WorkspaceEditorLayer'

type Panel = 'finder' | 'lens' | 'guide' | 'runs' | 'changes' | 'index' | 'views' | null

/** What the workspace's keys read and change. */
export interface WorkspaceKeys {
  canEditAnnotations: boolean
  activeTool: WorkflowPaletteTool
  setActiveTool: (tool: WorkflowPaletteTool) => void
  editorCommandApiRef: MutableRefObject<WorkspaceEditorCommandApi | null>
  editorSelection: WorkspaceEditorSelectionSnapshot
  setEditorSelection: (selection: WorkspaceEditorSelectionSnapshot) => void
  emptySelection: WorkspaceEditorSelectionSnapshot
  clearSelection: () => void
  setPanel: Dispatch<SetStateAction<Panel>>
  mapOpen: boolean
  setMapOpen: Dispatch<SetStateAction<boolean>>
  toggleMap: () => void
  setStyleOpen: Dispatch<SetStateAction<boolean>>
  setExportOpen: Dispatch<SetStateAction<boolean>>
  presenting: boolean
  setPresenting: (presenting: boolean) => void
  togglePresenting: () => void
  setPreset: Dispatch<SetStateAction<CanvasPreset>>
  cameraRef: MutableRefObject<{ reset(): void; zoomStep(direction: number): void } | null>
  readingRef: MutableRefObject<{ escape(): boolean }>
  selectedProcessId: string | null
  selectedConnectionId: string | null
  isEditorCommandContext: boolean
  deleteApiRef: MutableRefObject<{ deleteSelection: CanvasActionContext['deleteSelection'] }>
  duplicateNode: CanvasActionContext['duplicateNode']
  selectAllRef: MutableRefObject<() => void>
  undo: CanvasActionContext['undo']
  redo: CanvasActionContext['redo']
}

/**
 * The workspace's keyboard: tool keys, Esc's order, the reading and view keys (archify's), the editor's select-all and
 * undo, and the canvas actions. One listener reads the latest state each time a key is pressed.
 */
export function useWorkspaceKeyboard(keys: WorkspaceKeys) {
  const latest = useRef(keys)
  useEffect(() => {
    latest.current = keys
  })
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => handleKeyDown(event, latest.current)
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [])
}

function handleKeyDown(e: KeyboardEvent, k: WorkspaceKeys) {
  const target = e.target as HTMLElement
  // A focused dropdown takes its keys too: typing picks an option there.
  const isEditing =
    target.tagName === 'INPUT' ||
    target.tagName === 'TEXTAREA' ||
    target.tagName === 'SELECT' ||
    target.isContentEditable

  if (!isEditing) {
    const shortcutTool = getWorkspaceToolShortcut(e)
    if (shortcutTool && (shortcutTool === 'select' || k.canEditAnnotations)) {
      e.preventDefault()
      k.setActiveTool(shortcutTool)
      if (shortcutTool === 'select') {
        k.editorCommandApiRef.current?.clearSelection()
        k.setEditorSelection(k.emptySelection)
        k.clearSelection()
      }
      return
    }

    if (e.key === 'Escape' && (k.activeTool !== 'select' || k.editorSelection.elements.length > 0)) {
      e.preventDefault()
      k.setActiveTool('select')
      k.editorCommandApiRef.current?.clearSelection()
      k.setEditorSelection(k.emptySelection)
      k.clearSelection()
      return
    }

    // Reading: "/" finds a process (or a route's start or end), "?" opens the guide; once the Pointer tool is back
    // (above), Esc stops picking a route, then stops reading.
    if (!e.ctrlKey && !e.metaKey && !e.altKey && (e.key === '/' || e.key === '?')) {
      e.preventDefault()
      k.setPanel(e.key === '/' ? 'finder' : 'guide')
      if (e.key === '?') k.setMapOpen(false)
      return
    }
    // archify's view keys: M the semantic radar, F the presentation stage, S the visual style, E the export menu,
    // + - 0 the camera stepper. (R, L and T stay the editor's tools.)
    if (!e.ctrlKey && !e.metaKey && !e.altKey && ['m', 'M', 'f', 'F', 's', 'S', 'e', 'E', '+', '=', '-', '0'].includes(e.key)) {
      e.preventDefault()
      if (e.key === 'm' || e.key === 'M') k.toggleMap()
      else if (e.key === 'e' || e.key === 'E') {
        k.setStyleOpen(false)
        k.setExportOpen((open) => !open)
      }
      else if (e.key === 'f' || e.key === 'F') k.togglePresenting()
      else if (e.key === 's' || e.key === 'S') k.setPreset(nextPreset)
      else if (e.key === '0') k.cameraRef.current?.reset()
      else k.cameraRef.current?.zoomStep(e.key === '-' ? -1 : 1)
      return
    }
    // archify's Esc order: the radar closes, then the route and the reading stop, then the selection clears (below),
    // and only then the stage ends.
    if (e.key === 'Escape' && k.mapOpen) {
      e.preventDefault()
      k.setMapOpen(false)
      return
    }
    if (e.key === 'Escape' && k.readingRef.current.escape()) {
      e.preventDefault()
      return
    }
    if (e.key === 'Escape' && k.presenting && !k.selectedProcessId && !k.selectedConnectionId) {
      e.preventDefault()
      k.setPresenting(false)
      return
    }

    if (
      (e.ctrlKey || e.metaKey) &&
      e.key.toLowerCase() === 'a' &&
      (isWorkflowEditorTool(k.activeTool) || k.editorSelection.elements.length > 0)
    ) {
      e.preventDefault()
      k.editorCommandApiRef.current?.selectAll()
      k.clearSelection()
      return
    }

    const undoRedoShortcut = getUndoRedoShortcut(e)
    if (undoRedoShortcut && k.isEditorCommandContext) {
      e.preventDefault()
      if (undoRedoShortcut === 'undo') {
        k.editorCommandApiRef.current?.undo()
      } else {
        k.editorCommandApiRef.current?.redo()
      }
      return
    }
  }

  const ctx = {
    selectedProcessId: k.selectedProcessId,
    selectedConnectionId: k.selectedConnectionId,
    isEditing,
    deleteSelection: () => k.deleteApiRef.current.deleteSelection(),
    duplicateNode: k.duplicateNode,
    clearSelection: k.clearSelection,
    selectAll: () => k.selectAllRef.current(),
    undo: k.undo,
    redo: k.redo,
  }

  for (const action of CANVAS_ACTIONS) {
    if (action.keyTest(e) && action.predicate(ctx)) {
      e.preventDefault()
      void action.handler(ctx)
      break
    }
  }
}
