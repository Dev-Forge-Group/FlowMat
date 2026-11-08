import { useEffect, useMemo, useState, type PointerEvent as ReactPointerEvent, type ReactNode } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  BringToFront,
  Circle,
  Copy,
  Download,
  Group,
  Layers,
  Minus,
  MousePointer2,
  PenLine,
  Redo2,
  RotateCw,
  Save,
  SendToBack,
  Square,
  Trash2,
  Triangle,
  Type,
  Ungroup,
  Undo2,
} from 'lucide-react'
import { SvgEditorSurface } from '../../../lib/flowmat-editor/react'
import {
  angleBetween,
  boxCenter,
  cloneEditorElement,
  createElementId,
  createEmptyEditorDocument,
  createEllipseElement,
  createFreehandElement,
  createHistoryState,
  createLineElement,
  createRectangleElement,
  createRectangleFromDrag,
  createTextElement,
  createTriangleElement,
  deleteElements,
  deserializeEditorDocument,
  cleanupEmptyEditorGroups,
  expandGroupSelection,
  findElementsInBox,
  findTopmostElementIdAtPoint,
  groupEditorElements,
  getSelectedElementBounds,
  insertElement,
  moveLineEndpoint,
  normalizeBox,
  pushHistory,
  redoHistory,
  resizeBoxFromHandle,
  resizeElementWithinBox,
  rotateElement,
  selectElements,
  serializeEditorDocument,
  setSelection,
  snapPoint,
  toElementId,
  translateElement,
  ungroupEditorElements,
  undoHistory,
  updateElement,
  type Box2,
  type EditorDocument,
  type EditorElement,
  type ElementId,
  type HistoryState,
  type LineEndpoint,
  type ResizeHandle,
  type Vec2,
} from '../../../lib/flowmat-editor'
import { fetchEditorDocument, saveEditorDocument } from '../../../entities/editor-document/api/editorDocumentApi'

type DemoTool = 'select' | 'rectangle' | 'ellipse' | 'triangle' | 'line' | 'freehand' | 'text'

type InteractionState =
  | { type: 'draw'; tool: Exclude<DemoTool, 'select' | 'text'>; origin: Vec2; current: Vec2; points?: Vec2[] }
  | { type: 'move'; start: Vec2; baseDocument: EditorDocument; selectedIds: readonly ElementId[] }
  | { type: 'marquee'; origin: Vec2; current: Vec2 }
  | { type: 'resize'; handle: ResizeHandle; baseDocument: EditorDocument; baseBounds: Box2; selectedIds: readonly ElementId[] }
  | { type: 'line-endpoint'; endpoint: LineEndpoint; baseDocument: EditorDocument; selectedId: ElementId }
  | {
      type: 'rotate'
      center: Vec2
      startAngle: number
      baseDocument: EditorDocument
      selectedIds: readonly ElementId[]
      initialRotations: ReadonlyMap<ElementId, number>
    }

const GRID_SIZE = 16

const SHAPE_STYLE = {
  fill: '#ffffff',
  stroke: '#1f2937',
  strokeWidth: 1.5,
}

const PALETTE = {
  rectangle: { fill: '#dff7ef', stroke: '#0f766e' },
  ellipse: { fill: '#eef2ff', stroke: '#4338ca' },
  triangle: { fill: '#fff7ed', stroke: '#c2410c' },
  line: { stroke: '#7c3aed' },
  freehand: { stroke: '#be123c' },
  text: { color: '#111827' },
}

