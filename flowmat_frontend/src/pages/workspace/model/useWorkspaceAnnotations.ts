import {
  useBatchCanvasAnnotationMutation,
  useCreateCanvasAnnotationMutation,
  useDeleteCanvasAnnotationMutation,
  usePatchCanvasAnnotationMutation,
  type PatchCanvasAnnotationInput,
} from '../../../entities/canvas-annotation/api/canvasAnnotationApi'
import type { CanvasAnnotationPoint } from '../../../entities/workflow/model/types'
import { normalizeFreehand } from './workspaceEditorElements'

type Point = { x: number; y: number }

/** The canvas annotations' commands: a shape or a note dropped where clicked, a freehand stroke, a change, a delete. */
export function useWorkspaceAnnotations(workflowId: string) {
  const createAnnotation = useCreateCanvasAnnotationMutation(workflowId)
  const patchAnnotation = usePatchCanvasAnnotationMutation(workflowId)
  const deleteAnnotation = useDeleteCanvasAnnotationMutation(workflowId)
  const batchAnnotationMutation = useBatchCanvasAnnotationMutation(workflowId)

  async function handleCreateShapeAnnotation(position: Point) {
    await createAnnotation.mutateAsync({
      annotationType: 'shape',
      shapeKind: 'rectangle',
      posX: Math.round(position.x - 80),
      posY: Math.round(position.y - 48),
      width: 160,
      height: 96,
      style: { stroke: 'var(--text-h)', strokeWidth: 2, fill: 'transparent' },
    })
  }

  async function handleCreateTextAnnotation(position: Point) {
    await createAnnotation.mutateAsync({
      annotationType: 'text',
      posX: Math.round(position.x - 110),
      posY: Math.round(position.y - 28),
      width: 220,
      height: 56,
      textContent: 'New note',
      style: { stroke: 'var(--text-h)', strokeWidth: 1, fill: 'rgba(255,255,255,0.72)' },
    })
  }

  async function handleCompleteFreehand(points: CanvasAnnotationPoint[]) {
    if (points.length < 2) return
    const normalized = normalizeFreehand(points)
    await createAnnotation.mutateAsync({
      annotationType: 'freehand',
      posX: normalized.posX,
      posY: normalized.posY,
      width: normalized.width,
      height: normalized.height,
      points: normalized.points,
      style: { stroke: 'var(--accent)', strokeWidth: 2 },
    })
  }

  async function handleUpdateAnnotation(annotationId: string, input: PatchCanvasAnnotationInput) {
    await patchAnnotation.mutateAsync({ annotationId, input })
  }

  async function handleDeleteAnnotations(annotationIds: string[]) {
    await Promise.all(annotationIds.map((annotationId) => deleteAnnotation.mutateAsync(annotationId)))
  }

  return {
    batchAnnotationMutation,
    handleCreateShapeAnnotation,
    handleCreateTextAnnotation,
    handleCompleteFreehand,
    handleUpdateAnnotation,
    handleDeleteAnnotations,
  }
}
