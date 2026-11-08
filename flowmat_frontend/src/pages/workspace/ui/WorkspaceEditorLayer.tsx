import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type MouseEvent as ReactMouseEvent,
  type PointerEvent as ReactPointerEvent,
} from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useReactFlow } from '@xyflow/react'
import {
  angleBetween,
  boxCenter,
  cloneEditorElement,
  createElementId,
  createEmptyEditorDocument,
  createLineElement,
  cleanupEmptyEditorGroups,
  deleteElements as deleteEditorElements,
  expandGroupSelection,
  findElementsInBox,
  findNearestAnchor,
  getAnchorPoint,
  getSelectedElementBounds,
  groupEditorElements,
  insertElement,
  mergeEditorDocumentsById,
  moveLineEndpoint,
  normalizeBox,
  recomputeBoundLines,
  resizeBoxFromHandle,
  resizeElementWithinBox,
  resizeRotatedElementFromHandle,
  rotateElement,
  selectElements,
  snapPoint,
  toElementId,
  toSvgRenderNodes,
  translateElement,
  ungroupEditorElements,
  updateElement,
  type AnchorSide,
  type Box2,
  type EditorDocument,
  type EditorElement,
  type ElementBinding,
  type ElementId,
  type LineEndpoint,
  type ResizeHandle,
  type Vec2,
} from '../../../lib/flowmat-editor'
import {
  annotationsToEditorDocument,
  editorElementToPatchAnnotationInput,
} from '../../../entities/canvas-annotation/model/annotationEditorAdapter'
import {
  AnchorPointOverlay,
  ArrowMarkerDefs,
  LineEndpointHandles,
  SelectionHandles,
  SelectionOutline,
  SvgElementView,
} from '../../../lib/flowmat-editor/react'
import {
  editorDocumentQueryKey,
  useEditorDocumentQuery,
  useSaveEditorDocumentMutation,
} from '../../../entities/editor-document/api/editorDocumentApi'
import type { CanvasAnnotationViewModel } from '../../../entities/workflow/model/types'
import { errorMessage, errorStatus } from '../../../shared/lib/errorMessage'
import type { WorkflowPaletteTool } from '../../../entities/workflow/model/nodeCatalog'
import type { PatchCanvasAnnotationInput } from '../../../entities/canvas-annotation/api/canvasAnnotationApi'
import type {
  AlignDirection,
  DistributeAxis,
} from '../../../entities/canvas-annotation/model/annotationLayout'
import {
  getWorkspaceEditorSelectionCapabilities,
  splitWorkspaceEditorSelection as splitSelectedIds,
  type WorkspaceEditorSelectionCapabilities,
} from '../model/workspaceEditorSelection'
import { alignWorkspaceEditorElements, distributeWorkspaceEditorElements } from '../model/workspaceEditorLayout'

const WORKSPACE_LAYER_EXTENT = 100000
const WORKSPACE_GRID_SIZE = 8
const WORKSPACE_EDITOR_HISTORY_LIMIT = 100
const WORKSPACE_CONNECTOR_ANCHOR_THRESHOLD = 14

// Stable placeholder used while the editor-document query is pending/undefined.
// A fresh createEmptyEditorDocument() per render would give backendElementIds/editableIds
// a new reference every render, keeping the selection-cleanup effect below permanently
// re-armed and driving an update-depth loop into React Flow's StoreUpdater.
const EMPTY_BACKEND_DOCUMENT: EditorDocument = createEmptyEditorDocument()

export interface WorkspaceEditorSelectionSnapshot extends WorkspaceEditorSelectionCapabilities {
  selectedIds: readonly ElementId[]
  elements: readonly EditorElement[]
  canUndo: boolean
  canRedo: boolean
}

export interface WorkspaceEditorStylePatch {
  fill?: string
  stroke?: string
  strokeWidth?: number
  opacity?: number
  text?: string
  color?: string
  fontSize?: number
}

export interface WorkspaceEditorCommandApi {
  clearSelection(): void
  selectAll(): void
  deleteSelected(): void
  duplicateSelected(): void
  groupSelected(): void
  ungroupSelected(): void
  bringSelectedToFront(): void
  sendSelectedToBack(): void
  alignSelected(direction: AlignDirection): void
  distributeSelected(axis: DistributeAxis): void
  updateSelectedStyle(patch: WorkspaceEditorStylePatch): void
  undo(): void
  redo(): void
  canUndo(): boolean
  canRedo(): boolean
  recordBackendSnapshot(document: EditorDocument, selectedIds?: readonly ElementId[]): void
  resetHistory(): void
}

interface InteractionDocuments {
  backendDocument: EditorDocument
  annotationDocument: EditorDocument
  selectedIds: readonly ElementId[]
}

interface WorkspaceEditorHistorySnapshot {
  document: EditorDocument
  selectedIds: readonly ElementId[]
}

interface WorkspaceEditorHistoryState {
  past: readonly WorkspaceEditorHistorySnapshot[]
  future: readonly WorkspaceEditorHistorySnapshot[]
}

type EditorInteraction =
  | { type: 'move'; start: Vec2; base: InteractionDocuments }
  | { type: 'marquee'; origin: Vec2; current: Vec2 }
  | { type: 'resize'; handle: ResizeHandle; base: InteractionDocuments; baseBounds: Box2 }
  | { type: 'line-endpoint'; endpoint: LineEndpoint; base: InteractionDocuments }
  | {
      type: 'rotate'
      center: Vec2
      startAngle: number
      base: InteractionDocuments
      initialRotations: ReadonlyMap<ElementId, number>
    }
  | {
      type: 'draw-connector'
      startElementId: ElementId
      startAnchor: AnchorSide
      startPoint: Vec2
      current: Vec2
      hoverElementId: ElementId | null
      hoverAnchor: AnchorSide | null
    }

interface Props {
  workflowId: string
  annotations: CanvasAnnotationViewModel[]
  activeTool: WorkflowPaletteTool
  editable: boolean
  onError?(message: string): void
  onSelectionChange?(snapshot: WorkspaceEditorSelectionSnapshot): void
  onCommandReady?(api: WorkspaceEditorCommandApi | null): void
  onUpdateAnnotation(annotationId: string, input: PatchCanvasAnnotationInput): Promise<void>
  onDeleteAnnotations(annotationIds: string[]): Promise<void>
  onEditAnnotationText?(annotationId: string): void
}