export function EditorDemoRoute() {
  const [searchParams] = useSearchParams()
  const workflowId = searchParams.get('workflowId')?.trim() || null
  const initialDocument = useMemo(() => createInitialDocument(), [])
  const [tool, setTool] = useState<DemoTool>('select')
  const [history, setHistory] = useState<HistoryState<EditorDocument>>(() => createHistoryState(initialDocument))
  const [interaction, setInteraction] = useState<InteractionState | null>(null)
  const [draftDocument, setDraftDocument] = useState<EditorDocument | null>(null)
  const [savedSnapshot, setSavedSnapshot] = useState(() => serializeEditorDocument(initialDocument))
  const [snapEnabled, setSnapEnabled] = useState(true)
  const [showLayers, setShowLayers] = useState(true)
  const [status, setStatus] = useState('Ready')

  const document = draftDocument ?? history.present
  const selectedIds = document.selectedIds
  const activeElement = selectedIds.length === 1 ? document.elements.find((element) => element.id === selectedIds[0]) ?? null : null
  const previewElement = useMemo(() => buildPreviewElement(interaction, document), [document, interaction])
  const marquee = interaction?.type === 'marquee' ? normalizeBoxFromPoints(interaction.origin, interaction.current) : null

  useEffect(() => {
    if (!workflowId) return
    let cancelled = false
    setStatus('Loading backend')
    void fetchEditorDocument(workflowId)
      .then((nextDocument) => {
        if (cancelled) return
        setHistory(createHistoryState(nextDocument))
        setSavedSnapshot(serializeEditorDocument(nextDocument))
        setStatus('Loaded backend')
      })
      .catch((error: unknown) => {
        if (cancelled) return
        setStatus(`Load failed: ${toStatusMessage(error)}`)
      })
    return () => {
      cancelled = true
    }
  }, [workflowId])

  function snap(point: Vec2) {
    return snapPoint(point, { enabled: snapEnabled, gridSize: GRID_SIZE })
  }

  function commitDocument(nextDocument: EditorDocument, nextStatus: string) {
    setDraftDocument(null)
    setHistory((current) => pushHistory(current, nextDocument))
    setStatus(nextStatus)
  }

  function clearInteraction() {
    setInteraction(null)
    setDraftDocument(null)
  }

  function nextId(doc: EditorDocument) {
    return createElementId(doc.nextElementSeq, 'shape')
  }

  function insertAndSelect(baseDocument: EditorDocument, element: EditorElement, nextStatus: string) {
    commitDocument(
      selectElements(
        {
          ...insertElement(baseDocument, element),
          nextElementSeq: baseDocument.nextElementSeq + 1,
        },
        [element.id],
      ),
      nextStatus,
    )
    setTool('select')
  }

  function handlePointerDown({ point, originalEvent }: { point: Vec2; originalEvent: ReactPointerEvent<SVGSVGElement> }) {
    originalEvent.currentTarget.setPointerCapture(originalEvent.pointerId)
    const action = getEditorAction(originalEvent)
    const snappedPoint = snap(point)

    if (action.kind === 'line-endpoint') {
      const targetId = getElementIdFromTarget(originalEvent.target)
      const target = targetId ? document.elements.find((element) => element.id === targetId) : null
      if (target?.type !== 'line' || target.rotation !== 0) return
      const nextDocument = setSelection(document, [target.id])
      setHistory((current) => ({ ...current, present: nextDocument }))
      setInteraction({
        type: 'line-endpoint',
        endpoint: action.endpoint,
        baseDocument: nextDocument,
        selectedId: target.id,
      })
      setStatus('Editing line')
      return
    }

    if (action.kind === 'resize' && document.selectedIds.length > 0) {
      const bounds = getSelectedElementBounds(document)
      if (!bounds) return
      setInteraction({
        type: 'resize',
        handle: action.handle,
        baseDocument: document,
        baseBounds: bounds,
        selectedIds: document.selectedIds,
      })
      setStatus('Resizing')
      return
    }

    if (action.kind === 'rotate' && document.selectedIds.length > 0) {
      const bounds = getSelectedElementBounds(document)
      if (!bounds) return
      const center = boxCenter(bounds)
      setInteraction({
        type: 'rotate',
        center,
        startAngle: angleBetween(center, point),
        baseDocument: document,
        selectedIds: document.selectedIds,
        initialRotations: new Map(
          document.elements
            .filter((element) => document.selectedIds.includes(element.id))
            .map((element) => [element.id, element.rotation]),
        ),
      })
      setStatus('Rotating')
      return
    }

    if (tool !== 'select' && tool !== 'text') {
      setInteraction({
        type: 'draw',
        tool,
        origin: snappedPoint,
        current: snappedPoint,
        points: tool === 'freehand' ? [snappedPoint] : undefined,
      })
      setStatus('Drawing')
      return
    }

    if (tool === 'text') {
      const text = window.prompt('Text', 'Text')
      if (!text || text.trim() === '') {
        setStatus('Ready')
        return
      }
      insertAndSelect(
        history.present,
        createTextElement({
          id: nextId(history.present),
          x: snappedPoint.x,
          y: snappedPoint.y,
          width: Math.max(120, text.length * 9),
          height: 32,
          order: history.present.elements.length + 1,
          text,
          style: { ...createTextStyle(), color: PALETTE.text.color },
        }),
        'Text',
      )
      return
    }

    const hitId = findTopmostElementIdAtPoint(document.elements, point)
    if (hitId) {
      const hitUnitIds = expandGroupSelection(document, [hitId])
      const nextDocument = originalEvent.shiftKey
        ? toggleSelectedUnit(document, hitUnitIds)
        : hitUnitIds.every((id) => document.selectedIds.includes(id))
          ? document
          : setSelection(document, hitUnitIds)
      setHistory((current) => ({ ...current, present: nextDocument }))
      setInteraction({
        type: 'move',
        start: snappedPoint,
        baseDocument: nextDocument,
        selectedIds: nextDocument.selectedIds,
      })
      setStatus('Moving')
      return
    }

    if (originalEvent.shiftKey) {
      setInteraction({ type: 'marquee', origin: point, current: point })
    } else {
      setHistory((current) => ({ ...current, present: setSelection(current.present, []) }))
      setInteraction({ type: 'marquee', origin: point, current: point })
    }
    setStatus('Selecting')
  }

  function handlePointerMove({ point, originalEvent }: { point: Vec2; originalEvent: ReactPointerEvent<SVGSVGElement> }) {
    const currentPoint = snap(point)
    if (!interaction) return

    if (interaction.type === 'draw') {
      setInteraction((current) => {
        if (!current || current.type !== 'draw') return current
        if (current.tool === 'freehand') {
          return { ...current, current: point, points: [...(current.points ?? []), point] }
        }
        return { ...current, current: currentPoint }
      })
      return
    }

    if (interaction.type === 'move') {
      const delta = { x: currentPoint.x - interaction.start.x, y: currentPoint.y - interaction.start.y }
      const selectedSet = new Set(interaction.selectedIds)
      setDraftDocument({
        ...interaction.baseDocument,
        elements: interaction.baseDocument.elements.map((element) =>
          selectedSet.has(element.id) ? translateElement(element, delta) : element,
        ),
      })
      return
    }

    if (interaction.type === 'marquee') {
      setInteraction({ ...interaction, current: point })
      const box = normalizeBoxFromPoints(interaction.origin, point)
      const hits = expandGroupSelection(history.present, findElementsInBox(history.present.elements, box))
      setDraftDocument(setSelection(history.present, hits))
      return
    }

    if (interaction.type === 'resize') {
      const nextBounds = resizeBoxFromHandle(interaction.baseBounds, interaction.handle, currentPoint)
      const selectedSet = new Set(interaction.selectedIds)
      setDraftDocument({
        ...interaction.baseDocument,
        elements: interaction.baseDocument.elements.map((element) =>
          selectedSet.has(element.id) ? resizeElementWithinBox(element, interaction.baseBounds, nextBounds) : element,
        ),
      })
      return
    }

    if (interaction.type === 'line-endpoint') {
      setDraftDocument({
        ...interaction.baseDocument,
        elements: interaction.baseDocument.elements.map((element) =>
          element.id === interaction.selectedId && element.type === 'line'
            ? moveLineEndpoint(element, interaction.endpoint, currentPoint)
            : element,
        ),
      })
      return
    }

    if (interaction.type === 'rotate') {
      const currentAngle = angleBetween(interaction.center, point)
      let delta = currentAngle - interaction.startAngle
      if (originalEvent.shiftKey) {
        delta = Math.round(delta / 15) * 15
      }
      const selectedSet = new Set(interaction.selectedIds)
      setDraftDocument({
        ...interaction.baseDocument,
        elements: interaction.baseDocument.elements.map((element) =>
          selectedSet.has(element.id)
            ? rotateElement(element, (interaction.initialRotations.get(element.id) ?? element.rotation) + delta)
            : element,
        ),
      })
    }
  }

  function handlePointerUp({ point, originalEvent }: { point: Vec2; originalEvent: ReactPointerEvent<SVGSVGElement> }) {
    if (originalEvent.currentTarget.hasPointerCapture(originalEvent.pointerId)) {
      originalEvent.currentTarget.releasePointerCapture(originalEvent.pointerId)
    }

    if (!interaction) return

    if (interaction.type === 'draw') {
      const element = buildElementFromDraw(interaction, history.present, snap(point))
      clearInteraction()
      if (element) {
        insertAndSelect(history.present, element, labelForTool(interaction.tool))
      } else {
        setStatus('Ready')
      }
      return
    }

    if (interaction.type === 'marquee') {
      if (draftDocument) setHistory((current) => ({ ...current, present: draftDocument }))
      clearInteraction()
      setStatus('Selected')
      return
    }

    if (
      interaction.type === 'move' ||
      interaction.type === 'resize' ||
      interaction.type === 'rotate' ||
      interaction.type === 'line-endpoint'
    ) {
      if (draftDocument) {
        commitDocument(
          draftDocument,
          interaction.type === 'move'
            ? 'Moved'
            : interaction.type === 'resize'
              ? 'Resized'
              : interaction.type === 'rotate'
                ? 'Rotated'
                : 'Line endpoint',
        )
      }
      clearInteraction()
    }
  }

  function handleUndo() {
    clearInteraction()
    setHistory((current) => undoHistory(current))
    setStatus('Undo')
  }

  function handleRedo() {
    clearInteraction()
    setHistory((current) => redoHistory(current))
    setStatus('Redo')
  }

  async function handleSave() {
    if (workflowId) {
      setStatus('Saving backend')
      try {
        const savedDocument = await saveEditorDocument(workflowId, document)
        setSavedSnapshot(serializeEditorDocument(savedDocument))
        setHistory((current) => ({ ...current, present: savedDocument }))
        setStatus('Saved backend')
      } catch (error) {
        setStatus(`Save failed: ${toStatusMessage(error)}`)
      }
      return
    }
    setSavedSnapshot(serializeEditorDocument(document))
    setStatus('Saved')
  }

  async function handleReload() {
    if (workflowId) {
      setStatus('Reloading backend')
      try {
        const nextDocument = await fetchEditorDocument(workflowId)
        clearInteraction()
        setHistory(createHistoryState(nextDocument))
        setSavedSnapshot(serializeEditorDocument(nextDocument))
        setStatus('Reloaded backend')
      } catch (error) {
        setStatus(`Reload failed: ${toStatusMessage(error)}`)
      }
      return
    }
    const nextDocument = deserializeEditorDocument(savedSnapshot)
    clearInteraction()
    setHistory(createHistoryState(nextDocument))
    setStatus('Reloaded')
  }

  function handleDelete() {
    if (document.selectedIds.length === 0) return
    commitDocument(cleanupEmptyEditorGroups(deleteElements(document, document.selectedIds)), 'Deleted')
  }

  function handleDuplicate() {
    if (document.selectedIds.length === 0) return
    let nextDocument = document
    const newIds: ElementId[] = []
    for (const element of document.elements.filter((item) => document.selectedIds.includes(item.id))) {
      const id = createElementId(nextDocument.nextElementSeq, 'shape')
      const clone = cloneEditorElement(element, id, nextDocument.elements.length + 1)
      nextDocument = {
        ...insertElement(nextDocument, clone),
        nextElementSeq: nextDocument.nextElementSeq + 1,
      }
      newIds.push(id)
    }
    commitDocument(setSelection(nextDocument, newIds), 'Duplicated')
  }

  function handleZOrder(direction: 'front' | 'back') {
    if (document.selectedIds.length === 0) return
    const selectedSet = new Set(document.selectedIds)
    const sorted = [...document.elements].sort((a, b) => a.order - b.order)
    const selected = sorted.filter((element) => selectedSet.has(element.id))
    const rest = sorted.filter((element) => !selectedSet.has(element.id))
    const nextOrder = direction === 'front' ? [...rest, ...selected] : [...selected, ...rest]
    commitDocument(
      {
        ...document,
        elements: nextOrder.map((element, index) => ({ ...element, order: index + 1 })),
      },
      direction === 'front' ? 'Front' : 'Back',
    )
  }

  function handleGroup() {
    const result = groupEditorElements(document, document.selectedIds)
    if (result.selectedIds.length < 2) return
    commitDocument(result.document, 'Grouped')
  }

  function handleUngroup() {
    const result = ungroupEditorElements(document, document.selectedIds)
    if (result.document === document) return
    commitDocument(result.document, 'Ungrouped')
  }

  function rotateSelected() {
    if (document.selectedIds.length === 0) return
    let nextDocument = document
    for (const id of document.selectedIds) {
      nextDocument = updateElement(nextDocument, id, (element) => rotateElement(element, element.rotation + 15))
    }
    commitDocument(nextDocument, 'Rotated')
  }

  return (
    <main className="editor-demo">
      <header className="editor-demo__toolbar">
        <div className="editor-demo__brand">
          <strong>FlowMat Editor</strong>
          <span>{status}</span>
        </div>
        <div className="editor-demo__controls" role="toolbar" aria-label="Editor controls">
          <ToolButton active={tool === 'select'} title="Select" onClick={() => setTool('select')} icon={<MousePointer2 size={17} />} label="Select" />
          <ToolButton active={tool === 'rectangle'} title="Rectangle" onClick={() => setTool('rectangle')} icon={<Square size={17} />} label="Rect" />
          <ToolButton active={tool === 'ellipse'} title="Ellipse" onClick={() => setTool('ellipse')} icon={<Circle size={17} />} label="Ellipse" />
          <ToolButton active={tool === 'triangle'} title="Triangle" onClick={() => setTool('triangle')} icon={<Triangle size={17} />} label="Triangle" />
          <ToolButton active={tool === 'line'} title="Line" onClick={() => setTool('line')} icon={<Minus size={17} />} label="Line" />
          <ToolButton active={tool === 'freehand'} title="Freehand" onClick={() => setTool('freehand')} icon={<PenLine size={17} />} label="Pen" />
          <ToolButton active={tool === 'text'} title="Text" onClick={() => setTool('text')} icon={<Type size={17} />} label="Text" />
        </div>
        <div className="editor-demo__controls" role="toolbar" aria-label="Document controls">
          <ToolButton title="Undo" onClick={handleUndo} disabled={history.past.length === 0} icon={<Undo2 size={17} />} label="Undo" />
          <ToolButton title="Redo" onClick={handleRedo} disabled={history.future.length === 0} icon={<Redo2 size={17} />} label="Redo" />
          <ToolButton title="Duplicate" onClick={handleDuplicate} disabled={selectedIds.length === 0} icon={<Copy size={17} />} label="Copy" />
          <ToolButton title="Delete" onClick={handleDelete} disabled={selectedIds.length === 0} icon={<Trash2 size={17} />} label="Delete" />
          <ToolButton title="Rotate" onClick={rotateSelected} disabled={selectedIds.length === 0} icon={<RotateCw size={17} />} label="Rotate" />
          <ToolButton title="Group" onClick={handleGroup} disabled={selectedIds.length < 2} icon={<Group size={17} />} label="Group" />
          <ToolButton title="Ungroup" onClick={handleUngroup} disabled={!hasGroupedSelection(document)} icon={<Ungroup size={17} />} label="Ungroup" />
          <ToolButton title="Bring to front" onClick={() => handleZOrder('front')} disabled={selectedIds.length === 0} icon={<BringToFront size={17} />} label="Front" />
          <ToolButton title="Send to back" onClick={() => handleZOrder('back')} disabled={selectedIds.length === 0} icon={<SendToBack size={17} />} label="Back" />
          <ToolButton title="Save" onClick={handleSave} icon={<Save size={17} />} label="Save" />
          <ToolButton title="Reload" onClick={handleReload} icon={<Download size={17} />} label="Reload" />
          <ToolButton active={snapEnabled} title="Snap to grid" onClick={() => setSnapEnabled((value) => !value)} icon={<Layers size={17} />} label="Snap" />
        </div>
      </header>
      <section className={showLayers ? 'editor-demo__workspace' : 'editor-demo__workspace editor-demo__workspace--canvas-only'}>
        <aside className="editor-demo__sidepanel">
          <div className="editor-demo__panel-header">
            <strong>Layers</strong>
            <button type="button" onClick={() => setShowLayers(false)} title="Hide layers">Hide</button>
          </div>
          <div className="editor-demo__layer-list">
            {[...document.elements]
              .sort((a, b) => b.order - a.order)
              .map((element) => (
                <button
                  type="button"
                  key={element.id}
                  className={selectedIds.includes(element.id) ? 'editor-demo__layer editor-demo__layer--active' : 'editor-demo__layer'}
                  onClick={() => setHistory((current) => ({ ...current, present: setSelection(current.present, [element.id]) }))}
                >
                  <span>{element.type}</span>
                  <small>{element.id}</small>
                </button>
              ))}
          </div>
        </aside>
        {!showLayers && (
          <button className="editor-demo__show-panel" type="button" onClick={() => setShowLayers(true)}>
            Layers
          </button>
        )}
        <div className="editor-demo__stage">
          <SvgEditorSurface
            document={document}
            previewElement={previewElement}
            marquee={marquee}
            onPointerDown={handlePointerDown}
            onPointerMove={handlePointerMove}
            onPointerUp={handlePointerUp}
          />
        </div>
        <aside className="editor-demo__sidepanel editor-demo__sidepanel--right">
          <div className="editor-demo__panel-header">
            <strong>Properties</strong>
          </div>
          {activeElement ? (
            <dl className="editor-demo__properties">
              <dt>Type</dt>
              <dd>{activeElement.type}</dd>
              <dt>X</dt>
              <dd>{Math.round(activeElement.x)}</dd>
              <dt>Y</dt>
              <dd>{Math.round(activeElement.y)}</dd>
              <dt>W</dt>
              <dd>{Math.round(activeElement.width)}</dd>
              <dt>H</dt>
              <dd>{Math.round(activeElement.height)}</dd>
              <dt>Rotation</dt>
              <dd>{Math.round(activeElement.rotation)} deg</dd>
              <dt>Order</dt>
              <dd>{activeElement.order}</dd>
            </dl>
          ) : (
            <p className="editor-demo__empty">Select one element.</p>
          )}
        </aside>
      </section>
      <footer className="editor-demo__status">
        <span>{document.elements.length} elements</span>
        <span>{document.selectedIds.length} selected</span>
        <span>tool {tool}</span>
        <span>snap {snapEnabled ? 'on' : 'off'}</span>
        <span>schema v{document.schemaVersion}</span>
      </footer>
    </main>
  )
}

