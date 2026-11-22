import {
  angleBetween,
  cloneEditorElement,
  createElementId,
  createLineElement,
  getAnchorPoint,
  expandGroupSelection,
  insertElement,
  mergeEditorDocumentsById,
  moveLineEndpoint,
  recomputeBoundLines,
  resizeBoxFromHandle,
  resizeElementWithinBox,
  resizeRotatedElementFromHandle,
  rotateElement,
  selectElements,
  toElementId,
  translateElement,
  updateElement,
  type AnchorSide,
  type Box2,
  type ElementBinding,
  type EditorDocument,
  type EditorElement,
  type ElementId,
  type LineEndpoint,
  type ResizeHandle,
  type Vec2,
} from '../../../lib/flowmat-editor'
import type { AlignDirection, DistributeAxis } from '../../../entities/canvas-annotation/model/annotationLayout'
import type { CanvasAnnotationViewModel } from '../../../entities/workflow/model/types'
import {
  splitWorkspaceEditorSelection as splitSelectedIds,
  type WorkspaceEditorSelectionCapabilities,
} from './workspaceEditorSelection'

/**
 * The drawing layer's documents (the workflow's editor document and its annotations shown as editor elements): what a
 * selection, a drag, a duplicate, a reorder or a style change makes of them, and the undo history of the editor document.
 * WorkspaceEditorLayer holds the state and draws; this works it out.
 */

/** How many editor document changes undo goes back. */
export const WORKSPACE_EDITOR_HISTORY_LIMIT = 100

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

/** The documents an interaction started from, and what it moves. */
export interface InteractionDocuments {
  backendDocument: EditorDocument
  annotationDocument: EditorDocument
  selectedIds: readonly ElementId[]
}

export interface WorkspaceEditorHistorySnapshot {
  document: EditorDocument
  selectedIds: readonly ElementId[]
}

export interface WorkspaceEditorHistoryState {
  past: readonly WorkspaceEditorHistorySnapshot[]
  future: readonly WorkspaceEditorHistorySnapshot[]
}

/** A press on the drawing layer being dragged: what it does and what it started from. */
export type EditorInteraction =
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

