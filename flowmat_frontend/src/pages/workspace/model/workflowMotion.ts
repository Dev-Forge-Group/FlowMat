/**
 * archify's six-second motion export (adapted from archify viewer/export.js, Apache-2.0): a short WebM of the workflow in
 * which a signal runs along every connection in the order the flow goes, and each process glows as a signal reaches it.
 *
 * As in archify, the picture is drawn once from the vector export and the signals are drawn over it on every frame, along
 * the paths the canvas drew: a CSS animation drawn through an image does not move. Unlike archify, whose diagrams number
 * their steps, the order comes from the workflow itself: a connection starts when the flow has reached its source, and
 * a process no connection touches stays as drawn.
 */

export const MOTION = {
  /** The clip's length. */
  seconds: 6,
  fps: 30,
  /** The clip is at most this wide, as archify's is; a smaller workflow keeps its own size. */
  maxWidth: 1280,
  /** How long a signal takes along one connection. */
  travel: 1.75,
  /** How long a process glows when a signal reaches it. */
  pulse: 1.2,
  /**
   * From one depth of the flow to the next: a signal leaves a process as the one before reaches it, like a relay. In a
   * workflow too deep for the clip the step shortens, so the signals overlap but the flow still ends inside six seconds.
   */
  step: 1.75,
  /** The still moment after the last signal before the flow runs again. */
  settle: 0.25,
  bitsPerSecond: 6_000_000,
  /** The signal's colour: the route's sky, which reads on the grey lines in both themes. */
  colour: '#0ea5e9',
} as const

export interface Point { x: number; y: number }
export interface MotionEdgeInput { id: string; source: string; target: string; points: readonly Point[] }
export interface MotionNodeInput { id: string; x: number; y: number; width: number; height: number }

export interface MotionEdge extends MotionEdgeInput { delay: number }
export interface MotionNode extends MotionNodeInput { delay: number }

/** Everything a frame draws, in canvas coordinates, and how long one run of the flow takes before it starts again. */
export interface MotionScene {
  edges: MotionEdge[]
  nodes: MotionNode[]
  period: number
}

/**
 * How far each process is from where the flow starts: 0 for a process nothing flows into, then one more for each
 * connection along the shortest way there. A process that only a loop reaches counts from the first such process in
 * the order given, so every process has a depth and the same workflow always gives the same depths.
 */
export function flowDepths(nodeIds: readonly string[], edges: readonly { source: string; target: string }[]): Map<string, number> {
  const known = new Set(nodeIds)
  const outgoing = new Map<string, string[]>()
  const incoming = new Map<string, number>()
  for (const edge of edges) {
    if (!known.has(edge.source) || !known.has(edge.target)) continue
    outgoing.set(edge.source, [...(outgoing.get(edge.source) ?? []), edge.target])
    incoming.set(edge.target, (incoming.get(edge.target) ?? 0) + 1)
  }
  const depths = new Map<string, number>()
  const spread = (starts: string[]) => {
    const queue = [...starts]
    for (const start of starts) depths.set(start, 0)
    while (queue.length > 0) {
      const id = queue.shift()!
      for (const next of outgoing.get(id) ?? []) {
        if (depths.has(next)) continue
        depths.set(next, depths.get(id)! + 1)
        queue.push(next)
      }
    }
  }
  spread(nodeIds.filter((id) => !incoming.has(id)))
  for (const id of nodeIds) if (!depths.has(id)) spread([id])
  return depths
}

/**
 * The scene for these processes and connections: when each signal starts and when each process glows. A process no
 * connection touches has nothing flowing through it and stays as drawn.
 */
export function motionScene(nodes: readonly MotionNodeInput[], edges: readonly MotionEdgeInput[]): MotionScene {
  const drawn = edges.filter((edge) => edge.points.length > 1)
  const depths = flowDepths(nodes.map((node) => node.id), drawn)
  const deepest = Math.max(0, ...drawn.map((edge) => depths.get(edge.source) ?? 0))
  const room = MOTION.seconds - MOTION.travel - MOTION.settle
  const step = deepest > 0 ? Math.min(MOTION.step, room / deepest) : MOTION.step
  const timed = drawn.map((edge) => ({ ...edge, delay: (depths.get(edge.source) ?? 0) * step }))
  const lit = nodes.flatMap((node) => {
    const arriving = timed.filter((edge) => edge.target === node.id)
    if (arriving.length === 0 && !timed.some((edge) => edge.source === node.id)) return []
    // A process glows when the first signal reaches it; where the flow starts, it glows as its signals leave.
    const delay = arriving.length > 0 && (depths.get(node.id) ?? 0) > 0
      ? Math.min(...arriving.map((edge) => edge.delay + MOTION.travel))
      : 0
    return [{ ...node, delay }]
  })
  const last = Math.max(0, ...timed.map((edge) => edge.delay))
  const period = Math.min(MOTION.seconds, last + MOTION.travel + MOTION.settle)
  return { edges: timed, nodes: lit, period }
}

