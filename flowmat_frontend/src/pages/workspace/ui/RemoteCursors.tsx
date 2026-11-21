import type { RefObject } from 'react'

interface Props {
  cursors: ReadonlyMap<string, { x: number; y: number }>
  /** This browser's collaborator id, whose own cursor is not drawn. */
  selfId: string | null | undefined
  containerRef: RefObject<HTMLElement | null>
}

/** Collaborators' cursors over the canvas. Their coordinates are window-relative, so the canvas's rect is taken off. */
export function RemoteCursors({ cursors, selfId, containerRef }: Props) {
  const rect = containerRef.current?.getBoundingClientRect()
  if (!rect) return null
  return (
    <>
      {[...cursors.entries()]
        .filter(([uid]) => uid !== selfId)
        .map(([uid, cursor]) => (
          <div key={uid} className="remote-cursor" style={{ left: cursor.x - rect.left, top: cursor.y - rect.top }}>
            <div className="remote-cursor__dot" />
            <span className="remote-cursor__label">{uid.slice(0, 8)}</span>
          </div>
        ))}
    </>
  )
}