function toStatusMessage(error: unknown): string {
  if (typeof error === 'object' && error !== null && 'message' in error) {
    const message = (error as { message?: unknown }).message
    if (typeof message === 'string' && message.trim().length > 0) return message
  }
  return 'Request failed'
}

function ToolButton({
  active,
  disabled,
  title,
  onClick,
  icon,
  label,
}: {
  active?: boolean
  disabled?: boolean
  title: string
  onClick(): void
  icon: ReactNode
  label: string
}) {
  return (
    <button
      type="button"
      className={active ? 'editor-demo__button editor-demo__button--active' : 'editor-demo__button'}
      onClick={onClick}
      disabled={disabled}
      title={title}
    >
      {icon}
      <span>{label}</span>
    </button>
  )
}

function buildPreviewElement(interaction: InteractionState | null, doc: EditorDocument): EditorElement | null {
  if (!interaction || interaction.type !== 'draw') return null
  return buildElementFromDraw(interaction, doc, interaction.current, true)
}

function buildElementFromDraw(
  interaction: Extract<InteractionState, { type: 'draw' }>,
  doc: EditorDocument,
  current: Vec2,
  preview = false,
): EditorElement | null {
  const id = preview ? toElementId('preview-shape') : createElementId(doc.nextElementSeq, 'shape')
  const order = preview ? Number.MAX_SAFE_INTEGER : doc.elements.length + 1
  const drag = { origin: interaction.origin, current }
  const box = normalizeBoxFromPoints(interaction.origin, current)

  if (interaction.tool === 'rectangle') {
    return createRectangleFromDrag({
      id,
      drag,
      order,
      minSize: preview ? 1 : 6,
      style: {
        ...SHAPE_STYLE,
        fill: preview ? 'rgba(15, 118, 110, 0.12)' : PALETTE.rectangle.fill,
        stroke: PALETTE.rectangle.stroke,
        strokeStyle: preview ? 'dashed' : 'solid',
      },
    })
  }

  if (interaction.tool === 'ellipse') {
    if (!preview && (box.width < 6 || box.height < 6)) return null
    return createEllipseElement({
      id,
      x: box.x,
      y: box.y,
      width: box.width,
      height: box.height,
      order,
      style: {
        ...SHAPE_STYLE,
        fill: preview ? 'rgba(67, 56, 202, 0.12)' : PALETTE.ellipse.fill,
        stroke: PALETTE.ellipse.stroke,
        strokeStyle: preview ? 'dashed' : 'solid',
      },
    })
  }

  if (interaction.tool === 'triangle') {
    if (!preview && (box.width < 6 || box.height < 6)) return null
    return createTriangleElement({
      id,
      x: box.x,
      y: box.y,
      width: box.width,
      height: box.height,
      order,
      style: {
        ...SHAPE_STYLE,
        fill: preview ? 'rgba(194, 65, 12, 0.12)' : PALETTE.triangle.fill,
        stroke: PALETTE.triangle.stroke,
        strokeStyle: preview ? 'dashed' : 'solid',
      },
    })
  }

  if (interaction.tool === 'line') {
    if (!preview && Math.hypot(current.x - interaction.origin.x, current.y - interaction.origin.y) < 6) return null
    return createLineElement({
      id,
      start: interaction.origin,
      end: current,
      order,
      style: {
        stroke: PALETTE.line.stroke,
        strokeWidth: 3,
        strokeStyle: preview ? 'dashed' : 'solid',
        opacity: 1,
        startArrow: 'none',
        endArrow: 'none',
      },
    })
  }

  const points = interaction.points ?? []
  if (!preview && points.length < 2) return null
  return createFreehandElement({
    id,
    points,
    order,
    style: {
      stroke: PALETTE.freehand.stroke,
      strokeWidth: 2.5,
      strokeStyle: 'solid',
      opacity: preview ? 0.7 : 1,
      startArrow: 'none',
      endArrow: 'none',
    },
  })
}

