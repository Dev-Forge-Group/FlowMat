import { useCallback, useEffect, useRef, useState, type MouseEvent } from 'react'
import type { ReactFlowInstance } from '@xyflow/react'
import type { CanvasAnnotationPoint } from '../../../entities/workflow/model/types'
import type { PresenceMessage } from '../../../entities/workflow/api/useWorkflowSync'
import type { WorkflowPaletteTool } from '../../../entities/workflow/model/nodeCatalog'

type Point = { x: number; y: number }

interface Options {
  activeTool: WorkflowPaletteTool
  reactFlowInstance: ReactFlowInstance | null
  sendPresence(message: Omit<PresenceMessage, 'userId' | 'clientId' | 'workflowId' | 'timestamp'>): void
  onCompleteFreehand(points: CanvasAnnotationPoint[]): Promise<void> | void
}

/** Below this many flow-space pixels from the last point, a move adds nothing to the stroke. */
const MIN_STEP = 2
/** How often, at most, collaborators are sent the stroke in progress. */
const PRESENCE_INTERVAL_MS = 40

/**
 * Freehand drawing on the canvas: a press on the empty pane with the freehand tool starts a stroke, the pointer adds
 * to it anywhere in the window, and letting go (or leaving the canvas) saves it if it has two points or more.
 * Collaborators see the stroke as it is drawn.
 */
export function useFreehandDrawing({ activeTool, reactFlowInstance, sendPresence, onCompleteFreehand }: Options) {
  const [freehandDraft, setFreehandDraft] = useState<Point[]>([])
  const [isFreehandDrawing, setIsFreehandDrawing] = useState(false)
  const lastPresenceSentAtRef = useRef(0)

  const emitFreehandPresence = useCallback((points: Point[], inProgress: boolean) => {
    const now = Date.now()
    if (inProgress && now - lastPresenceSentAtRef.current < PRESENCE_INTERVAL_MS) return
    lastPresenceSentAtRef.current = now
    sendPresence({
      type: 'ANNOTATION_DRAWING',
      annotation: { annotationType: 'freehand', points: points.map((point) => [point.x, point.y]), inProgress },
    })
  }, [sendPresence])

  const appendFreehandPoint = useCallback((clientX: number, clientY: number) => {
    if (!reactFlowInstance) return
    const point = reactFlowInstance.screenToFlowPosition({ x: clientX, y: clientY })
    setFreehandDraft((prev) => {
      const last = prev[prev.length - 1]
      if (last && Math.hypot(last.x - point.x, last.y - point.y) < MIN_STEP) return prev
      const next = [...prev, point]
      emitFreehandPresence(next, true)
      return next
    })
  }, [emitFreehandPresence, reactFlowInstance])

  const finishFreehandDrawing = useCallback(() => {
    if (!isFreehandDrawing) return
    setIsFreehandDrawing(false)
    setFreehandDraft((prev) => {
      if (prev.length > 1) void onCompleteFreehand(prev)
      emitFreehandPresence([], false)
      return []
    })
  }, [emitFreehandPresence, isFreehandDrawing, onCompleteFreehand])

  useEffect(() => {
    if (!isFreehandDrawing) return
    const handleWindowMove = (event: globalThis.MouseEvent) => appendFreehandPoint(event.clientX, event.clientY)
    const handleWindowUp = () => finishFreehandDrawing()
    window.addEventListener('mousemove', handleWindowMove)
    window.addEventListener('mouseup', handleWindowUp)
    return () => {
      window.removeEventListener('mousemove', handleWindowMove)
      window.removeEventListener('mouseup', handleWindowUp)
    }
  }, [appendFreehandPoint, finishFreehandDrawing, isFreehandDrawing])

  const handleFreehandPointerDown = useCallback((event: MouseEvent<HTMLDivElement>) => {
    if (activeTool !== 'annotation-freehand' || event.button !== 0 || !reactFlowInstance) return
    const target = event.target as HTMLElement | null
    if (!target?.closest('.react-flow__pane')) return
    event.preventDefault()
    setIsFreehandDrawing(true)
    setFreehandDraft([])
    appendFreehandPoint(event.clientX, event.clientY)
  }, [activeTool, appendFreehandPoint, reactFlowInstance])

  return { freehandDraft, isFreehandDrawing, finishFreehandDrawing, handleFreehandPointerDown }
}
