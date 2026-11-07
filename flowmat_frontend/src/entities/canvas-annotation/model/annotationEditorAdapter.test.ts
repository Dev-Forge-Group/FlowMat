import { describe, expect, it } from 'vitest'
import { createLineElement, createTriangleElement, toElementId } from '../../../lib/flowmat-editor'
import {
  annotationToEditorElement,
  annotationsToEditorDocument,
  editorElementToCreateAnnotationInput,
  editorElementToPatchAnnotationInput,
} from './annotationEditorAdapter'
import type { CanvasAnnotationViewModel } from '../../workflow/model/types'

describe('annotationEditorAdapter', () => {
  it('converts rectangle annotations to editor elements and create payloads', () => {
    const annotation = makeAnnotation({
      annotationId: 'ann_rect',
      annotationType: 'shape',
      shapeKind: 'rectangle',
      style: { fill: '#fff', stroke: '#111', strokeWidth: 2, cornerRadius: 6 },
    })

    const elementResult = annotationToEditorElement(annotation, 3)

    expect(elementResult.ok).toBe(true)
    if (!elementResult.ok) return
    expect(elementResult.value).toMatchObject({
      id: 'ann_rect',
      type: 'rectangle',
      x: 10,
      y: 20,
      width: 180,
      height: 88,
      order: 3,
      cornerRadius: 6,
    })

    const payload = editorElementToCreateAnnotationInput(elementResult.value)
    expect(payload).toEqual({
      ok: true,
      value: {
        annotationType: 'shape',
        shapeKind: 'rectangle',
        posX: 10,
        posY: 20,
        width: 180,
        height: 88,
        rotation: 0,
        groupId: null,
        style: {
          fill: '#fff',
          stroke: '#111',
          strokeWidth: 2,
          strokeStyle: 'solid',
          opacity: 1,
          cornerRadius: 6,
        },
      },
    })
  })

  it('converts diamond annotations to polygon elements and back to diamond payloads', () => {
    const annotation = makeAnnotation({
      annotationId: 'ann_diamond',
      annotationType: 'shape',
      shapeKind: 'diamond',
      size: { width: 120, height: 80 },
    })

    const elementResult = annotationToEditorElement(annotation)

    expect(elementResult.ok).toBe(true)
    if (!elementResult.ok) return
    expect(elementResult.value.type).toBe('polygon')

    const payload = editorElementToCreateAnnotationInput(elementResult.value)
    expect(payload.ok).toBe(true)
    if (!payload.ok) return
    expect(payload.value.annotationType).toBe('shape')
    expect(payload.value.shapeKind).toBe('diamond')
  })

  it('converts freehand annotations preserving local points', () => {
    const annotation = makeAnnotation({
      annotationId: 'ann_freehand',
      annotationType: 'freehand',
      shapeKind: null,
      points: [
        { x: 0, y: 0 },
        { x: 20, y: 10 },
      ],
      style: { stroke: '#f00', strokeWidth: 4 },
    })

    const elementResult = annotationToEditorElement(annotation)

    expect(elementResult.ok).toBe(true)
    if (!elementResult.ok) return
    expect(elementResult.value).toMatchObject({ type: 'freehand', points: annotation.points })

    const payload = editorElementToPatchAnnotationInput(elementResult.value, annotation)
    expect(payload).toEqual({
      ok: true,
      value: {
        posX: 10,
        posY: 20,
        width: 180,
        height: 88,
        rotation: 0,
        groupId: null,
        points: [
          [0, 0],
          [20, 10],
        ],
        style: {
          stroke: '#f00',
          strokeWidth: 4,
          strokeStyle: 'solid',
          opacity: 1,
        },
        lockedYn: 'N',
        version: 1,
        versionNonce: 99,
      },
    })
  })

  it('converts text annotations to editor text elements', () => {
    const annotation = makeAnnotation({
      annotationId: 'ann_text',
      annotationType: 'text',
      shapeKind: null,
      textContent: 'Hello',
      style: { color: '#333', fontSize: 20, fontWeight: 700 },
    })

    const elementResult = annotationToEditorElement(annotation)

    expect(elementResult.ok).toBe(true)
    if (!elementResult.ok) return
    expect(elementResult.value).toMatchObject({
      type: 'text',
      text: 'Hello',
      style: { color: '#333', fontSize: 20, fontWeight: 700 },
    })
  })

  it('keeps unsupported triangle and line out of the legacy annotation API', () => {
    const triangle = createTriangleElement({ id: toElementId('triangle'), x: 0, y: 0, width: 80, height: 60 })
    const line = createLineElement({ id: toElementId('line'), start: { x: 0, y: 0 }, end: { x: 80, y: 80 } })

    expect(editorElementToCreateAnnotationInput(triangle)).toMatchObject({
      ok: false,
      code: 'unsupported_editor_element',
    })
    expect(editorElementToCreateAnnotationInput(line)).toMatchObject({
      ok: false,
      code: 'unsupported_editor_element',
    })
  })

  it('builds editor documents from supported annotations only', () => {
    const doc = annotationsToEditorDocument([
      makeAnnotation({ annotationId: 'ann_rect', annotationType: 'shape', shapeKind: 'rectangle' }),
      makeAnnotation({ annotationId: 'ann_bad', annotationType: 'shape', shapeKind: null }),
    ])

    expect(doc.elements.map((element) => element.id)).toEqual(['ann_rect'])
    expect(doc.nextElementSeq).toBe(2)
  })
})

function makeAnnotation(overrides: Partial<CanvasAnnotationViewModel>): CanvasAnnotationViewModel {
  return {
    id: overrides.annotationId ?? 'ann_1',
    annotationId: overrides.annotationId ?? 'ann_1',
    workflowId: 'wf_1',
    projectId: 'prj_1',
    annotationType: 'shape',
    shapeKind: 'rectangle',
    position: { x: 10, y: 20 },
    size: { width: 180, height: 88 },
    rotation: 0,
    points: [],
    textContent: null,
    style: {},
    zIndex: 'a0',
    groupId: null,
    locked: false,
    version: 1,
    versionNonce: 99,
    ...overrides,
  }
}
