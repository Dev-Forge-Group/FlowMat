import { useCallback, useEffect, useRef } from 'react'
import { toBlob, toPng } from 'html-to-image'
import { getNodesBounds, getViewportForBounds, useReactFlow, type Node } from '@xyflow/react'
import type { RfEdge, RfNode } from '../model/canvasFlowElements'
import { SHARE_CARD, shareCardSvg, type ShareCardText } from '../model/shareCard'
import { motionMimeType, motionScene, type MotionEdgeInput } from '../model/workflowMotion'
import { workflowSvg, workflowSvgParts, type SvgConnection, type SvgProcess, type SvgTheme } from '../model/workflowSvg'
import { CONNECTION_TYPE_STYLE } from './CanvasEdge'
import { recordMotion } from './recordMotion'

/** How a picture export ended: made, nothing to draw, the browser cannot, or it failed with a message. */
export type ExportOutcome = { kind: 'done' } | { kind: 'empty' } | { kind: 'unsupported' } | { kind: 'failed'; message: string }

/** The raster picture's target: a file to save, or the clipboard (archify's "PNG to clipboard"). */
export type PngTarget = 'download' | 'clipboard'
export type PngExport = (filename: string, target?: PngTarget) => Promise<ExportOutcome>
export type SvgExport = (filename: string, theme?: SvgTheme) => Promise<ExportOutcome>
/** A share card of a reading (archify's route and reach share cards): what it says and what it draws strongly. */
export interface ShareCardRequest {
  text: ShareCardText
  nodeIds: ReadonlySet<string>
  edgeIds: ReadonlySet<string>
  theme: 'light' | 'dark'
}
export type ShareCardExport = (filename: string, card: ShareCardRequest) => Promise<ExportOutcome>
/** archify's six-second motion WebM of the workflow (workflowMotion.ts). */
export type MotionExport = (filename: string, theme: 'light' | 'dark') => Promise<ExportOutcome>

/** The card twice as large as it is drawn, so it stays sharp where it is pasted. */
const SHARE_CARD_SCALE = 2

/**
 * The canvas's pictures, handed to the page once React Flow is there: PNG (to a file or the clipboard), SVG, a share
 * card and the motion clip. Must be inside ReactFlow.
 */
