import { describe, expect, it } from 'vitest'
import {
  createEllipseElement,
  createEmptyEditorDocument,
  createLineElement,
  createRectangleElement,
  createTriangleElement,
  toElementId,
} from '../../../lib/flowmat-editor'
import {
  backendDtoToEditorDocument,
  editorDocumentToBackendSaveInput,
  editorElementChanges,
  isServerEditorDocument,
  type BackendEditorDocumentDto,
} from './editorDocumentBackendAdapter'

describe('editorDocumentBackendAdapter', () => {
  it('maps editor elements into backend geometry payloads', () => {
    const doc = {
      ...createEmptyEditorDocument({ nextElementSeq: 5 }),
      elements: [
        createRectangleElement({ id: toElementId('rect-1'), x: 0, y: 0, width: 80, height: 40, cornerRadius: 4 }),
        createTriangleElement({ id: toElementId('tri-1'), x: 100, y: 0, width: 80, height: 70 }),
        createLineElement({ id: toElementId('line-1'), start: { x: 200, y: 10 }, end: { x: 260, y: 60 } }),
      ],
    }

    const payload = editorDocumentToBackendSaveInput(doc)

    expect(payload.elements).toHaveLength(3)
    expect(payload.elements[0]).toMatchObject({
      id: 'rect-1',
      type: 'rectangle',
      geometry: { cornerRadius: 4 },
    })
    expect(payload.elements[1].geometry).toMatchObject({ closed: true })
    expect(payload.elements[2].geometry).toMatchObject({
      start: { x: 0, y: 0 },
      end: { x: 60, y: 50 },
    })
  })

  it('hydrates backend documents into validated editor documents', () => {
    const rectangle = createRectangleElement({ id: toElementId('rect-1'), x: 0, y: 0, width: 80, height: 40 })
    const ellipse = createEllipseElement({ id: toElementId('ellipse-1'), x: 100, y: 0, width: 80, height: 40 })
    const payload = editorDocumentToBackendSaveInput({
      ...createEmptyEditorDocument({ nextElementSeq: 3 }),
      elements: [rectangle, ellipse],
    })
    const dto: BackendEditorDocumentDto = {
      ...payload,
      version: 2,
      versionNonce: 123,
      elements: payload.elements.map((element, index) => ({
        ...element,
        version: index + 1,
        versionNonce: index + 10,
      })),
    }

    const doc = backendDtoToEditorDocument(dto)

    expect(doc.elements.map((element) => element.id)).toEqual(['rect-1', 'ellipse-1'])
    expect(doc.selectedIds).toEqual([])
    expect(doc.nextElementSeq).toBe(3)
  })

  it('round-trips connector bindings on a line element through the backend payload', () => {
    const line = createLineElement({
      id: toElementId('line-1'),
      start: { x: 0, y: 0 },
      end: { x: 80, y: 0 },
      startBinding: { elementId: toElementId('rect-1'), anchor: 'right' },
      endBinding: { elementId: toElementId('rect-2'), anchor: 'left' },
    })
    const payload = editorDocumentToBackendSaveInput({
      ...createEmptyEditorDocument({ nextElementSeq: 2 }),
      elements: [line],
    })

    expect(payload.elements[0].geometry).toMatchObject({
      startBinding: { elementId: 'rect-1', anchor: 'right' },
      endBinding: { elementId: 'rect-2', anchor: 'left' },
    })

    const dto: BackendEditorDocumentDto = {
      ...payload,
      version: 1,
      versionNonce: 1,
      elements: payload.elements.map((element) => ({ ...element, version: 1, versionNonce: 1 })),
    }
    const doc = backendDtoToEditorDocument(dto)
    const restored = doc.elements[0]

    expect(restored.type).toBe('line')
    if (restored.type === 'line') {
      expect(restored.startBinding).toEqual({ elementId: 'rect-1', anchor: 'right' })
      expect(restored.endBinding).toEqual({ elementId: 'rect-2', anchor: 'left' })
    }
  })

  it('hydrates a line with no bindings back to null (backward compatible with pre-fix saved data)', () => {
    const line = createLineElement({ id: toElementId('line-1'), start: { x: 0, y: 0 }, end: { x: 40, y: 0 } })
    const payload = editorDocumentToBackendSaveInput({
      ...createEmptyEditorDocument({ nextElementSeq: 2 }),
      elements: [line],
    })
    const dto: BackendEditorDocumentDto = {
      ...payload,
      version: 1,
      versionNonce: 1,
      elements: payload.elements.map((element) => ({ ...element, version: 1, versionNonce: 1 })),
    }

    const doc = backendDtoToEditorDocument(dto)
    const restored = doc.elements[0]

    expect(restored.type).toBe('line')
    if (restored.type === 'line') {
      expect(restored.startBinding).toBeNull()
      expect(restored.endBinding).toBeNull()
    }
  })
})

describe('element changes (ADR-042)', () => {
  const rect = (id: string, x: number) => createRectangleElement({ id: toElementId(id), x, y: 0, width: 80, height: 40 })
  /** What the server gives for these elements, each at its own version. */
  const server = (versions: Record<string, number>, ...elements: ReturnType<typeof rect>[]) => {
    const payload = editorDocumentToBackendSaveInput({ ...createEmptyEditorDocument({ nextElementSeq: 4 }), elements })
    return backendDtoToEditorDocument({ ...payload, version: 5, versionNonce: 1,
      elements: payload.elements.map((element) => ({ ...element, version: versions[element.id], versionNonce: 1 })) })
  }

  it('keeps each element\'s version beside a document the server gave, and tells it from a draft', () => {
    const base = server({ 'shape-1': 3, 'shape-2': 7 }, rect('shape-1', 0), rect('shape-2', 100))
    expect(base.elementVersions).toEqual({ 'shape-1': 3, 'shape-2': 7 })
    expect(isServerEditorDocument(base)).toBe(true)
    expect(isServerEditorDocument({ ...base })).toBe(false)
    expect(isServerEditorDocument(null)).toBe(false)
  })

  it('sends only what changed, each change with the version it was edited from', () => {
    const base = server({ 'shape-1': 3, 'shape-2': 7, 'shape-3': 2 }, rect('shape-1', 0), rect('shape-2', 100), rect('shape-3', 200))
    const next = { ...base, nextElementSeq: 6, elements: [base.elements[0], rect('shape-2', 150), rect('shape-5', 300)] }
    const { changes, nextElementSeq } = editorElementChanges(base, next)
    expect(changes.map((change) => ('deleteId' in change ? `delete ${change.deleteId}@${change.baseVersion}`
      : `${change.baseVersion === undefined ? 'add' : 'replace'} ${change.element.id}${change.baseVersion === undefined ? '' : `@${change.baseVersion}`}`)))
      .toEqual(['replace shape-2@7', 'add shape-5', 'delete shape-3@2'])
    expect(nextElementSeq).toBe(6)
  })

  it('has nothing to send for the same document', () => {
    const base = server({ 'shape-1': 1 }, rect('shape-1', 0))
    expect(editorElementChanges(base, { ...base, selectedIds: [toElementId('shape-1')] }).changes).toEqual([])
  })
})
