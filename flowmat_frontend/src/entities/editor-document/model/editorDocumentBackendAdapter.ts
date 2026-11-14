// FlowMat's backend editor-document format and its conversion to the editor model. It lives with FlowMat's API, not in
// the editor library: the library knows nothing of FlowMat's endpoints, versions or storage.
import {
  fromSerializedEditorDocument,
  toElementId,
  toSerializedEditorDocument,
  type AnchorSide,
  type Camera,
  type EditorDocument,
  type EditorElement,
  type ElementBinding,
  type LineStyle,
  type SerializedEditorDocument,
  type SerializedEditorElement,
  type ShapeStyle,
  type TextStyle,
} from '../../../lib/flowmat-editor'

export interface BackendEditorDocumentDto {
  schemaVersion: number
  camera: Camera
  nextElementSeq: number
  version: number
  versionNonce: number
  elements: BackendEditorElementDto[]
}

export interface BackendEditorElementDto {
  id: string
  type: EditorElement['type']
  x: number
  y: number
  width: number
  height: number
  rotation: number
  opacity: number
  parentId: string | null
  locked: boolean
  hidden: boolean
  order: number
  geometry: Record<string, unknown>
  style: unknown
  version: number
  versionNonce: number
}

export interface BackendEditorDocumentSaveInput {
  expectedVersion: number
  schemaVersion: number
  camera: Camera
  nextElementSeq: number
  elements: BackendEditorElementInput[]
}

export type BackendEditorElementInput = Omit<BackendEditorElementDto, 'version' | 'versionNonce'>

export function editorDocumentToBackendSaveInput(doc: EditorDocument): BackendEditorDocumentSaveInput {
  const serialized = toSerializedEditorDocument(doc)
  return {
    expectedVersion: doc.version ?? 0,
    schemaVersion: serialized.schemaVersion,
    camera: serialized.camera,
    nextElementSeq: serialized.nextElementSeq,
    elements: serialized.elements.map(serializedElementToBackendInput),
  }
}

export function backendDtoToEditorDocument(dto: BackendEditorDocumentDto): EditorDocument {
  const serialized: SerializedEditorDocument = {
    schemaVersion: dto.schemaVersion,
    selectedIds: [],
    camera: {
      x: readNumber(dto.camera.x),
      y: readNumber(dto.camera.y),
      zoom: readNumber(dto.camera.zoom),
    },
    nextElementSeq: dto.nextElementSeq,
    elements: dto.elements.map(backendElementToSerialized),
  }
  return {
    ...fromSerializedEditorDocument(serialized),
    version: dto.version,
  }
}

function serializedElementToBackendInput(element: SerializedEditorElement): BackendEditorElementInput {
  const common = {
    id: element.id,
    type: element.type,
    x: element.x,
    y: element.y,
    width: element.width,
    height: element.height,
    rotation: element.rotation,
    opacity: element.opacity,
    parentId: element.parentId,
    locked: element.locked,
    hidden: element.hidden,
    order: element.order,
  }

  switch (element.type) {
    case 'rectangle':
      return {
        ...common,
        geometry: { cornerRadius: element.cornerRadius },
        style: element.style,
      }
    case 'ellipse':
      return {
        ...common,
        geometry: {},
        style: element.style,
      }
    case 'polygon':
      return {
        ...common,
        geometry: { points: element.points, closed: element.closed },
        style: element.style,
      }
    case 'line':
      return {
        ...common,
        geometry: {
          start: element.start,
          end: element.end,
          startBinding: element.startBinding ?? null,
          endBinding: element.endBinding ?? null,
        },
        style: element.style,
      }
    case 'freehand':
      return {
        ...common,
        geometry: { points: element.points },
        style: element.style,
      }
    case 'text':
      return {
        ...common,
        geometry: { text: element.text },
        style: element.style,
      }
    case 'group':
      return {
        ...common,
        geometry: { childIds: element.childIds },
        style: {},
      }
  }
}

function backendElementToSerialized(element: BackendEditorElementDto): SerializedEditorElement {
  const geometry = asRecord(element.geometry)
  const common = {
    id: element.id,
    type: element.type,
    x: element.x,
    y: element.y,
    width: element.width,
    height: element.height,
    rotation: element.rotation,
    opacity: element.opacity,
    parentId: element.parentId,
    locked: element.locked,
    hidden: element.hidden,
    order: element.order,
  }

  switch (element.type) {
    case 'rectangle':
      return {
        ...common,
        type: 'rectangle',
        cornerRadius: readNumber(geometry.cornerRadius, 0),
        style: readShapeStyle(element.style),
      }
    case 'ellipse':
      return {
        ...common,
        type: 'ellipse',
        style: readShapeStyle(element.style),
      }
    case 'polygon':
      return {
        ...common,
        type: 'polygon',
        points: readVec2Array(geometry.points),
        closed: readBoolean(geometry.closed),
        style: readShapeStyle(element.style),
      }
    case 'line':
      return {
        ...common,
        type: 'line',
        start: readVec2(geometry.start),
        end: readVec2(geometry.end),
        style: readLineStyle(element.style),
        startBinding: readNullableBinding(geometry.startBinding),
        endBinding: readNullableBinding(geometry.endBinding),
      }
    case 'freehand':
      return {
        ...common,
        type: 'freehand',
        points: readVec2Array(geometry.points),
        style: readLineStyle(element.style),
      }
    case 'text':
      return {
        ...common,
        type: 'text',
        text: readString(geometry.text, ''),
        style: readTextStyle(element.style),
      }
    case 'group':
      return {
        ...common,
        type: 'group',
        childIds: readStringArray(geometry.childIds).map(toElementId),
      }
  }
}

function asRecord(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : {}
}

function readNumber(value: unknown, fallback?: number): number {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (fallback !== undefined) return fallback
  throw new Error('Expected finite number in backend editor document.')
}

function readBoolean(value: unknown): boolean {
  if (typeof value === 'boolean') return value
  throw new Error('Expected boolean in backend editor document.')
}

function readString(value: unknown, fallback?: string): string {
  if (typeof value === 'string') return value
  if (fallback !== undefined) return fallback
  throw new Error('Expected string in backend editor document.')
}

function readStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) {
    throw new Error('Expected string array in backend editor document.')
  }
  return value.map((item) => readString(item))
}

function readShapeStyle(value: unknown): ShapeStyle {
  return asRecord(value) as unknown as ShapeStyle
}

function readLineStyle(value: unknown): LineStyle {
  return asRecord(value) as unknown as LineStyle
}

function readTextStyle(value: unknown): TextStyle {
  return asRecord(value) as unknown as TextStyle
}

function readVec2(value: unknown) {
  const record = asRecord(value)
  return {
    x: readNumber(record.x),
    y: readNumber(record.y),
  }
}

function readNullableBinding(value: unknown): ElementBinding | null {
  if (value == null) return null
  const record = asRecord(value)
  return {
    elementId: toElementId(readString(record.elementId)),
    anchor: readAnchorSide(record.anchor),
  }
}

function readAnchorSide(value: unknown): AnchorSide {
  const text = readString(value)
  if (text === 'top' || text === 'right' || text === 'bottom' || text === 'left') return text
  throw new Error(`Unsupported anchor side: ${text}`)
}

function readVec2Array(value: unknown) {
  if (!Array.isArray(value)) {
    throw new Error('Expected point array in backend editor document.')
  }
  return value.map((point) => readVec2(point))
}
