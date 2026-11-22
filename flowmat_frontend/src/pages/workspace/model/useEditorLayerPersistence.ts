import { useCallback, useRef } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import type { EditorDocument, ElementId } from '../../../lib/flowmat-editor'
import type { PatchCanvasAnnotationInput } from '../../../entities/canvas-annotation/api/canvasAnnotationApi'
import { editorElementToPatchAnnotationInput } from '../../../entities/canvas-annotation/model/annotationEditorAdapter'
import {
  editorDocumentQueryKey,
  patchEditorElements,
  saveEditorDocument,
} from '../../../entities/editor-document/api/editorDocumentApi'
import {
  isServerEditorDocument,
  type ServerEditorDocument,
} from '../../../entities/editor-document/model/editorDocumentBackendAdapter'
import type { CanvasAnnotationViewModel } from '../../../entities/workflow/model/types'
import { errorMessage, errorStatus } from '../../../shared/lib/errorMessage'
import { keepsUnsavedEdit } from './workspaceEditorDocument'

interface Options {
  workflowId: string
  annotationByElementId: ReadonlyMap<ElementId, CanvasAnnotationViewModel>
  onError?(message: string): void
  onUpdateAnnotation(annotationId: string, input: PatchCanvasAnnotationInput): Promise<void>
  onDeleteAnnotations(annotationIds: string[]): Promise<void>
}

/** A change to the drawing layer: the editor document as it now is, and the annotations to patch or delete. */
export interface LayerChanges {
  backendDocument?: EditorDocument | null
  annotationDocument?: EditorDocument | null
  annotationIds?: readonly ElementId[]
  deleteAnnotationIds?: readonly ElementId[]
  annotationZIndexById?: ReadonlyMap<ElementId, string>
}

/**
 * Saving the drawing layer. An edit shows at once and is saved as element changes against the document the server last
 * gave (docs/architecture/adr/ADR-042-drawing-layer-element-changes.md), so someone else's edits to other elements are
 * kept. Saves go one after another, each against the answer to the one before. A passing failure keeps the edit on screen
 * for the next save; a conflict (someone changed the same element first) or a refusal shows the server's version again.
 * Annotations are patched one by one from their elements, and deleted by id.
 */
export function useEditorLayerPersistence({ workflowId, annotationByElementId, onError, onUpdateAnnotation,
  onDeleteAnnotations }: Options) {
  const queryClient = useQueryClient()
  /** The document the server last gave: what the next save's changes are worked out against. */
  const serverRef = useRef<ServerEditorDocument | null>(null)
  const savingRef = useRef<Promise<void>>(Promise.resolve())

  const persistDocument = useCallback((nextDocument: EditorDocument) => {
    const key = editorDocumentQueryKey(workflowId)
    const cached = queryClient.getQueryData<EditorDocument>(key)
    if (isServerEditorDocument(cached)) serverRef.current = cached
    // Show the edit at once: a drag clears its draft before this save returns, and the cache is what is drawn then.
    queryClient.setQueryData(key, { ...nextDocument, selectedIds: [] })
    const save = async () => {
      // A fetch that landed while the save before was on its way is the newer base.
      const latest = queryClient.getQueryData<EditorDocument>(key)
      if (isServerEditorDocument(latest)) serverRef.current = latest
      const base = serverRef.current
      try {
        const next = { ...nextDocument, selectedIds: [] }
        const savedDocument = base ? await patchEditorElements(workflowId, base, next) : await saveEditorDocument(workflowId, next)
        serverRef.current = savedDocument
        queryClient.setQueryData(key, savedDocument)
      } catch (error) {
        const message = errorMessage(error, 'Failed to save editor document.')
        const status = errorStatus(error)
        if (keepsUnsavedEdit(status)) {
          // A passing failure: keep the edit on screen; the next save works out its changes again, this edit included.
          onError?.(`${message} Your change is kept and will be saved with your next edit.`)
        } else {
          // Someone changed the same element first, or the change was refused: the server's version is the one to show.
          void queryClient.invalidateQueries({ queryKey: key })
          onError?.(status === 409 ? `${message} Their version is shown.` : message)
        }
      }
    }
    savingRef.current = savingRef.current.then(save)
    return savingRef.current
  }, [onError, queryClient, workflowId])

  const persistAnnotationDocument = useCallback(async (nextDocument: EditorDocument, annotationIds: readonly ElementId[],
    zIndexById?: ReadonlyMap<ElementId, string>) => {
    try {
      await Promise.all(annotationIds.map(async (id) => {
        const annotation = annotationByElementId.get(id)
        const element = nextDocument.elements.find((item) => item.id === id)
        if (!annotation || !element) return
        const patchResult = editorElementToPatchAnnotationInput(element, annotation)
        if (!patchResult.ok) {
          onError?.(patchResult.reason)
          return
        }
        const nextZIndex = zIndexById?.get(id)
        await onUpdateAnnotation(annotation.annotationId, {
          ...patchResult.value,
          style: patchResult.value.style ? { ...annotation.style, ...patchResult.value.style } : patchResult.value.style,
          ...(nextZIndex ? { zIndex: nextZIndex } : {}),
        })
      }))
    } catch (error) {
      onError?.(error instanceof Error ? error.message : 'Failed to save annotation edits.')
    }
  }, [annotationByElementId, onError, onUpdateAnnotation])

  const persistLayerChanges = useCallback(async ({ backendDocument, annotationDocument, annotationIds = [],
    deleteAnnotationIds = [], annotationZIndexById }: LayerChanges) => {
    const tasks: Array<Promise<unknown>> = []
    if (backendDocument) tasks.push(persistDocument(backendDocument))
    if (annotationDocument && annotationIds.length > 0) {
      tasks.push(persistAnnotationDocument(annotationDocument, annotationIds, annotationZIndexById))
    }
    if (deleteAnnotationIds.length > 0) {
      tasks.push(onDeleteAnnotations(deleteAnnotationIds.map((id) => String(id))).catch((error) => {
        onError?.(error instanceof Error ? error.message : 'Failed to delete annotations.')
      }))
    }
    await Promise.all(tasks)
  }, [onDeleteAnnotations, onError, persistAnnotationDocument, persistDocument])

  return { persistDocument, persistLayerChanges }
}