export function WorkspaceEditorLayer({
  workflowId,
  annotations,
  activeTool,
  editable,
  onError,
  onSelectionChange,
  onCommandReady,
  onUpdateAnnotation,
  onDeleteAnnotations,
  onEditAnnotationText,
}: Props) {
  const { screenToFlowPosition } = useReactFlow()
  const queryClient = useQueryClient()
  const { data: backendDocument } = useEditorDocumentQuery(workflowId)
  const saveMutation = useSaveEditorDocumentMutation(workflowId)
  const [selectedIds, setSelectedIds] = useState<readonly ElementId[]>([])
  const [interaction, setInteraction] = useState<EditorInteraction | null>(null)
  const [draftBackendDocument, setDraftBackendDocument] = useState<EditorDocument | null>(null)
  const [draftAnnotationDocument, setDraftAnnotationDocument] = useState<EditorDocument | null>(null)
  const [backendHistory, setBackendHistory] = useState<WorkspaceEditorHistoryState>(() => ({
    past: [],
    future: [],
  }))
  const [isShiftPressed, setIsShiftPressed] = useState(false)

  const baseBackendDocument = backendDocument ?? EMPTY_BACKEND_DOCUMENT
  const annotationDocument = useMemo(() => annotationsToEditorDocument(annotations), [annotations])
  const editableDocument = draftBackendDocument ?? baseBackendDocument
  const editableAnnotationDocument = draftAnnotationDocument ?? annotationDocument
  const backendElementIds = useMemo(
    () => new Set(editableDocument.elements.map((element) => element.id)),
    [editableDocument.elements],
  )
  const annotationByElementId = useMemo(
    () => new Map(annotations.map((annotation) => [toElementId(annotation.annotationId), annotation])),
    [annotations],
  )
  const editableAnnotationIds = useMemo(
    () => new Set(
      editableAnnotationDocument.elements
        .map((element) => element.id)
        .filter((id) => !backendElementIds.has(id)),
    ),
    [backendElementIds, editableAnnotationDocument.elements],
  )
  const editableIds = useMemo(
    () => new Set([...backendElementIds, ...editableAnnotationIds]),
    [backendElementIds, editableAnnotationIds],
  )
  const displayDocument = useMemo(() => {
    const merged = mergeEditorDocumentsById(editableAnnotationDocument, editableDocument)
    return selectElements(merged, selectedIds)
  }, [editableAnnotationDocument, editableDocument, selectedIds])
  const selectedElements = useMemo(
    () => displayDocument.elements.filter((element) => selectedIds.includes(element.id)),
    [displayDocument.elements, selectedIds],
  )
  const selectionCapabilities = useMemo(
    () => getWorkspaceEditorSelectionCapabilities(editable, editableDocument, editableAnnotationDocument, selectedIds),
    [editable, editableAnnotationDocument, editableDocument, selectedIds],
  )
  const selectionBounds = getSelectedElementBounds(displayDocument)
  const marqueeBox = interaction?.type === 'marquee'
    ? normalizeBox({
        x: interaction.origin.x,
        y: interaction.origin.y,
        width: interaction.current.x - interaction.origin.x,
        height: interaction.current.y - interaction.origin.y,
      })
    : null
  const marqueeReady = editable && activeTool === 'select' && (isShiftPressed || interaction?.type === 'marquee')
  const canUndoBackendDocument = backendHistory.past.length > 0
  const canRedoBackendDocument = backendHistory.future.length > 0

  useEffect(() => {
    setSelectedIds((current) => filterStable(current, (id) => editableIds.has(id)))
  }, [editableIds])

  useEffect(() => {
    onSelectionChange?.({
      ...selectionCapabilities,
      selectedIds,
      elements: selectedElements,
      canUndo: canUndoBackendDocument,
      canRedo: canRedoBackendDocument,
    })
  }, [canRedoBackendDocument, canUndoBackendDocument, onSelectionChange, selectedElements, selectedIds, selectionCapabilities])

  const persistDocument = useCallback(
    async (nextDocument: EditorDocument) => {
      const key = editorDocumentQueryKey(workflowId)
      // Show the edit at once: a drag clears its draft before this save returns, and the cache is what is drawn then.
      queryClient.setQueryData(key, { ...nextDocument, selectedIds: [] })
      try {
        const savedDocument = await saveMutation.mutateAsync({
          ...nextDocument,
          selectedIds: [],
        })
        queryClient.setQueryData(key, savedDocument)
      } catch (error) {
        const message = errorMessage(error, 'Failed to save editor document.')
        if (keepsUnsavedEdit(errorStatus(error))) {
          // A passing failure: keep the edit on screen; the next save sends the whole document, this edit included.
          onError?.(`${message} Your change is kept and will be saved with your next edit.`)
        } else {
          // A conflict or a refused document: the server's version is the one to show.
          void queryClient.invalidateQueries({ queryKey: key })
          onError?.(message)
        }
      }
    },
    [onError, queryClient, saveMutation, workflowId],
  )

  const persistAnnotationDocument = useCallback(
    async (
      nextDocument: EditorDocument,
      annotationIds: readonly ElementId[],
      options: { zIndexById?: ReadonlyMap<ElementId, string> } = {},
    ) => {
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

          const nextZIndex = options.zIndexById?.get(id)
          await onUpdateAnnotation(annotation.annotationId, {
            ...patchResult.value,
            style: patchResult.value.style
              ? { ...annotation.style, ...patchResult.value.style }
              : patchResult.value.style,
            ...(nextZIndex ? { zIndex: nextZIndex } : {}),
          })
        }))
      } catch (error) {
        onError?.(error instanceof Error ? error.message : 'Failed to save annotation edits.')
      }
    },
    [annotationByElementId, onError, onUpdateAnnotation],
  )

  const persistLayerChanges = useCallback(
    async ({
      backendDocument: nextBackendDocument,
      annotationDocument: nextAnnotationDocument,
      annotationIds = [],
      deleteAnnotationIds = [],
      annotationZIndexById,
    }: {
      backendDocument?: EditorDocument | null
      annotationDocument?: EditorDocument | null
      annotationIds?: readonly ElementId[]
      deleteAnnotationIds?: readonly ElementId[]
      annotationZIndexById?: ReadonlyMap<ElementId, string>
    }) => {
      const tasks: Array<Promise<unknown>> = []
      if (nextBackendDocument) tasks.push(persistDocument(nextBackendDocument))
      if (nextAnnotationDocument && annotationIds.length > 0) {
        tasks.push(persistAnnotationDocument(nextAnnotationDocument, annotationIds, {
          zIndexById: annotationZIndexById,
        }))
      }
      if (deleteAnnotationIds.length > 0) {
        tasks.push(
          onDeleteAnnotations(deleteAnnotationIds.map((id) => String(id))).catch((error) => {
            onError?.(error instanceof Error ? error.message : 'Failed to delete annotations.')
          }),
        )
      }
      await Promise.all(tasks)
    },
    [onDeleteAnnotations, onError, persistAnnotationDocument, persistDocument],
  )

  const pushBackendHistorySnapshot = useCallback((
    document: EditorDocument,
    ids: readonly ElementId[] = selectedIds,
  ) => {
    const snapshot = createBackendHistorySnapshot(document, ids)
    setBackendHistory((current) => ({
      past: [...current.past, snapshot].slice(-WORKSPACE_EDITOR_HISTORY_LIMIT),
      future: [],
    }))
  }, [selectedIds])

  const undoBackendDocument = useCallback(() => {
    const target = backendHistory.past[backendHistory.past.length - 1]
    if (!editable || !target) return

    const currentSnapshot = createBackendHistorySnapshot(editableDocument, selectedIds)
    setBackendHistory({
      past: backendHistory.past.slice(0, -1),
      future: [currentSnapshot, ...backendHistory.future],
    })
    setSelectedIds(target.selectedIds)
    setDraftBackendDocument(null)
    setDraftAnnotationDocument(null)
    void persistDocument(selectElements(target.document, target.selectedIds))
  }, [backendHistory, editable, editableDocument, persistDocument, selectedIds])

  const redoBackendDocument = useCallback(() => {
    const target = backendHistory.future[0]
    if (!editable || !target) return

    const currentSnapshot = createBackendHistorySnapshot(editableDocument, selectedIds)
    setBackendHistory({
      past: [...backendHistory.past, currentSnapshot].slice(-WORKSPACE_EDITOR_HISTORY_LIMIT),
      future: backendHistory.future.slice(1),
    })
    setSelectedIds(target.selectedIds)
    setDraftBackendDocument(null)
    setDraftAnnotationDocument(null)
    void persistDocument(selectElements(target.document, target.selectedIds))
  }, [backendHistory, editable, editableDocument, persistDocument, selectedIds])

  const resetBackendHistory = useCallback(() => {
    setBackendHistory({ past: [], future: [] })
  }, [])

  const deleteSelectedElements = useCallback(async () => {
    if (!editable || selectedIds.length === 0) return
    const split = splitSelectedIds(editableDocument, editableAnnotationDocument, selectedIds)
    const nextBackendDocument = split.backendIds.length > 0
      ? cleanupEmptyEditorGroups(deleteEditorElements(editableDocument, split.backendIds))
      : null
    if (nextBackendDocument && split.annotationIds.length === 0) {
      pushBackendHistorySnapshot(editableDocument, selectedIds)
    }

    setSelectedIds([])
    setDraftBackendDocument(null)
    setDraftAnnotationDocument(null)

    await persistLayerChanges({
      backendDocument: nextBackendDocument,
      deleteAnnotationIds: split.annotationIds,
    })
  }, [editable, editableAnnotationDocument, editableDocument, persistLayerChanges, pushBackendHistorySnapshot, selectedIds])

  const duplicateSelectedElements = useCallback(async () => {
    if (!editable || selectedIds.length === 0) return
    const split = splitSelectedIds(editableDocument, editableAnnotationDocument, selectedIds)
    if (split.annotationIds.length > 0) {
      onError?.('Legacy annotations cannot be duplicated from the editor layer yet.')
    }
    if (split.backendIds.length === 0) return

    pushBackendHistorySnapshot(editableDocument, selectedIds)
    const result = duplicateEditorElements(editableDocument, split.backendIds)
    setSelectedIds(result.selectedIds)
    setDraftBackendDocument(null)
    setDraftAnnotationDocument(null)
    await persistDocument(result.document)
  }, [editable, editableAnnotationDocument, editableDocument, onError, persistDocument, pushBackendHistorySnapshot, selectedIds])

  const createConnectorElement = useCallback(async (
    start: ElementBinding,
    end: ElementBinding,
  ) => {
    if (!editable || start.elementId === end.elementId) return
    const startElement = editableDocument.elements.find((element) => element.id === start.elementId)
    const endElement = editableDocument.elements.find((element) => element.id === end.elementId)
    if (!startElement || !endElement) return

    const usedIds = new Set(editableDocument.elements.map((element) => element.id))
    let nextSequence = Math.max(1, editableDocument.nextElementSeq)
    while (usedIds.has(createElementId(nextSequence, 'shape'))) nextSequence += 1
    const id = createElementId(nextSequence, 'shape')
    const order = Math.max(0, ...editableDocument.elements.map((element) => element.order)) + 1

    const connector = createLineElement({
      id,
      start: getAnchorPoint(startElement, start.anchor),
      end: getAnchorPoint(endElement, end.anchor),
      order,
      style: {
        stroke: '#7c3aed',
        strokeWidth: 2,
        strokeStyle: 'solid',
        opacity: 1,
        startArrow: 'none',
        endArrow: 'arrow',
      },
      startBinding: start,
      endBinding: end,
    })

    pushBackendHistorySnapshot(editableDocument, selectedIds)
    const nextDocument = { ...insertElement(editableDocument, connector), nextElementSeq: nextSequence + 1 }
    setSelectedIds([id])
    setDraftBackendDocument(null)
    setDraftAnnotationDocument(null)
    await persistDocument(nextDocument)
  }, [editable, editableDocument, persistDocument, pushBackendHistorySnapshot, selectedIds])

  const groupSelectedElements = useCallback(async () => {
    if (!selectionCapabilities.canGroup) return
    const split = splitSelectedIds(editableDocument, editableAnnotationDocument, selectedIds)
    const canGroupAnnotations = split.annotationIds.length > 1
    const canGroupBackend = split.backendIds.length > 1
    if (canGroupBackend && !canGroupAnnotations) {
      pushBackendHistorySnapshot(editableDocument, selectedIds)
    }
    if (canGroupAnnotations) {
      const nextGroupId = `grp_${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`
      await persistLayerChanges({
        annotationDocument: transformSelected(editableAnnotationDocument, split.annotationIds, (element) => ({
          ...element,
          parentId: toElementId(nextGroupId),
        })),
        annotationIds: split.annotationIds,
      })
    }
    if (canGroupBackend) {
      const result = groupEditorElements(editableDocument, split.backendIds)
      setSelectedIds([...result.selectedIds, ...split.annotationIds])
      setDraftBackendDocument(null)
      setDraftAnnotationDocument(null)
      await persistDocument(result.document)
      return
    }
    setDraftAnnotationDocument(null)
  }, [editableAnnotationDocument, editableDocument, persistDocument, persistLayerChanges, pushBackendHistorySnapshot, selectedIds, selectionCapabilities.canGroup])

  const ungroupSelectedElements = useCallback(async () => {
    if (!selectionCapabilities.canUngroup) return
    const split = splitSelectedIds(editableDocument, editableAnnotationDocument, selectedIds)
    const groupedAnnotationIds = split.annotationIds.filter((id) =>
      editableAnnotationDocument.elements.some((element) => element.id === id && element.parentId),
    )
    const canUngroupBackend = getWorkspaceEditorSelectionCapabilities(
      editable, editableDocument, editableAnnotationDocument, split.backendIds,
    ).canUngroup
    if (groupedAnnotationIds.length > 0) {
      await persistLayerChanges({
        annotationDocument: transformSelected(editableAnnotationDocument, groupedAnnotationIds, (element) => ({
          ...element,
          parentId: null,
        })),
        annotationIds: groupedAnnotationIds,
      })
    }
    if (canUngroupBackend) {
      if (groupedAnnotationIds.length === 0) {
        pushBackendHistorySnapshot(editableDocument, selectedIds)
      }
      const result = ungroupEditorElements(editableDocument, split.backendIds)
      setSelectedIds([...result.selectedIds, ...split.annotationIds])
      setDraftBackendDocument(null)
      setDraftAnnotationDocument(null)
      await persistDocument(result.document)
      return
    }
    setDraftAnnotationDocument(null)
  }, [editable, editableAnnotationDocument, editableDocument, persistDocument, persistLayerChanges, pushBackendHistorySnapshot, selectedIds, selectionCapabilities.canUngroup])

  const reorderSelectedElementsInLayer = useCallback(async (direction: 'front' | 'back') => {
    if (!editable || selectedIds.length === 0) return
    const split = splitSelectedIds(editableDocument, editableAnnotationDocument, selectedIds)
    const nextBackendDocument = split.backendIds.length > 0
      ? reorderEditorElements(editableDocument, split.backendIds, direction)
      : null
    const annotationZIndexById = split.annotationIds.length > 0
      ? buildAnnotationZIndexPatch(annotations, split.annotationIds, direction)
      : undefined
    if (nextBackendDocument && split.annotationIds.length === 0) {
      pushBackendHistorySnapshot(editableDocument, selectedIds)
    }

    setDraftBackendDocument(null)
    setDraftAnnotationDocument(null)
    await persistLayerChanges({
      backendDocument: nextBackendDocument,
      annotationDocument: split.annotationIds.length > 0 ? editableAnnotationDocument : null,
      annotationIds: split.annotationIds,
      annotationZIndexById,
    })
  }, [annotations, editable, editableAnnotationDocument, editableDocument, persistLayerChanges, pushBackendHistorySnapshot, selectedIds])

  const alignSelected = useCallback((direction: AlignDirection) => {
    if (!selectionCapabilities.canAlign || selectionCapabilities.selectionKind !== 'backend') return
    const nextBackendDocument = alignWorkspaceEditorElements(editableDocument, selectedIds, direction)
    if (nextBackendDocument === editableDocument) return
    pushBackendHistorySnapshot(editableDocument, selectedIds)
    setDraftBackendDocument(null)
    setDraftAnnotationDocument(null)
    void persistLayerChanges({ backendDocument: nextBackendDocument })
  }, [editableDocument, persistLayerChanges, pushBackendHistorySnapshot, selectedIds, selectionCapabilities.canAlign, selectionCapabilities.selectionKind])

  const distributeSelected = useCallback((axis: DistributeAxis) => {
    if (!selectionCapabilities.canDistribute || selectionCapabilities.selectionKind !== 'backend') return
    const nextBackendDocument = distributeWorkspaceEditorElements(editableDocument, selectedIds, axis)
    if (nextBackendDocument === editableDocument) return
    pushBackendHistorySnapshot(editableDocument, selectedIds)
    setDraftBackendDocument(null)
    setDraftAnnotationDocument(null)
    void persistLayerChanges({ backendDocument: nextBackendDocument })
  }, [editableDocument, persistLayerChanges, pushBackendHistorySnapshot, selectedIds, selectionCapabilities.canDistribute, selectionCapabilities.selectionKind])

  const updateSelectedStyle = useCallback(async (patch: WorkspaceEditorStylePatch) => {
    if (!editable || selectedIds.length === 0) return
    const split = splitSelectedIds(editableDocument, editableAnnotationDocument, selectedIds)
    const nextBackendDocument = split.backendIds.length > 0
      ? selectElements(
          transformSelected(editableDocument, split.backendIds, (element) => patchEditorElement(element, patch)),
          selectedIds,
        )
      : null
    const nextAnnotationDocument = split.annotationIds.length > 0
      ? selectElements(
          transformSelected(editableAnnotationDocument, split.annotationIds, (element) =>
            patchEditorElement(element, patch),
          ),
          selectedIds,
        )
      : null
    if (nextBackendDocument && split.annotationIds.length === 0) {
      pushBackendHistorySnapshot(editableDocument, selectedIds)
    }

    setDraftBackendDocument(null)
    setDraftAnnotationDocument(null)
    await persistLayerChanges({
      backendDocument: nextBackendDocument,
      annotationDocument: nextAnnotationDocument,
      annotationIds: split.annotationIds,
    })
  }, [editable, editableAnnotationDocument, editableDocument, persistLayerChanges, pushBackendHistorySnapshot, selectedIds])

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Shift') setIsShiftPressed(true)
    }
    const handleKeyUp = (event: KeyboardEvent) => {
      if (event.key === 'Shift') setIsShiftPressed(false)
    }
    const handleBlur = () => setIsShiftPressed(false)

    window.addEventListener('keydown', handleKeyDown)
    window.addEventListener('keyup', handleKeyUp)
    window.addEventListener('blur', handleBlur)
    return () => {
      window.removeEventListener('keydown', handleKeyDown)
      window.removeEventListener('keyup', handleKeyUp)
      window.removeEventListener('blur', handleBlur)
    }
  }, [])

  useEffect(() => {
    if (!editable || selectedIds.length === 0) return

    const handleKeyDown = (event: KeyboardEvent) => {
      if (isTextEditingTarget(event.target)) return
      const action = getKeyboardAction(event)
      if (action === 'none') return
      event.preventDefault()
      if (action === 'delete') {
        void deleteSelectedElements()
        return
      }
      if (action === 'duplicate') {
        if (event.repeat) return
        void duplicateSelectedElements()
        return
      }
      if (action === 'group') {
        if (event.repeat) return
        void groupSelectedElements()
        return
      }
      if (action === 'ungroup') {
        if (event.repeat) return
        void ungroupSelectedElements()
        return
      }
      void reorderSelectedElementsInLayer(action)
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [
    deleteSelectedElements,
    duplicateSelectedElements,
    editable,
    groupSelectedElements,
    reorderSelectedElementsInLayer,
    selectedIds.length,
    ungroupSelectedElements,
  ])

  useEffect(() => {
    if (!onCommandReady) return
    onCommandReady({
      clearSelection: () => {
        setSelectedIds([])
        setDraftBackendDocument(null)
        setDraftAnnotationDocument(null)
      },
      selectAll: () => {
        if (!editable) return
        setSelectedIds(getSelectableElementIds(displayDocument, editableIds))
        setDraftBackendDocument(null)
        setDraftAnnotationDocument(null)
      },
      deleteSelected: () => {
        void deleteSelectedElements()
      },
      duplicateSelected: () => {
        void duplicateSelectedElements()
      },
      groupSelected: () => {
        void groupSelectedElements()
      },
      ungroupSelected: () => {
        void ungroupSelectedElements()
      },
      bringSelectedToFront: () => {
        void reorderSelectedElementsInLayer('front')
      },
      sendSelectedToBack: () => {
        void reorderSelectedElementsInLayer('back')
      },
      alignSelected: (direction) => {
        alignSelected(direction)
      },
      distributeSelected: (axis) => {
        distributeSelected(axis)
      },
      updateSelectedStyle: (patch) => {
        void updateSelectedStyle(patch)
      },
      undo: () => {
        undoBackendDocument()
      },
      redo: () => {
        redoBackendDocument()
      },
      canUndo: () => canUndoBackendDocument,
      canRedo: () => canRedoBackendDocument,
      recordBackendSnapshot: (document, ids) => {
        pushBackendHistorySnapshot(document, ids)
      },
      resetHistory: () => {
        resetBackendHistory()
      },
    })
    return () => onCommandReady(null)
  }, [
    alignSelected,
    canRedoBackendDocument,
    canUndoBackendDocument,
    deleteSelectedElements,
    distributeSelected,
    duplicateSelectedElements,
    displayDocument,
    editable,
    editableIds,
    groupSelectedElements,
    onCommandReady,
    pushBackendHistorySnapshot,
    reorderSelectedElementsInLayer,
    redoBackendDocument,
    resetBackendHistory,
    ungroupSelectedElements,
    undoBackendDocument,
    updateSelectedStyle,
  ])

  function toCanvasPoint(event: ReactPointerEvent<SVGSVGElement>): Vec2 {
    return screenToFlowPosition({ x: event.clientX, y: event.clientY })
  }

  function toSnappedCanvasPoint(event: ReactPointerEvent<SVGSVGElement>): Vec2 {
    const point = toCanvasPoint(event)
    return event.altKey ? point : snapPoint(point, { enabled: true, gridSize: WORKSPACE_GRID_SIZE })
  }

  function handlePointerDown(event: ReactPointerEvent<SVGSVGElement>) {
    if (!editable) return
    const action = getEditorAction(event)
    const point = toCanvasPoint(event)
    const snappedPoint = toSnappedCanvasPoint(event)
    const targetId = getElementIdFromTarget(event.target)

    if (activeTool === 'editor-connector') {
      const anchorMatch = findNearestAnchor(displayDocument.elements, point, WORKSPACE_CONNECTOR_ANCHOR_THRESHOLD)
      if (!anchorMatch || !backendElementIds.has(anchorMatch.elementId)) return
      event.preventDefault()
      event.stopPropagation()
      event.currentTarget.setPointerCapture(event.pointerId)
      setInteraction({
        type: 'draw-connector',
        startElementId: anchorMatch.elementId,
        startAnchor: anchorMatch.side,
        startPoint: anchorMatch.point,
        current: anchorMatch.point,
        hoverElementId: null,
        hoverAnchor: null,
      })
      return
    }

    if (action.kind === 'line-endpoint') {
      if (!targetId || !editableIds.has(targetId)) return
      const target = displayDocument.elements.find((element) => element.id === targetId)
      if (target?.type !== 'line' || target.rotation !== 0) return
      event.preventDefault()
      event.stopPropagation()
      event.currentTarget.setPointerCapture(event.pointerId)
      setSelectedIds([targetId])
      setInteraction({
        type: 'line-endpoint',
        endpoint: action.endpoint,
        base: createInteractionDocuments(editableDocument, editableAnnotationDocument, [targetId]),
      })
      return
    }

    if (action.kind === 'resize' && selectedIds.length > 0) {
      const bounds = getSelectedElementBounds(selectElements(displayDocument, selectedIds))
      if (!bounds) return
      event.preventDefault()
      event.stopPropagation()
      event.currentTarget.setPointerCapture(event.pointerId)
      setInteraction({
        type: 'resize',
        handle: action.handle,
        base: createInteractionDocuments(editableDocument, editableAnnotationDocument, selectedIds),
        baseBounds: bounds,
      })
      return
    }

    if (action.kind === 'rotate' && selectedIds.length > 0) {
      const bounds = getSelectedElementBounds(selectElements(displayDocument, selectedIds))
      if (!bounds) return
      event.preventDefault()
      event.stopPropagation()
      event.currentTarget.setPointerCapture(event.pointerId)
      const base = createInteractionDocuments(editableDocument, editableAnnotationDocument, selectedIds)
      setInteraction({
        type: 'rotate',
        center: boxCenter(bounds),
        startAngle: angleBetween(boxCenter(bounds), point),
        base,
        initialRotations: readElementRotations(
          createDisplayDocument(base.annotationDocument, base.backendDocument, base.selectedIds),
          base.selectedIds,
        ),
      })
      return
    }

    if (!targetId && activeTool === 'select' && (event.shiftKey || isShiftPressed)) {
      event.preventDefault()
      event.stopPropagation()
      event.currentTarget.setPointerCapture(event.pointerId)
      setInteraction({ type: 'marquee', origin: point, current: point })
      setSelectedIds([])
      setDraftBackendDocument(null)
      setDraftAnnotationDocument(null)
      return
    }

    if (!targetId || !editableIds.has(targetId)) return

    event.preventDefault()
    event.stopPropagation()
    event.currentTarget.setPointerCapture(event.pointerId)

    const targetUnitIds = expandWorkspaceSelection(editableDocument, editableAnnotationDocument, annotations, [targetId])
    const nextSelectedIds = event.shiftKey
      ? toggleSelectedUnit(selectedIds, targetUnitIds)
      : targetUnitIds.every((id) => selectedIds.includes(id))
        ? selectedIds
        : targetUnitIds
    setSelectedIds(nextSelectedIds)
    setInteraction({
      type: 'move',
      start: snappedPoint,
      base: createInteractionDocuments(editableDocument, editableAnnotationDocument, nextSelectedIds),
    })
  }

  function handlePointerMove(event: ReactPointerEvent<SVGSVGElement>) {
    if (!interaction) return
    event.preventDefault()
    event.stopPropagation()
    const point = toCanvasPoint(event)
    const snappedPoint = toSnappedCanvasPoint(event)

    if (interaction.type === 'marquee') {
      const box = normalizeBox({
        x: interaction.origin.x,
        y: interaction.origin.y,
        width: point.x - interaction.origin.x,
        height: point.y - interaction.origin.y,
      })
      setInteraction({ ...interaction, current: point })
      setSelectedIds(expandWorkspaceSelection(
        editableDocument,
        editableAnnotationDocument,
        annotations,
        findElementsInBox(displayDocument.elements, box),
      ))
      return
    }

    if (interaction.type === 'draw-connector') {
      const hoverMatch = findNearestAnchor(
        displayDocument.elements,
        point,
        WORKSPACE_CONNECTOR_ANCHOR_THRESHOLD,
        interaction.startElementId,
      )
      setInteraction({
        ...interaction,
        current: hoverMatch ? hoverMatch.point : point,
        hoverElementId: hoverMatch?.elementId ?? null,
        hoverAnchor: hoverMatch?.side ?? null,
      })
      return
    }

    if (interaction.type === 'move') {
      const dx = snappedPoint.x - interaction.start.x
      const dy = snappedPoint.y - interaction.start.y
      const result = transformLayerDocuments(interaction.base, (element) => translateElement(element, { x: dx, y: dy }))
      setDraftBackendDocument(result.backendDocument)
      setDraftAnnotationDocument(result.annotationDocument)
      return
    }

    if (interaction.type === 'resize') {
      const singleSelectedElement = interaction.base.selectedIds.length === 1
        ? findElementInBase(interaction.base, interaction.base.selectedIds[0])
        : undefined

      const result = singleSelectedElement && singleSelectedElement.rotation !== 0
        ? transformLayerDocuments(interaction.base, (element) =>
            resizeRotatedElementFromHandle(element, interaction.baseBounds, interaction.handle, snappedPoint),
          )
        : (() => {
            const nextBounds = resizeBoxFromHandle(interaction.baseBounds, interaction.handle, snappedPoint)
            return transformLayerDocuments(interaction.base, (element) =>
              resizeElementWithinBox(element, interaction.baseBounds, nextBounds),
            )
          })()
      setDraftBackendDocument(result.backendDocument)
      setDraftAnnotationDocument(result.annotationDocument)
      return
    }

    if (interaction.type === 'line-endpoint') {
      const result = transformLayerDocuments(interaction.base, (element) =>
        element.type === 'line' ? moveLineEndpoint(element, interaction.endpoint, snappedPoint) : element,
      )
      setDraftBackendDocument(result.backendDocument)
      setDraftAnnotationDocument(result.annotationDocument)
      return
    }

    const delta = angleBetween(interaction.center, point) - interaction.startAngle
    const result = transformLayerDocuments(interaction.base, (element) =>
      rotateElement(element, (interaction.initialRotations.get(element.id) ?? element.rotation) + delta),
    )
    setDraftBackendDocument(result.backendDocument)
    setDraftAnnotationDocument(result.annotationDocument)
  }

  function handlePointerUp(event: ReactPointerEvent<SVGSVGElement>) {
    if (!interaction) return
    event.preventDefault()
    event.stopPropagation()
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId)
    }
    if (interaction.type === 'draw-connector') {
      const finished = interaction
      setInteraction(null)
      if (finished.hoverElementId && finished.hoverAnchor) {
        void createConnectorElement(
          { elementId: finished.startElementId, anchor: finished.startAnchor },
          { elementId: finished.hoverElementId, anchor: finished.hoverAnchor },
        )
      }
      return
    }
    const nextBackendDocument = draftBackendDocument
    const nextAnnotationDocument = draftAnnotationDocument
    const selectedIdsToPersist = interaction.type === 'marquee' ? [] : interaction.base.selectedIds
    setInteraction(null)
    setDraftBackendDocument(null)
    setDraftAnnotationDocument(null)
    if (interaction.type === 'marquee') return
    if (nextBackendDocument || nextAnnotationDocument) {
      const split = splitSelectedIds(
        interaction.base.backendDocument,
        interaction.base.annotationDocument,
        selectedIdsToPersist,
      )
      if (
        nextBackendDocument &&
        split.backendIds.length > 0 &&
        split.annotationIds.length === 0 &&
        !areEditorDocumentsEqual(interaction.base.backendDocument, nextBackendDocument)
      ) {
        pushBackendHistorySnapshot(interaction.base.backendDocument, selectedIdsToPersist)
      }
      void persistLayerChanges({
        backendDocument: nextBackendDocument,
        annotationDocument: nextAnnotationDocument,
        annotationIds: split.annotationIds,
      })
    }
  }

  function handleDoubleClick(event: ReactMouseEvent<SVGSVGElement>) {
    if (!editable || !onEditAnnotationText) return
    const targetId = getElementIdFromTarget(event.target)
    if (!targetId || !editableAnnotationIds.has(targetId)) return
    const annotation = annotationByElementId.get(targetId)
    if (annotation?.annotationType !== 'text') return
    event.preventDefault()
    event.stopPropagation()
    onEditAnnotationText(annotation.annotationId)
  }

  const layerClassName = `flowmat-workspace-editor-layer flowmat-workspace-editor-layer--interactive${
    marqueeReady ? ' flowmat-workspace-editor-layer--marquee-ready' : ''
  }`

  return (
    <svg
      className={layerClassName}
      aria-hidden="true"
      focusable="false"
      width={WORKSPACE_LAYER_EXTENT * 2}
      height={WORKSPACE_LAYER_EXTENT * 2}
      viewBox={`${-WORKSPACE_LAYER_EXTENT} ${-WORKSPACE_LAYER_EXTENT} ${WORKSPACE_LAYER_EXTENT * 2} ${WORKSPACE_LAYER_EXTENT * 2}`}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
      onPointerCancel={handlePointerUp}
      onDoubleClick={handleDoubleClick}
      data-active-tool={activeTool}
    >
      <defs>
        <ArrowMarkerDefs />
      </defs>
      {toSvgRenderNodes(displayDocument.elements).map((node) => (
        <SvgElementView
          key={node.id}
          element={node.element}
          transform={node.transform}
          selected={selectedIds.includes(node.element.id)}
          preview={false}
          interactive={editable && editableIds.has(node.element.id)}
        />
      ))}
      {displayDocument.elements.map((element) => (
        selectedIds.includes(element.id) ? <SelectionOutline key={`${element.id}-selection`} element={element} /> : null
      ))}
      {editable && selectionBounds && (
        <SelectionHandles
          bounds={selectionBounds}
          rotation={selectedElements.length === 1 ? selectedElements[0].rotation : 0}
        />
      )}
      {editable && displayDocument.elements.map((element) => (
        selectedIds.includes(element.id) && element.type === 'line' && element.rotation === 0
          ? <LineEndpointHandles key={`${element.id}-line-endpoints`} element={element} />
          : null
      ))}
      {marqueeBox && <MarqueeBox box={marqueeBox} />}
      {editable && activeTool === 'editor-connector' && (
        <AnchorPointOverlay
          elements={displayDocument.elements.filter((element) => backendElementIds.has(element.id))}
          activeElementId={interaction?.type === 'draw-connector' ? interaction.hoverElementId : null}
          activeAnchor={interaction?.type === 'draw-connector' ? interaction.hoverAnchor : null}
          draft={
            interaction?.type === 'draw-connector'
              ? { from: interaction.startPoint, to: interaction.current }
              : null
          }
        />
      )}
    </svg>
  )
}