export function ExportController({ onReady, onSvgReady, onShareCardReady, onMotionReady }: {
  onReady?: (fn: PngExport) => void
  onSvgReady?: (fn: SvgExport) => void
  onShareCardReady?: (fn: ShareCardExport) => void
  onMotionReady?: (fn: MotionExport) => void
}) {
  const { getNodes, getEdges } = useReactFlow()
  const getNodesRef = useRef(getNodes)
  getNodesRef.current = getNodes
  const getEdgesRef = useRef(getEdges)
  getEdgesRef.current = getEdges

  // The workflow as the vector pictures draw it: the boxes React Flow measured and the paths it drew, each line in its
  // kind's own look. Null when there is no process to draw.
  const drawn = useCallback((): { processes: SvgProcess[]; connections: SvgConnection[] } | null => {
      const nodes = (getNodesRef.current() as unknown as RfNode[]).filter((node) => node.type === 'flowmatNode')
      if (nodes.length === 0) return null
      const measured = (node: RfNode) => (node as unknown as { measured?: { width?: number; height?: number } }).measured
      const processes = nodes.map((node) => ({ id: node.id, x: node.position.x, y: node.position.y,
        width: measured(node)?.width ?? node.width, height: measured(node)?.height ?? node.height,
        name: node.data.name, kind: node.data.nodeType }))
      const connections = (getEdgesRef.current() as unknown as RfEdge[]).flatMap((edge) => {
        const path = document.querySelector<SVGPathElement>(
          `.react-flow__edge[data-id="${CSS.escape(edge.id)}"] path.react-flow__edge-path`)
        const d = path?.getAttribute('d')
        if (!path || !d) return []
        const look = CONNECTION_TYPE_STYLE[edge.data.connectionType] ?? { stroke: '#94a3b8' }
        const length = typeof path.getTotalLength === 'function' ? path.getTotalLength() : 0
        const at = (distance: number) => {
          const point = path.getPointAtLength(distance)
          return { x: point.x, y: point.y }
        }
        return [{ id: edge.id, d, stroke: look.stroke, dash: look.strokeDasharray ?? null, label: edge.data.label,
          labelAt: length > 0 ? at(length / 2) : null, ends: length > 0 ? [at(0), at(length)] as const : null }]
      })
      return { processes, connections }
  }, [])

  useEffect(() => {
    onSvgReady?.(async (filename: string, theme: SvgTheme = 'light') => {
      const workflow = drawn()
      if (!workflow) return { kind: 'empty' }
      const { processes, connections } = workflow
      const url = URL.createObjectURL(new Blob([workflowSvg(filename, processes, connections, theme)], { type: 'image/svg+xml' }))
      const a = document.createElement('a')
      a.href = url
      a.download = theme === 'light' ? `${filename}.svg` : `${filename}-${theme}.svg`
      a.click()
      window.setTimeout(() => URL.revokeObjectURL(url), 0)
      return { kind: 'done' }
    })
  }, [onSvgReady, drawn])

  // The share card: the workflow with the reading drawn strongly, under its badge, title and summary, as one PNG.
  useEffect(() => {
    onShareCardReady?.(async (filename: string, card: ShareCardRequest) => {
      const workflow = drawn()
      if (!workflow) return { kind: 'empty' }
      const diagram = workflowSvgParts(workflow.processes, workflow.connections, card.theme,
        { nodeIds: card.nodeIds, edgeIds: card.edgeIds, accent: card.text.accent })
      const svg = shareCardSvg({ title: filename, text: card.text, diagram, theme: card.theme })
      const source = URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml;charset=utf-8' }))
      try {
        const image = new Image()
        await new Promise<void>((resolve, reject) => {
          image.onload = () => resolve()
          image.onerror = () => reject(new Error('The card could not be drawn.'))
          image.src = source
        })
        const canvas = document.createElement('canvas')
        canvas.width = SHARE_CARD.width * SHARE_CARD_SCALE
        canvas.height = SHARE_CARD.height * SHARE_CARD_SCALE
        const context = canvas.getContext('2d')
        if (!context) return { kind: 'unsupported' }
        context.drawImage(image, 0, 0, canvas.width, canvas.height)
        const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'))
        if (!blob) return { kind: 'failed', message: 'The browser made no picture.' }
        const url = URL.createObjectURL(blob)
        const a = document.createElement('a')
        a.href = url
        a.download = `${filename}-${card.text.suffix}.png`
        a.click()
        window.setTimeout(() => URL.revokeObjectURL(url), 0)
        return { kind: 'done' }
      } catch (error) {
        return { kind: 'failed', message: error instanceof Error ? error.message : 'The card could not be drawn.' }
      } finally {
        URL.revokeObjectURL(source)
      }
    })
  }, [onShareCardReady, drawn])

  // The motion clip: the still vector picture with a signal running along each line in flow order, recorded as a WebM.
  useEffect(() => {
    onMotionReady?.(async (filename: string, theme: 'light' | 'dark') => {
      const mimeType = motionMimeType()
      if (!mimeType) return { kind: 'unsupported' }
      const workflow = drawn()
      if (!workflow) return { kind: 'empty' }
      // Each line sampled along the path the canvas drew, every dozen canvas pixels or so.
      const lines = (getEdgesRef.current() as unknown as RfEdge[]).flatMap((edge): MotionEdgeInput[] => {
        const path = document.querySelector<SVGPathElement>(
          `.react-flow__edge[data-id="${CSS.escape(edge.id)}"] path.react-flow__edge-path`)
        const length = path && typeof path.getTotalLength === 'function' ? path.getTotalLength() : 0
        if (!path || !(length > 0)) return []
        const count = Math.max(12, Math.min(72, Math.ceil(length / 12)))
        const points = Array.from({ length: count + 1 }, (_, index) => {
          const point = path.getPointAtLength((length * index) / count)
          return { x: point.x, y: point.y }
        })
        return [{ id: edge.id, source: edge.source, target: edge.target, points }]
      })
      const scene = motionScene(workflow.processes, lines)
      if (scene.edges.length === 0) return { kind: 'empty' }
      const view = workflowSvgParts(workflow.processes, workflow.connections, theme)
      try {
        const blob = await recordMotion(workflowSvg(filename, workflow.processes, workflow.connections, theme), view, scene, mimeType)
        const url = URL.createObjectURL(blob)
        const a = document.createElement('a')
        a.href = url
        a.download = `${filename}.webm`
        a.click()
        window.setTimeout(() => URL.revokeObjectURL(url), 0)
        return { kind: 'done' }
      } catch (error) {
        return { kind: 'failed', message: error instanceof Error ? error.message : 'The clip could not be recorded.' }
      }
    })
  }, [onMotionReady, drawn])

  useEffect(() => {
    onReady?.(async (filename: string, target: PngTarget = 'download') => {
      const nodes = getNodesRef.current() as unknown as RfNode[]
      if (nodes.length === 0) return { kind: 'empty' }
      // The clipboard takes a picture only where the browser can write one (archify checks the same before offering it).
      const clipboard = target === 'clipboard'
      if (clipboard && (typeof ClipboardItem === 'undefined' || typeof navigator.clipboard?.write !== 'function')) {
        return { kind: 'unsupported' }
      }
      const bounds = getNodesBounds(nodes as unknown as Node[])
      const W = 2400
      const H = 1600
      const viewport = getViewportForBounds(bounds, W, H, 0.1, 2, 0.1)
      const el = document.querySelector('.react-flow__viewport') as HTMLElement | null
      if (!el) return { kind: 'empty' }
      // The picture is the workflow itself: reading highlights and the selection stay out of it.
      const frame = el.closest('.workspace-canvas-viewport')
      frame?.classList.add('reading-exporting')
      const restoreLines = plainSelectedLines(el)
      const options = {
        backgroundColor: '#f4f3f8',
        width: W,
        height: H,
        style: {
          width: `${W}px`,
          height: `${H}px`,
          transform: `translate(${viewport.x}px,${viewport.y}px) scale(${viewport.zoom})`,
          transformOrigin: 'top left',
        },
      }
      const restore = () => {
        restoreLines()
        frame?.classList.remove('reading-exporting')
      }
      try {
        if (clipboard) {
          // The item is made at once, with the picture to come, so the write stays inside the press that asked for it.
          const picture = toBlob(el, options).then((blob) => {
            if (!blob) throw new Error('the picture came out empty')
            return blob
          })
          await navigator.clipboard.write([new ClipboardItem({ 'image/png': picture })])
        } else {
          const dataUrl = await toPng(el, options)
          const a = document.createElement('a')
          a.href = dataUrl
          a.download = `${filename}.png`
          a.click()
        }
        return { kind: 'done' }
      } catch (error) {
        return { kind: 'failed', message: error instanceof Error ? error.message : String(error) }
      } finally {
        restore()
      }
    })
  }, [onReady])

  return null
}

