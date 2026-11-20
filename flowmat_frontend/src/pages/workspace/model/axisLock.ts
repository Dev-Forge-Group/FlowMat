/**
 * settingTool's Shift lock while dragging (Qt/Utils/point_alignment.py; its behaviour, not its code): with Shift held, a
 * drag moves only along the axis it has moved further on, so a process slides straight across or straight down from
 * where it was picked up. Every process dragged together keeps the same move.
 */

export interface Point { x: number; y: number }

/** The move from where the drag began, kept to its longer axis. */
export function lockedMove(start: Point, proposed: Point): Point {
  const dx = proposed.x - start.x
  const dy = proposed.y - start.y
  return Math.abs(dx) >= Math.abs(dy) ? { x: dx, y: 0 } : { x: 0, y: dy }
}

/**
 * The positions of processes dragged together with Shift held: the move of the one that leads (the first given) is
 * kept to its longer axis, and every other process makes the same move from its own start. A process without a known
 * start is left where the drag put it.
 */
export function lockDrag(
  starts: ReadonlyMap<string, Point>,
  proposed: ReadonlyArray<{ id: string; position: Point }>,
): Map<string, Point> {
  const result = new Map<string, Point>()
  const lead = proposed.find((entry) => starts.has(entry.id))
  if (!lead) return result
  const move = lockedMove(starts.get(lead.id)!, lead.position)
  for (const entry of proposed) {
    const start = starts.get(entry.id)
    result.set(entry.id, start ? { x: start.x + move.x, y: start.y + move.y } : entry.position)
  }
  return result
}
