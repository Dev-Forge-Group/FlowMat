import { useCallback, useEffect, useMemo, useRef, useState, type DragEvent, type MouseEvent } from 'react'
import { toPng } from 'html-to-image'
import {
  ReactFlow,
  Background,
  BackgroundVariant,
  Controls,
  MiniMap,
  useViewport,
  getBezierPath,
  useUpdateNodeInternals,
  useOnViewportChange,
  reconnectEdge,
  ViewportPortal,
  getNodesBounds,
  getViewportForBounds,
  useReactFlow,
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
  type Viewport,
  type ConnectionLineComponentProps,
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
import { CanvasNode } from './CanvasNode'
import { CanvasEdge } from './CanvasEdge'
import { PALETTE_DRAG_MIME } from './canvasConstants'
import { canvasNodePosition } from '../model/canvasNodePosition'
import { useWorkspaceStore } from '../model/workspaceStore'
import { useCanvasInteractionStore } from '../model/canvasInteractionStore'
import { useTheme } from '../../../app/providers/ThemeProvider'
import {
  useWorkflowSync,
  type NodeMoveMessage,
  type PresenceMessage,
  type GraphChangeMessage,
} from '../../../entities/workflow/api/useWorkflowSync'
import {
  WorkspaceEditorLayer,
  type WorkspaceEditorCommandApi,
  type WorkspaceEditorSelectionSnapshot,
} from './WorkspaceEditorLayer'

const nodeTypes = { flowmatNode: CanvasNode }
const edgeTypes = { flowmatEdge: CanvasEdge }

const SNAP_THRESHOLD = 8 // flow-space pixels

interface SnapGuide {
  type: 'vertical' | 'horizontal'
  position: number
  start: number
  end: number
}

interface AnnotationPreview {
  points: { x: number; y: number }[]
  annotationType: string
}

function calculateSnapGuides(
  dragging: { x: number; y: number; w: number; h: number },
  others: Array<{ id: string; x: number; y: number; w: number; h: number }>
): SnapGuide[] {
  const dL = dragging.x
  const dC = dragging.x + dragging.w / 2
  const dR = dragging.x + dragging.w
  const dT = dragging.y
  const dM = dragging.y + dragging.h / 2
  const dB = dragging.y + dragging.h

  let bestX: { dist: number; guide: SnapGuide } | null = null
  let bestY: { dist: number; guide: SnapGuide } | null = null

  for (const o of others) {
    const oL = o.x
    const oC = o.x + o.w / 2
    const oR = o.x + o.w
    const oT = o.y
    const oM = o.y + o.h / 2
    const oB = o.y + o.h

    for (const dPt of [dL, dC, dR]) {
      for (const oPt of [oL, oC, oR]) {
        const dist = Math.abs(dPt - oPt)
        if (dist < SNAP_THRESHOLD && (!bestX || dist < bestX.dist)) {
          const allY = [dT, dB, oT, oB]
          bestX = {
            dist,
            guide: { type: 'vertical', position: oPt, start: Math.min(...allY), end: Math.max(...allY) },
          }
        }
      }
    }

    for (const dPt of [dT, dM, dB]) {
      for (const oPt of [oT, oM, oB]) {
        const dist = Math.abs(dPt - oPt)
        if (dist < SNAP_THRESHOLD && (!bestY || dist < bestY.dist)) {
          const allX = [dL, dR, oL, oR]
          bestY = {
            dist,
            guide: { type: 'horizontal', position: oPt, start: Math.min(...allX), end: Math.max(...allX) },
          }
        }
      }
    }
  }

  return [bestX?.guide, bestY?.guide].filter(Boolean) as SnapGuide[]
}

function CustomConnectionLine({
  fromX,
  fromY,
  toX,
  toY,
  fromPosition,
  toPosition,
  connectionStatus,
}: ConnectionLineComponentProps) {
  const [path] = getBezierPath({
    sourceX: fromX,
    sourceY: fromY,
    sourcePosition: fromPosition,
    targetX: toX,
    targetY: toY,
    targetPosition: toPosition,
  })

  const stroke =
    connectionStatus === 'valid'
      ? '#22c55e'
      : connectionStatus === 'invalid'
        ? '#ef4444'
        : '#94a3b8'

  return (
    <path
      d={path}
      fill="none"
      style={{ stroke, strokeWidth: 2, strokeDasharray: '6 3' }}
    />
  )
}

// Renders alignment guide lines in screen space (must be inside ReactFlow for context)
function SnapGuideLayer({ guides }: { guides: SnapGuide[] }) {
  const { x: vpX, y: vpY, zoom } = useViewport()
  if (guides.length === 0) return null

  const toSX = (fx: number) => fx * zoom + vpX
  const toSY = (fy: number) => fy * zoom + vpY

  return (
    <svg
      style={{
        position: 'absolute',
        inset: 0,
        width: '100%',
        height: '100%',
        pointerEvents: 'none',
        zIndex: 5,
        overflow: 'visible',
      }}
    >
      {guides.map((guide, i) =>
        guide.type === 'vertical' ? (
          <line
            key={i}
            x1={toSX(guide.position)}
            y1={toSY(guide.start - 20)}
            x2={toSX(guide.position)}
            y2={toSY(guide.end + 20)}
            stroke="var(--accent)"
            strokeWidth={1}
            strokeDasharray="4 3"
            opacity={0.85}
          />
        ) : (
          <line
            key={i}
            x1={toSX(guide.start - 20)}
            y1={toSY(guide.position)}
            x2={toSX(guide.end + 20)}
            y2={toSY(guide.position)}
            stroke="var(--accent)"
            strokeWidth={1}
            strokeDasharray="4 3"
            opacity={0.85}
          />
        )
      )}
    </svg>
  )
}

function buildOverlayPath(
  points: { x: number; y: number }[],
  toScreenX: (x: number) => number,
  toScreenY: (y: number) => number
) {
  if (points.length === 0) return ''
  return points
    .map((point, index) => `${index === 0 ? 'M' : 'L'} ${toScreenX(point.x)} ${toScreenY(point.y)}`)
    .join(' ')
}

function AnnotationPreviewLayer({
  localDraft,
  remotePreviews,
}: {
  localDraft: { x: number; y: number }[]
  remotePreviews: ReadonlyMap<string, AnnotationPreview>
}) {
  const { x: vpX, y: vpY, zoom } = useViewport()
  const toScreenX = useCallback((x: number) => x * zoom + vpX, [vpX, zoom])
  const toScreenY = useCallback((y: number) => y * zoom + vpY, [vpY, zoom])

  const remoteFreehands = [...remotePreviews.values()].filter(
    (preview) => preview.annotationType === 'freehand' && preview.points.length > 1
  )
  const localPath =
    localDraft.length > 1 ? buildOverlayPath(localDraft, toScreenX, toScreenY) : ''

  if (!localPath && remoteFreehands.length === 0) return null

  return (
    <svg
      style={{
        position: 'absolute',
        inset: 0,
        width: '100%',
        height: '100%',
        pointerEvents: 'none',
        zIndex: 7,
        overflow: 'visible',
      }}
    >
      {remoteFreehands.map((preview, index) => (
        <path
          key={`remote-${index}`}
          d={buildOverlayPath(preview.points, toScreenX, toScreenY)}
          fill="none"
          stroke="var(--accent)"
          strokeWidth={2}
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeDasharray="7 5"
          opacity={0.55}
        />
      ))}
      {localPath && (
        <path
          d={localPath}
          fill="none"
          stroke="var(--accent)"
          strokeWidth={2.5}
          strokeLinecap="round"
          strokeLinejoin="round"
          opacity={0.9}
        />
      )}
    </svg>
  )
}

function ExportController({ onReady }: { onReady?: (fn: (filename: string) => void) => void }) {
  const { getNodes } = useReactFlow()
  const getNodesRef = useRef(getNodes)
  getNodesRef.current = getNodes

  useEffect(() => {
    onReady?.((filename: string) => {
      const nodes = getNodesRef.current() as unknown as RfNode[]
      if (nodes.length === 0) return
      const bounds = getNodesBounds(nodes as unknown as Node[])
      const W = 2400
      const H = 1600
      const viewport = getViewportForBounds(bounds, W, H, 0.1, 2, 0.1)
      const el = document.querySelector('.react-flow__viewport') as HTMLElement | null
      if (!el) return
      // The picture is the workflow itself: reading highlights stay out of it.
      const frame = el.closest('.workspace-canvas-viewport')
      frame?.classList.add('reading-exporting')
      void toPng(el, {
        backgroundColor: '#f4f3f8',
        width: W,
        height: H,
        style: {
          width: `${W}px`,
          height: `${H}px`,
          transform: `translate(${viewport.x}px,${viewport.y}px) scale(${viewport.zoom})`,
          transformOrigin: 'top left',
        },
      }).then((dataUrl) => {
        const a = document.createElement('a')
        a.href = dataUrl
        a.download = `${filename}.png`
        a.click()
      }).finally(() => frame?.classList.remove('reading-exporting'))
    })
  }, [onReady])

  return null
}

function ViewportPersister({ storageKey }: { storageKey: string }) {
  useOnViewportChange({
    onEnd: (viewport: Viewport) => {
      localStorage.setItem(storageKey, JSON.stringify(viewport))
    },
  })
  return null
}

/** Calls updateNodeInternals for nodes whose handle count changed. Must be inside ReactFlow. */
function InternalsUpdater({ nodeIds }: { nodeIds: string[] }) {
  const updateNodeInternals = useUpdateNodeInternals()
  useEffect(() => {
    if (nodeIds.length > 0) updateNodeInternals(nodeIds)
  }, [nodeIds, updateNodeInternals])
  return null
}

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
  onAnnotationSelectionReady?(getSelectedAnnotationIds: () => string[]): void
  onExportReady?(fn: (filename: string) => void): void
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
  /** A way to bring one process into view, for the process finder. */
  onCenterReady?(fn: (processId: string) => void): void
}