function createBackendHistorySnapshot(
  document: EditorDocument,
  selectedIds: readonly ElementId[],
): WorkspaceEditorHistorySnapshot {
  const clonedDocument = selectElements(cloneEditorDocumentSnapshot(document), selectedIds)
  return {
    document: clonedDocument,
    selectedIds: clonedDocument.selectedIds,
  }
}

function cloneEditorDocumentSnapshot(document: EditorDocument): EditorDocument {
  return {
    ...document,
    camera: { ...document.camera },
    selectedIds: [...document.selectedIds],
    elements: document.elements.map(cloneEditorElementSnapshot),
  }
}

function cloneEditorElementSnapshot(element: EditorElement): EditorElement {
  switch (element.type) {
    case 'rectangle':
      return { ...element, style: { ...element.style } }
    case 'ellipse':
      return { ...element, style: { ...element.style } }
    case 'polygon':
      return {
        ...element,
        points: element.points.map((point) => ({ ...point })),
        style: { ...element.style },
      }
    case 'line':
      return {
        ...element,
        start: { ...element.start },
        end: { ...element.end },
        style: { ...element.style },
      }
    case 'freehand':
      return {
        ...element,
        points: element.points.map((point) => ({ ...point })),
        style: { ...element.style },
      }
    case 'text':
      return { ...element, style: { ...element.style } }
    case 'group':
      return { ...element, childIds: [...element.childIds] }
  }
}

