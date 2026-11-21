import { Suspense, lazy, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import type {
  ConnectCompletePayload,
  ConnectStartPayload,
  WorkflowCanvasViewModel,
} from '../../../entities/workflow/model/types'
import { useWorkspaceStore } from '../model/workspaceStore'
import { useWorkflowCanvasActions } from '../model/useWorkflowCanvasActions'
import { WorkspaceChecks } from './WorkspaceChecks'
import { WorkspaceInspector } from './WorkspaceInspector'
import { WorkspaceTopbar } from './WorkspaceTopbar'
import { ReadingPanels, type ReadingPanel } from './ReadingPanels'
import { RemoteCursors } from './RemoteCursors'
import { useCanvasRequests } from '../model/useCanvasRequests'
import { useEditorDocumentActions } from '../model/useEditorDocumentActions'
import { useWorkspaceAnnotations } from '../model/useWorkspaceAnnotations'
import { useWorkspaceExport } from '../model/useWorkspaceExport'
import { workspaceRibbonTabs } from './workspaceRibbon'
import { useWorkspaceCollaboration } from '../model/useWorkspaceCollaboration'
import { useWorkspaceKeyboard } from '../model/useWorkspaceKeyboard'
import { useCanvasInteractionStore } from '../model/canvasInteractionStore'
import { useAutoLayout } from '../model/useAutoLayout'
import { useWorkflowsQuery } from '../../../entities/workflow/api/useWorkflowsQuery'
import { useUpdateWorkflowMutation } from '../../../entities/workflow/api/useUpdateWorkflowMutation'
import { useLocation, useNavigate } from 'react-router-dom'
import { NodePaletteSidebar } from './NodePaletteSidebar'
import { SemanticPassport, type PassportPosition } from './SemanticPassport'
import { RouteProbe } from './RouteProbe'
import { DiagramNav } from './DiagramNav'
import { ReadingReceipts } from './ReadingReceipts'
import './archifyReading.css'
import { StylePresetMenu, type CanvasPreset } from './StylePresetMenu'
import { ExportMenu } from './ExportMenu'
import { useGuidedViewsQuery } from '../../../entities/workflow/api/useGuidedViews'
import { inReadingOrder, type GuidedChapter } from '../model/guidedViews'
import { intentAnnouncement } from '../model/processRelations'
import type { LayoutSnapshot } from '../model/layoutChecks'
import { useRevisionChanges } from '../model/useRevisionChanges'
import { useCanvasReading } from '../model/useCanvasReading'
import { useRunOverlay } from '../model/useRunOverlay'
import { useMotion } from '../model/motion'
import type {
  WorkspaceEditorCommandApi,
  WorkspaceEditorSelectionSnapshot,
} from './WorkspaceEditorLayer'
import { preloadCanvasViewport, preloadNodePickerPopup } from './workspacePreload'
import { isWorkflowEditorTool } from '../../../entities/workflow/model/nodeCatalog'
import { Ribbon } from '../../../widgets/canvas-toolbar/ui/Ribbon'

const CanvasViewport = lazy(() =>
  preloadCanvasViewport().then((module) => ({ default: module.CanvasViewport }))
)
const NodePickerPopup = lazy(() =>
  preloadNodePickerPopup().then((module) => ({ default: module.NodePickerPopup }))
)

interface Props {
  canvas: WorkflowCanvasViewModel
  projectId: string
}

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

function CanvasFallback() {
  return <div className="workspace-loading">Loading canvas...</div>
}

export function WorkflowCanvasPage({ canvas, projectId: _projectId }: Props) {
  const selectedProcessId = useWorkspaceStore((s) => s.selectedProcessId)
  const selectedConnectionId = useWorkspaceStore((s) => s.selectedConnectionId)
  const selectNode = useWorkspaceStore((s) => s.selectNode)
  const selectEdge = useWorkspaceStore((s) => s.selectEdge)
  const clearSelection = useWorkspaceStore((s) => s.clearSelection)
  const inlineEditingNodeId = useWorkspaceStore((s) => s.inlineEditingNodeId)
  const panelWidths = useWorkspaceStore((s) => s.panelWidths)

  const [localPanelWidths, setLocalPanelWidths] = useState(panelWidths)

  const [activeRibbonTabId, setActiveRibbonTabId] = useState('home')
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
  const canEditAnnotations =
    canvas.workflow.currentUserRole === 'editor' || canvas.workflow.currentUserRole === 'owner'

  const {
    batchAnnotationMutation,
    handleCreateShapeAnnotation,
    handleCreateTextAnnotation,
    handleCompleteFreehand,
    handleUpdateAnnotation,
    handleDeleteAnnotations,
  } = useWorkspaceAnnotations(canvas.workflow.workflowId)

  const queryClient = useQueryClient()
  // Declared before collaboration, which holds back a rename's change while the name is being edited.
  const editingWorkflowNameRef = useRef(false)
  const {
    remoteCursors,
    editingPresence,
    remoteAnnotationPreviews,
    syncUserId,
    handlePresence,
    handleGraphChange,
    handleReconnect,
    handleSyncReady,
  } = useWorkspaceCollaboration({ canvas, queryClient, inlineEditingNodeId, selectedProcessId, editingWorkflowNameRef })

  const workflows = workflowsQuery.data ?? []
  const { applyLayout } = useAutoLayout({
    nodes: canvas.nodes,
    edges: canvas.edges,
    onBatchUpdate: batchUpdateNodePositions,
    onFitView: () => fitViewRef.current(),
  })

  const clearEditorSelection = useCallback(() => setEditorSelection(EMPTY_EDITOR_SELECTION), [])
  const { savedLabel, handleSaveEditorDocument, handleReloadEditorDocument, handleCreateEditorElement } =
    useEditorDocumentActions({ workflowId: canvas.workflow.workflowId, canEdit: canEditAnnotations, editorCommandApiRef,
      clearEditorSelection, setWorkspaceMessage, setActiveTool })

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
  // Reading the canvas (focus, upstream, downstream, route, find); view only, kept in the address hash.
  const revealRef = useRef<(processIds: string[]) => void>(() => {})
  const handleRevealReady = useCallback((fn: (processIds: string[]) => void) => { revealRef.current = fn }, [])
  const revealProcesses = useCallback((processIds: string[]) => revealRef.current(processIds), [])
  // The canvas as drawn, measured when the workflow is checked (layout warnings; never part of publishing).
  const layoutRef = useRef<(() => LayoutSnapshot) | null>(null)
  const handleLayoutReady = useCallback((fn: () => LayoutSnapshot) => { layoutRef.current = fn }, [])
  const measureLayout = useCallback(() => layoutRef.current?.() ?? null, [])
  // archify's guided views: the workflow's chapters, stored with it (ADR-037). Until they load, a `#view=` link waits.
  const guidedViews = useGuidedViewsQuery(canvas.workflow.workflowId)
  const chapters = useMemo((): GuidedChapter[] => (guidedViews.data ?? []).map((view) => ({ id: view.guidedViewId,
    label: view.label, note: view.note, focus: view.focusProcessIds, version: view.version })), [guidedViews.data])
  // The processes selected on the canvas, in reading order (top to bottom, then left to right), for a new chapter.
  const [selectedProcessIds, setSelectedProcessIds] = useState<string[]>([])
  const selectedInOrder = useMemo(() => inReadingOrder(canvas.nodes, selectedProcessIds), [canvas.nodes, selectedProcessIds])
  const handleProcessSelection = useCallback((ids: string[]) => {
    setSelectedProcessIds((current) => (current.length === ids.length && current.every((id, index) => id === ids[index])
      ? current : ids))
  }, [])
  const reading = useCanvasReading(canvas, revealProcesses, chapters)
  const readingRef = useRef(reading)
  readingRef.current = reading
  // A production run shown over the canvas (address `run=`): each process's mark from its latest step; view only.
  const canvasProcessIds = useMemo(() => new Set(canvas.nodes.map((node) => node.processId)), [canvas.nodes])
  const [panel, setPanel] = useState<ReadingPanel | null>(null)
  const runView = useRunOverlay(canvas.workflow.workflowId, reading.run.id, canvasProcessIds, panel === 'runs')
  const changesView = useRevisionChanges(canvas.workflow.workflowId, reading.changes.id, canvas)
  // archify's view controls: the semantic radar (overview map), the camera stepper and the zoom it shows.
  const [mapOpen, setMapOpen] = useState(false)
  // As in archify, the radar and the lens take turns: opening one closes the other, and the guide and a route close the radar.
  const toggleMap = useCallback(() => {
    if (!mapOpen) setPanel((current) => (current === 'lens' ? null : current))
    setMapOpen(!mapOpen)
  }, [mapOpen])
  // archify's visual style try-on: the canvas's look while this page is open, never saved; S cycles it.
  const [preset, setPreset] = useState<CanvasPreset>('classic')
  const [styleOpen, setStyleOpen] = useState(false)
  const closeStyle = useCallback(() => setStyleOpen(false), [])
  // archify's export menu (E) and what the last export said, shown for a moment as archify's toast is.
  const [exportOpen, setExportOpen] = useState(false)
  const closeExport = useCallback(() => setExportOpen(false), [])
  const {
    shareCard,
    exportPngRef,
    exportSvgRef,
    exportShareCardRef,
    exportMotionRef,
    exportStatus,
    motionAvailability,
    runExport,
    exportJson,
  } = useWorkspaceExport(canvas, reading.view)
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
    : selectedProcessId ?? (reading.state?.kind === 'focus' ? reading.state.processId : reading.pinned?.from ?? null)
  const passportNode = passportProcessId ? canvas.nodeMap[passportProcessId] ?? null : null
  // The radar marks the processes in hand; a process picked on it is read like one found with the finder.
  const focusedProcessId = reading.state?.kind === 'focus' ? reading.state.processId : null
  const radarActiveIds = useMemo(() => new Set([selectedProcessId, focusedProcessId].filter((id): id is string => Boolean(id))),
    [focusedProcessId, selectedProcessId])
  /** A process selected on the canvas's behalf: the editor elements' selection is let go first. */
  const selectProcess = useCallback((processId: string) => {
    editorCommandApiRef.current?.clearSelection()
    setEditorSelection(EMPTY_EDITOR_SELECTION)
    selectNode(processId)
  }, [selectNode])
  const focusFromRadar = useCallback((processId: string) => {
    selectProcess(processId)
    if (!readingRef.current.pick(processId)) readingRef.current.focus(processId)
  }, [selectProcess])
  /** A process chosen in the index or the finder: read, or named as a route's start (brought into view) or end. */
  const chooseProcess = (processId: string) => {
    selectProcess(processId)
    const naming = reading.picking?.from === null
    if (!reading.pick(processId)) reading.focus(processId)
    else if (naming) reading.reveal([processId])
  }
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
  const intentStatus = intentShown?.id && intentByKeyboard
    ? intentAnnouncement(reading.nameOf(intentShown.id), reading.relationsOf(intentShown.id)) : ''
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

  const { deleteApiRef, handleBeforeDelete, handleDeleteElements } = useCanvasRequests(canvasRef,
    { deleteConnection, deleteElements, deleteNode, duplicateNode, updateNode })
  useWorkspaceKeyboard({
    canEditAnnotations, activeTool, setActiveTool, editorCommandApiRef, editorSelection, setEditorSelection,
    emptySelection: EMPTY_EDITOR_SELECTION, clearSelection, setPanel, mapOpen, setMapOpen, toggleMap, setStyleOpen,
    setExportOpen, presenting, setPresenting, togglePresenting, setPreset, cameraRef, readingRef, selectedProcessId,
    selectedConnectionId, isEditorCommandContext, deleteApiRef, duplicateNode, selectAllRef, undo, redo,
  })

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

  async function handleNodePick(tool: Parameters<typeof createNodeFromTool>[0]) {
    if (!nodePicker) return
    closeNodePicker()
    if (nodePicker.kind === 'connect-drop') {
      await createNodeFromConnectionDrop(nodePicker.draft, tool, nodePicker.flowPosition)
    } else {
      await insertNodeOnEdge(nodePicker.edgeId, tool, nodePicker.flowPosition)
    }
  }

  const ribbonTabs = workspaceRibbonTabs({
    editorSelection, canEditAnnotations, editorCommandApiRef, canvas, batchAnnotationMutation, setWorkspaceMessage, activeTool,
    setActiveTool, addNode, isEditorCommandContext, undo, redo, past, future, applyLayout, exportJson, runExport,
    handleSaveEditorDocument, handleReloadEditorDocument, fitViewRef, selectAllRef, paletteDefinitions, remoteCursors,
    syncUserId, savedLabel, workflows, navigate,
  })

  return (
    <div className="workspace-layout" data-present={presenting ? 'true' : undefined}>
      <WorkspaceTopbar workflow={canvas.workflow} activeTool={activeTool} paletteDefinitions={paletteDefinitions}
        editingRef={editingWorkflowNameRef}
        onRename={(workflowName) => updateWorkflowMutation.mutateAsync({ workflowId: canvas.workflow.workflowId, workflowName })} />

      <Ribbon tabs={ribbonTabs} activeTabId={activeRibbonTabId} onTabChange={setActiveRibbonTabId} />

      <WorkspaceChecks canvas={canvas} editable={canEditAnnotations} measureLayout={measureLayout} nameOf={reading.nameOf}
        reveal={reading.reveal} />

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
                selectProcess(processId)
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
              onProcessSelectionChange={handleProcessSelection}
              onExportReady={(fn) => { exportPngRef.current = fn }}
              onSvgExportReady={(fn) => { exportSvgRef.current = fn }}
              onShareCardExportReady={(fn) => { exportShareCardRef.current = fn }}
              onMotionExportReady={(fn) => { exportMotionRef.current = fn }}
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
              preset={preset}
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
                if (reading.state?.kind === 'focus' || reading.state?.kind === 'relation') reading.clear()
              }}
              onFollow={(processId) => {
                // Walks the graph: the process at the other end is selected and brought into view.
                selectProcess(processId)
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
            indexOpen={panel === 'index'} onIndex={() => setPanel((open) => (open === 'index' ? null : 'index'))}
            viewsOpen={panel === 'views'} onViews={() => setPanel((open) => (open === 'views' ? null : 'views'))}
            onChanges={() => setPanel((open) => (open === 'changes' ? null : 'changes'))}
            onFind={() => setPanel('finder')} onGuide={() => {
              setMapOpen(false)
              setPanel('guide')
            }}
            zoom={zoom} onZoomOut={() => cameraRef.current?.zoomStep(-1)} onReset={() => cameraRef.current?.reset()}
            onZoomIn={() => cameraRef.current?.zoomStep(1)}
            preset={preset} styleOpen={styleOpen} onStyle={() => {
              setExportOpen(false)
              setStyleOpen((open) => !open)
            }}
            exportOpen={exportOpen} onExport={() => {
              setStyleOpen(false)
              setExportOpen((open) => !open)
            }}
            motion={{ still: motion.still, reduced: motion.reduced, onToggle: motion.toggle }}
            presenting={presenting} onPresent={togglePresenting} />
          {styleOpen && <StylePresetMenu preset={preset} onChoose={setPreset} onClose={closeStyle} />}
          {exportOpen && <ExportMenu shareCard={shareCard?.variant ?? null} motion={motionAvailability} onChoose={runExport}
            onClose={closeExport} />}
          <span className="export-status" role="status" aria-live="polite">{exportStatus}</span>
          <ReadingPanels panel={panel} onClose={() => setPanel(null)} canvas={canvas} reading={reading} runView={runView}
            changesView={changesView} chapters={chapters} selectedInOrder={selectedInOrder} canEdit={canEditAnnotations}
            currentProcessId={passportProcessId} previewId={intentShown?.id ?? null}
            onRename={(processId, name) => void updateNode({ processId, processName: name })}
            onChoose={chooseProcess}
            onGuideAction={(action) => {
              if (action === 'route') beginRoute()
              else if (action === 'present') {
                setPanel(null)
                setPresenting(true)
              } else if (action === 'map') {
                setPanel(null)
                setMapOpen(true)
              } else setPanel(action === 'find' ? 'finder' : action)
            }} />
          <RemoteCursors cursors={remoteCursors} selfId={syncUserId} containerRef={canvasContainerRef} />
        </main>

        <div className="panel-resize-handle" onMouseDown={makeResizeHandler('right')} />
        <aside className="workspace-panel workspace-panel--right" style={{ width: localPanelWidths.right }}>
          <WorkspaceInspector canvas={canvas} editorSelection={editorSelection} editorCommandsRef={editorCommandApiRef}
            onNodeSubmit={updateNode} onNodeDelete={(processId) => deleteApiRef.current.deleteNode(processId)}
            onPortCreate={createPort} onPortUpdate={updatePort} onPortDelete={deletePort}
            onConnectionSubmit={updateConnection} onConnectionDelete={(connectionId) => deleteApiRef.current.deleteEdge(connectionId)} />
        </aside>
      </div>
    </div>
  )
}





