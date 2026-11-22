import type { CanvasAnnotationPoint } from '../../../entities/workflow/model/types'
import type { WorkflowEditorTool } from '../../../entities/workflow/model/nodeCatalog'
import {
  createElementId,
  createEllipseElement,
  createLineElement,
  createRectangleElement,
  createTextElement,
  createTriangleElement,
  type EditorDocument,
  type EditorElement,
  type Vec2,
} from '../../../lib/flowmat-editor'

/** New editor elements placed on the workspace, freehand strokes made relative, and what a selection's style reads as. */

export const WORKSPACE_EDITOR_GRID_SIZE = 8

export function normalizeFreehand(points: CanvasAnnotationPoint[]) {
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

export function createWorkspaceEditorElement(
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

export function readEditorSelectionStyle(elements: readonly EditorElement[]) {
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

export function isShapeEditorElement(element: EditorElement) {
  return element.type === 'rectangle' || element.type === 'ellipse' || element.type === 'polygon'
}

export function hasGroupedEditorSelection(elements: readonly EditorElement[]) {
  return elements.some((element) => element.parentId != null)
}

export function editorElementLabel(element: EditorElement) {
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

export function toHexColor(value: string | undefined, fallback: string) {
  return value && /^#[0-9a-f]{6}$/i.test(value) ? value : fallback
}