/**
 * The picture copies a line's markup, not its computed style, so a selected line would keep its selection colour: for
 * the export, each selected line and its arrow take the look of their kind (CanvasEdge.tsx keeps it in variables).
 * Returns what puts them back.
 */
function plainSelectedLines(root: HTMLElement): () => void {
  const undo: (() => void)[] = []
  const restyle = (element: SVGElement, apply: (style: CSSStyleDeclaration, stroke: string, dash: string) => void) => {
    const stroke = element.style.getPropertyValue('--edge-type-stroke')
    if (!stroke) return
    const before = element.getAttribute('style')
    apply(element.style, stroke, element.style.getPropertyValue('--edge-type-dash'))
    undo.push(() => (before == null ? element.removeAttribute('style') : element.setAttribute('style', before)))
  }
  root.querySelectorAll<SVGPathElement>('.react-flow__edge.selected path.react-flow__edge-path').forEach((path) =>
    restyle(path, (style, stroke, dash) => {
      style.stroke = stroke
      style.strokeWidth = '1.5'
      style.strokeDasharray = dash && dash !== 'none' ? dash : ''
    }))
  root.querySelectorAll<SVGPathElement>('.react-flow__edge.selected marker path').forEach((path) =>
    restyle(path, (style, stroke) => { style.fill = stroke }))
  return () => undo.forEach((restore) => restore())
}
