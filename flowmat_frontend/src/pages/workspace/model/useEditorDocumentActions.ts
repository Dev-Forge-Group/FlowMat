import { useEffect, useRef, useState, type MutableRefObject } from 'react'
import { useIsMutating, useQueryClient } from '@tanstack/react-query'
import {
  editorDocumentQueryKey,
  fetchEditorDocument,
  saveEditorDocument,
} from '../../../entities/editor-document/api/editorDocumentApi'
import type { WorkflowEditorTool, WorkflowPaletteTool } from '../../../entities/workflow/model/nodeCatalog'
import { insertElement, snapPoint, type Vec2 } from '../../../lib/flowmat-editor'
import type { WorkspaceEditorCommandApi } from '../ui/WorkspaceEditorLayer'
import { createWorkspaceEditorElement, WORKSPACE_EDITOR_GRID_SIZE } from './workspaceEditorElements'

interface Options {
  workflowId: string
  canEdit: boolean
  editorCommandApiRef: MutableRefObject<WorkspaceEditorCommandApi | null>
  clearEditorSelection(): void
  setWorkspaceMessage(message: string | null): void
  setActiveTool(tool: WorkflowPaletteTool): void
}

/**
 * The workflow's editor document (the drawing layer's shapes, lines and text) saved, reloaded and added to, and the
 * save status the ribbon shows: "saving" while any change is on its way, "saved" for two seconds after.
 */
export function useEditorDocumentActions({ workflowId, canEdit, editorCommandApiRef, clearEditorSelection,
  setWorkspaceMessage, setActiveTool }: Options) {
  const queryClient = useQueryClient()
  const isMutating = useIsMutating()
  const [savedLabel, setSavedLabel] = useState<'saving' | 'saved' | null>(null)
  const savedTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    if (isMutating > 0) {
      if (savedTimerRef.current) clearTimeout(savedTimerRef.current)
      setSavedLabel('saving')
    } else if (savedLabel === 'saving') {
      setSavedLabel('saved')
      savedTimerRef.current = setTimeout(() => setSavedLabel(null), 2000)
    }
  }, [isMutating, savedLabel])

  function startSaving() {
    setWorkspaceMessage(null)
    if (savedTimerRef.current) clearTimeout(savedTimerRef.current)
    setSavedLabel('saving')
  }

  function markSaved() {
    if (savedTimerRef.current) clearTimeout(savedTimerRef.current)
    setSavedLabel('saved')
    savedTimerRef.current = setTimeout(() => setSavedLabel(null), 2000)
  }

  function failSaving(error: unknown, fallback: string) {
    setSavedLabel(null)
    setWorkspaceMessage(error instanceof Error ? error.message : fallback)
  }

  async function handleSaveEditorDocument() {
    if (!canEdit) return
    startSaving()
    try {
      const editorDocument = await fetchEditorDocument(workflowId)
      const savedDocument = await saveEditorDocument(workflowId, editorDocument)
      queryClient.setQueryData(editorDocumentQueryKey(workflowId), savedDocument)
      markSaved()
    } catch (error) {
      failSaving(error, 'Failed to save editor document.')
    }
  }

  async function handleReloadEditorDocument() {
    editorCommandApiRef.current?.resetHistory()
    clearEditorSelection()
    await queryClient.invalidateQueries({ queryKey: editorDocumentQueryKey(workflowId) })
  }

  async function handleCreateEditorElement(tool: WorkflowEditorTool, position: Vec2) {
    if (!canEdit) return
    // Connectors are drawn by dragging between two shapes inside WorkspaceEditorLayer;
    // a plain pane click has no second anchor to bind to, so it's a no-op here.
    if (tool === 'editor-connector') return
    startSaving()
    try {
      const editorDocument = await fetchEditorDocument(workflowId)
      const snappedPosition = snapPoint(position, { enabled: true, gridSize: WORKSPACE_EDITOR_GRID_SIZE })
      const { element, nextElementSeq } = createWorkspaceEditorElement(tool, snappedPosition, editorDocument)
      const nextDocument = { ...insertElement(editorDocument, element), nextElementSeq }
      const savedDocument = await saveEditorDocument(workflowId, nextDocument)
      editorCommandApiRef.current?.recordBackendSnapshot(editorDocument, [])
      queryClient.setQueryData(editorDocumentQueryKey(workflowId), savedDocument)
      markSaved()
      setActiveTool('select')
    } catch (error) {
      failSaving(error, 'Failed to create editor element.')
    }
  }

  return { savedLabel, handleSaveEditorDocument, handleReloadEditorDocument, handleCreateEditorElement }
}
