import {
  createEllipseElement,
  createEmptyEditorDocument,
  createPolygonElement,
  createRectangleElement,
  createTextElement,
  toElementId,
  type EditorDocument,
  type EditorElement,
  type LineStyle,
  type PolygonElement,
  type ShapeStyle,
  type TextStyle,
} from '../../../lib/flowmat-editor'
import type { CanvasAnnotationViewModel } from '../../workflow/model/types'
import type {
  CreateCanvasAnnotationInput,
  PatchCanvasAnnotationInput,
} from '../api/canvasAnnotationApi'

export type AnnotationAdapterErrorCode =
  | 'unsupported_annotation'
  | 'unsupported_editor_element'
  | 'invalid_editor_element'

export type AnnotationAdapterResult<T> =
  | { ok: true; value: T }
  | { ok: false; code: AnnotationAdapterErrorCode; reason: string }

export function annotationsToEditorDocument(annotations: readonly CanvasAnnotationViewModel[]): EditorDocument {
  const elements = annotations
    .map((annotation, index) => annotationToEditorElement(annotation, index + 1))
    .filter((result): result is AnnotationAdapterResult<EditorElement> & { ok: true } => result.ok)
    .map((result) => result.value)

  return {
    ...createEmptyEditorDocument({ nextElementSeq: elements.length + 1 }),
    elements,
  }
}

export function annotationToEditorElement(
  annotation: CanvasAnnotationViewModel,
  order = 0,
): AnnotationAdapterResult<EditorElement> {
  const base = {
    id: toElementId(annotation.annotationId),
    x: annotation.position.x,
    y: annotation.position.y,
    width: annotation.size.width,
    height: annotation.size.height,
    rotation: annotation.rotation,
    opacity: readNumberStyle(annotation.style, 'opacity', 1),
    parentId: annotation.groupId ? toElementId(annotation.groupId) : null,
    locked: annotation.locked,
    hidden: false,
    order,
  }

  if (annotation.annotationType === 'shape') {
    if (annotation.shapeKind === 'rectangle') {
      return {
        ok: true,
        value: createRectangleElement({
          ...base,
          cornerRadius: readNumberStyle(annotation.style, 'cornerRadius', 0),
          style: readShapeStyle(annotation.style),
        }),
      }
    }

    if (annotation.shapeKind === 'ellipse') {
      return {
        ok: true,
        value: createEllipseElement({
          ...base,
          style: readShapeStyle(annotation.style),
        }),
      }
    }

    if (annotation.shapeKind === 'diamond') {
      return {
        ok: true,
        value: createPolygonElement({
          ...base,
          closed: true,
          points: diamondPoints(annotation.size.width, annotation.size.height),
          style: readShapeStyle(annotation.style),
        }),
      }
    }

    return {
      ok: false,
      code: 'unsupported_annotation',
      reason: `Unsupported annotation shapeKind: ${annotation.shapeKind}`,
    }
  }

  if (annotation.annotationType === 'freehand') {
    return {
      ok: true,
      value: {
        ...base,
        type: 'freehand',
        points: annotation.points.map((point) => ({ x: point.x, y: point.y })),
        style: readLineStyle(annotation.style),
      },
    }
  }

  if (annotation.annotationType === 'text') {
    return {
      ok: true,
      value: createTextElement({
        ...base,
        text: annotation.textContent ?? '',
        style: readTextStyle(annotation.style),
      }),
    }
  }

  return {
    ok: false,
    code: 'unsupported_annotation',
    reason: `Unsupported annotationType: ${annotation.annotationType}`,
  }
}

export function editorElementToCreateAnnotationInput(
  element: EditorElement,
): AnnotationAdapterResult<CreateCanvasAnnotationInput> {
  const common = {
    posX: Math.round(element.x),
    posY: Math.round(element.y),
    width: Math.round(element.width),
    height: Math.round(element.height),
    rotation: element.rotation,
    groupId: element.parentId,
  }

  switch (element.type) {
    case 'rectangle':
      return {
        ok: true,
        value: {
          ...common,
          annotationType: 'shape',
          shapeKind: 'rectangle',
          style: shapeStyleToAnnotationStyle(element.style, { cornerRadius: element.cornerRadius }),
        },
      }
    case 'ellipse':
      return {
        ok: true,
        value: {
          ...common,
          annotationType: 'shape',
          shapeKind: 'ellipse',
          style: shapeStyleToAnnotationStyle(element.style),
        },
      }
    case 'polygon':
      if (!isDiamondElement(element)) {
        return unsupportedElement(element, 'Legacy annotation API only supports diamond polygons.')
      }
      return {
        ok: true,
        value: {
          ...common,
          annotationType: 'shape',
          shapeKind: 'diamond',
          style: shapeStyleToAnnotationStyle(element.style),
        },
      }
    case 'freehand':
      return {
        ok: true,
        value: {
          ...common,
          annotationType: 'freehand',
          points: pointsToPayload(element.points),
          style: lineStyleToAnnotationStyle(element.style),
        },
      }
    case 'text':
      return {
        ok: true,
        value: {
          ...common,
          annotationType: 'text',
          textContent: element.text,
          style: textStyleToAnnotationStyle(element.style),
        },
      }
    case 'line':
    case 'group':
      return unsupportedElement(element, 'Legacy annotation API has no line or group annotation type.')
  }
}

