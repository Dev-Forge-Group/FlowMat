import { useCallback } from 'react'
import { getBezierPath, useViewport, type ConnectionLineComponentProps } from '@xyflow/react'
import type { SnapGuide } from '../model/snapGuides'

/** What is drawn over the canvas while something is being made: the line being connected, snap guides, freehand strokes. */

export interface AnnotationPreview {
  points: { x: number; y: number }[]
  annotationType: string
}

export function CustomConnectionLine({
  fromX,
  fromY,
  toX,
  toY,
  fromPosition,
  toPosition,
  connectionStatus,
}: ConnectionLineComponentProps) {
  const [path] = getBezierPath({
    sourceX: fromX,
    sourceY: fromY,
    sourcePosition: fromPosition,
    targetX: toX,
    targetY: toY,
    targetPosition: toPosition,
  })

  const stroke =
    connectionStatus === 'valid'
      ? '#22c55e'
      : connectionStatus === 'invalid'
        ? '#ef4444'
        : '#94a3b8'

  return (
    <path
      d={path}
      fill="none"
      style={{ stroke, strokeWidth: 2, strokeDasharray: '6 3' }}
    />
  )
}

// Renders alignment guide lines in screen space (must be inside ReactFlow for context)
export function SnapGuideLayer({ guides }: { guides: SnapGuide[] }) {
  const { x: vpX, y: vpY, zoom } = useViewport()
  if (guides.length === 0) return null

  const toSX = (fx: number) => fx * zoom + vpX
  const toSY = (fy: number) => fy * zoom + vpY

  return (
    <svg
      style={{
        position: 'absolute',
        inset: 0,
        width: '100%',
        height: '100%',
        pointerEvents: 'none',
        zIndex: 5,
        overflow: 'visible',
      }}
    >
      {guides.map((guide, i) =>
        guide.type === 'vertical' ? (
          <line
            key={i}
            x1={toSX(guide.position)}
            y1={toSY(guide.start - 20)}
            x2={toSX(guide.position)}
            y2={toSY(guide.end + 20)}
            stroke="var(--accent)"
            strokeWidth={1}
            strokeDasharray="4 3"
            opacity={0.85}
          />
        ) : (
          <line
            key={i}
            x1={toSX(guide.start - 20)}
            y1={toSY(guide.position)}
            x2={toSX(guide.end + 20)}
            y2={toSY(guide.position)}
            stroke="var(--accent)"
            strokeWidth={1}
            strokeDasharray="4 3"
            opacity={0.85}
          />
        )
      )}
    </svg>
  )
}

export function buildOverlayPath(
  points: { x: number; y: number }[],
  toScreenX: (x: number) => number,
  toScreenY: (y: number) => number
) {
  if (points.length === 0) return ''
  return points
    .map((point, index) => `${index === 0 ? 'M' : 'L'} ${toScreenX(point.x)} ${toScreenY(point.y)}`)
    .join(' ')
}

export function AnnotationPreviewLayer({
  localDraft,
  remotePreviews,
}: {
  localDraft: { x: number; y: number }[]
  remotePreviews: ReadonlyMap<string, AnnotationPreview>
}) {
  const { x: vpX, y: vpY, zoom } = useViewport()
  const toScreenX = useCallback((x: number) => x * zoom + vpX, [vpX, zoom])
  const toScreenY = useCallback((y: number) => y * zoom + vpY, [vpY, zoom])

  const remoteFreehands = [...remotePreviews.values()].filter(
    (preview) => preview.annotationType === 'freehand' && preview.points.length > 1
  )
  const localPath =
    localDraft.length > 1 ? buildOverlayPath(localDraft, toScreenX, toScreenY) : ''

  if (!localPath && remoteFreehands.length === 0) return null

  return (
    <svg
      style={{
        position: 'absolute',
        inset: 0,
        width: '100%',
        height: '100%',
        pointerEvents: 'none',
        zIndex: 7,
        overflow: 'visible',
      }}
    >
      {remoteFreehands.map((preview, index) => (
        <path
          key={`remote-${index}`}
          d={buildOverlayPath(preview.points, toScreenX, toScreenY)}
          fill="none"
          stroke="var(--accent)"
          strokeWidth={2}
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeDasharray="7 5"
          opacity={0.55}
        />
      ))}
      {localPath && (
        <path
          d={localPath}
          fill="none"
          stroke="var(--accent)"
          strokeWidth={2.5}
          strokeLinecap="round"
          strokeLinejoin="round"
          opacity={0.9}
        />
      )}
    </svg>
  )
}