/** The point a fraction of the way along a sampled path. */
export function pointAlong(points: readonly Point[], progress: number): Point {
  const scaled = Math.max(0, Math.min(1, progress)) * (points.length - 1)
  const index = Math.min(points.length - 2, Math.floor(scaled))
  const mix = scaled - index
  return {
    x: points[index].x + (points[index + 1].x - points[index].x) * mix,
    y: points[index].y + (points[index + 1].y - points[index].y) * mix,
  }
}

/** What is lit at a moment of the clip: each moving signal's head and tail along its path, and each glowing process. */
export function litAt(scene: MotionScene, elapsed: number): {
  edges: { edge: MotionEdge; head: number; tail: number; strength: number }[]
  nodes: { node: MotionNode; glow: number }[]
} {
  const time = scene.period > 0 ? elapsed % scene.period : elapsed
  const edges = scene.edges.flatMap((edge) => {
    const progress = (time - edge.delay) / MOTION.travel
    if (progress < 0 || progress > 1) return []
    return [{ edge, head: progress, tail: Math.max(0, progress - 0.24), strength: Math.sin(Math.PI * progress) }]
  })
  const nodes = scene.nodes.flatMap((node) => {
    const progress = (time - node.delay) / MOTION.pulse
    if (progress < 0 || progress > 1) return []
    const glow = Math.sin(Math.PI * progress)
    return glow > 0.02 ? [{ node, glow }] : []
  })
  return { edges, nodes }
}

/**
 * One frame: the still picture, then the signals and glows of that moment. {@code view} is the picture's area in canvas
 * coordinates, which the frame fills.
 */
export function drawMotionFrame(
  context: CanvasRenderingContext2D,
  picture: CanvasImageSource,
  view: { left: number; top: number; width: number; height: number },
  scene: MotionScene,
  elapsed: number,
): void {
  const { width, height } = context.canvas
  context.clearRect(0, 0, width, height)
  context.drawImage(picture, 0, 0, width, height)
  context.save()
  context.scale(width / view.width, height / view.height)
  context.translate(-view.left, -view.top)
  const lit = litAt(scene, elapsed)
  for (const { edge, head, tail, strength } of lit.edges) {
    context.save()
    context.strokeStyle = MOTION.colour
    context.fillStyle = MOTION.colour
    context.lineCap = 'round'
    context.lineJoin = 'round'
    context.lineWidth = 3.4
    context.globalAlpha = 0.42 + strength * 0.5
    context.shadowColor = MOTION.colour
    context.shadowBlur = 10
    context.beginPath()
    for (let index = 0; index <= 14; index++) {
      const point = pointAlong(edge.points, tail + ((head - tail) * index) / 14)
      if (index === 0) context.moveTo(point.x, point.y)
      else context.lineTo(point.x, point.y)
    }
    context.stroke()
    const tip = pointAlong(edge.points, head)
    context.globalAlpha = 0.95
    context.beginPath()
    context.arc(tip.x, tip.y, 4.8, 0, Math.PI * 2)
    context.fill()
    context.restore()
  }
  for (const { node, glow } of lit.nodes) {
    context.save()
    context.strokeStyle = MOTION.colour
    context.lineWidth = 1.4 + glow * 1.8
    context.globalAlpha = glow * 0.55
    context.shadowColor = MOTION.colour
    context.shadowBlur = 12 * glow
    const inset = 2 + glow * 3
    roundedRect(context, node.x - inset, node.y - inset, node.width + inset * 2, node.height + inset * 2, 12 + inset)
    context.stroke()
    context.restore()
  }
  context.restore()
}

function roundedRect(context: CanvasRenderingContext2D, x: number, y: number, width: number, height: number, radius: number) {
  const r = Math.max(0, Math.min(radius, width / 2, height / 2))
  context.beginPath()
  context.moveTo(x + r, y)
  context.lineTo(x + width - r, y)
  context.quadraticCurveTo(x + width, y, x + width, y + r)
  context.lineTo(x + width, y + height - r)
  context.quadraticCurveTo(x + width, y + height, x + width - r, y + height)
  context.lineTo(x + r, y + height)
  context.quadraticCurveTo(x, y + height, x, y + height - r)
  context.lineTo(x, y + r)
  context.quadraticCurveTo(x, y, x + r, y)
  context.closePath()
}

/** The WebM type this browser records, or null when it cannot record a canvas (archify offers the export only then). */
export function motionMimeType(): string | null {
  if (typeof MediaRecorder === 'undefined' || typeof HTMLCanvasElement === 'undefined'
    || typeof HTMLCanvasElement.prototype.captureStream !== 'function') return null
  const candidates = ['video/webm;codecs=vp9', 'video/webm;codecs=vp8', 'video/webm']
  return candidates.find((type) => typeof MediaRecorder.isTypeSupported !== 'function' || MediaRecorder.isTypeSupported(type)) ?? null
}
