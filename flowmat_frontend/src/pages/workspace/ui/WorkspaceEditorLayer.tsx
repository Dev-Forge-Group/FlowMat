import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type MouseEvent as ReactMouseEvent,
  type PointerEvent as ReactPointerEvent,
} from 'react'
import { useReactFlow } from '@xyflow/react'
import {
  angleBetween,
  boxCenter,
  createEmptyEditorDocument,
  cleanupEmptyEditorGroups,
  deleteElements as deleteEditorElements,
  findElementsInBox,
  findNearestAnchor,
  getSelectedElementBounds,
  groupEditorElements,
  mergeEditorDocumentsById,
  normalizeBox,
  selectElements,
  snapPoint,
  toElementId,
  toSvgRenderNodes,
  ungroupEditorElements,
  type Box2,
  type EditorDocument,
  type ElementBinding,
  type ElementId,
  type Vec2,
} from '../../../lib/flowmat-editor'
import { annotationsToEditorDocument } from '../../../entities/canvas-annotation/model/annotationEditorAdapter'
import {
  AnchorPointOverlay,
  ArrowMarkerDefs,
  LineEndpointHandles,
  SelectionHandles,
  SelectionOutline,
  SvgElementView,
} from '../../../lib/flowmat-editor/react'
import { useEditorDocumentQuery } from '../../../entities/editor-document/api/editorDocumentApi'
import type { CanvasAnnotationViewModel } from '../../../entities/workflow/model/types'
import type { WorkflowPaletteTool } from '../../../entities/workflow/model/nodeCatalog'
import type { PatchCanvasAnnotationInput } from '../../../entities/canvas-annotation/api/canvasAnnotationApi'
import type {
  AlignDirection,
  DistributeAxis,
} from '../../../entities/canvas-annotation/model/annotationLayout'
import {
  getWorkspaceEditorSelectionCapabilities,
  splitWorkspaceEditorSelection as splitSelectedIds,
} from '../model/workspaceEditorSelection'
import { alignWorkspaceEditorElements, distributeWorkspaceEditorElements } from '../model/workspaceEditorLayout'
import {
  areEditorDocumentsEqual,
  buildAnnotationZIndexPatch,
  createBackendHistorySnapshot,
  createDisplayDocument,
  createInteractionDocuments,
  duplicateEditorElements,
  expandWorkspaceSelection,
  filterStable,
  getSelectableElementIds,
  addConnector,
  patchEditorElement,
  readElementRotations,
  reorderEditorElements,
  toggleSelectedUnit,
  transformSelected,
  draggedDocuments,
  recordHistory,
  redoHistory,
  undoHistory,
  type EditorInteraction,
  type WorkspaceEditorCommandApi,
  type WorkspaceEditorHistoryState,
  type WorkspaceEditorSelectionSnapshot,
  type WorkspaceEditorStylePatch,
} from '../model/workspaceEditorDocument'
import { getEditorAction, getElementIdFromTarget, getKeyboardAction, isTextEditingTarget } from './workspaceEditorEvents'
import { useEditorLayerPersistence } from '../model/useEditorLayerPersistence'

export type {
  WorkspaceEditorCommandApi,
  WorkspaceEditorSelectionSnapshot,
  WorkspaceEditorStylePatch,
} from '../model/workspaceEditorDocument'

const WORKSPACE_LAYER_EXTENT = 100000
const WORKSPACE_GRID_SIZE = 8
const WORKSPACE_CONNECTOR_ANCHOR_THRESHOLD = 14

