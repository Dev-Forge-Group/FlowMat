import { useCallback, useEffect, useMemo, useRef, useState, type DragEvent, type MouseEvent } from 'react'
import {
  ReactFlow,
  Background,
  BackgroundVariant,
  reconnectEdge,
  ViewportPortal,
  type NodeChange,
  type EdgeChange,
  type Connection,
  type OnConnectStart,
  type OnConnectEnd,
  type OnNodeDrag,
  type OnReconnect,
  type ReactFlowInstance,
  type Node,
  type Edge,
  applyNodeChanges,
  applyEdgeChanges,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import type {
  CanvasAnnotationViewModel,
  CanvasNodeViewModel,
  CanvasEdgeViewModel,
  ConnectStartPayload,
  ConnectCompletePayload,
} from '../../../entities/workflow/model/types'
import {
  isWorkflowEditorTool,
  type WorkflowEditorTool,
  type WorkflowPaletteTool,
} from '../../../entities/workflow/model/nodeCatalog'
import type { PatchCanvasAnnotationInput } from '../../../entities/canvas-annotation/api/canvasAnnotationApi'
import type { CanvasReadingHighlight } from '../model/canvasReadingModel'
import type { RunMark } from '../model/runOverlay'
import type { LayoutSnapshot } from '../model/layoutChecks'
import { CanvasNode } from './CanvasNode'
import { CanvasEdge } from './CanvasEdge'
import { PALETTE_DRAG_MIME } from './canvasConstants'
import { canvasNodePosition } from '../model/canvasNodePosition'
import { lockDrag } from '../model/axisLock'
import { reuseUnchangedNodes } from '../model/reuseUnchangedNodes'
import { useWorkspaceStore } from '../model/workspaceStore'
import { useCanvasInteractionStore } from '../model/canvasInteractionStore'
import { useTheme } from '../../../app/providers/ThemeProvider'
import { motionStill } from '../model/motion'
import { SemanticRadar } from './SemanticRadar'
import type { CanvasPreset } from './StylePresetMenu'
import {
  useWorkflowSync,
  type NodeMoveMessage,
  type PresenceMessage,
  type GraphChangeMessage,
} from '../../../entities/workflow/api/useWorkflowSync'
import { snapGuidesFor, type SnapGuide } from '../model/snapGuides'
import { usePlanTraceStore } from '../model/planTrace'
import { connectionMarkClass, processMarkClass, type ChangeMarks } from '../model/canvasMarks'
import { useFreehandDrawing } from '../model/useFreehandDrawing'
import { useCanvasViewHandles, type CanvasCamera } from './useCanvasViewHandles'
import { connectionPayload, isNewConnection, toRfEdges, toRfProcessNodes, type RfEdge, type RfNode } from '../model/canvasFlowElements'
import { AnnotationPreviewLayer, CustomConnectionLine, SnapGuideLayer, type AnnotationPreview } from './CanvasOverlayLayers'
import { ExportController, type MotionExport, type PngExport, type ShareCardExport, type SvgExport } from './CanvasExportController'
import { HandleBoundsRecovery, InternalsUpdater, loadSavedViewport, ViewportPersister, ZoomReporter } from './CanvasFlowWatchers'
import {
  WorkspaceEditorLayer,
  type WorkspaceEditorCommandApi,
  type WorkspaceEditorSelectionSnapshot,
} from './WorkspaceEditorLayer'
import { nodeDisplayHeight } from '../model/nodeHeight'

const nodeTypes = { flowmatNode: CanvasNode }
const edgeTypes = { flowmatEdge: CanvasEdge }

interface Props {
  workflowId: string
  nodes: CanvasNodeViewModel[]
  edges: CanvasEdgeViewModel[]
  annotations: CanvasAnnotationViewModel[]
  activeTool: WorkflowPaletteTool
  selectedNodeId: string | null
  selectedEdgeId: string | null
  drawingEnabled: boolean
  canEditAnnotations: boolean
  onNodeSelect(id: string): void
  onEdgeSelect(id: string): void
  onNodeDragEnd(processId: string, x: number, y: number): void
  onNodeResize(processId: string, width: number, height: number): void
  onConnectStart(payload: ConnectStartPayload): void
  onConnectComplete(payload: ConnectCompletePayload): void
  onCanvasClick(position: { x: number; y: number }): void
  onNodeDrop(tool: WorkflowPaletteTool, position: { x: number; y: number }): void
  onCreateEditorElement(tool: WorkflowEditorTool, position: { x: number; y: number }): Promise<void>
  onCreateShapeAnnotation(position: { x: number; y: number }): Promise<void>
  onCreateTextAnnotation(position: { x: number; y: number }): Promise<void>
  onCompleteFreehand(points: { x: number; y: number }[]): Promise<void>
  onUpdateAnnotation(
    annotationId: string,
    input: PatchCanvasAnnotationInput
  ): Promise<void>
  onDeleteAnnotations(annotationIds: string[]): Promise<void>
  onConnectDropOnCanvas(payload: {
    fromProcessId: string
    fromHandleId: string | null
    fromHandleType: 'source' | 'target'
    flowPosition: { x: number; y: number }
    screenPosition: { x: number; y: number }
  }): void
  onFitViewReady?(fn: () => void): void
  onSelectAllReady?(fn: () => void): void
  /** The processes selected on the canvas, whenever the selection changes (for a guided view's new chapter). */
  onProcessSelectionChange?(processIds: string[]): void
  onAnnotationSelectionReady?(getSelectedAnnotationIds: () => string[]): void
  onExportReady?(fn: PngExport): void
  /** A way to download the workflow as a vector picture (workflowSvg.ts). */
  onSvgExportReady?(fn: SvgExport): void
  onShareCardExportReady?(fn: ShareCardExport): void
  onMotionExportReady?(fn: MotionExport): void
  onEdgeReconnect?(oldEdgeId: string, newConnection: ConnectCompletePayload): void
  onPresence?(msg: PresenceMessage): void
  onGraphChange?(msg: GraphChangeMessage): void
  onReconnect?(forceSnapshot: boolean): void
  onBeforeDelete?(params: { nodeIds: string[]; edgeIds: string[] }): Promise<boolean>
  onDeleteElements?(params: { nodeIds: string[]; edgeIds: string[] }): Promise<void>
  onDeleteReady?(api: {
    deleteSelection(): Promise<void>
    deleteNode(nodeId: string): Promise<void>
    deleteEdge(edgeId: string): Promise<void>
  }): void
  onEditorDocumentError?(message: string): void
  editingPresence?: ReadonlyMap<string, string>
  onSyncReady?(api: {
    sendPresence: (msg: Omit<PresenceMessage, 'userId' | 'clientId' | 'workflowId' | 'timestamp'>) => void
    clientId: string
    ownUserId: string | null
  }): void
  onEditorSelectionChange?(snapshot: WorkspaceEditorSelectionSnapshot): void
  onEditorCommandReady?(api: WorkspaceEditorCommandApi | null): void
  remoteAnnotationPreviews?: ReadonlyMap<string, { points: { x: number; y: number }[]; annotationType: string }>
  /** What is being read (a process's neighbours, upstream, downstream or a route); the rest recedes. View only. */
  reading?: CanvasReadingHighlight | null
  /** A way to bring processes into view (a lone one with room around it), for reading the canvas. */
  onRevealReady?(fn: (processIds: string[]) => void): void
  /** A production run shown over the canvas: each process's mark from its latest step. View only. */
  runMarks?: ReadonlyMap<string, RunMark> | null
  /** What the draft changed since a published revision: added or changed processes and connections. View only. */
  changeMarks?: ChangeMarks | null
  /** A way to measure the canvas as drawn (process boxes and the lines' paths), for the layout warnings. */
  onLayoutReady?(fn: () => LayoutSnapshot): void
  /** archify's Semantic Radar: the overview map, shown only while it is open. */
  mapOpen?: boolean
  /**
   * archify's Intent Trace: the process a fine pointer rests on (after 90 ms) or the keyboard focuses, and null once it
   * leaves; `keyboard` says the focus brought it.
   */
  onIntent?(processId: string | null, keyboard: boolean): void
  /** archify's visual style for the canvas; Classic is FlowMat's own look. */
  preset?: CanvasPreset
  /** The processes the radar marks as in hand. */
  radarActiveIds?: ReadonlySet<string>
  /** Reads a process picked on the radar. */
  onRadarFocus?(processId: string): void
  onRadarClose?(): void
  /** archify's camera stepper: zoom in or out to the next 25% step (25%–300%), or back to the whole canvas. */
  onCameraReady?(api: CanvasCamera): void
  /** The zoom when the canvas first shows and after each move ends. */
  onZoomChange?(zoom: number): void
}

const NO_IDS: ReadonlySet<string> = new Set()

/** archify's Intent Trace waits this long under a resting pointer, so passing over a process does not flash the canvas. */
const INTENT_DELAY_MS = 90

function finePointer() {
  return typeof window.matchMedia !== 'function' || window.matchMedia('(hover: hover) and (pointer: fine)').matches
}

export function CanvasViewport({
  workflowId,
  nodes,
  edges,
  annotations,
  activeTool,
  selectedNodeId,
  selectedEdgeId,
  drawingEnabled,
  canEditAnnotations,
  onNodeSelect,
  onEdgeSelect,
  onNodeDragEnd,
  onNodeResize,
  onConnectStart,
  onConnectComplete,
  onCanvasClick,
  onNodeDrop,
  onCreateEditorElement,
  onCreateShapeAnnotation,
  onCreateTextAnnotation,
  onCompleteFreehand,
  onUpdateAnnotation,
  onDeleteAnnotations,
  onConnectDropOnCanvas,
  onFitViewReady,
  onSelectAllReady,
  onProcessSelectionChange,
  onAnnotationSelectionReady,
  onExportReady,
  onSvgExportReady,
  onShareCardExportReady,
  onMotionExportReady,
  onEdgeReconnect,
  onPresence,
  onGraphChange,
  onReconnect,
  onBeforeDelete,
  onDeleteElements,
  onDeleteReady,
  onSyncReady,
  onEditorDocumentError,
  onEditorSelectionChange,
  onEditorCommandReady,
  editingPresence,
  remoteAnnotationPreviews,
  reading = null,
  onRevealReady,
  runMarks = null,
  changeMarks = null,
  onLayoutReady,
  mapOpen = false,
  onIntent,
  preset = 'classic',
  radarActiveIds = NO_IDS,
  onRadarFocus,
  onRadarClose,
  onCameraReady,
  onZoomChange,
}: Props) {
  // archify's Intent Trace: what the pointer rests on and what the keyboard focused, so leaving one falls back to the other.
  const intentRef = useRef<{ hovered: string | null; focused: string | null; timer: number | undefined }>({
    hovered: null, focused: null, timer: undefined })
  const processIds = useMemo(() => new Set(nodes.map((node) => node.id)), [nodes])
  const intentAt = (processId: string | null) => {
    window.clearTimeout(intentRef.current.timer)
    intentRef.current.hovered = null
    onIntent?.(processId, false)
  }
  useEffect(() => {
    const leave = () => {
      window.clearTimeout(intentRef.current.timer)
      intentRef.current.hovered = null
      onIntent?.(null, false)
    }
    window.addEventListener('blur', leave)
    return () => window.removeEventListener('blur', leave)
  }, [onIntent])
  const storageKey = `flowmat-viewport-${workflowId}`
  const savedViewport = useMemo(() => loadSavedViewport(storageKey), [storageKey])
  // A link that opens a reading opens on what it reads rather than on the last saved view.
  const [initialReading] = useState(() => (reading && reading.nodeIds.size > 0 ? [...reading.nodeIds] : null))
  const { resolvedTheme } = useTheme()
  const selectNode = useWorkspaceStore((s) => s.selectNode)
  const selectEdge = useWorkspaceStore((s) => s.selectEdge)
  const startInlineEdit = useWorkspaceStore((s) => s.startInlineEdit)
  const startInlineEditEdge = useWorkspaceStore((s) => s.startInlineEditEdge)
  const setMultiSelect = useWorkspaceStore((s) => s.setMultiSelect)

  const applyRemoteNodeMove = useCallback((message: NodeMoveMessage) => {
    setLocalNodes((nds) =>
      nds.map((n) =>
        n.id === message.processId
          ? { ...n, position: { x: message.x, y: message.y } }
          : n
      )
    )
  }, [])

  const { sendNodeMove, sendPresence, clientId, ownUserId } = useWorkflowSync(
    workflowId,
    applyRemoteNodeMove,
    onPresence,
    onGraphChange,
    onReconnect,
  )

  const onSyncReadyRef = useRef(onSyncReady)
  onSyncReadyRef.current = onSyncReady
  useEffect(() => {
    onSyncReadyRef.current?.({ sendPresence, clientId, ownUserId })
  }, [sendPresence, clientId, ownUserId])
  const setHoveredEdgeId = useCanvasInteractionStore((s) => s.setHoveredEdgeId)
  const [reactFlowInstance, setReactFlowInstance] = useState<ReactFlowInstance | null>(null)
  const selectedEditorAnnotationIdsRef = useRef<string[]>([])

  useCanvasViewHandles(reactFlowInstance, { onRevealReady, onCameraReady, onLayoutReady })

  useEffect(() => {
    if (reactFlowInstance) {
      // Fitting a small graph would zoom in up to the canvas maximum (300%), pushing the ports off screen; stop at 100%.
      onFitViewReady?.(() => reactFlowInstance.fitView({ padding: 0.15, maxZoom: 1, duration: motionStill() ? 0 : 300 }))
      onSelectAllReady?.(() => {
        setLocalNodes((nds) => {
          if (nds.every((n) => n.selected)) return nds
          return nds.map((n) => (n.selected ? n : { ...n, selected: true }))
        })
      })
      onDeleteReady?.({
        deleteSelection: async () => {
          const selectedNodes = reactFlowInstance.getNodes().filter((node) => node.selected)
          const selectedEdges = reactFlowInstance.getEdges().filter((edge) => edge.selected)
          await reactFlowInstance.deleteElements({ nodes: selectedNodes, edges: selectedEdges })
        },
        deleteNode: async (nodeId: string) => {
          await reactFlowInstance.deleteElements({ nodes: [{ id: nodeId }] })
        },
        deleteEdge: async (edgeId: string) => {
          await reactFlowInstance.deleteElements({ edges: [{ id: edgeId }] })
        },
      })
      onAnnotationSelectionReady?.(() => selectedEditorAnnotationIdsRef.current)
    }
  }, [reactFlowInstance, onDeleteReady, onFitViewReady, onSelectAllReady, onAnnotationSelectionReady])
  const [snapGuides, setSnapGuides] = useState<SnapGuide[]>([])
  const [nodesToUpdateInternals, setNodesToUpdateInternals] = useState<string[]>([])

  const handleEditAnnotationText = useCallback((annotationId: string) => {
    const annotation = annotations.find((item) => item.id === annotationId)
    if (!annotation) return
    const nextText = window.prompt('Edit annotation text', annotation.textContent ?? '')
    if (nextText == null) return
    void onUpdateAnnotation(annotationId, {
      textContent: nextText,
      version: annotation.version,
      versionNonce: annotation.versionNonce,
    })
  }, [annotations, onUpdateAnnotation])

  const handleEditorSelectionChange = useCallback(
    (snapshot: WorkspaceEditorSelectionSnapshot) => {
      const annotationIds = new Set(annotations.map((annotation) => annotation.annotationId))
      selectedEditorAnnotationIdsRef.current = snapshot.selectedIds
        .map((id) => String(id))
        .filter((id) => annotationIds.has(id))
      onEditorSelectionChange?.(snapshot)
    },
    [annotations, onEditorSelectionChange],
  )

  const [localNodes, setLocalNodes] = useState<RfNode[]>(() => [
    ...toRfProcessNodes(nodes, selectedNodeId),
  ])
  const [localEdges, setLocalEdges] = useState<RfEdge[]>(() => toRfEdges(edges, selectedEdgeId))

  // Keep a ref so snap calculation always reads the latest positions without deps churn
  const localNodesRef = useRef<RfNode[]>(localNodes)
  const serverPositionsRef = useRef(new Map(nodes.map((node) => [node.id, { ...node.position }])))
  // Track I/O counts per node to detect handle additions/removals
  const ioCountRef = useRef<Map<string, number>>(new Map())

  useEffect(() => {
    const changed: string[] = []
    for (const node of nodes) {
      const ioCount = node.inputs.length + node.outputs.length
      const prev = ioCountRef.current.get(node.id)
      if (prev !== undefined && prev !== ioCount) changed.push(node.id)
      ioCountRef.current.set(node.id, ioCount)
    }
    if (changed.length > 0) setNodesToUpdateInternals(changed)

    const previousServerPositions = serverPositionsRef.current
    serverPositionsRef.current = new Map(nodes.map((node) => [node.id, { ...node.position }]))
    setLocalNodes((prev) => {
      const next = toRfProcessNodes(nodes, selectedNodeId)
      // What React Flow keeps on a node (its measured size, dragging) comes over from the existing one, and a node that
      // comes out the same keeps its object, so React Flow's measurement of it stays (reuseUnchangedNodes).
      const updated = reuseUnchangedNodes(prev, next.map((n) => {
        const existing = prev.find((p) => p.id === n.id)
        const editingByUserId = editingPresence?.get(n.id) ?? null
        const nodeWithEditing: RfNode = { ...n, data: { ...n.data, editingByUserId } }
        if (existing) {
          return {
            ...existing,
            ...nodeWithEditing,
            selected: existing.selected,
            width: existing.width ?? n.width,
            // A height React Flow holds from a resize stays, but a step that gained ports grows to hold them.
            height: Math.max(existing.height ?? n.height, nodeDisplayHeight({ size: { height: 0 }, inputs: n.data.inputs, outputs: n.data.outputs })),
            position: canvasNodePosition(n.position, previousServerPositions.get(n.id), existing),
          }
        }
        return nodeWithEditing
      }))
      localNodesRef.current = updated
      return updated
    })
  }, [nodes, selectedNodeId, editingPresence])

  useEffect(() => {
    setLocalEdges(toRfEdges(edges, selectedEdgeId))
  }, [edges, selectedEdgeId])

  // settingTool's Shift lock (axisLock.ts): where each dragged process was picked up, and whether Shift is held now.
  const dragStartsRef = useRef<Map<string, { x: number; y: number }>>(new Map())
  const shiftRef = useRef(false)
  useEffect(() => {
    const track = (event: KeyboardEvent | PointerEvent) => { shiftRef.current = event.shiftKey }
    const release = () => { shiftRef.current = false }
    window.addEventListener('keydown', track, true)
    window.addEventListener('keyup', track, true)
    window.addEventListener('pointermove', track, true)
    window.addEventListener('blur', release)
    return () => {
      window.removeEventListener('keydown', track, true)
      window.removeEventListener('keyup', track, true)
      window.removeEventListener('pointermove', track, true)
      window.removeEventListener('blur', release)
    }
  }, [])

  const handleNodesChange = useCallback(
    (incoming: NodeChange[]) => {
      // With Shift held, a drag keeps to the axis it has moved further along, every dragged process alike.
      let changes = incoming
      if (shiftRef.current && dragStartsRef.current.size > 0) {
        const moving = incoming.flatMap((change) => (change.type === 'position' && change.position
          ? [{ id: change.id, position: change.position }] : []))
        const locked = lockDrag(dragStartsRef.current, moving)
        if (locked.size > 0) {
          changes = incoming.map((change) => (change.type === 'position' && locked.has(change.id)
            ? { ...change, position: locked.get(change.id)! } : change))
        }
      }
      setLocalNodes((nds) => {
        const updated = applyNodeChanges(changes, nds as unknown as Node[]) as unknown as RfNode[]
        localNodesRef.current = updated
        return updated
      })

      for (const change of changes) {
        if (change.type === 'position' && change.dragging === false && change.position) {
          onNodeDragEnd(change.id, change.position.x, change.position.y)
        }
        if (change.type === 'dimensions' && change.resizing === false && change.dimensions) {
          onNodeResize(change.id, change.dimensions.width, change.dimensions.height)
        }
      }
    },
    [onNodeDragEnd, onNodeResize]
  )

  const handleEdgesChange = useCallback((changes: EdgeChange[]) => {
    setLocalEdges((eds) => applyEdgeChanges(changes, eds as unknown as Edge[]) as unknown as RfEdge[])
  }, [])

  const handleNodeDrag = useCallback((_event: unknown, node: Node) => {
    if (!localNodesRef.current.some((n) => n.id === node.id)) return
    setSnapGuides(snapGuidesFor(node.id, node.position, localNodesRef.current))
    if (node.type === 'flowmatNode') {
      sendNodeMove(node.id, node.position.x, node.position.y)
    }
  }, [sendNodeMove])

  const handleNodeDragStart = useCallback((_event: unknown, _node: Node, dragged: Node[]) => {
    dragStartsRef.current = new Map(dragged.map((one) => [one.id, { x: one.position.x, y: one.position.y }]))
  }, [])

  const handleNodeDragStop = useCallback(() => {
    setSnapGuides([])
    dragStartsRef.current = new Map()
  }, [])

  const handleConnectStart: OnConnectStart = useCallback(
    (_event, { nodeId, handleId }) => {
      if (nodeId && handleId) {
        onConnectStart({ processId: nodeId, ioId: handleId, handleId })
      }
    },
    [onConnectStart]
  )

  const handleReconnect: OnReconnect = useCallback(
    (oldEdge, newConnection) => {
      setLocalEdges((eds) => reconnectEdge(oldEdge, newConnection, eds as unknown as Edge[]) as unknown as RfEdge[])
      const payload = connectionPayload(newConnection)
      if (onEdgeReconnect && payload) onEdgeReconnect(oldEdge.id, payload)
    },
    [onEdgeReconnect]
  )

  const handleIsValidConnection = useCallback((connection: Connection | Edge) => isNewConnection(connection, localEdges),
    [localEdges])

  const handleConnect = useCallback(
    (connection: Connection) => {
      const payload = connectionPayload(connection)
      if (payload) onConnectComplete(payload)
    },
    [onConnectComplete]
  )

  // Connection drag dropped on empty canvas → open the on-canvas node picker
  // (ported from tldraw's OnCanvasComponentPicker flow).
  const handleConnectEnd: OnConnectEnd = useCallback(
    (event, connectionState) => {
      if (connectionState.isValid) return
      const fromNode = connectionState.fromNode
      const fromHandle = connectionState.fromHandle
      if (!fromNode || !fromHandle || !reactFlowInstance) return

      const client =
        'clientX' in event
          ? { x: event.clientX, y: event.clientY }
          : { x: event.changedTouches[0].clientX, y: event.changedTouches[0].clientY }

      onConnectDropOnCanvas({
        fromProcessId: fromNode.id,
        fromHandleId: fromHandle.id ?? null,
        fromHandleType: fromHandle.type,
        flowPosition: reactFlowInstance.screenToFlowPosition(client),
        screenPosition: client,
      })
    },
    [onConnectDropOnCanvas, reactFlowInstance]
  )

  // Palette drag-to-create (ported from tldraw's useDragToCreate).
  const handleDragOver = useCallback((event: DragEvent) => {
    if (!event.dataTransfer.types.includes(PALETTE_DRAG_MIME)) return
    event.preventDefault()
    event.dataTransfer.dropEffect = 'move'
  }, [])

  const handleDrop = useCallback(
    (event: DragEvent) => {
      const tool = event.dataTransfer.getData(PALETTE_DRAG_MIME)
      if (!tool || !reactFlowInstance) return
      event.preventDefault()

      const position = reactFlowInstance.screenToFlowPosition({
        x: event.clientX,
        y: event.clientY,
      })
      onNodeDrop(tool as WorkflowPaletteTool, position)
    },
    [onNodeDrop, reactFlowInstance]
  )

  const handleEdgeMouseEnter = useCallback(
    (_event: MouseEvent, edge: Edge) => setHoveredEdgeId(edge.id),
    [setHoveredEdgeId]
  )

  const handleEdgeMouseLeave = useCallback(
    () => setHoveredEdgeId(null),
    [setHoveredEdgeId]
  )

  const handlePaneClick = useCallback(
    (event: MouseEvent) => {
      if (!reactFlowInstance) return
      const position = reactFlowInstance.screenToFlowPosition({
        x: event.clientX,
        y: event.clientY,
      })
      if (activeTool === 'annotation-shape') {
        void onCreateShapeAnnotation(position)
        return
      }
      if (isWorkflowEditorTool(activeTool)) {
        void onCreateEditorElement(activeTool, position)
        return
      }
      if (activeTool === 'annotation-text') {
        void onCreateTextAnnotation(position)
        return
      }
      onCanvasClick(position)
    },
    [activeTool, onCanvasClick, onCreateEditorElement, onCreateShapeAnnotation, onCreateTextAnnotation, reactFlowInstance]
  )

  const handleBeforeDelete = useCallback(
    async ({ nodes, edges }: { nodes: Node[]; edges: Edge[] }) => {
      if (!onBeforeDelete) return true
      return onBeforeDelete({
        nodeIds: nodes.map((node) => node.id),
        edgeIds: edges.map((edge) => edge.id),
      })
    },
    [onBeforeDelete]
  )

  const handleDelete = useCallback(
    async ({ nodes, edges }: { nodes: Node[]; edges: Edge[] }) => {
      if (!onDeleteElements) return
      await onDeleteElements({
        nodeIds: nodes.map((node) => node.id),
        edgeIds: edges.map((edge) => edge.id),
      })
    },
    [onDeleteElements]
  )

  const { freehandDraft, isFreehandDrawing, finishFreehandDrawing, handleFreehandPointerDown } =
    useFreehandDrawing({ activeTool, reactFlowInstance, sendPresence, onCompleteFreehand })

  // Reading marks every process and connection as part of what is read or receding (index.css "Canvas reading").
  // A run shown over the canvas adds each process's mark beside it ("run-mark run-<mark>"); a compared revision adds
  // what changed since it on the other corner ("change-mark change-<mark>") and on connections ("change-<mark>").
  const traced = usePlanTraceStore((s) => s.traced)
  const readingNodes = useMemo(() => (reading || runMarks || changeMarks || traced.size > 0
    ? localNodes.map((node) => ({ ...node, className: [processMarkClass(node.id, reading, runMarks, changeMarks),
      traced.has(node.id) ? 'is-traced' : ''].filter(Boolean).join(' ') }))
    : localNodes), [localNodes, reading, runMarks, changeMarks, traced])
  const readingEdges = useMemo(() => (reading || changeMarks
    ? localEdges.map((edge) => ({ ...edge, className: connectionMarkClass(edge.id, reading, changeMarks) }))
    : localEdges), [localEdges, reading, changeMarks])

  return (
    <div
      className="workspace-canvas-viewport workspace-canvas-viewport--editor-layer"
      data-preset={preset}
      style={{ width: '100%', height: '100%', cursor: drawingEnabled ? 'crosshair' : 'default' }}
      onMouseDown={(event) => {
        // A press anywhere but on a process ends the preview, as in archify.
        if (!(event.target as Element).closest('.react-flow__node')) intentAt(null)
        handleFreehandPointerDown(event)
      }}
      onFocus={(event) => {
        // The process itself took the focus, not a field inside it (renaming).
        const target = event.target as HTMLElement
        const id = target.classList.contains('react-flow__node') ? target.dataset.id : undefined
        if (!id || !processIds.has(id)) return
        intentRef.current.focused = id
        window.clearTimeout(intentRef.current.timer)
        onIntent?.(id, true)
      }}
      onKeyDown={(event) => {
        // Enter on a focused process opens it, as a click does (archify's "Press Enter for details").
        const target = event.target as HTMLElement
        const id = target.classList.contains('react-flow__node') ? target.dataset.id : undefined
        if (event.key !== 'Enter' || !id || !processIds.has(id)) return
        event.preventDefault()
        selectNode(id)
        onNodeSelect(id)
      }}
      onBlur={(event) => {
        const node = event.target as HTMLElement
        if (!node.classList.contains('react-flow__node') || intentRef.current.focused !== node.dataset.id) return
        intentRef.current.focused = null
        onIntent?.(intentRef.current.hovered, false)
      }}
      onMouseLeave={() => {
        if (isFreehandDrawing) {
          finishFreehandDrawing()
        }
      }}
    >
      <ReactFlow
        onInit={setReactFlowInstance}
        nodes={readingNodes as unknown as Node[]}
        edges={readingEdges as unknown as Edge[]}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
        onNodesChange={handleNodesChange}
        onEdgesChange={handleEdgesChange}
        onNodeClick={(_e, node) => {
          selectNode(node.id)
          onNodeSelect(node.id)
        }}
        onNodeMouseEnter={(event, node) => {
          // A fine pointer resting on a process, not one dragging or drawing; touch goes straight to the click.
          if (node.type !== 'flowmatNode' || event.buttons !== 0 || !finePointer()) return
          intentRef.current.hovered = node.id
          window.clearTimeout(intentRef.current.timer)
          intentRef.current.timer = window.setTimeout(() => {
            if (intentRef.current.hovered === node.id) onIntent?.(node.id, false)
          }, motionStill() ? 0 : INTENT_DELAY_MS)
        }}
        onNodeMouseLeave={(_event, node) => {
          if (intentRef.current.hovered !== node.id) return
          intentRef.current.hovered = null
          window.clearTimeout(intentRef.current.timer)
          onIntent?.(intentRef.current.focused, intentRef.current.focused !== null)
        }}
        onNodeDoubleClick={(_e, node) => {
          startInlineEdit(node.id)
        }}
        onEdgeDoubleClick={(_e, edge) => {
          selectEdge(edge.id)
          startInlineEditEdge(edge.id)
        }}
        onSelectionChange={({ nodes: selNodes }) => {
          if (selNodes.length > 1) setMultiSelect()
          onProcessSelectionChange?.(selNodes.filter((node) => node.type === 'flowmatNode').map((node) => node.id))
        }}
        onEdgeClick={(_e, edge) => {
          selectEdge(edge.id)
          onEdgeSelect(edge.id)
        }}
        onNodeDrag={handleNodeDrag as OnNodeDrag}
        onNodeDragStart={handleNodeDragStart as OnNodeDrag}
        onNodeDragStop={handleNodeDragStop}
        onConnectStart={handleConnectStart}
        onConnect={handleConnect}
        onReconnect={handleReconnect}
        onConnectEnd={handleConnectEnd}
        onPaneClick={handlePaneClick}
        onMouseMove={(e: React.MouseEvent) => {
          sendPresence({ type: 'CURSOR_MOVED', cursorX: e.clientX, cursorY: e.clientY })
        }}
        onDragOver={handleDragOver}
        onDrop={handleDrop}
        onEdgeMouseEnter={handleEdgeMouseEnter}
        onEdgeMouseLeave={handleEdgeMouseLeave}
        connectionLineComponent={CustomConnectionLine}
        colorMode={resolvedTheme}
        isValidConnection={handleIsValidConnection}
        onBeforeDelete={handleBeforeDelete}
        onDelete={(payload) => {
          void handleDelete({
            nodes: payload.nodes as unknown as Node[],
            edges: payload.edges as unknown as Edge[],
          })
        }}
        onlyRenderVisibleElements={localNodes.length > 50}
        {...(initialReading
          ? { fitView: true, fitViewOptions: { nodes: initialReading.map((id) => ({ id })), padding: 0.2, maxZoom: 1 } }
          : savedViewport
            ? { defaultViewport: savedViewport }
            : { fitView: true, fitViewOptions: { padding: 0.15, maxZoom: 1 } }
        )}
        minZoom={0.1}
        maxZoom={3}
        snapToGrid
        snapGrid={[8, 8]}
        deleteKeyCode={null}
        panOnDrag={activeTool !== 'annotation-freehand'}
        selectionOnDrag={activeTool !== 'annotation-freehand'}
        elementsSelectable={activeTool !== 'annotation-freehand'}
        nodesDraggable={activeTool !== 'annotation-freehand'}
      >
        <SnapGuideLayer guides={snapGuides} />
        <ViewportPortal>
          <WorkspaceEditorLayer
            workflowId={workflowId}
            annotations={annotations}
            activeTool={activeTool}
            editable={canEditAnnotations}
            onError={onEditorDocumentError}
            onSelectionChange={handleEditorSelectionChange}
            onCommandReady={onEditorCommandReady}
            onUpdateAnnotation={onUpdateAnnotation}
            onDeleteAnnotations={onDeleteAnnotations}
            onEditAnnotationText={handleEditAnnotationText}
          />
        </ViewportPortal>
        <AnnotationPreviewLayer
          localDraft={freehandDraft}
          remotePreviews={remoteAnnotationPreviews ?? new Map<string, AnnotationPreview>()}
        />
        <InternalsUpdater nodeIds={nodesToUpdateInternals} />
        <HandleBoundsRecovery nodes={readingNodes} />
        <ViewportPersister storageKey={storageKey} />
        <ExportController onReady={onExportReady} onSvgReady={onSvgExportReady} onShareCardReady={onShareCardExportReady}
          onMotionReady={onMotionExportReady} />
        <ZoomReporter onZoomChange={onZoomChange} />
        {/* Blueprint draws a drafting grid and Editorial a clean page; the others keep the dots. */}
        {preset === 'blueprint'
          ? <Background variant={BackgroundVariant.Lines} gap={24} lineWidth={1}
            color="color-mix(in srgb, var(--ax-frontend-stroke) 16%, transparent)" />
          : preset !== 'editorial' && <Background variant={BackgroundVariant.Dots} gap={20} size={1} color="var(--border)" />}
        {/* archify's Semantic Radar: the overview map opens from MAP (M) instead of always taking room. */}
        <SemanticRadar open={mapOpen} activeIds={radarActiveIds} onFocus={(processId) => onRadarFocus?.(processId)}
          onClose={() => onRadarClose?.()} />
      </ReactFlow>
    </div>
  )
}