function normalizeBoxFromPoints(a: Vec2, b: Vec2): Box2 {
  return normalizeBox({ x: a.x, y: a.y, width: b.x - a.x, height: b.y - a.y })
}

function toggleSelectedUnit(doc: EditorDocument, unitIds: readonly ElementId[]): EditorDocument {
  const unitSet = new Set(unitIds)
  const hasFullUnit = unitIds.length > 0 && unitIds.every((id) => doc.selectedIds.includes(id))
  return hasFullUnit
    ? setSelection(doc, doc.selectedIds.filter((selectedId) => !unitSet.has(selectedId)))
    : setSelection(doc, [...doc.selectedIds, ...unitIds])
}

function hasGroupedSelection(doc: EditorDocument): boolean {
  return doc.selectedIds.some((id) =>
    doc.elements.some((element) => element.id === id && element.parentId != null),
  )
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

function getElementIdFromTarget(target: EventTarget | null): ElementId | null {
  if (!(target instanceof Element)) return null
  const element = target.closest('[data-element-id]')
  const id = element?.getAttribute('data-element-id')
  return id ? toElementId(id) : null
}

function labelForTool(tool: Exclude<DemoTool, 'select' | 'text'>): string {
  if (tool === 'freehand') return 'Freehand'
  return tool[0].toUpperCase() + tool.slice(1)
}

function createTextStyle() {
  return {
    color: '#111827',
    fontFamily: 'Inter, system-ui, sans-serif',
    fontSize: 18,
    fontWeight: 600,
    opacity: 1,
  }
}

function createInitialDocument(): EditorDocument {
  const base = createEmptyEditorDocument({ nextElementSeq: 5 })
  const rectangle = createRectangleElement({
    id: createElementId(1, 'shape'),
    x: 180,
    y: 180,
    width: 210,
    height: 130,
    order: 1,
    cornerRadius: 8,
    style: { ...SHAPE_STYLE, fill: PALETTE.rectangle.fill, stroke: PALETTE.rectangle.stroke },
  })
  const ellipse = createEllipseElement({
    id: createElementId(2, 'shape'),
    x: 470,
    y: 190,
    width: 180,
    height: 110,
    order: 2,
    style: { ...SHAPE_STYLE, fill: PALETTE.ellipse.fill, stroke: PALETTE.ellipse.stroke },
  })
  const triangle = createTriangleElement({
    id: createElementId(3, 'shape'),
    x: 730,
    y: 170,
    width: 170,
    height: 140,
    order: 3,
    style: { ...SHAPE_STYLE, fill: PALETTE.triangle.fill, stroke: PALETTE.triangle.stroke },
  })
  const line = createLineElement({
    id: createElementId(4, 'shape'),
    start: { x: 970, y: 220 },
    end: { x: 1160, y: 310 },
    order: 4,
    style: { stroke: PALETTE.line.stroke, strokeWidth: 3, strokeStyle: 'solid', opacity: 1, startArrow: 'none', endArrow: 'none' },
  })

  return {
    ...base,
    elements: [rectangle, ellipse, triangle, line],
  }
}