type RfNode = {
  id: string
  type: 'flowmatNode'
  position: { x: number; y: number }
  width: number
  height: number
  selected: boolean
  draggable?: boolean
  dragging?: boolean
  data: CanvasNodeViewModel
}

type RfEdge = {
  id: string
  type: 'flowmatEdge'
  source: string
  target: string
  sourceHandle: string | null
  targetHandle: string | null
  selected: boolean
  data: CanvasEdgeViewModel
}

function toRfProcessNodes(nodes: CanvasNodeViewModel[], selectedId: string | null): RfNode[] {
  return nodes.map((n) => ({
    id: n.id,
    type: 'flowmatNode' as const,
    position: n.position,
    width: n.size.width,
    height: n.size.height,
    selected: n.id === selectedId,
    data: n,
  }))
}

function toRfEdges(edges: CanvasEdgeViewModel[], selectedId: string | null): RfEdge[] {
  return edges.map((e) => ({
    id: e.id,
    type: 'flowmatEdge' as const,
    source: e.source,
    target: e.target,
    sourceHandle: e.sourceHandle,
    targetHandle: e.targetHandle,
    selected: e.id === selectedId,
    data: e,
  }))
}

function loadSavedViewport(storageKey: string): Viewport | undefined {
  try {
    const raw = localStorage.getItem(storageKey)
    if (!raw) return undefined
    return JSON.parse(raw) as Viewport
  } catch {
    return undefined
  }
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
  onAnnotationSelectionReady,
  onExportReady,
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
  onCenterReady,
}: Props) {
  const storageKey = `flowmat-viewport-${workflowId}`
  const savedViewport = useMemo(() => loadSavedViewport(storageKey), [storageKey])
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

  useEffect(() => {
    if (!reactFlowInstance) return
    onCenterReady?.((processId) => {
      void reactFlowInstance.fitView({ nodes: [{ id: processId }], padding: 0.8, maxZoom: 1.25, duration: 300 })
    })
  }, [onCenterReady, reactFlowInstance])

  useEffect(() => {
    if (reactFlowInstance) {
      onFitViewReady?.(() => reactFlowInstance.fitView({ padding: 0.15, duration: 300 }))
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
  const [freehandDraft, setFreehandDraft] = useState<{ x: number; y: number }[]>([])
  const [isFreehandDrawing, setIsFreehandDrawing] = useState(false)
  const lastAnnotationPresenceSentAtRef = useRef(0)

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
      const updated = next.map((n) => {
        const existing = prev.find((p) => p.id === n.id)
        const editingByUserId = editingPresence?.get(n.id) ?? null
        const nodeWithEditing: RfNode = { ...n, data: { ...n.data, editingByUserId } }
        if (existing) {
          return {
            ...nodeWithEditing,
            selected: existing.selected,
            width: existing.width ?? n.width,
            height: existing.height ?? n.height,
            dragging: existing.dragging,
            position: canvasNodePosition(n.position, previousServerPositions.get(n.id), existing),
          }
        }
        return nodeWithEditing
      })
      localNodesRef.current = updated
      return updated
    })
  }, [nodes, selectedNodeId, editingPresence])

  useEffect(() => {
    setLocalEdges(toRfEdges(edges, selectedEdgeId))
  }, [edges, selectedEdgeId])

  const handleNodesChange = useCallback(
    (changes: NodeChange[]) => {
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
    const current = localNodesRef.current
    const dragging = current.find((n) => n.id === node.id)
    if (!dragging) return

    const others = current
      .filter((n) => n.id !== node.id)
      .map((n) => ({ id: n.id, x: n.position.x, y: n.position.y, w: n.width, h: n.height }))

    setSnapGuides(
      calculateSnapGuides(
        { x: node.position.x, y: node.position.y, w: dragging.width, h: dragging.height },
        others
      )
    )

    if (node.type === 'flowmatNode') {
      sendNodeMove(node.id, node.position.x, node.position.y)
    }
  }, [sendNodeMove])

  const handleNodeDragStop = useCallback(() => {
    setSnapGuides([])
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
      if (onEdgeReconnect && newConnection.source && newConnection.target) {
        onEdgeReconnect(oldEdge.id, {
          fromProcessId: newConnection.source,
          toProcessId: newConnection.target,
          fromIoId: newConnection.sourceHandle ?? null,
          toIoId: newConnection.targetHandle ?? null,
          sourceHandle: newConnection.sourceHandle ?? 'out-default',
          targetHandle: newConnection.targetHandle ?? 'in-default',
        })
      }
    },
    [onEdgeReconnect]
  )

  const handleIsValidConnection = useCallback(
    (connection: Connection | Edge) => {
      if (connection.source === connection.target) return false
      const sh = 'sourceHandle' in connection ? (connection.sourceHandle ?? null) : null
      const th = 'targetHandle' in connection ? (connection.targetHandle ?? null) : null
      return !localEdges.some(
        (e) =>
          e.source === connection.source &&
          e.target === connection.target &&
          e.sourceHandle === sh &&
          e.targetHandle === th
      )
    },
    [localEdges]
  )

  const handleConnect = useCallback(
    (connection: Connection) => {
      if (!connection.source || !connection.target) return
      onConnectComplete({
        fromProcessId: connection.source,
        toProcessId: connection.target,
        fromIoId: connection.sourceHandle ?? null,
        toIoId: connection.targetHandle ?? null,
        sourceHandle: connection.sourceHandle ?? 'out-default',
        targetHandle: connection.targetHandle ?? 'in-default',
      })
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

  const emitFreehandPresence = useCallback(
    (points: { x: number; y: number }[], inProgress: boolean) => {
      const now = Date.now()
      if (inProgress && now - lastAnnotationPresenceSentAtRef.current < 40) return
      lastAnnotationPresenceSentAtRef.current = now
      sendPresence({
        type: 'ANNOTATION_DRAWING',
        annotation: {
          annotationType: 'freehand',
          points: points.map((point) => [point.x, point.y]),
          inProgress,
        },
      })
    },
    [sendPresence]
  )

  const appendFreehandPoint = useCallback(
    (clientX: number, clientY: number) => {
      if (!reactFlowInstance) return
      const point = reactFlowInstance.screenToFlowPosition({ x: clientX, y: clientY })
      setFreehandDraft((prev) => {
        const last = prev[prev.length - 1]
        if (last && Math.hypot(last.x - point.x, last.y - point.y) < 2) {
          return prev
        }
        const next = [...prev, point]
        emitFreehandPresence(next, true)
        return next
      })
    },
    [emitFreehandPresence, reactFlowInstance]
  )

  const finishFreehandDrawing = useCallback(() => {
    if (!isFreehandDrawing) return
    setIsFreehandDrawing(false)
    setFreehandDraft((prev) => {
      if (prev.length > 1) {
        void onCompleteFreehand(prev)
      }
      emitFreehandPresence([], false)
      return []
    })
  }, [emitFreehandPresence, isFreehandDrawing, onCompleteFreehand])

  useEffect(() => {
    if (!isFreehandDrawing) return
    const handleWindowMove = (event: globalThis.MouseEvent) => {
      appendFreehandPoint(event.clientX, event.clientY)
    }
    const handleWindowUp = () => {
      finishFreehandDrawing()
    }

    window.addEventListener('mousemove', handleWindowMove)
    window.addEventListener('mouseup', handleWindowUp)

    return () => {
      window.removeEventListener('mousemove', handleWindowMove)
      window.removeEventListener('mouseup', handleWindowUp)
    }
  }, [appendFreehandPoint, finishFreehandDrawing, isFreehandDrawing])

  const handleFreehandPointerDown = useCallback(
    (event: MouseEvent<HTMLDivElement>) => {
      if (activeTool !== 'annotation-freehand' || event.button !== 0 || !reactFlowInstance) return
      const target = event.target as HTMLElement | null
      if (!target?.closest('.react-flow__pane')) return
      event.preventDefault()
      setIsFreehandDrawing(true)
      setFreehandDraft([])
      appendFreehandPoint(event.clientX, event.clientY)
    },
    [activeTool, appendFreehandPoint, reactFlowInstance]
  )

  // Reading marks every process and connection as part of what is read or receding (index.css "Canvas reading").
  const readingNodes = useMemo(() => (reading
    ? localNodes.map((node) => ({ ...node, className: reading.nodeIds.has(node.id)
      ? `reading-on reading-${reading.tone}${node.id === reading.origin ? ' reading-origin' : ''}` : 'reading-dim' }))
    : localNodes), [localNodes, reading])
  const readingEdges = useMemo(() => (reading
    ? localEdges.map((edge) => ({ ...edge, className: reading.edgeIds.has(edge.id) ? `reading-on reading-${reading.tone}` : 'reading-dim' }))
    : localEdges), [localEdges, reading])

  return (
    <div
      className="workspace-canvas-viewport workspace-canvas-viewport--editor-layer"
      style={{ width: '100%', height: '100%', cursor: drawingEnabled ? 'crosshair' : 'default' }}
      onMouseDown={handleFreehandPointerDown}
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
        onNodeDoubleClick={(_e, node) => {
          startInlineEdit(node.id)
        }}
        onEdgeDoubleClick={(_e, edge) => {
          selectEdge(edge.id)
          startInlineEditEdge(edge.id)
        }}
        onSelectionChange={({ nodes: selNodes }) => {
          if (selNodes.length > 1) setMultiSelect()
        }}
        onEdgeClick={(_e, edge) => {
          selectEdge(edge.id)
          onEdgeSelect(edge.id)
        }}
        onNodeDrag={handleNodeDrag as OnNodeDrag}
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
        {...(savedViewport
          ? { defaultViewport: savedViewport }
          : { fitView: true, fitViewOptions: { padding: 0.15 } }
        )}
        minZoom={0.1}
        maxZoom={2.5}
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
        <ViewportPersister storageKey={storageKey} />
        <ExportController onReady={onExportReady} />
        <Background variant={BackgroundVariant.Dots} gap={20} size={1} color="var(--border)" />
        <Controls />
        <MiniMap zoomable pannable nodeStrokeWidth={2} />
      </ReactFlow>
    </div>
  )
}