function areEditorDocumentsEqual(a: EditorDocument, b: EditorDocument): boolean {
  return JSON.stringify(a) === JSON.stringify(b)
}

function duplicateEditorElements(
  document: EditorDocument,
  selectedIds: readonly ElementId[],
): { document: EditorDocument; selectedIds: ElementId[] } {
  let nextDocument = document
  let nextSequence = Math.max(1, document.nextElementSeq)
  let nextOrder = Math.max(0, ...document.elements.map((element) => element.order)) + 1
  const usedIds = new Set(document.elements.map((element) => element.id))
  const newIds: ElementId[] = []

  for (const element of document.elements.filter((item) => selectedIds.includes(item.id))) {
    while (usedIds.has(createElementId(nextSequence, 'shape'))) nextSequence += 1
    const id = createElementId(nextSequence, 'shape')
    usedIds.add(id)
    nextDocument = insertElement(nextDocument, cloneEditorElement(element, id, nextOrder))
    newIds.push(id)
    nextSequence += 1
    nextOrder += 1
  }

  return {
    document: selectElements({ ...nextDocument, nextElementSeq: nextSequence }, newIds),
    selectedIds: newIds,
  }
}

function reorderEditorElements(
  document: EditorDocument,
  selectedIds: readonly ElementId[],
  direction: 'front' | 'back',
): EditorDocument {
  const selectedSet = new Set(selectedIds)
  const sorted = [...document.elements].sort((a, b) => a.order - b.order)
  const selected = sorted.filter((element) => selectedSet.has(element.id))
  const rest = sorted.filter((element) => !selectedSet.has(element.id))
  const nextOrder = direction === 'front' ? [...rest, ...selected] : [...selected, ...rest]
  return {
    ...document,
    elements: nextOrder.map((element, index) => ({ ...element, order: index + 1 })),
  }
}