export function createBackendHistorySnapshot(
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

export function areEditorDocumentsEqual(a: EditorDocument, b: EditorDocument): boolean {
  return JSON.stringify(a) === JSON.stringify(b)
}

export function duplicateEditorElements(
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

export function reorderEditorElements(
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

export function patchEditorElement(element: EditorElement, patch: WorkspaceEditorStylePatch): EditorElement {
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

export function createDisplayDocument(
  annotationDocument: EditorDocument,
  backendDocument: EditorDocument,
  selectedIds: readonly ElementId[],
): EditorDocument {
  return selectElements(mergeEditorDocumentsById(annotationDocument, backendDocument), selectedIds)
}

export function createInteractionDocuments(
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

export function findElementInBase(base: InteractionDocuments, id: ElementId): EditorElement | undefined {
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

export function transformLayerDocuments(
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

export function expandWorkspaceSelection(
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

export function buildAnnotationZIndexPatch(
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

export function readElementRotations(document: EditorDocument, selectedIds: readonly ElementId[]): ReadonlyMap<ElementId, number> {
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

export function getSelectableElementIds(document: EditorDocument, editableIds: ReadonlySet<ElementId>): ElementId[] {
  return document.elements
    .filter((element) => editableIds.has(element.id) && !element.hidden && element.type !== 'group')
    .map((element) => element.id)
}

export function transformSelected(
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

export function toggleSelectedUnit(selectedIds: readonly ElementId[], unitIds: readonly ElementId[]): ElementId[] {
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

function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, value))
}

/** An interaction that drags the selection: moving, resizing, moving a line's end or rotating. */
export type DragInteraction = Extract<EditorInteraction, { type: 'move' | 'resize' | 'line-endpoint' | 'rotate' }>

/**
 * The documents a drag makes from the ones it started from, with the pointer at {@code point} ({@code snapped} on the
 * grid). Moving, resizing and moving a line's end follow the snapped point; rotating follows the pointer itself. A lone
 * rotated element resizes about its own axes. Lines bound to moved shapes follow them.
 */
export function draggedDocuments(interaction: DragInteraction, point: Vec2, snapped: Vec2) {
  const { base } = interaction
  switch (interaction.type) {
    case 'move': {
      const delta = { x: snapped.x - interaction.start.x, y: snapped.y - interaction.start.y }
      return transformLayerDocuments(base, (element) => translateElement(element, delta))
    }
    case 'resize': {
      const lone = base.selectedIds.length === 1 ? findElementInBase(base, base.selectedIds[0]) : undefined
      if (lone && lone.rotation !== 0) {
        return transformLayerDocuments(base, (element) =>
          resizeRotatedElementFromHandle(element, interaction.baseBounds, interaction.handle, snapped))
      }
      const nextBounds = resizeBoxFromHandle(interaction.baseBounds, interaction.handle, snapped)
      return transformLayerDocuments(base, (element) => resizeElementWithinBox(element, interaction.baseBounds, nextBounds))
    }
    case 'line-endpoint':
      return transformLayerDocuments(base, (element) =>
        element.type === 'line' ? moveLineEndpoint(element, interaction.endpoint, snapped) : element)
    case 'rotate': {
      const delta = angleBetween(interaction.center, point) - interaction.startAngle
      return transformLayerDocuments(base, (element) =>
        rotateElement(element, (interaction.initialRotations.get(element.id) ?? element.rotation) + delta))
    }
  }
}

/** The history with a snapshot taken before a change: the redo list is dropped, and only the newest are kept. */
export function recordHistory(history: WorkspaceEditorHistoryState, snapshot: WorkspaceEditorHistorySnapshot,
  limit = WORKSPACE_EDITOR_HISTORY_LIMIT): WorkspaceEditorHistoryState {
  return { past: [...history.past, snapshot].slice(-limit), future: [] }
}

/** Undoing: the last snapshot to go back to, and the history with the present ready to redo. None with nothing to undo. */
export function undoHistory(history: WorkspaceEditorHistoryState, present: WorkspaceEditorHistorySnapshot):
  { target: WorkspaceEditorHistorySnapshot; history: WorkspaceEditorHistoryState } | null {
  const target = history.past[history.past.length - 1]
  if (!target) return null
  return { target, history: { past: history.past.slice(0, -1), future: [present, ...history.future] } }
}

/** Redoing: the next snapshot, and the history with the present ready to undo again. None with nothing to redo. */
export function redoHistory(history: WorkspaceEditorHistoryState, present: WorkspaceEditorHistorySnapshot,
  limit = WORKSPACE_EDITOR_HISTORY_LIMIT):
  { target: WorkspaceEditorHistorySnapshot; history: WorkspaceEditorHistoryState } | null {
  const target = history.future[0]
  if (!target) return null
  return { target, history: { past: [...history.past, present].slice(-limit), future: history.future.slice(1) } }
}

/** How a connector drawn between two shapes looks: a purple line with an arrow at its end. */
const CONNECTOR_STYLE = {
  stroke: '#7c3aed', strokeWidth: 2, strokeStyle: 'solid', opacity: 1, startArrow: 'none', endArrow: 'arrow',
} as const

/**
 * The document with a connector from one shape's anchor to another's, bound to both so it follows them, on top of
 * everything, under the next free id. None from a shape to itself or when either shape is not in the document.
 */
export function addConnector(document: EditorDocument, start: ElementBinding, end: ElementBinding):
  { document: EditorDocument; id: ElementId } | null {
  if (start.elementId === end.elementId) return null
  const startElement = document.elements.find((element) => element.id === start.elementId)
  const endElement = document.elements.find((element) => element.id === end.elementId)
  if (!startElement || !endElement) return null
  const usedIds = new Set(document.elements.map((element) => element.id))
  let sequence = Math.max(1, document.nextElementSeq)
  while (usedIds.has(createElementId(sequence, 'shape'))) sequence += 1
  const id = createElementId(sequence, 'shape')
  const connector = createLineElement({
    id,
    start: getAnchorPoint(startElement, start.anchor),
    end: getAnchorPoint(endElement, end.anchor),
    order: Math.max(0, ...document.elements.map((element) => element.order)) + 1,
    style: { ...CONNECTOR_STYLE },
    startBinding: start,
    endBinding: end,
  })
  return { document: { ...insertElement(document, connector), nextElementSeq: sequence + 1 }, id }
}
