import { Suspense, lazy, useCallback, useEffect, useMemo, useRef, useState, type MutableRefObject } from 'react'
import { useIsMutating, useQueryClient } from '@tanstack/react-query'
import type {
  CanvasAnnotationPoint,
  ConnectCompletePayload,
  ConnectStartPayload,
  WorkflowCanvasViewModel,
} from '../../../entities/workflow/model/types'
import { fetchWorkflowGraphChanges } from '../../../entities/workflow/api/workflowGraphChanges'
import { fetchWorkflowPresenceSnapshot } from '../../../entities/workflow/api/workflowPresenceSnapshot'
import { applyGraphChangesToCanvas } from '../../../entities/workflow/model/applyGraphChangesToCanvas'
import { useWorkspaceStore } from '../model/workspaceStore'
import { useWorkflowCanvasActions } from '../model/useWorkflowCanvasActions'
import { validationSelection } from '../model/validationPanelModel'
import { ValidationIssueList } from './ValidationIssueList'
import { useCanvasInteractionStore } from '../model/canvasInteractionStore'
import { createWorkspaceSelectionCommands } from '../model/workspaceSelectionCommands'
import { getRelatedConnectionIds } from '../../../entities/workflow/model/connectionPolicy'
import { useAutoLayout } from '../model/useAutoLayout'
import { CANVAS_ACTIONS } from '../model/canvasActions'
import { useWorkflowsQuery } from '../../../entities/workflow/api/useWorkflowsQuery'
import { useUpdateWorkflowMutation } from '../../../entities/workflow/api/useUpdateWorkflowMutation'
import { useWorkflowValidationQuery, workflowValidationQueryKey } from '../../../entities/workflow/api/useWorkflowValidationQuery'
import type { PresenceMessage, GraphChangeMessage } from '../../../entities/workflow/api/useWorkflowSync'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { NodePaletteSidebar } from './NodePaletteSidebar'
import { EditorPublish } from './EditorPublish'
import { SemanticPassport, type PassportPosition } from './SemanticPassport'
import { RouteProbe } from './RouteProbe'
import { DiagramNav } from './DiagramNav'
import { ReadingReceipts } from './ReadingReceipts'
import './archifyReading.css'
import { SemanticLens } from './SemanticLens'
import { ProcessFinder, type FinderContext } from './ProcessFinder'
import { DiagramGuide } from './DiagramGuide'
import { RunPicker } from './RunPicker'
import { ChangesPicker } from './ChangesPicker'
import { LayoutWarningList } from './LayoutWarningList'
import { layoutWarnings, type LayoutSnapshot, type LayoutWarning } from '../model/layoutChecks'
import { useRevisionChanges } from '../model/useRevisionChanges'
import { useCanvasReading } from '../model/useCanvasReading'
import { useRunOverlay } from '../model/useRunOverlay'
import { useMotion } from '../model/motion'
import type {
  WorkspaceEditorCommandApi,
  WorkspaceEditorSelectionSnapshot,
} from './WorkspaceEditorLayer'
import {
  useBatchCanvasAnnotationMutation,
  useCreateCanvasAnnotationMutation,
  useDeleteCanvasAnnotationMutation,
  usePatchCanvasAnnotationMutation,
  type PatchCanvasAnnotationInput,
} from '../../../entities/canvas-annotation/api/canvasAnnotationApi'
import {
  editorDocumentQueryKey,
  fetchEditorDocument,
  saveEditorDocument,
} from '../../../entities/editor-document/api/editorDocumentApi'
import {
  preloadCanvasViewport,
  preloadConnectionInspector,
  preloadNodeInspector,
  preloadNodePickerPopup,
} from './workspacePreload'
import {
  isWorkflowEditorTool,
  type WorkflowEditorTool,
  type WorkflowPaletteTool,
} from '../../../entities/workflow/model/nodeCatalog'
import { Circle, Minus, Pencil, Shapes, Square, Triangle, Type as TypeIcon, Waypoints } from 'lucide-react'
import { Ribbon } from '../../../widgets/canvas-toolbar/ui/Ribbon'
import {
  buildRibbonTabs,
  type RibbonButtonHandlers,
  type RibbonDynamicButtons,
  type RibbonGroupContent,
} from '../../../widgets/canvas-toolbar/config/ribbonConfig'
import {
  createElementId,
  createEllipseElement,
  createLineElement,
  createRectangleElement,
  createTextElement,
  createTriangleElement,
  insertElement,
  snapPoint,
  type EditorDocument,
  type EditorElement,
  type Vec2,
} from '../../../lib/flowmat-editor'

const CanvasViewport = lazy(() =>
  preloadCanvasViewport().then((module) => ({ default: module.CanvasViewport }))
)
const ConnectionInspector = lazy(() =>
  preloadConnectionInspector().then((module) => ({ default: module.ConnectionInspector }))
)
const NodeInspector = lazy(() =>
  preloadNodeInspector().then((module) => ({ default: module.NodeInspector }))
)
const NodePickerPopup = lazy(() =>
  preloadNodePickerPopup().then((module) => ({ default: module.NodePickerPopup }))
)

interface Props {
  canvas: WorkflowCanvasViewModel
  projectId: string
}

const ANNOTATION_TOOL_DEFINITIONS: Array<{
  tool: Extract<WorkflowPaletteTool, 'annotation-shape' | 'annotation-text' | 'annotation-freehand'>
  label: string
  description: string
  icon: typeof Square
}> = [
  { tool: 'annotation-shape', label: 'Shape', description: 'Place shape annotations on the canvas.', icon: Shapes },
  { tool: 'annotation-text', label: 'Text', description: 'Drop standalone notes on the canvas.', icon: TypeIcon },
  {
    tool: 'annotation-freehand',
    label: 'Freehand',
    description: 'Sketch directly with live collaborator previews.',
    icon: Pencil,
  },
]

const EDITOR_TOOL_DEFINITIONS: Array<{
  tool: WorkflowEditorTool
  label: string
  description: string
  icon: typeof Square
}> = [
  { tool: 'editor-rectangle', label: 'Rectangle', description: 'Create backend editor rectangles.', icon: Square },
  { tool: 'editor-ellipse', label: 'Ellipse', description: 'Create backend editor circles and ovals.', icon: Circle },
  { tool: 'editor-triangle', label: 'Triangle', description: 'Create backend editor polygon triangles.', icon: Triangle },
  { tool: 'editor-line', label: 'Line', description: 'Create backend editor lines.', icon: Minus },
  {
    tool: 'editor-connector',
    label: 'Connector',
    description: 'Drag between two shapes to draw a line that follows them.',
    icon: Waypoints,
  },
  { tool: 'editor-text', label: 'Text', description: 'Create backend editor text.', icon: TypeIcon },
]

const EMPTY_EDITOR_SELECTION: WorkspaceEditorSelectionSnapshot = Object.freeze({
  selectedIds: [],
  elements: [],
  selectionKind: 'none',
  canAlign: false,
  canDistribute: false,
  canGroup: false,
  canUngroup: false,
  canUndo: false,
  canRedo: false,
})
const WORKSPACE_EDITOR_GRID_SIZE = 8

function getToolLabel(
  tool: WorkflowPaletteTool,
  paletteDefinitions: ReturnType<typeof useWorkflowCanvasActions>['paletteDefinitions']
) {
  if (tool === 'select') return 'Pointer'
  const editorDefinition = EDITOR_TOOL_DEFINITIONS.find((definition) => definition.tool === tool)
  if (editorDefinition) return editorDefinition.label
  const annotationDefinition = ANNOTATION_TOOL_DEFINITIONS.find((definition) => definition.tool === tool)
  if (annotationDefinition) return annotationDefinition.label
  return paletteDefinitions.find((definition) => definition.tool === tool)?.label ?? tool
}

function getWorkspaceToolShortcut(event: KeyboardEvent): WorkflowPaletteTool | null {
  if (event.ctrlKey || event.metaKey || event.altKey) return null
  switch (event.key.toLowerCase()) {
    case 'v':
      return 'select'
    case 'r':
      return 'editor-rectangle'
    case 'o':
      return 'editor-ellipse'
    case 'l':
      return 'editor-line'
    case 'c':
      return 'editor-connector'
    case 't':
      return 'editor-text'
    default:
      return null
  }
}

function getUndoRedoShortcut(event: KeyboardEvent): 'undo' | 'redo' | null {
  if (!(event.ctrlKey || event.metaKey) || event.altKey) return null
  const key = event.key.toLowerCase()
  if (key === 'z') return event.shiftKey ? 'redo' : 'undo'
  if (key === 'y') return 'redo'
  return null
}

function normalizeFreehand(points: CanvasAnnotationPoint[]) {
  const xs = points.map((point) => point.x)
  const ys = points.map((point) => point.y)
  const minX = Math.min(...xs)
  const minY = Math.min(...ys)
  const maxX = Math.max(...xs)
  const maxY = Math.max(...ys)

  return {
    posX: Math.round(minX),
    posY: Math.round(minY),
    width: Math.max(8, Math.round(maxX - minX)),
    height: Math.max(8, Math.round(maxY - minY)),
    points: points.map((point) => [Math.round(point.x - minX), Math.round(point.y - minY)] as [number, number]),
  }
}

const WORKSPACE_EDITOR_SHAPE_STYLE = {
  fill: '#dff7ef',
  stroke: '#0f766e',
  strokeWidth: 1.5,
  strokeStyle: 'solid' as const,
  opacity: 1,
}