function patchEditorElement(element: EditorElement, patch: WorkspaceEditorStylePatch): EditorElement {
  const opacity = patch.opacity == null ? undefined : clamp(patch.opacity, 0, 1)
  const strokeWidth = patch.strokeWidth == null ? undefined : Math.max(0, patch.strokeWidth)
  const fontSize = patch.fontSize == null ? undefined : Math.max(1, patch.fontSize)

  switch (element.type) {
    case 'rectangle':
    case 'ellipse':
    case 'polygon':
      return {
        ...element,
        opacity: opacity ?? element.opacity,
        style: {
          ...element.style,
          fill: patch.fill ?? element.style.fill,
          stroke: patch.stroke ?? element.style.stroke,
          strokeWidth: strokeWidth ?? element.style.strokeWidth,
          opacity: opacity ?? element.style.opacity,
        },
      }
    case 'line':
    case 'freehand':
      return {
        ...element,
        opacity: opacity ?? element.opacity,
        style: {
          ...element.style,
          stroke: patch.stroke ?? element.style.stroke,
          strokeWidth: strokeWidth ?? element.style.strokeWidth,
          opacity: opacity ?? element.style.opacity,
        },
      }
    case 'text':
      return {
        ...element,
        opacity: opacity ?? element.opacity,
        text: patch.text ?? element.text,
        style: {
          ...element.style,
          color: patch.color ?? patch.stroke ?? element.style.color,
          fontSize: fontSize ?? element.style.fontSize,
          opacity: opacity ?? element.style.opacity,
        },
      }
    case 'group':
      return element
  }
}