// Stable placeholder used while the editor-document query is pending/undefined.
// A fresh createEmptyEditorDocument() per render would give backendElementIds/editableIds
// a new reference every render, keeping the selection-cleanup effect below permanently
// re-armed and driving an update-depth loop into React Flow's StoreUpdater.
const EMPTY_BACKEND_DOCUMENT: EditorDocument = createEmptyEditorDocument()

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
  const { data: backendDocument } = useEditorDocumentQuery(workflowId)
  const [selectedIds, setSelectedIds] = useState<readonly ElementId[]>([])
  const [interaction, setInteraction] = useState<EditorInteraction | null>(null)
  const [draftBackendDocument, setDraftBackendDocument] = useState<EditorDocument | null>(null)
  const [draftAnnotationDocument, setDraftAnnotationDocument] = useState<EditorDocument | null>(null)
  /** Lets go of an interaction's drafts, so the documents as saved (or being saved) are drawn. */
  const releaseDrafts = useCallback(() => {
    setDraftBackendDocument(null)
    setDraftAnnotationDocument(null)
  }, [])
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

  const { persistDocument, persistLayerChanges } = useEditorLayerPersistence({ workflowId, annotationByElementId, onError,
    onUpdateAnnotation, onDeleteAnnotations })

  const pushBackendHistorySnapshot = useCallback((document: EditorDocument, ids: readonly ElementId[] = selectedIds) => {
    const snapshot = createBackendHistorySnapshot(document, ids)
    setBackendHistory((current) => recordHistory(current, snapshot))
  }, [selectedIds])

  /** Undo or redo of the editor document: the snapshot is shown and saved, with its selection, drafts let go. */
  const stepBackendHistory = useCallback((step: typeof undoHistory) => {
    if (!editable) return
    const stepped = step(backendHistory, createBackendHistorySnapshot(editableDocument, selectedIds))
    if (!stepped) return
    const { target } = stepped
    setBackendHistory(stepped.history)
    setSelectedIds(target.selectedIds)
    releaseDrafts()
    void persistDocument(selectElements(target.document, target.selectedIds))
  }, [backendHistory, editable, editableDocument, persistDocument, selectedIds, releaseDrafts])
  const undoBackendDocument = useCallback(() => stepBackendHistory(undoHistory), [stepBackendHistory])
  const redoBackendDocument = useCallback(() => stepBackendHistory(redoHistory), [stepBackendHistory])

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
    releaseDrafts()

    await persistLayerChanges({
      backendDocument: nextBackendDocument,
      deleteAnnotationIds: split.annotationIds,
    })
  }, [editable, editableAnnotationDocument, editableDocument, persistLayerChanges, pushBackendHistorySnapshot, selectedIds, releaseDrafts])

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
    releaseDrafts()
    await persistDocument(result.document)
  }, [editable, editableAnnotationDocument, editableDocument, onError, persistDocument, pushBackendHistorySnapshot, selectedIds, releaseDrafts])

  const createConnectorElement = useCallback(async (start: ElementBinding, end: ElementBinding) => {
    const added = editable ? addConnector(editableDocument, start, end) : null
    if (!added) return
    pushBackendHistorySnapshot(editableDocument, selectedIds)
    setSelectedIds([added.id])
    releaseDrafts()
    await persistDocument(added.document)
  }, [editable, editableDocument, persistDocument, pushBackendHistorySnapshot, releaseDrafts, selectedIds])

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
      releaseDrafts()
      await persistDocument(result.document)
      return
    }
    setDraftAnnotationDocument(null)
  }, [editableAnnotationDocument, editableDocument, persistDocument, persistLayerChanges, pushBackendHistorySnapshot, selectedIds, selectionCapabilities.canGroup, releaseDrafts])

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
      releaseDrafts()
      await persistDocument(result.document)
      return
    }
    setDraftAnnotationDocument(null)
  }, [editable, editableAnnotationDocument, editableDocument, persistDocument, persistLayerChanges, pushBackendHistorySnapshot, selectedIds, selectionCapabilities.canUngroup, releaseDrafts])

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

    releaseDrafts()
    await persistLayerChanges({
      backendDocument: nextBackendDocument,
      annotationDocument: split.annotationIds.length > 0 ? editableAnnotationDocument : null,
      annotationIds: split.annotationIds,
      annotationZIndexById,
    })
  }, [annotations, editable, editableAnnotationDocument, editableDocument, persistLayerChanges, pushBackendHistorySnapshot, selectedIds, releaseDrafts])

  const alignSelected = useCallback((direction: AlignDirection) => {
    if (!selectionCapabilities.canAlign || selectionCapabilities.selectionKind !== 'backend') return
    const nextBackendDocument = alignWorkspaceEditorElements(editableDocument, selectedIds, direction)
    if (nextBackendDocument === editableDocument) return
    pushBackendHistorySnapshot(editableDocument, selectedIds)
    releaseDrafts()
    void persistLayerChanges({ backendDocument: nextBackendDocument })
  }, [editableDocument, persistLayerChanges, pushBackendHistorySnapshot, selectedIds, selectionCapabilities.canAlign, selectionCapabilities.selectionKind, releaseDrafts])

  const distributeSelected = useCallback((axis: DistributeAxis) => {
    if (!selectionCapabilities.canDistribute || selectionCapabilities.selectionKind !== 'backend') return
    const nextBackendDocument = distributeWorkspaceEditorElements(editableDocument, selectedIds, axis)
    if (nextBackendDocument === editableDocument) return
    pushBackendHistorySnapshot(editableDocument, selectedIds)
    releaseDrafts()
    void persistLayerChanges({ backendDocument: nextBackendDocument })
  }, [editableDocument, persistLayerChanges, pushBackendHistorySnapshot, selectedIds, selectionCapabilities.canDistribute, selectionCapabilities.selectionKind, releaseDrafts])

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

    releaseDrafts()
    await persistLayerChanges({
      backendDocument: nextBackendDocument,
      annotationDocument: nextAnnotationDocument,
      annotationIds: split.annotationIds,
    })
  }, [editable, editableAnnotationDocument, editableDocument, persistLayerChanges, pushBackendHistorySnapshot, selectedIds, releaseDrafts])

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
        releaseDrafts()
      },
      selectAll: () => {
        if (!editable) return
        setSelectedIds(getSelectableElementIds(displayDocument, editableIds))
        releaseDrafts()
      },
      deleteSelected: () => void deleteSelectedElements(),
      duplicateSelected: () => void duplicateSelectedElements(),
      groupSelected: () => void groupSelectedElements(),
      ungroupSelected: () => void ungroupSelectedElements(),
      bringSelectedToFront: () => void reorderSelectedElementsInLayer('front'),
      sendSelectedToBack: () => void reorderSelectedElementsInLayer('back'),
      alignSelected,
      distributeSelected,
      updateSelectedStyle: (patch) => void updateSelectedStyle(patch),
      undo: undoBackendDocument,
      redo: redoBackendDocument,
      canUndo: () => canUndoBackendDocument,
      canRedo: () => canRedoBackendDocument,
      recordBackendSnapshot: (document, ids) => pushBackendHistorySnapshot(document, ids),
      resetHistory: resetBackendHistory,
    })
    return () => onCommandReady(null)
  }, [alignSelected, canRedoBackendDocument, canUndoBackendDocument, deleteSelectedElements, distributeSelected,
    duplicateSelectedElements, displayDocument, editable, editableIds, groupSelectedElements, onCommandReady,
    pushBackendHistorySnapshot, releaseDrafts, reorderSelectedElementsInLayer, redoBackendDocument, resetBackendHistory,
    ungroupSelectedElements, undoBackendDocument, updateSelectedStyle])

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
      releaseDrafts()
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

    const result = draggedDocuments(interaction, point, snappedPoint)
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
    releaseDrafts()
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
