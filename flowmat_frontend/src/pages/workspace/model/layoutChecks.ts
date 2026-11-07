/**
 * Where the drawn canvas is hard to read (archify's geometry gates, kept as warnings as its standard profile does): a
 * connection that passes over a process it does not join, and two connections that cross. Measured from what the canvas
 * actually drew, in canvas coordinates; never part of publishing.
 */
export interface LayoutBox { id: string; x: number; y: number; width: number; height: number }
export interface LayoutPoint { x: number; y: number }
export interface LayoutLine { id: string; from: string; to: string; points: readonly LayoutPoint[] }
export interface LayoutSnapshot { boxes: readonly LayoutBox[]; lines: readonly LayoutLine[] }

export type LayoutWarning =
  | { code: 'LINE_OVER_PROCESS'; connectionId: string; from: string; to: string; processId: string }
  | { code: 'LINES_CROSS'; connectionIds: [string, string]; ends: [[string, string], [string, string]]; at: LayoutPoint }

/** A line only grazing a process's border, as rounded drawing can, is not over it. */
const INSET = 1

export function layoutWarnings(snapshot: LayoutSnapshot): LayoutWarning[] {
  const warnings: LayoutWarning[] = []
  for (const line of snapshot.lines) {
    for (const box of snapshot.boxes) {
      if (box.id === line.from || box.id === line.to) continue
      if (passesOver(line.points, box)) {
        warnings.push({ code: 'LINE_OVER_PROCESS', connectionId: line.id, from: line.from, to: line.to, processId: box.id })
      }
    }
  }
  const lines = snapshot.lines.map((line) => ({ line, bounds: boundsOf(line.points) }))
  for (let first = 0; first < lines.length; first++) {
    for (let second = first + 1; second < lines.length; second++) {
      const a = lines[first]
      const b = lines[second]
      // Lines that share a process meet at it by design.
      if (a.line.from === b.line.from || a.line.from === b.line.to || a.line.to === b.line.from || a.line.to === b.line.to) continue
      if (!overlaps(a.bounds, b.bounds)) continue
      const at = crossing(a.line.points, b.line.points)
      if (at) {
        warnings.push({ code: 'LINES_CROSS', connectionIds: [a.line.id, b.line.id],
          ends: [[a.line.from, a.line.to], [b.line.from, b.line.to]], at })
      }
    }
  }
  return warnings
}

/** One sentence per warning, with processes by name. */
export function layoutWarningText(warning: LayoutWarning, nameOf: (processId: string) => string): string {
  if (warning.code === 'LINE_OVER_PROCESS') {
    return `The connection from "${nameOf(warning.from)}" to "${nameOf(warning.to)}" passes over "${nameOf(warning.processId)}".`
  }
  const [[a, b], [c, d]] = warning.ends
  return `The connections ${nameOf(a)} → ${nameOf(b)} and ${nameOf(c)} → ${nameOf(d)} cross.`
}

function passesOver(points: readonly LayoutPoint[], box: LayoutBox): boolean {
  const left = box.x + INSET
  const top = box.y + INSET
  const right = box.x + box.width - INSET
  const bottom = box.y + box.height - INSET
  if (right <= left || bottom <= top) return false
  for (let index = 1; index < points.length; index++) {
    if (segmentHitsRect(points[index - 1], points[index], left, top, right, bottom)) return true
  }
  return false
}

/** Liang–Barsky: whether any part of the segment lies inside the rectangle. */
function segmentHitsRect(a: LayoutPoint, b: LayoutPoint, left: number, top: number, right: number, bottom: number): boolean {
  const dx = b.x - a.x
  const dy = b.y - a.y
  let enter = 0
  let leave = 1
  const sides: [number, number][] = [[-dx, a.x - left], [dx, right - a.x], [-dy, a.y - top], [dy, bottom - a.y]]
  for (const [p, q] of sides) {
    if (p === 0) {
      if (q < 0) return false
      continue
    }
    const t = q / p
    if (p < 0) enter = Math.max(enter, t)
    else leave = Math.min(leave, t)
    if (enter > leave) return false
  }
  return true
}

/** The first point where the two lines properly cross: each passes from one side of the other to its other side. */
function crossing(first: readonly LayoutPoint[], second: readonly LayoutPoint[]): LayoutPoint | null {
  for (let i = 1; i < first.length; i++) {
    const a = first[i - 1]
    const b = first[i]
    const segment = boundsOf([a, b])
    for (let j = 1; j < second.length; j++) {
      const c = second[j - 1]
      const d = second[j]
      if (!overlaps(segment, boundsOf([c, d]))) continue
      const d1 = side(c, d, a)
      const d2 = side(c, d, b)
      const d3 = side(a, b, c)
      const d4 = side(a, b, d)
      if (d1 * d2 < 0 && d3 * d4 < 0) {
        const t = d1 / (d1 - d2)
        return { x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t }
      }
    }
  }
  return null
}

function side(a: LayoutPoint, b: LayoutPoint, point: LayoutPoint): number {
  return (b.x - a.x) * (point.y - a.y) - (b.y - a.y) * (point.x - a.x)
}

interface Bounds { left: number; top: number; right: number; bottom: number }

function boundsOf(points: readonly LayoutPoint[]): Bounds {
  return {
    left: Math.min(...points.map((point) => point.x)),
    top: Math.min(...points.map((point) => point.y)),
    right: Math.max(...points.map((point) => point.x)),
    bottom: Math.max(...points.map((point) => point.y)),
  }
}

function overlaps(a: Bounds, b: Bounds): boolean {
  return a.left <= b.right && b.left <= a.right && a.top <= b.bottom && b.top <= a.bottom
}