function createDisplayDocument(
  annotationDocument: EditorDocument,
  backendDocument: EditorDocument,
  selectedIds: readonly ElementId[],
): EditorDocument {
  return selectElements(mergeEditorDocumentsById(annotationDocument, backendDocument), selectedIds)
}

function createInteractionDocuments(
  backendDocument: EditorDocument,
  annotationDocument: EditorDocument,
  selectedIds: readonly ElementId[],
): InteractionDocuments {
  return {
    backendDocument,
    annotationDocument,
    selectedIds,
  }
}

function findElementInBase(base: InteractionDocuments, id: ElementId): EditorElement | undefined {
  return (
    base.backendDocument.elements.find((element) => element.id === id) ??
    base.annotationDocument.elements.find((element) => element.id === id)
  )
}

/**
 * Whether a failed save of the editor document leaves the edit on screen: yes when the failure says nothing about the
 * document (no response, a rate limit, a server error), so the next save sends it again; no for a conflict (409, someone
 * saved first) or a refused document (other 4xx), where the server's version is what to show.
 */
export function keepsUnsavedEdit(httpStatus: number | null): boolean {
  return httpStatus === null || httpStatus === 429 || httpStatus >= 500
}

/**
 * `list.filter(predicate)`, but returns `list` itself (same reference) when nothing was
 * actually removed. `filter` always allocates a new array, so a plain
 * `setState(current => current.filter(...))` never lets React bail out via `Object.is`
 * even when the filtered content is identical — that's what turned this into an
 * unbounded re-render loop when it ran against an unstable-per-render `editableIds` set.
 */