function createWorkspaceEditorElement(
  tool: WorkflowEditorTool,
  position: Vec2,
  document: EditorDocument,
): { element: EditorElement; nextElementSeq: number } {
  const idPrefix = tool === 'editor-text' ? 'text' : 'shape'
  const nextSequence = nextAvailableElementSequence(document, idPrefix)
  const id = createElementId(nextSequence, idPrefix)
  const order = document.elements.length + 1

  switch (tool) {
    case 'editor-rectangle':
      return {
        element: createRectangleElement({
          id,
          x: Math.round(position.x - 80),
          y: Math.round(position.y - 48),
          width: 160,
          height: 96,
          order,
          cornerRadius: 8,
          style: WORKSPACE_EDITOR_SHAPE_STYLE,
        }),
        nextElementSeq: nextSequence + 1,
      }
    case 'editor-ellipse':
      return {
        element: createEllipseElement({
          id,
          x: Math.round(position.x - 80),
          y: Math.round(position.y - 48),
          width: 160,
          height: 96,
          order,
          style: {
            ...WORKSPACE_EDITOR_SHAPE_STYLE,
            fill: '#eef2ff',
            stroke: '#4338ca',
          },
        }),
        nextElementSeq: nextSequence + 1,
      }
    case 'editor-triangle':
      return {
        element: createTriangleElement({
          id,
          x: Math.round(position.x - 75),
          y: Math.round(position.y - 60),
          width: 150,
          height: 120,
          order,
          style: {
            ...WORKSPACE_EDITOR_SHAPE_STYLE,
            fill: '#fff7ed',
            stroke: '#c2410c',
          },
        }),
        nextElementSeq: nextSequence + 1,
      }
    case 'editor-line':
      return {
        element: createLineElement({
          id,
          start: { x: Math.round(position.x - 80), y: Math.round(position.y) },
          end: { x: Math.round(position.x + 80), y: Math.round(position.y) },
          order,
          style: {
            stroke: '#7c3aed',
            strokeWidth: 3,
            strokeStyle: 'solid',
            opacity: 1,
            startArrow: 'none',
            endArrow: 'none',
          },
        }),
        nextElementSeq: nextSequence + 1,
      }
    case 'editor-connector':
      // Connectors are created by dragging between two shapes' anchor points inside
      // WorkspaceEditorLayer's own pointer handlers, not by a single pane click.
      throw new Error('Connector elements are created via drag-to-connect, not click placement.')
    case 'editor-text':
      return {
        element: createTextElement({
          id,
          x: Math.round(position.x - 60),
          y: Math.round(position.y - 18),
          width: 140,
          height: 36,
          order,
          text: 'Text',
          style: {
            color: '#111827',
            fontFamily: 'Inter, system-ui, sans-serif',
            fontSize: 16,
            fontWeight: 600,
            opacity: 1,
          },
        }),
        nextElementSeq: nextSequence + 1,
      }
  }
}

function nextAvailableElementSequence(document: EditorDocument, prefix: string) {
  const ids = new Set(document.elements.map((element) => element.id))
  let sequence = Math.max(1, document.nextElementSeq)
  while (ids.has(createElementId(sequence, prefix))) {
    sequence += 1
  }
  return sequence
}

function CanvasFallback() {
  return <div className="workspace-loading">Loading canvas...</div>
}

function InspectorFallback() {
  return (
    <div className="inspector-summary">
      <p>Loading inspector...</p>
    </div>
  )
}

function EditorElementInspector({
  selection,
  commandsRef,
}: {
  selection: WorkspaceEditorSelectionSnapshot
  commandsRef: MutableRefObject<WorkspaceEditorCommandApi | null>
}) {
  const selectedCount = selection.elements.length
  const firstElement = selection.elements[0] ?? null
  const summaryLabel = selectedCount === 1 && firstElement
    ? editorElementLabel(firstElement)
    : `${selectedCount} editor elements`
  const style = readEditorSelectionStyle(selection.elements)
  const supportsFill = selection.elements.some(isShapeEditorElement)
  const supportsStroke = selection.elements.some((element) => (
    isShapeEditorElement(element) || element.type === 'line' || element.type === 'freehand' || element.type === 'text'
  ))
  const supportsText = firstElement?.type === 'text' && selectedCount === 1
  const grouped = hasGroupedEditorSelection(selection.elements)

  return (
    <div className="inspector editor-inspector">
      <h2 className="inspector__title">{summaryLabel}</h2>
      <div className="editor-inspector__commands">
        <button type="button" onClick={() => commandsRef.current?.undo()} disabled={!commandsRef.current || !selection.canUndo}>
          Undo
        </button>
        <button type="button" onClick={() => commandsRef.current?.redo()} disabled={!commandsRef.current || !selection.canRedo}>
          Redo
        </button>
        <button type="button" onClick={() => commandsRef.current?.duplicateSelected()} disabled={!commandsRef.current}>
          Duplicate
        </button>
        <button type="button" onClick={() => commandsRef.current?.deleteSelected()} disabled={!commandsRef.current}>
          Delete
        </button>
        <button type="button" onClick={() => commandsRef.current?.groupSelected()} disabled={!commandsRef.current || selectedCount < 2}>
          Group
        </button>
        <button type="button" onClick={() => commandsRef.current?.ungroupSelected()} disabled={!commandsRef.current || !grouped}>
          Ungroup
        </button>
        <button type="button" onClick={() => commandsRef.current?.sendSelectedToBack()} disabled={!commandsRef.current}>
          Back
        </button>
        <button type="button" onClick={() => commandsRef.current?.bringSelectedToFront()} disabled={!commandsRef.current}>
          Front
        </button>
      </div>
      <div className="inspector__section">
        <label className="editor-inspector__field">
          <span>Fill</span>
          <input
            type="color"
            disabled={!supportsFill}
            value={toHexColor(style.fill, '#ffffff')}
            onChange={(event) => commandsRef.current?.updateSelectedStyle({ fill: event.target.value })}
          />
        </label>
        <label className="editor-inspector__field">
          <span>Stroke</span>
          <input
            type="color"
            disabled={!supportsStroke}
            value={toHexColor(style.stroke, '#111827')}
            onChange={(event) => commandsRef.current?.updateSelectedStyle({ stroke: event.target.value, color: event.target.value })}
          />
        </label>
        <label className="editor-inspector__field">
          <span>Width</span>
          <input
            type="number"
            min={0}
            max={24}
            step={0.5}
            disabled={!supportsStroke}
            value={style.strokeWidth}
            onChange={(event) => commandsRef.current?.updateSelectedStyle({ strokeWidth: Number(event.target.value) })}
          />
        </label>
        <label className="editor-inspector__field">
          <span>Opacity</span>
          <input
            type="range"
            min={0.05}
            max={1}
            step={0.05}
            value={style.opacity}
            onChange={(event) => commandsRef.current?.updateSelectedStyle({ opacity: Number(event.target.value) })}
          />
        </label>
        {supportsText && (
          <>
            <label className="editor-inspector__field">
              <span>Text</span>
              <input
                type="text"
                value={firstElement.text}
                onChange={(event) => commandsRef.current?.updateSelectedStyle({ text: event.target.value })}
              />
            </label>
            <label className="editor-inspector__field">
              <span>Size</span>
              <input
                type="number"
                min={8}
                max={96}
                step={1}
                value={firstElement.style.fontSize}
                onChange={(event) => commandsRef.current?.updateSelectedStyle({ fontSize: Number(event.target.value) })}
              />
            </label>
          </>
        )}
      </div>
    </div>
  )
}

function readEditorSelectionStyle(elements: readonly EditorElement[]) {
  const first = elements[0]
  if (!first) {
    return { fill: '#ffffff', stroke: '#111827', strokeWidth: 1, opacity: 1 }
  }
  if (isShapeEditorElement(first)) {
    return {
      fill: first.style.fill,
      stroke: first.style.stroke,
      strokeWidth: first.style.strokeWidth,
      opacity: first.opacity,
    }
  }
  if (first.type === 'line' || first.type === 'freehand') {
    return {
      fill: '#ffffff',
      stroke: first.style.stroke,
      strokeWidth: first.style.strokeWidth,
      opacity: first.opacity,
    }
  }
  if (first.type === 'text') {
    return {
      fill: '#ffffff',
      stroke: first.style.color,
      strokeWidth: 1,
      opacity: first.opacity,
    }
  }
  return { fill: '#ffffff', stroke: '#111827', strokeWidth: 1, opacity: first.opacity }
}

function isShapeEditorElement(element: EditorElement) {
  return element.type === 'rectangle' || element.type === 'ellipse' || element.type === 'polygon'
}

function hasGroupedEditorSelection(elements: readonly EditorElement[]) {
  return elements.some((element) => element.parentId != null)
}

function editorElementLabel(element: EditorElement) {
  switch (element.type) {
    case 'rectangle':
      return 'Rectangle'
    case 'ellipse':
      return 'Ellipse'
    case 'polygon':
      return 'Polygon'
    case 'line':
      return 'Line'
    case 'freehand':
      return 'Freehand'
    case 'text':
      return 'Text'
    case 'group':
      return 'Group'
  }
}

function toHexColor(value: string | undefined, fallback: string) {
  return value && /^#[0-9a-f]{6}$/i.test(value) ? value : fallback
}