export function editorElementToPatchAnnotationInput(
  element: EditorElement,
  version?: Pick<CanvasAnnotationViewModel, 'version' | 'versionNonce'>,
): AnnotationAdapterResult<PatchCanvasAnnotationInput> {
  const createResult = editorElementToCreateAnnotationInput(element)
  if (!createResult.ok) return createResult

  const { annotationType: _annotationType, shapeKind: _shapeKind, ...patchable } = createResult.value
  return {
    ok: true,
    value: {
      ...patchable,
      lockedYn: element.locked ? 'Y' : 'N',
      version: version?.version,
      versionNonce: version?.versionNonce,
    },
  }
}

function unsupportedElement<T extends EditorElement>(element: T, reason: string): AnnotationAdapterResult<never> {
  return {
    ok: false,
    code: 'unsupported_editor_element',
    reason: `${reason} elementType=${element.type}`,
  }
}

function readShapeStyle(style: Record<string, unknown>): ShapeStyle {
  return {
    fill: readStringStyle(style, 'fill', '#ffffff'),
    stroke: readStringStyle(style, 'stroke', '#111827'),
    strokeWidth: readNumberStyle(style, 'strokeWidth', 1),
    strokeStyle: readStrokeStyle(style),
    opacity: readNumberStyle(style, 'opacity', 1),
  }
}

function readLineStyle(style: Record<string, unknown>): LineStyle {
  return {
    stroke: readStringStyle(style, 'stroke', '#111827'),
    strokeWidth: readNumberStyle(style, 'strokeWidth', 1),
    strokeStyle: readStrokeStyle(style),
    opacity: readNumberStyle(style, 'opacity', 1),
    startArrow: 'none',
    endArrow: 'none',
  }
}

function readTextStyle(style: Record<string, unknown>): TextStyle {
  return {
    color: readStringStyle(style, 'color', '#111827'),
    fontFamily: readStringStyle(style, 'fontFamily', 'Inter, system-ui, sans-serif'),
    fontSize: readNumberStyle(style, 'fontSize', 14),
    fontWeight: readNumberStyle(style, 'fontWeight', 400),
    opacity: readNumberStyle(style, 'opacity', 1),
  }
}

function readStrokeStyle(style: Record<string, unknown>): 'solid' | 'dashed' | 'dotted' {
  const value = style.strokeStyle
  return value === 'dashed' || value === 'dotted' ? value : 'solid'
}

function readStringStyle(style: Record<string, unknown>, key: string, fallback: string): string {
  const value = style[key]
  return typeof value === 'string' && value.trim() !== '' ? value : fallback
}

function readNumberStyle(style: Record<string, unknown>, key: string, fallback: number): number {
  const value = style[key]
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

function shapeStyleToAnnotationStyle(style: ShapeStyle, extra: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    fill: style.fill,
    stroke: style.stroke,
    strokeWidth: style.strokeWidth,
    strokeStyle: style.strokeStyle,
    opacity: style.opacity,
    ...extra,
  }
}

function lineStyleToAnnotationStyle(style: LineStyle): Record<string, unknown> {
  return {
    stroke: style.stroke,
    strokeWidth: style.strokeWidth,
    strokeStyle: style.strokeStyle,
    opacity: style.opacity,
  }
}

function textStyleToAnnotationStyle(style: TextStyle): Record<string, unknown> {
  return {
    color: style.color,
    fontFamily: style.fontFamily,
    fontSize: style.fontSize,
    fontWeight: style.fontWeight,
    opacity: style.opacity,
  }
}

function pointsToPayload(points: readonly { x: number; y: number }[]): number[][] {
  return points.map((point) => [Math.round(point.x), Math.round(point.y)])
}

function diamondPoints(width: number, height: number) {
  return [
    { x: width / 2, y: 0 },
    { x: width, y: height / 2 },
    { x: width / 2, y: height },
    { x: 0, y: height / 2 },
  ]
}

function isDiamondElement(element: PolygonElement): boolean {
  if (!element.closed || element.points.length !== 4) return false
  const expected = diamondPoints(element.width, element.height)
  return element.points.every((point, index) => {
    const target = expected[index]
    return Math.abs(point.x - target.x) < 0.001 && Math.abs(point.y - target.y) < 0.001
  })
}