export function filterStable<T>(list: readonly T[], predicate: (item: T) => boolean): readonly T[] {
  const filtered = list.filter(predicate)
  return filtered.length === list.length ? list : filtered
}

function transformLayerDocuments(
  base: InteractionDocuments,
  transform: (element: EditorElement) => EditorElement,
): { backendDocument: EditorDocument | null; annotationDocument: EditorDocument | null; backendIds: ElementId[] } {
  const split = splitSelectedIds(base.backendDocument, base.annotationDocument, base.selectedIds)
  return {
    backendDocument: split.backendIds.length > 0
      ? recomputeBoundLines(transformSelected(base.backendDocument, split.backendIds, transform), split.backendIds)
      : null,
    annotationDocument: split.annotationIds.length > 0
      ? transformSelected(base.annotationDocument, split.annotationIds, transform)
      : null,
    backendIds: split.backendIds,
  }
}

function expandWorkspaceSelection(
  backendDocument: EditorDocument,
  annotationDocument: EditorDocument,
  annotations: readonly CanvasAnnotationViewModel[],
  ids: readonly ElementId[],
): ElementId[] {
  const split = splitSelectedIds(backendDocument, annotationDocument, ids)
  return uniqueIds([
    ...expandGroupSelection(backendDocument, split.backendIds),
    ...expandAnnotationGroupSelection(annotations, split.annotationIds),
  ])
}