export function WorkflowCanvasPage({ canvas, projectId: _projectId }: Props) {
  const selectedProcessId = useWorkspaceStore((s) => s.selectedProcessId)
  const selectedConnectionId = useWorkspaceStore((s) => s.selectedConnectionId)
  const selectedPortId = useWorkspaceStore((s) => s.selectedPortId)
  const inspectorMode = useWorkspaceStore((s) => s.inspectorMode)
  const selectNode = useWorkspaceStore((s) => s.selectNode)
  const selectEdge = useWorkspaceStore((s) => s.selectEdge)
  const clearSelection = useWorkspaceStore((s) => s.clearSelection)
  const pendingRename = useWorkspaceStore((s) => s.pendingRename)
  const clearPendingRename = useWorkspaceStore((s) => s.clearPendingRename)
  const inlineEditingNodeId = useWorkspaceStore((s) => s.inlineEditingNodeId)
  const inlineEditingEdgeId = useWorkspaceStore((s) => s.inlineEditingEdgeId)
  const stopInlineEditEdge = useWorkspaceStore((s) => s.stopInlineEditEdge)
  const panelWidths = useWorkspaceStore((s) => s.panelWidths)

  const panelWidthRef = useRef(panelWidths)
  panelWidthRef.current = panelWidths
  const [localPanelWidths, setLocalPanelWidths] = useState(panelWidths)

  const [activeRibbonTabId, setActiveRibbonTabId] = useState('home')
  const [showValidation, setShowValidation] = useState(false)
  const validationQuery = useWorkflowValidationQuery(canvas.workflow.workflowId, showValidation)
  const [editorSelection, setEditorSelection] = useState<WorkspaceEditorSelectionSnapshot>(EMPTY_EDITOR_SELECTION)
  const editorCommandApiRef = useRef<WorkspaceEditorCommandApi | null>(null)

  const makeResizeHandler = useCallback(
    (side: 'left' | 'right') =>
      (e: React.MouseEvent) => {
        e.preventDefault()
        const startX = e.clientX
        const startWidth = side === 'left' ? localPanelWidths.left : localPanelWidths.right
        function onMove(ev: MouseEvent) {
          const delta = side === 'left' ? ev.clientX - startX : startX - ev.clientX
          setLocalPanelWidths((w) => ({ ...w, [side]: Math.max(160, Math.min(400, startWidth + delta)) }))
        }
        function onUp() {
          window.removeEventListener('mousemove', onMove)
          window.removeEventListener('mouseup', onUp)
        }
        window.addEventListener('mousemove', onMove)
        window.addEventListener('mouseup', onUp)
      },
    [localPanelWidths.left, localPanelWidths.right]
  )

  const {
    activeTool,
    setActiveTool,
    setWorkspaceMessage,
    workspaceMessage,
    paletteDefinitions,
    commandHistory,
    addNode,
    createNodeAt,
    createNodeFromTool,
    createNodeFromConnectionDrop,
    insertNodeOnEdge,
    updateNode,
    createPort,
    updatePort,
    deletePort,
    saveNodePosition,
    duplicateNode,
    batchUpdateNodePositions,
    updateConnection,
    deleteConnection,
    deleteElements,
    deleteNode,
    createConnection,
  } = useWorkflowCanvasActions({ canvas, clearSelection })

  const navigate = useNavigate()
  const updateWorkflowMutation = useUpdateWorkflowMutation()
  const workflowsQuery = useWorkflowsQuery(canvas.workflow.projectId)
  const createAnnotationMutation = useCreateCanvasAnnotationMutation(canvas.workflow.workflowId)
  const patchAnnotationMutation = usePatchCanvasAnnotationMutation(canvas.workflow.workflowId)
  const deleteAnnotationMutation = useDeleteCanvasAnnotationMutation(canvas.workflow.workflowId)
  const batchAnnotationMutation = useBatchCanvasAnnotationMutation(canvas.workflow.workflowId)
  const canEditAnnotations =
    canvas.workflow.currentUserRole === 'editor' || canvas.workflow.currentUserRole === 'owner'

  // Presence: remote cursor + node-editing state
  const [remoteCursors, setRemoteCursors] = useState<
    Map<string, { x: number; y: number; type: string }>
  >(new Map())
  const [editingPresence, setEditingPresence] = useState<ReadonlyMap<string, string>>(new Map())
  const [remoteAnnotationPreviews, setRemoteAnnotationPreviews] = useState<
    ReadonlyMap<string, { points: CanvasAnnotationPoint[]; annotationType: string }>
  >(new Map())
  const [syncUserId, setSyncUserId] = useState<string | null>(null)
  const sendPresenceRef = useRef<
    (msg: Omit<PresenceMessage, 'userId' | 'clientId' | 'workflowId' | 'timestamp'>) => void
  >(() => {})

  const handlePresence = useCallback((msg: PresenceMessage) => {
    if (!msg.userId) return
    const uid = msg.userId

    setRemoteCursors((prev) => {
      const next = new Map(prev)
      if (msg.type === 'LEAVE') {
        next.delete(uid)
      } else if (msg.type === 'CURSOR_MOVED' && msg.cursorX != null && msg.cursorY != null) {
        next.set(uid, { x: msg.cursorX, y: msg.cursorY, type: msg.type })
      } else if (msg.type === 'JOIN' || msg.type === 'NODE_EDITING') {
        next.set(uid, { x: 0, y: 0, type: msg.type })
      }
      return next
    })

    if (msg.type === 'ANNOTATION_DRAWING' || msg.type === 'LEAVE') {
      setRemoteAnnotationPreviews((prev) => {
        const next = new Map(prev)
        if (msg.type === 'LEAVE' || !msg.annotation?.inProgress) {
          next.delete(uid)
          return next
        }
        next.set(uid, {
          annotationType: msg.annotation.annotationType,
          points: msg.annotation.points.map(([x, y]) => ({ x, y })),
        })
        return next
      })
    }

    if (msg.type === 'NODE_EDITING' || msg.type === 'LEAVE') {
      setEditingPresence((prev) => {
        const next = new Map(prev)
        for (const [nodeId, editUid] of next) {
          if (editUid === uid) next.delete(nodeId)
        }
        if (msg.type === 'NODE_EDITING' && msg.editingProcessId) {
          next.set(msg.editingProcessId, uid)
        }
        return next
      })
    }
  }, [])

  const queryClient = useQueryClient()
  useEffect(() => {
    void queryClient.invalidateQueries({ queryKey: workflowValidationQueryKey(canvas.workflow.workflowId) })
  }, [canvas.nodes, canvas.edges, canvas.workflow.workflowId, queryClient])
  const graphSeqRef = useRef(canvas.graphSeq)
  const deferredGraphResyncFromRef = useRef<number | null>(null)

  useEffect(() => {
    graphSeqRef.current = canvas.graphSeq
    deferredGraphResyncFromRef.current = null
  }, [canvas.workflow.workflowId, canvas.graphSeq])

  const invalidateEditorDocument = useCallback(() => {
    void queryClient.invalidateQueries({
      queryKey: editorDocumentQueryKey(canvas.workflow.workflowId),
    })
  }, [canvas.workflow.workflowId, queryClient])

  const applyGraphChanges = useCallback((changes: GraphChangeMessage[]) => {
    if (changes.length === 0) return

    const maxSeq = changes.reduce((max, change) => Math.max(max, change.seq), graphSeqRef.current)
    let deferredFromSeq: number | null = null

    queryClient.setQueryData<WorkflowCanvasViewModel>(
      ['workflow-canvas', canvas.workflow.workflowId],
      (current) => {
        if (!current) return current

        const applicableChanges = changes.filter((change) => {
          const state = useWorkspaceStore.getState()
          const shouldSkip =
            (change.changeType === 'NODE_UPDATED' && change.entityId === state.inlineEditingNodeId) ||
            (change.changeType === 'CONNECTION_UPDATED' && change.entityId === state.inlineEditingEdgeId) ||
            (change.changeType === 'WORKFLOW_UPDATED' && editingWorkflowNameRef.current)
          if (!shouldSkip) return true
          const candidate = Math.max(0, change.seq - 1)
          deferredFromSeq = deferredFromSeq == null ? candidate : Math.min(deferredFromSeq, candidate)
          return false
        })

        if (applicableChanges.length === 0) {
          return { ...current, graphSeq: maxSeq }
        }

        return applyGraphChangesToCanvas(current, applicableChanges)
      }
    )

    graphSeqRef.current = maxSeq
    if (deferredFromSeq != null) {
      deferredGraphResyncFromRef.current =
        deferredGraphResyncFromRef.current == null
          ? deferredFromSeq
          : Math.min(deferredGraphResyncFromRef.current, deferredFromSeq)
    }
    invalidateEditorDocument()
  }, [invalidateEditorDocument, queryClient, canvas.workflow.workflowId])

  const resyncGraphChanges = useCallback(async (sinceSeq: number) => {
    try {
      const data = await fetchWorkflowGraphChanges(canvas.workflow.workflowId, sinceSeq)
      if (data.resetRequired) {
        await queryClient.invalidateQueries({
          queryKey: ['workflow-canvas', canvas.workflow.workflowId],
        })
        invalidateEditorDocument()
        return
      }
      if (data.changes.length > 0) {
        applyGraphChanges(data.changes)
        return
      }
      if (data.currentSeq <= graphSeqRef.current) return
      graphSeqRef.current = data.currentSeq
      queryClient.setQueryData<WorkflowCanvasViewModel>(
        ['workflow-canvas', canvas.workflow.workflowId],
        (current) => (current ? { ...current, graphSeq: data.currentSeq } : current)
      )
    } catch {
      await queryClient.invalidateQueries({
        queryKey: ['workflow-canvas', canvas.workflow.workflowId],
      })
      invalidateEditorDocument()
    }
  }, [applyGraphChanges, invalidateEditorDocument, queryClient, canvas.workflow.workflowId])

  const loadPresenceSnapshot = useCallback(async (ownUserId: string | null) => {
    try {
      const snapshot = await fetchWorkflowPresenceSnapshot(canvas.workflow.workflowId)
      const cursorMap = new Map<string, { x: number; y: number; type: string }>()
      const editingMap = new Map<string, string>()
      for (const entry of snapshot) {
        if (!entry.userId || entry.userId === ownUserId) continue
        if (entry.cursorX != null && entry.cursorY != null) {
          cursorMap.set(entry.userId, { x: entry.cursorX, y: entry.cursorY, type: entry.type })
        } else {
          cursorMap.set(entry.userId, { x: 0, y: 0, type: entry.type })
        }
        if (entry.editingProcessId) {
          editingMap.set(entry.editingProcessId, entry.userId)
        }
      }
      setRemoteCursors(cursorMap)
      setEditingPresence(editingMap)
    } catch {
      // Ignore snapshot failures; live presence stream will continue to work.
    }
  }, [canvas.workflow.workflowId])

  const handleGraphChange = useCallback((msg: GraphChangeMessage) => {
    if (msg.changeType === 'RESET_REQUIRED') {
      void queryClient.invalidateQueries({ queryKey: ['workflow-canvas', canvas.workflow.workflowId] })
      invalidateEditorDocument()
      return
    }
    if (msg.seq <= graphSeqRef.current) return
    if (msg.seq === graphSeqRef.current + 1) {
      applyGraphChanges([msg])
      return
    }
    void resyncGraphChanges(graphSeqRef.current)
  }, [applyGraphChanges, canvas.workflow.workflowId, invalidateEditorDocument, queryClient, resyncGraphChanges])

  const handleReconnect = useCallback((forceSnapshot: boolean) => {
    if (forceSnapshot) {
      void queryClient.invalidateQueries({ queryKey: ['workflow-canvas', canvas.workflow.workflowId] })
    } else {
      void resyncGraphChanges(graphSeqRef.current)
    }
    invalidateEditorDocument()
    void loadPresenceSnapshot(syncUserId)
  }, [canvas.workflow.workflowId, invalidateEditorDocument, loadPresenceSnapshot, queryClient, resyncGraphChanges, syncUserId])

  useEffect(() => {
    if (inlineEditingNodeId || deferredGraphResyncFromRef.current == null) return
    const sinceSeq = deferredGraphResyncFromRef.current
    deferredGraphResyncFromRef.current = null
    void resyncGraphChanges(sinceSeq)
  }, [inlineEditingNodeId, resyncGraphChanges])

  const handleSyncReady = useCallback((api: {
    sendPresence: (msg: Omit<PresenceMessage, 'userId' | 'clientId' | 'workflowId' | 'timestamp'>) => void
    clientId: string
    ownUserId: string | null
  }) => {
    sendPresenceRef.current = api.sendPresence
    setSyncUserId(api.ownUserId)
    void loadPresenceSnapshot(api.ownUserId)
  }, [loadPresenceSnapshot])

  useEffect(() => {
    sendPresenceRef.current({ type: 'NODE_EDITING', editingProcessId: selectedProcessId ?? undefined })
  }, [selectedProcessId])

  // Item I: broadcast LEAVE when the tab closes or navigates away
  useEffect(() => {
    const onUnload = () => sendPresenceRef.current({ type: 'LEAVE' })
    window.addEventListener('beforeunload', onUnload)
    return () => {
      window.removeEventListener('beforeunload', onUnload)
      sendPresenceRef.current({ type: 'LEAVE' })
    }
  }, [])

  const workflows = workflowsQuery.data ?? []
  const [editingWorkflowName, setEditingWorkflowName] = useState(false)
  const [draftWorkflowName, setDraftWorkflowName] = useState(canvas.workflow.workflowName)
  const workflowNameInputRef = useRef<HTMLInputElement>(null)
  const editingWorkflowNameRef = useRef(false)

  useEffect(() => {
    editingWorkflowNameRef.current = editingWorkflowName
  }, [editingWorkflowName])

  useEffect(() => {
    if (editingWorkflowName) workflowNameInputRef.current?.select()
  }, [editingWorkflowName])

  async function commitWorkflowName() {
    setEditingWorkflowName(false)
    const trimmed = draftWorkflowName.trim()
    if (!trimmed || trimmed === canvas.workflow.workflowName) return
    await updateWorkflowMutation.mutateAsync({
      workflowId: canvas.workflow.workflowId,
      workflowName: trimmed,
    })
  }

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

  const { applyLayout } = useAutoLayout({
    nodes: canvas.nodes,
    edges: canvas.edges,
    onBatchUpdate: batchUpdateNodePositions,
    onFitView: () => fitViewRef.current(),
  })

  function markEditorDocumentSaved() {
    if (savedTimerRef.current) clearTimeout(savedTimerRef.current)
    setSavedLabel('saved')
    savedTimerRef.current = setTimeout(() => setSavedLabel(null), 2000)
  }

  async function loadEditorDocument() {
    return fetchEditorDocument(canvas.workflow.workflowId)
  }

  async function handleSaveEditorDocument() {
    if (!canEditAnnotations) return
    setWorkspaceMessage(null)
    if (savedTimerRef.current) clearTimeout(savedTimerRef.current)
    setSavedLabel('saving')

    try {
      const editorDocument = await loadEditorDocument()
      const savedDocument = await saveEditorDocument(canvas.workflow.workflowId, editorDocument)
      queryClient.setQueryData(editorDocumentQueryKey(canvas.workflow.workflowId), savedDocument)
      markEditorDocumentSaved()
    } catch (error) {
      setSavedLabel(null)
      setWorkspaceMessage(error instanceof Error ? error.message : 'Failed to save editor document.')
    }
  }

  async function handleReloadEditorDocument() {
    editorCommandApiRef.current?.resetHistory()
    setEditorSelection(EMPTY_EDITOR_SELECTION)
    await queryClient.invalidateQueries({
      queryKey: editorDocumentQueryKey(canvas.workflow.workflowId),
    })
  }

  async function handleCreateEditorElement(tool: WorkflowEditorTool, position: Vec2) {
    if (!canEditAnnotations) return
    // Connectors are drawn by dragging between two shapes inside WorkspaceEditorLayer;
    // a plain pane click has no second anchor to bind to, so it's a no-op here.
    if (tool === 'editor-connector') return
    setWorkspaceMessage(null)
    if (savedTimerRef.current) clearTimeout(savedTimerRef.current)
    setSavedLabel('saving')

    try {
      const editorDocument = await loadEditorDocument()
      const snappedPosition = snapPoint(position, { enabled: true, gridSize: WORKSPACE_EDITOR_GRID_SIZE })
      const { element, nextElementSeq } = createWorkspaceEditorElement(tool, snappedPosition, editorDocument)
      const nextDocument = {
        ...insertElement(editorDocument, element),
        nextElementSeq,
      }
      const savedDocument = await saveEditorDocument(canvas.workflow.workflowId, nextDocument)
      editorCommandApiRef.current?.recordBackendSnapshot(editorDocument, [])
      queryClient.setQueryData(editorDocumentQueryKey(canvas.workflow.workflowId), savedDocument)
      markEditorDocumentSaved()
      setActiveTool('select')
    } catch (error) {
      setSavedLabel(null)
      setWorkspaceMessage(error instanceof Error ? error.message : 'Failed to create editor element.')
    }
  }

  const handleEditorSelectionChange = useCallback((snapshot: WorkspaceEditorSelectionSnapshot) => {
    setEditorSelection(snapshot)
    if (snapshot.elements.length > 0) {
      clearSelection()
    }
  }, [clearSelection])

  const handleEditorCommandReady = useCallback((api: WorkspaceEditorCommandApi | null) => {
    editorCommandApiRef.current = api
  }, [])

  const nodePicker = useCanvasInteractionStore((s) => s.nodePicker)
  const openNodePicker = useCanvasInteractionStore((s) => s.openNodePicker)
  const closeNodePicker = useCanvasInteractionStore((s) => s.closeNodePicker)
  const pendingDeleteNodeId = useCanvasInteractionStore((s) => s.pendingDeleteNodeId)
  const pendingDeleteEdgeId = useCanvasInteractionStore((s) => s.pendingDeleteEdgeId)
  const clearDeleteRequest = useCanvasInteractionStore((s) => s.clearDeleteRequest)
  const pendingDuplicateNodeId = useCanvasInteractionStore((s) => s.pendingDuplicateNodeId)
  const clearDuplicateRequest = useCanvasInteractionStore((s) => s.clearDuplicateRequest)
  const pendingColorChange = useCanvasInteractionStore((s) => s.pendingColorChange)
  const clearColorChange = useCanvasInteractionStore((s) => s.clearColorChange)

  const { past, future, undo, redo } = commandHistory
  const hasWorkflowSelection = Boolean(selectedProcessId || selectedConnectionId)
  const isEditorCommandContext = canEditAnnotations && !hasWorkflowSelection && (
    isWorkflowEditorTool(activeTool) ||
    editorSelection.elements.length > 0 ||
    editorSelection.canUndo ||
    editorSelection.canRedo
  )

  // Declare refs and stable callbacks BEFORE any useEffect that references them
  const canvasRef = useRef(canvas)
  canvasRef.current = canvas
  const canvasContainerRef = useRef<HTMLElement>(null)
  const fitViewRef = useRef<() => void>(() => {})
  const selectAllRef = useRef<() => void>(() => {})
  const exportPngRef = useRef<(filename: string) => void>(() => {})
  const exportSvgRef = useRef<(filename: string) => void>(() => {})
  // Reading the canvas (focus, upstream, downstream, route, find); view only, kept in the address hash.
  const revealRef = useRef<(processIds: string[]) => void>(() => {})
  const handleRevealReady = useCallback((fn: (processIds: string[]) => void) => { revealRef.current = fn }, [])
  const revealProcesses = useCallback((processIds: string[]) => revealRef.current(processIds), [])
  // The canvas as drawn, measured when the workflow is checked (layout warnings; never part of publishing).
  const layoutRef = useRef<(() => LayoutSnapshot) | null>(null)
  const handleLayoutReady = useCallback((fn: () => LayoutSnapshot) => { layoutRef.current = fn }, [])
  const [layoutFound, setLayoutFound] = useState<LayoutWarning[] | null>(null)
  const checkLayout = useCallback(() => {
    const measure = layoutRef.current
    setLayoutFound(measure ? layoutWarnings(measure()) : null)
  }, [])
  const reading = useCanvasReading(canvas, revealProcesses)
  const readingRef = useRef(reading)
  readingRef.current = reading
  // A production run shown over the canvas (address `run=`): each process's mark from its latest step; view only.
  const canvasProcessIds = useMemo(() => new Set(canvas.nodes.map((node) => node.processId)), [canvas.nodes])
  // archify keeps one reading panel open at a time: the finder, the lens, the guide, or FlowMat's run and revision pickers.
  const [panel, setPanel] = useState<'finder' | 'lens' | 'guide' | 'runs' | 'changes' | null>(null)
  const runView = useRunOverlay(canvas.workflow.workflowId, reading.run.id, canvasProcessIds, panel === 'runs')
  const changesView = useRevisionChanges(canvas.workflow.workflowId, reading.changes.id, canvas)
  // archify's view controls: the semantic radar (overview map), the camera stepper and the zoom it shows.
  const [mapOpen, setMapOpen] = useState(false)
  // As in archify, the radar and the lens take turns: opening one closes the other, and the guide and a route close the radar.
  const toggleMap = useCallback(() => {
    if (!mapOpen) setPanel((current) => (current === 'lens' ? null : current))
    setMapOpen(!mapOpen)
  }, [mapOpen])
  // Where the reader moved the Semantic Passport; it stays there from one process to the next.
  const [passportPosition, setPassportPosition] = useState<PassportPosition | null>(null)
  const [zoom, setZoom] = useState(1)
  const cameraRef = useRef<{ zoomStep(direction: 1 | -1): void; reset(): void } | null>(null)
  const handleCameraReady = useCallback((api: { zoomStep(direction: 1 | -1): void; reset(): void }) => {
    cameraRef.current = api
  }, [])
  // archify's toolbar: the motion control (Live or Still) and the presentation stage, which the address keeps as
  // `?present=1` so a link opens on the stage.
  const motion = useMotion()
  const location = useLocation()
  const presenting = new URLSearchParams(location.search).get('present') === '1'
  // Like the reading, it reads the rest of the address when it writes, so a reading written a moment before is kept.
  const setPresenting = useCallback((next: boolean) => {
    const params = new URLSearchParams(window.location.search)
    if (next) params.set('present', '1')
    else params.delete('present')
    const search = params.toString()
    navigate({ pathname: location.pathname, search: search ? `?${search}` : '', hash: window.location.hash }, { replace: true })
  }, [location.pathname, navigate])
  const togglePresenting = useCallback(() => {
    setPresenting(new URLSearchParams(window.location.search).get('present') !== '1')
  }, [setPresenting])
  // The stage changes the canvas's size, so the camera shows the whole canvas again once it has.
  const presentedRef = useRef(presenting)
  useEffect(() => {
    if (presentedRef.current === presenting) return
    presentedRef.current = presenting
    let frame = requestAnimationFrame(() => {
      frame = requestAnimationFrame(() => cameraRef.current?.reset())
    })
    return () => cancelAnimationFrame(frame)
  }, [presenting])
  // The Semantic Passport follows the process in hand: the selected one, else the one being read. While a route is picked
  // or shown the Route Probe owns the reading, as in archify, so the passport steps aside.
  const routeOwnsReading = Boolean(reading.picking) || reading.state?.kind === 'route'
  const passportProcessId = routeOwnsReading ? null
    : selectedProcessId ?? (reading.state?.kind === 'focus' ? reading.state.processId : null)
  const passportNode = passportProcessId ? canvas.nodeMap[passportProcessId] ?? null : null
  // The radar marks the processes in hand; a process picked on it is read like one found with the finder.
  const focusedProcessId = reading.state?.kind === 'focus' ? reading.state.processId : null
  const radarActiveIds = useMemo(() => new Set([selectedProcessId, focusedProcessId].filter((id): id is string => Boolean(id))),
    [focusedProcessId, selectedProcessId])
  const focusFromRadar = useCallback((processId: string) => {
    editorCommandApiRef.current?.clearSelection()
    setEditorSelection(EMPTY_EDITOR_SELECTION)
    selectNode(processId)
    if (!readingRef.current.pick(processId)) readingRef.current.focus(processId)
  }, [selectNode])
  const closeMap = useCallback(() => setMapOpen(false), [])
  // archify's Intent Trace: the process pointed at or focused, with its neighbours, while nothing is read, picked,
  // previewed or selected and the guide is closed. The keyboard's preview is announced with its connections.
  const [intentByKeyboard, setIntentByKeyboard] = useState(false)
  const handleIntent = useCallback((processId: string | null, keyboard: boolean) => {
    readingRef.current.intent.show(processId)
    setIntentByKeyboard(keyboard && processId !== null)
  }, [])
  const intentShown = !reading.highlight && !selectedProcessId && panel !== 'guide' ? reading.intent : null
  const canvasHighlight = reading.highlight ?? intentShown?.highlight ?? null
  const intentStatus = (() => {
    if (!intentShown?.id || !intentByKeyboard) return ''
    const relations = reading.relationsOf(intentShown.id)
    const total = relations.out.length + relations.in.length + relations.loop.length
    const loops = relations.loop.length > 0 ? `, ${relations.loop.length} self loop` : ''
    return `${reading.nameOf(intentShown.id)}. ${relations.out.length} outgoing, ${relations.in.length} incoming${loops}. `
      + `${total} connections. Press Enter for details.`
  })()
  // archify's route begin: the process in the passport is the start, else the start is picked first; the panels close.
  const beginRoute = () => {
    setPanel(null)
    setMapOpen(false)
    reading.startRoute(passportProcessId)
  }
  const toggleRoute = () => {
    if (reading.picking) reading.cancelRoute()
    else if (reading.state?.kind === 'route') reading.clear()
    else beginRoute()
  }
  const finderContext: FinderContext = !reading.picking ? { kind: 'focus' }
    : reading.picking.from == null ? { kind: 'route-source', badges: reading.picking.choices }
      : { kind: 'route-target', fromName: reading.nameOf(reading.picking.from), badges: reading.picking.choices }
  const linksOf = (processId: string) => {
    const relations = reading.relationsOf(processId)
    return relations.out.length + relations.in.length + relations.loop.length
  }

  const deleteApiRef = useRef<{
    deleteSelection(): Promise<void>
    deleteNode(nodeId: string): Promise<void>
    deleteEdge(edgeId: string): Promise<void>
  }>({
    deleteSelection: async () => {},
    deleteNode: async () => {},
    deleteEdge: async () => {},
  })

  const handleBeforeDelete = useCallback(
    async ({ nodeIds, edgeIds }: { nodeIds: string[]; edgeIds: string[] }) => {
      if (nodeIds.length === 0) return true

      const connectedEdgeIds = new Set<string>()
      for (const nodeId of nodeIds) {
        for (const edgeId of getRelatedConnectionIds(canvasRef.current.edges, nodeId)) {
          connectedEdgeIds.add(edgeId)
        }
      }

      const connectedCount = connectedEdgeIds.size
      if (connectedCount === 0) return true

      const extraEdgeCount = [...connectedEdgeIds].filter((edgeId) => !edgeIds.includes(edgeId)).length
      const message =
        nodeIds.length === 1
          ? `Deleting this node will also remove ${connectedCount} connected connection${connectedCount === 1 ? '' : 's'}. Continue?`
          : `Deleting ${nodeIds.length} nodes will also remove ${connectedCount} connected connections${extraEdgeCount > 0 ? ` (${extraEdgeCount} implicit)` : ''}. Continue?`

      return window.confirm(message)
    },
    []
  )

  const handleDeleteElements = useCallback(
    async ({ nodeIds, edgeIds }: { nodeIds: string[]; edgeIds: string[] }) => {
      if (nodeIds.length === 0 && edgeIds.length === 0) return

      if (nodeIds.length === 0) {
        if (edgeIds.length === 1) {
          await deleteConnection(edgeIds[0])
          return
        }

        await deleteElements({ nodeIds, edgeIds })
        return
      }

      if (nodeIds.length === 1) {
        const relatedEdgeIds = new Set(getRelatedConnectionIds(canvasRef.current.edges, nodeIds[0]))
        const hasStandaloneEdges = edgeIds.some((edgeId) => !relatedEdgeIds.has(edgeId))
        if (!hasStandaloneEdges) {
          await deleteNode(nodeIds[0])
          return
        }
      }

      await deleteElements({ nodeIds, edgeIds })
    },
    [deleteConnection, deleteElements, deleteNode]
  )
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      const target = e.target as HTMLElement
      // A focused dropdown takes its keys too: typing picks an option there.
      const isEditing =
        target.tagName === 'INPUT' ||
        target.tagName === 'TEXTAREA' ||
        target.tagName === 'SELECT' ||
        target.isContentEditable

      if (!isEditing) {
        const shortcutTool = getWorkspaceToolShortcut(e)
        if (shortcutTool && (shortcutTool === 'select' || canEditAnnotations)) {
          e.preventDefault()
          setActiveTool(shortcutTool)
          if (shortcutTool === 'select') {
            editorCommandApiRef.current?.clearSelection()
            setEditorSelection(EMPTY_EDITOR_SELECTION)
            clearSelection()
          }
          return
        }

        if (e.key === 'Escape' && (activeTool !== 'select' || editorSelection.elements.length > 0)) {
          e.preventDefault()
          setActiveTool('select')
          editorCommandApiRef.current?.clearSelection()
          setEditorSelection(EMPTY_EDITOR_SELECTION)
          clearSelection()
          return
        }

        // Reading: "/" finds a process (or a route's start or end), "?" opens the guide; once the Pointer tool is back
        // (above), Esc stops picking a route, then stops reading.
        if (!e.ctrlKey && !e.metaKey && !e.altKey && (e.key === '/' || e.key === '?')) {
          e.preventDefault()
          setPanel(e.key === '/' ? 'finder' : 'guide')
          if (e.key === '?') setMapOpen(false)
          return
        }
        // archify's view keys: M the semantic radar, F the presentation stage, + - 0 the camera stepper. (R, L and T stay
        // the editor's tools.)
        if (!e.ctrlKey && !e.metaKey && !e.altKey && ['m', 'M', 'f', 'F', '+', '=', '-', '0'].includes(e.key)) {
          e.preventDefault()
          if (e.key === 'm' || e.key === 'M') toggleMap()
          else if (e.key === 'f' || e.key === 'F') togglePresenting()
          else if (e.key === '0') cameraRef.current?.reset()
          else cameraRef.current?.zoomStep(e.key === '-' ? -1 : 1)
          return
        }
        // archify's Esc order: the radar closes, then the route and the reading stop, then the selection clears (below),
        // and only then the stage ends.
        if (e.key === 'Escape' && mapOpen) {
          e.preventDefault()
          setMapOpen(false)
          return
        }
        if (e.key === 'Escape' && readingRef.current.escape()) {
          e.preventDefault()
          return
        }
        if (e.key === 'Escape' && presenting && !selectedProcessId && !selectedConnectionId) {
          e.preventDefault()
          setPresenting(false)
          return
        }

        if (
          (e.ctrlKey || e.metaKey) &&
          e.key.toLowerCase() === 'a' &&
          (isWorkflowEditorTool(activeTool) || editorSelection.elements.length > 0)
        ) {
          e.preventDefault()
          editorCommandApiRef.current?.selectAll()
          clearSelection()
          return
        }

        const undoRedoShortcut = getUndoRedoShortcut(e)
        if (undoRedoShortcut && isEditorCommandContext) {
          e.preventDefault()
          if (undoRedoShortcut === 'undo') {
            editorCommandApiRef.current?.undo()
          } else {
            editorCommandApiRef.current?.redo()
          }
          return
        }
      }

      const ctx = {
        selectedProcessId,
        selectedConnectionId,
        isEditing,
        deleteSelection: () => deleteApiRef.current.deleteSelection(),
        duplicateNode,
        clearSelection,
        selectAll: () => selectAllRef.current(),
        undo,
        redo,
      }

      for (const action of CANVAS_ACTIONS) {
        if (action.keyTest(e) && action.predicate(ctx)) {
          e.preventDefault()
          void action.handler(ctx)
          break
        }
      }
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [
    activeTool,
    canEditAnnotations,
    clearSelection,
    duplicateNode,
    editorSelection.canRedo,
    editorSelection.canUndo,
    editorSelection.elements.length,
    isEditorCommandContext,
    mapOpen,
    presenting,
    redo,
    selectedConnectionId,
    selectedProcessId,
    setActiveTool,
    setPresenting,
    toggleMap,
    togglePresenting,
    undo,
  ])


  useEffect(() => {
    if (pendingDeleteNodeId) {
      clearDeleteRequest()
      void deleteApiRef.current.deleteNode(pendingDeleteNodeId)
    }
  }, [pendingDeleteNodeId, clearDeleteRequest])

  useEffect(() => {
    if (pendingDeleteEdgeId) {
      clearDeleteRequest()
      void deleteApiRef.current.deleteEdge(pendingDeleteEdgeId)
    }
  }, [pendingDeleteEdgeId, clearDeleteRequest])

  useEffect(() => {
    if (pendingDuplicateNodeId) {
      clearDuplicateRequest()
      void duplicateNode(pendingDuplicateNodeId)
    }
  }, [pendingDuplicateNodeId, clearDuplicateRequest, duplicateNode])

  useEffect(() => {
    if (pendingColorChange) {
      clearColorChange()
      void updateNode({ processId: pendingColorChange.nodeId, colorScheme: pendingColorChange.colorScheme })
    }
  }, [pendingColorChange, clearColorChange, updateNode])

  useEffect(() => {
    if (pendingRename) {
      clearPendingRename()
      void updateNode({ processId: pendingRename.nodeId, processName: pendingRename.name })
    }
  }, [pendingRename, clearPendingRename, updateNode])

  const selectedNode = selectedProcessId ? canvas.nodeMap[selectedProcessId] ?? null : null
  const selectedEdge = selectedConnectionId
    ? canvas.edges.find((edge) => edge.id === selectedConnectionId) ?? null
    : null
  const selectedPort = selectedPortId ? canvas.portMap[selectedPortId] ?? null : null
  const activeInspectorMode = editorSelection.elements.length > 0 ? 'editor' : inspectorMode

  function exportJson() {
    const payload = {
      version: 1,
      exportedAt: new Date().toISOString(),
      workflow: { id: canvas.workflow.workflowId, name: canvas.workflow.workflowName },
      nodes: canvas.nodes.map((n) => ({
        id: n.id,
        name: n.name,
        nodeType: n.nodeType,
        processType: n.processType,
        colorScheme: n.colorScheme,
        posX: Math.round(n.position.x),
        posY: Math.round(n.position.y),
        width: n.size.width,
        height: n.size.height,
        description: n.description,
        inputs: n.inputs.map((p) => ({ name: p.name, ioType: p.ioType, direction: p.direction, quantity: p.quantity, unit: p.unit, colorScheme: p.colorScheme })),
        outputs: n.outputs.map((p) => ({ name: p.name, ioType: p.ioType, direction: p.direction, quantity: p.quantity, unit: p.unit, colorScheme: p.colorScheme })),
      })),
      edges: canvas.edges.map((e) => ({
        id: e.id,
        source: e.source,
        target: e.target,
        connectionType: e.connectionType,
        label: e.label,
        flowRate: e.flowRate,
        unit: e.unit,
        delayTimeSec: e.delayTimeSec,
        lossRate: e.lossRate,
        priority: e.priority,
      })),
      annotations: canvas.annotations.map((annotation) => ({
        id: annotation.id,
        annotationType: annotation.annotationType,
        shapeKind: annotation.shapeKind,
        posX: Math.round(annotation.position.x),
        posY: Math.round(annotation.position.y),
        width: annotation.size.width,
        height: annotation.size.height,
        rotation: annotation.rotation,
        points: annotation.points.map((point) => ({ x: point.x, y: point.y })),
        textContent: annotation.textContent,
        style: annotation.style,
        zIndex: annotation.zIndex,
        groupId: annotation.groupId,
      })),
    }
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `${canvas.workflow.workflowName}.flowmat.json`
    a.click()
    URL.revokeObjectURL(a.href)
  }

  async function handleNodeDragEnd(processId: string, x: number, y: number) {
    await saveNodePosition(processId, x, y)
  }

  async function handleNodeResize(processId: string, width: number, height: number) {
    await updateNode({ processId, width: Math.round(width), height: Math.round(height) })
  }

  function handleConnectStart(_payload: ConnectStartPayload) {
    // pendingConnectionDraft reserved for future collaboration feature
  }

  async function handleConnectComplete(payload: ConnectCompletePayload) {
    await createConnection(payload)
  }

  async function handleCreateShapeAnnotation(position: { x: number; y: number }) {
    await createAnnotationMutation.mutateAsync({
      annotationType: 'shape',
      shapeKind: 'rectangle',
      posX: Math.round(position.x - 80),
      posY: Math.round(position.y - 48),
      width: 160,
      height: 96,
      style: {
        stroke: 'var(--text-h)',
        strokeWidth: 2,
        fill: 'transparent',
      },
    })
  }

  async function handleCreateTextAnnotation(position: { x: number; y: number }) {
    await createAnnotationMutation.mutateAsync({
      annotationType: 'text',
      posX: Math.round(position.x - 110),
      posY: Math.round(position.y - 28),
      width: 220,
      height: 56,
      textContent: 'New note',
      style: {
        stroke: 'var(--text-h)',
        strokeWidth: 1,
        fill: 'rgba(255,255,255,0.72)',
      },
    })
  }

  async function handleCompleteFreehand(points: CanvasAnnotationPoint[]) {
    if (points.length < 2) return
    const normalized = normalizeFreehand(points)
    await createAnnotationMutation.mutateAsync({
      annotationType: 'freehand',
      posX: normalized.posX,
      posY: normalized.posY,
      width: normalized.width,
      height: normalized.height,
      points: normalized.points,
      style: {
        stroke: 'var(--accent)',
        strokeWidth: 2,
      },
    })
  }

  async function handleUpdateAnnotation(annotationId: string, input: PatchCanvasAnnotationInput) {
    await patchAnnotationMutation.mutateAsync({ annotationId, input })
  }

  async function handleDeleteAnnotations(annotationIds: string[]) {
    await Promise.all(annotationIds.map((annotationId) => deleteAnnotationMutation.mutateAsync(annotationId)))
  }

  async function handleNodePick(tool: Parameters<typeof createNodeFromTool>[0]) {
    if (!nodePicker) return
    closeNodePicker()
    if (nodePicker.kind === 'connect-drop') {
      await createNodeFromConnectionDrop(nodePicker.draft, tool, nodePicker.flowPosition)
    } else {
      await insertNodeOnEdge(nodePicker.edgeId, tool, nodePicker.flowPosition)
    }
  }

  const ribbonHandlers: RibbonButtonHandlers = {
    ...createWorkspaceSelectionCommands({
      selection: editorSelection,
      editable: canEditAnnotations,
      editorApi: editorCommandApiRef.current,
      annotations: canvas.annotations,
      batchAnnotations: (input) => batchAnnotationMutation.mutateAsync(input),
      onError: setWorkspaceMessage,
    }),
    'select-pointer': { onClick: () => setActiveTool('select'), active: activeTool === 'select' },
    'add-node': { onClick: () => void addNode() },
    undo: {
      onClick: () => {
        if (isEditorCommandContext) {
          editorCommandApiRef.current?.undo()
          return
        }
        void undo()
      },
      disabled: isEditorCommandContext ? !editorSelection.canUndo : past.length === 0,
      title: isEditorCommandContext
        ? (editorSelection.canUndo ? 'Undo editor document change' : 'Nothing to undo')
        : (past.length > 0 ? `Undo: ${past[past.length - 1].label}` : 'Nothing to undo'),
    },
    redo: {
      onClick: () => {
        if (isEditorCommandContext) {
          editorCommandApiRef.current?.redo()
          return
        }
        void redo()
      },
      disabled: isEditorCommandContext ? !editorSelection.canRedo : future.length === 0,
      title: isEditorCommandContext
        ? (editorSelection.canRedo ? 'Redo editor document change' : 'Nothing to redo')
        : (future.length > 0 ? `Redo: ${future[0].label}` : 'Nothing to redo'),
    },
    'layout-tb': {
      onClick: () => void applyLayout('TB'),
      disabled: canvas.nodes.length === 0,
      title: 'Auto layout top to bottom',
    },
    'layout-lr': {
      onClick: () => void applyLayout('LR'),
      disabled: canvas.nodes.length === 0,
      title: 'Auto layout left to right',
    },
    'export-json': { onClick: exportJson, disabled: canvas.nodes.length === 0, title: 'Export as JSON' },
    'export-png': {
      onClick: () => exportPngRef.current(canvas.workflow.workflowName),
      disabled: canvas.nodes.length === 0,
      title: 'Export as PNG',
    },
    'export-svg': {
      onClick: () => exportSvgRef.current(canvas.workflow.workflowName),
      disabled: canvas.nodes.length === 0,
      title: 'Export as SVG (vector, the workflow only)',
    },
    'save-editor-document': {
      onClick: () => void handleSaveEditorDocument(),
      disabled: !canEditAnnotations,
      title: canEditAnnotations ? 'Save editor document elements' : 'Viewers cannot save editor elements',
    },
    'reload-editor-document': {
      onClick: () => void handleReloadEditorDocument(),
      title: 'Reload editor document elements',
    },
    'duplicate-selected': {
      onClick: () => editorCommandApiRef.current?.duplicateSelected(),
      disabled: !editorCommandApiRef.current || editorSelection.elements.length === 0,
      title: 'Duplicate selected shapes',
    },
    'delete-selected': {
      onClick: () => editorCommandApiRef.current?.deleteSelected(),
      disabled: !editorCommandApiRef.current || editorSelection.elements.length === 0,
      title: 'Delete selected shapes',
    },
    'bring-to-front': {
      onClick: () => editorCommandApiRef.current?.bringSelectedToFront(),
      disabled: !editorCommandApiRef.current || editorSelection.elements.length === 0,
      title: 'Bring to front',
    },
    'send-to-back': {
      onClick: () => editorCommandApiRef.current?.sendSelectedToBack(),
      disabled: !editorCommandApiRef.current || editorSelection.elements.length === 0,
      title: 'Send to back',
    },
    'fit-view': {
      onClick: () => fitViewRef.current(),
      title: 'Fit view',
    },
    'select-all': {
      onClick: () => selectAllRef.current(),
      title: 'Select all',
    },
  }

  const ribbonDynamicButtons: RibbonDynamicButtons = {
    tools: paletteDefinitions.map((definition) => ({
      id: `tool-${definition.tool}`,
      icon: Shapes,
      label: definition.label,
      onClick: () => setActiveTool(definition.tool),
      active: activeTool === definition.tool,
    })),
    draw: [
      ...ANNOTATION_TOOL_DEFINITIONS.map((definition) => ({
        id: `tool-${definition.tool}`,
        icon: definition.icon,
        label: definition.label,
        onClick: () => setActiveTool(definition.tool),
        active: activeTool === definition.tool,
        disabled: !canEditAnnotations,
        title: canEditAnnotations ? definition.description : 'Viewers cannot create annotations',
      })),
      ...EDITOR_TOOL_DEFINITIONS.map((definition) => ({
        id: `tool-${definition.tool}`,
        icon: definition.icon,
        label: definition.label,
        onClick: () => setActiveTool(definition.tool),
        active: activeTool === definition.tool,
        disabled: !canEditAnnotations,
        title: canEditAnnotations ? definition.description : 'Viewers cannot create editor elements',
      })),
    ],
  }

  // Collaborate tab (step 4, migration plan §4): same state/logic as before, only the
  // render location moves off the title bar and into the Collaborate tab's groups.
  const ribbonGroupContent: RibbonGroupContent = {
    presence: remoteCursors.size > 0 && (
      <div className="presence-avatars" title={`${remoteCursors.size} collaborators online`}>
        {[...remoteCursors.keys()]
          .filter((uid) => uid !== syncUserId)
          .slice(0, 5)
          .map((uid) => (
            <span key={uid} className="presence-avatar" title={uid}>
              {uid.slice(0, 2).toUpperCase()}
            </span>
          ))}
        {remoteCursors.size > 5 && (
          <span className="presence-avatar presence-avatar--overflow">+{remoteCursors.size - 5}</span>
        )}
      </div>
    ),
    status: savedLabel && (
      <span className={`save-status save-status--${savedLabel}`}>
        {savedLabel === 'saving' ? 'Saving...' : 'Saved'}
      </span>
    ),
    workflow: workflows.length > 1 && (
      <select
        className="workflow-switcher"
        value={canvas.workflow.workflowId}
        onChange={(e) => {
          navigate(`/projects/${canvas.workflow.projectId}/workflows/${e.target.value}`)
        }}
        title="Switch workflow"
      >
        {workflows.map((wf) => (
          <option key={wf.workflowId} value={wf.workflowId}>
            {wf.workflowName}
          </option>
        ))}
      </select>
    ),
  }

  const ribbonTabs = buildRibbonTabs(ribbonHandlers, ribbonDynamicButtons, ribbonGroupContent)

  return (
    <div className="workspace-layout" data-present={presenting ? 'true' : undefined}>
      <header className="workspace-topbar">
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <Link to="/" className="topbar-home-link" title="Back to home">Home</Link>
          <div style={{ display: 'grid', gap: '4px' }}>
            {editingWorkflowName ? (
              <input
                ref={workflowNameInputRef}
                className="workspace-topbar__name-input"
                value={draftWorkflowName}
                onChange={(e) => setDraftWorkflowName(e.target.value)}
                onBlur={() => void commitWorkflowName()}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') void commitWorkflowName()
                  if (e.key === 'Escape') {
                    setDraftWorkflowName(canvas.workflow.workflowName)
                    setEditingWorkflowName(false)
                  }
                }}
              />
            ) : (
              <span
                className="workspace-topbar__project"
                title="Double-click to rename workflow"
                onDoubleClick={() => {
                  setDraftWorkflowName(canvas.workflow.workflowName)
                  setEditingWorkflowName(true)
                }}
              >
                {canvas.workflow.workflowName}
              </span>
            )}
            <span className="workspace-topbar__status">
              {canvas.workflow.workflowStatus} | {canvas.workflow.workflowType}
            </span>
          </div>
          {/* Workflow switcher select moved to Ribbon Collaborate tab > Workflow group (step 4). */}
        </div>
        {/* Save status label moved to Ribbon Collaborate tab > Status group (step 4). */}
        {/* Presence avatars moved to Ribbon Collaborate tab > Presence group (step 4). */}
        <div
          style={{
            display: 'flex',
            gap: '12px',
            alignItems: 'center',
            flexWrap: 'wrap',
            justifyContent: 'flex-end',
          }}
        >
          {/* Pointer/annotation tool buttons and Undo/Redo/Layout/Export moved to the Ribbon (Home/Annotate tabs). */}
            <span style={{ fontSize: '13px', opacity: 0.8 }}>
            Current tool: {getToolLabel(activeTool, paletteDefinitions)}.{' '}
            {activeTool === 'annotation-freehand'
              ? 'Drag on empty canvas to sketch. Collaborators see a live preview before the final stroke is saved.'
              : activeTool === 'annotation-shape'
                ? 'Click empty canvas to drop a shape annotation.'
                : activeTool === 'annotation-text'
                  ? 'Click empty canvas to drop a text annotation.'
                  : activeTool.startsWith('editor-')
                    ? 'Click empty canvas to create a backend editor element.'
                    : 'Click the canvas to place a node, then drag between handles to connect.'}
          </span>
        </div>
      </header>

      <Ribbon tabs={ribbonTabs} activeTabId={activeRibbonTabId} onTabChange={setActiveRibbonTabId} />

      <section aria-label="Workflow validation" style={{ margin: '12px 16px 0' }}>
        <button type="button" onClick={() => {
          checkLayout()
          if (showValidation) void validationQuery.refetch()
          else setShowValidation(true)
        }}>Check workflow</button>
        <EditorPublish workflowId={canvas.workflow.workflowId} projectId={canvas.workflow.projectId} editable={canEditAnnotations}
          onRefused={() => {
            checkLayout()
            if (showValidation) void validationQuery.refetch()
            else setShowValidation(true)
          }} />
        {showValidation && (
          <div role="status" style={{ marginTop: 8 }}>
            {validationQuery.isPending && <p>Checking workflow...</p>}
            {validationQuery.isError && <p role="alert">{validationQuery.error.message}</p>}
            {validationQuery.data && (
              <>
                <p>{validationQuery.data.errors} errors, {validationQuery.data.warnings} warnings</p>
                {validationQuery.data.issues.length === 0 && <p>No issues found.</p>}
                <ValidationIssueList issues={validationQuery.data.issues} nameOf={reading.nameOf} onSelect={(issue) => {
                  const target = validationSelection(issue)
                  if (target?.kind === 'connection') selectEdge(target.id)
                  else if (target?.kind === 'process') {
                    selectNode(target.id)
                    if (target.ioId) useWorkspaceStore.getState().selectPort(target.ioId)
                  }
                }} />
              </>
            )}
            <LayoutWarningList warnings={layoutFound} nameOf={reading.nameOf} onSelect={(warning) => {
              if (warning.code === 'LINE_OVER_PROCESS') {
                reading.reveal([warning.from, warning.to, warning.processId])
                selectEdge(warning.connectionId)
              } else {
                reading.reveal([...new Set(warning.ends.flat())])
                selectEdge(warning.connectionIds[0])
              }
            }} />
          </div>
        )}
      </section>

      {workspaceMessage && (
        <div
          style={{
            margin: '12px 16px 0',
            padding: '10px 12px',
            border: '1px solid #fca5a5',
            borderRadius: '10px',
            color: '#991b1b',
            background: '#fef2f2',
          }}
        >
          {workspaceMessage}
        </div>
      )}

      <div className="workspace-body">
        <aside className="workspace-panel workspace-panel--left"
          style={{ width: localPanelWidths.left, padding: 0, overflow: 'hidden' }}>
          {/* Shapes, text and freehand are on the ribbon's Annotate tab, with align, distribute and group. */}
          <NodePaletteSidebar paletteDefinitions={paletteDefinitions} activeTool={activeTool} setActiveTool={setActiveTool} />
        </aside>

        <div className="panel-resize-handle" onMouseDown={makeResizeHandler('left')} />
        <main ref={canvasContainerRef as React.RefObject<HTMLElement>} className="workspace-canvas">
          <Suspense fallback={<CanvasFallback />}>
            <CanvasViewport
              workflowId={canvas.workflow.workflowId}
              nodes={canvas.nodes}
              edges={canvas.edges}
              annotations={canvas.annotations}
              activeTool={activeTool}
              selectedNodeId={selectedProcessId}
              selectedEdgeId={selectedConnectionId}
              drawingEnabled={activeTool !== 'select'}
              canEditAnnotations={canEditAnnotations}
              onNodeSelect={(processId) => {
                // While a route is being picked, the click also names its start or end.
                readingRef.current.pick(processId)
                editorCommandApiRef.current?.clearSelection()
                setEditorSelection(EMPTY_EDITOR_SELECTION)
                selectNode(processId)
              }}
              onEdgeSelect={(connectionId) => {
                editorCommandApiRef.current?.clearSelection()
                setEditorSelection(EMPTY_EDITOR_SELECTION)
                selectEdge(connectionId)
              }}
              onNodeDragEnd={handleNodeDragEnd}
              onNodeResize={handleNodeResize}
              onConnectStart={handleConnectStart}
              onConnectComplete={handleConnectComplete}
              onCanvasClick={(position) => {
                editorCommandApiRef.current?.clearSelection()
                void createNodeAt(position)
              }}
              onNodeDrop={(tool, position) => void createNodeFromTool(tool, position)}
              onCreateEditorElement={handleCreateEditorElement}
              onCreateShapeAnnotation={handleCreateShapeAnnotation}
              onCreateTextAnnotation={handleCreateTextAnnotation}
              onCompleteFreehand={handleCompleteFreehand}
              onUpdateAnnotation={handleUpdateAnnotation}
              onDeleteAnnotations={handleDeleteAnnotations}
              onFitViewReady={(fn) => { fitViewRef.current = fn }}
              onSelectAllReady={(fn) => { selectAllRef.current = fn }}
              onExportReady={(fn) => { exportPngRef.current = fn }}
              onSvgExportReady={(fn) => { exportSvgRef.current = fn }}
              onDeleteReady={(api) => { deleteApiRef.current = api }}
              onPresence={handlePresence}
              onGraphChange={handleGraphChange}
              onReconnect={handleReconnect}
              onBeforeDelete={handleBeforeDelete}
              onDeleteElements={handleDeleteElements}
              onSyncReady={handleSyncReady}
              onEditorDocumentError={setWorkspaceMessage}
              onEditorSelectionChange={handleEditorSelectionChange}
              onEditorCommandReady={handleEditorCommandReady}
              editingPresence={editingPresence}
              remoteAnnotationPreviews={remoteAnnotationPreviews}
              reading={canvasHighlight}
              onIntent={handleIntent}
              onRevealReady={handleRevealReady}
              mapOpen={mapOpen}
              radarActiveIds={radarActiveIds}
              onRadarFocus={focusFromRadar}
              onRadarClose={closeMap}
              onCameraReady={handleCameraReady}
              onZoomChange={setZoom}
              runMarks={runView.marks}
              changeMarks={changesView.changes}
              onLayoutReady={handleLayoutReady}
              onEdgeReconnect={async (oldEdgeId, newConnection) => {
                await deleteConnection(oldEdgeId)
                await createConnection(newConnection)
              }}
              onConnectDropOnCanvas={(payload) =>
                openNodePicker({
                  kind: 'connect-drop',
                  flowPosition: payload.flowPosition,
                  screenPosition: payload.screenPosition,
                  draft: {
                    fromProcessId: payload.fromProcessId,
                    fromHandleId: payload.fromHandleId,
                    fromHandleType: payload.fromHandleType,
                  },
                })
              }
            />
            {nodePicker && (
              <NodePickerPopup
                picker={nodePicker}
                definitions={paletteDefinitions}
                onPick={(tool) => void handleNodePick(tool)}
                onClose={closeNodePicker}
              />
            )}
          </Suspense>
          {passportNode && (
            <SemanticPassport key={passportNode.processId} reading={reading} node={passportNode}
              position={passportPosition} onMove={setPassportPosition}
              onClose={() => {
                clearSelection()
                if (reading.state?.kind === 'focus') reading.clear()
              }}
              onFollow={(processId) => {
                // Walks the graph: the process at the other end is selected and brought into view.
                editorCommandApiRef.current?.clearSelection()
                setEditorSelection(EMPTY_EDITOR_SELECTION)
                selectNode(processId)
                reading.reveal([processId])
              }} />
          )}
          <RouteProbe reading={reading} still={motion.still} onFind={() => setPanel('finder')} />
          <p className="intent-trace-status" role="status" aria-live="polite" aria-atomic="true">{intentStatus}</p>
          <ReadingReceipts
            lens={reading.state?.kind === 'lens' && reading.view && panel !== 'lens'
              ? { text: reading.view.text, onClear: reading.clear } : null}
            run={runView.summary ? { text: runView.summary, onHide: () => reading.run.show(null) } : null}
            changes={changesView.summary ? { text: changesView.summary, onHide: () => reading.changes.show(null) } : null} />
          <DiagramNav
            routeActive={routeOwnsReading} onRoute={toggleRoute}
            mapOpen={mapOpen} onMap={toggleMap}
            lensActive={reading.state?.kind === 'lens'} onLens={() => {
              if (panel !== 'lens') setMapOpen(false)
              setPanel((open) => (open === 'lens' ? null : 'lens'))
            }}
            runsActive={reading.run.id != null} onRuns={() => setPanel((open) => (open === 'runs' ? null : 'runs'))}
            changesActive={reading.changes.id != null}
            onChanges={() => setPanel((open) => (open === 'changes' ? null : 'changes'))}
            onFind={() => setPanel('finder')} onGuide={() => {
              setMapOpen(false)
              setPanel('guide')
            }}
            zoom={zoom} onZoomOut={() => cameraRef.current?.zoomStep(-1)} onReset={() => cameraRef.current?.reset()}
            onZoomIn={() => cameraRef.current?.zoomStep(1)}
            motion={{ still: motion.still, reduced: motion.reduced, onToggle: motion.toggle }}
            presenting={presenting} onPresent={togglePresenting} />
          {panel === 'runs' && (
            <RunPicker runs={runView.runs} loading={runView.loading} shownId={reading.run.id} onShow={reading.run.show}
              onClose={() => setPanel(null)} />
          )}
          {panel === 'changes' && (
            <ChangesPicker revisions={changesView.revisions} loading={changesView.loading} shownId={reading.changes.id}
              changes={changesView.changes} nameOf={reading.nameOf} onShow={reading.changes.show}
              onReveal={(processId) => reading.reveal([processId])} onClose={() => setPanel(null)} />
          )}
          {panel === 'lens' && (
            <SemanticLens kinds={reading.lens.kinds} chosen={reading.lens.chosen}
              status={reading.state?.kind === 'lens' ? reading.view?.text ?? null : null}
              onToggle={reading.lens.toggle} onClear={reading.clear} onClose={() => setPanel(null)} />
          )}
          {panel === 'finder' && (
            <ProcessFinder
              nodes={canvas.nodes}
              context={finderContext}
              linksOf={linksOf}
              onClose={() => setPanel(null)}
              onPick={(processId) => {
                setPanel(null)
                editorCommandApiRef.current?.clearSelection()
                setEditorSelection(EMPTY_EDITOR_SELECTION)
                selectNode(processId)
                // While a route is picked the finder names its start (brought into view) or its end; else it reads the process.
                const naming = reading.picking?.from === null
                if (!reading.pick(processId)) reading.focus(processId)
                else if (naming) reading.reveal([processId])
              }}
            />
          )}
          {panel === 'guide' && (
            <DiagramGuide processes={canvas.nodes.length} connections={canvas.edges.length} onClose={() => setPanel(null)}
              onAction={(action) => {
                if (action === 'route') beginRoute()
                else if (action === 'present') {
                  setPanel(null)
                  setPresenting(true)
                } else if (action === 'map') {
                  setPanel(null)
                  setMapOpen(true)
                } else setPanel(action === 'find' ? 'finder' : action)
              }} />
          )}
          {/* Remote cursor overlay: cursor coordinates are window-relative, so subtract the canvas rect. */}
          {(() => {
            const rect = canvasContainerRef.current?.getBoundingClientRect()
            if (!rect) return null
            return [...remoteCursors.entries()]
              .filter(([uid]) => uid !== syncUserId)
              .map(([uid, cursor]) => (
                <div
                  key={uid}
                  className="remote-cursor"
                  style={{ left: cursor.x - rect.left, top: cursor.y - rect.top }}
                >
                  <div className="remote-cursor__dot" />
                  <span className="remote-cursor__label">{uid.slice(0, 8)}</span>
                </div>
              ))
          })()}
        </main>

        <div className="panel-resize-handle" onMouseDown={makeResizeHandler('right')} />
        <aside className="workspace-panel workspace-panel--right" style={{ width: localPanelWidths.right }}>
          {activeInspectorMode === 'editor' && (
            <EditorElementInspector
              selection={editorSelection}
              commandsRef={editorCommandApiRef}
            />
          )}
          {activeInspectorMode === 'node' && (
            <Suspense fallback={<InspectorFallback />}>
              <NodeInspector
                node={selectedNode}
                selectedPort={selectedPort}
                rules={[]}
                onNodeSubmit={updateNode}
                onNodeDelete={(processId) => deleteApiRef.current.deleteNode(processId)}
                onPortCreate={createPort}
                onPortUpdate={updatePort}
                onPortDelete={deletePort}
                onOpenRuleBuilder={() => {}}
              />
            </Suspense>
          )}
          {activeInspectorMode === 'connection' && (
            <Suspense fallback={<InspectorFallback />}>
              <ConnectionInspector
                edge={selectedEdge}
                rules={[]}
                onSubmit={async (input) => { stopInlineEditEdge(); await updateConnection(input) }}
                focusLabel={inlineEditingEdgeId === selectedEdge?.id}
                sourceIsExclusive={selectedEdge ? canvas.nodeMap[selectedEdge.fromProcessId]?.nodeType === 'exclusive' : false}
                sourceIsSplit={selectedEdge ? canvas.nodeMap[selectedEdge.fromProcessId]?.nodeType === 'split' : false}
                onDelete={(connectionId) => deleteApiRef.current.deleteEdge(connectionId)}
                onOpenRuleBuilder={() => {}}
              />
            </Suspense>
          )}
          {activeInspectorMode === 'multi' && (
            <div className="inspector-summary">
              <p>Multiple nodes selected</p>
              <p className="inspector-hint">
                Shift+click or drag to multi-select. Bulk delete is not fully supported in the inspector yet.
              </p>
            </div>
          )}
          {activeInspectorMode === 'none' && (
            <div className="inspector-summary">
              <p>
                {canvas.nodes.length} nodes | {canvas.edges.length} connections
              </p>
              <p className="inspector-hint">Click a node or connection to inspect it.</p>
            </div>
          )}
        </aside>
      </div>
    </div>
  )
}