function expandAnnotationGroupSelection(
  annotations: readonly CanvasAnnotationViewModel[],
  selectedIds: readonly ElementId[],
): ElementId[] {
  const selected = new Set(selectedIds.map((id) => String(id)))
  const groupedIds = new Set<string>()
  const byId = new Map(annotations.map((annotation) => [annotation.annotationId, annotation]))

  for (const id of selected) {
    const annotation = byId.get(id)
    if (!annotation) continue
    if (!annotation.groupId) {
      groupedIds.add(id)
      continue
    }
    for (const sibling of annotations) {
      if (sibling.groupId === annotation.groupId) groupedIds.add(sibling.annotationId)
    }
  }

  return [...groupedIds].map((id) => toElementId(id))
}

function buildAnnotationZIndexPatch(
  annotations: readonly CanvasAnnotationViewModel[],
  selectedIds: readonly ElementId[],
  direction: 'front' | 'back',
): ReadonlyMap<ElementId, string> {
  const selected = new Set(selectedIds.map((id) => String(id)))
  const selectedAnnotations = annotations.filter((annotation) => selected.has(annotation.annotationId))
  if (selectedAnnotations.length === 0) return new Map()

  const zValues = annotations.map((annotation, index) => readZIndexNumber(annotation.zIndex, index + 1))
  const boundary = direction === 'front' ? Math.max(...zValues) : Math.min(...zValues)
  const sortedSelected = selectedAnnotations.sort(
    (a, b) => readZIndexNumber(a.zIndex, 0) - readZIndexNumber(b.zIndex, 0),
  )
  const patch = new Map<ElementId, string>()

  sortedSelected.forEach((annotation, index) => {
    const offset = direction === 'front' ? index + 1 : index - sortedSelected.length
    patch.set(toElementId(annotation.annotationId), formatZIndex(boundary + offset))
  })

  return patch
}

function readZIndexNumber(value: string, fallback: number): number {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : fallback
}

function formatZIndex(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(6).replace(/\.?0+$/, '')
}

function readElementRotations(document: EditorDocument, selectedIds: readonly ElementId[]): ReadonlyMap<ElementId, number> {
  const selected = new Set(selectedIds)
  return new Map(
    document.elements
      .filter((element) => selected.has(element.id))
      .map((element) => [element.id, element.rotation]),
  )
}

function uniqueIds(ids: readonly ElementId[]): ElementId[] {
  const nextIds: ElementId[] = []
  for (const id of ids) {
    if (!nextIds.includes(id)) nextIds.push(id)
  }
  return nextIds
}

function getSelectableElementIds(document: EditorDocument, editableIds: ReadonlySet<ElementId>): ElementId[] {
  return document.elements
    .filter((element) => editableIds.has(element.id) && !element.hidden && element.type !== 'group')
    .map((element) => element.id)
}

function transformSelected(
  document: EditorDocument,
  selectedIds: readonly ElementId[],
  transform: (element: EditorDocument['elements'][number]) => EditorDocument['elements'][number],
): EditorDocument {
  let nextDocument = document
  for (const id of selectedIds) {
    nextDocument = updateElement(nextDocument, id, transform)
  }
  return nextDocument
}

function getElementIdFromTarget(target: EventTarget | null): ElementId | null {
  if (!(target instanceof Element)) return null
  const element = target.closest('[data-element-id]')
  const id = element?.getAttribute('data-element-id')
  return id ? toElementId(id) : null
}

function getEditorAction(
  event: ReactPointerEvent<SVGSVGElement>,
): { kind: 'none' } | { kind: 'rotate' } | { kind: 'resize'; handle: ResizeHandle } | { kind: 'line-endpoint'; endpoint: LineEndpoint } {
  const target = event.target
  if (!(target instanceof Element)) return { kind: 'none' }
  const action = target.getAttribute('data-editor-action')
  if (action === 'line-endpoint') {
    const endpoint = target.getAttribute('data-editor-endpoint')
    if (isLineEndpoint(endpoint)) return { kind: 'line-endpoint', endpoint }
  }
  if (action === 'rotate') return { kind: 'rotate' }
  if (action === 'resize') {
    const handle = target.getAttribute('data-editor-handle')
    if (isResizeHandle(handle)) return { kind: 'resize', handle }
  }
  return { kind: 'none' }
}

function isResizeHandle(value: string | null): value is ResizeHandle {
  return value === 'nw' || value === 'n' || value === 'ne' || value === 'e' || value === 'se' || value === 's' || value === 'sw' || value === 'w'
}

function isLineEndpoint(value: string | null): value is LineEndpoint {
  return value === 'start' || value === 'end'
}

function getKeyboardAction(event: KeyboardEvent): 'none' | 'delete' | 'duplicate' | 'front' | 'back' | 'group' | 'ungroup' {
  if (event.key === 'Delete' || event.key === 'Backspace') return 'delete'
  if (event.ctrlKey || event.metaKey) {
    const key = event.key.toLowerCase()
    if (key === 'g') return event.shiftKey ? 'ungroup' : 'group'
    if (key === 'd') return 'duplicate'
    if (event.key === ']') return 'front'
    if (event.key === '[') return 'back'
  }
  return 'none'
}

function toggleSelectedUnit(selectedIds: readonly ElementId[], unitIds: readonly ElementId[]): ElementId[] {
  const unitSet = new Set(unitIds)
  const hasFullUnit = unitIds.length > 0 && unitIds.every((id) => selectedIds.includes(id))
  if (hasFullUnit) {
    return selectedIds.filter((selectedId) => !unitSet.has(selectedId))
  }
  const nextIds = [...selectedIds]
  for (const id of unitIds) {
    if (!nextIds.includes(id)) nextIds.push(id)
  }
  return nextIds
}

function isTextEditingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  return Boolean(target.closest('input, textarea, select, [contenteditable="true"]'))
}

function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, value))
}

function MarqueeBox({ box }: { box: Box2 }) {
  return (
    <rect
      className="flowmat-editor-marquee"
      x={box.x}
      y={box.y}
      width={box.width}
      height={box.height}
      vectorEffect="non-scaling-stroke"
    />
  )
}
