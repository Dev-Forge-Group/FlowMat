import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { WorkflowCanvasViewModel } from '../../../entities/workflow/model/types'
import { motionMimeType } from './workflowMotion'
import { shareCardText } from './shareCard'
import { workflowJson } from './workflowJson'
import type { ExportChoice, MotionAvailability } from '../ui/ExportMenu'
import type { ExportOutcome, MotionExport, PngExport, ShareCardExport, SvgExport } from '../ui/CanvasViewport'

type ReadingView = Parameters<typeof shareCardText>[0]

/**
 * Exporting the workflow: the canvas hands over its PNG, SVG, share-card and motion exporters, and each export says how
 * it went for a moment (archify's toast). JSON is written from the canvas itself.
 */
export function useWorkspaceExport(canvas: WorkflowCanvasViewModel, readingView: ReadingView) {
  const exportPngRef = useRef<PngExport>(async () => ({ kind: 'empty' }))
  const exportSvgRef = useRef<SvgExport>(async () => ({ kind: 'empty' }))
  const exportShareCardRef = useRef<ShareCardExport>(async () => ({ kind: 'empty' }))
  const exportMotionRef = useRef<MotionExport>(async () => ({ kind: 'empty' }))
  const [exportStatus, setExportStatus] = useState<string | null>(null)
  // A motion clip takes its six seconds to record; its note stays until the clip is saved.
  const recordingRef = useRef(false)
  useEffect(() => {
    if (!exportStatus || recordingRef.current) return
    const timer = window.setTimeout(() => setExportStatus(null), 4000)
    return () => window.clearTimeout(timer)
  }, [exportStatus])
  // archify's share cards: one for the route being read, one for an upstream or downstream trace; none for other readings.
  const shareCard = useMemo(
    () => shareCardText(readingView, (processId) => canvas.nodeMap[processId]?.name ?? processId),
    [readingView, canvas.nodeMap],
  )
  const shareCardRef = useRef({ text: shareCard, view: readingView })
  shareCardRef.current = { text: shareCard, view: readingView }
  const motionAvailability: MotionAvailability = useMemo(
    () => (canvas.edges.length === 0 ? 'no-connections' : motionMimeType() ? 'ready' : 'unsupported'),
    [canvas.edges.length],
  )
  // Called inside the press that chose it, so the clipboard accepts the picture.
  const runExport = useCallback((choice: ExportChoice) => {
    const name = canvas.workflow.workflowName
    if (choice.kind === 'webm') {
      if (recordingRef.current) {
        setExportStatus('Already recording a motion clip')
        return
      }
      recordingRef.current = true
      setExportStatus('Recording 6 seconds of motion…')
      void exportMotionRef.current(name, document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light')
        .then((result: ExportOutcome) => {
          recordingRef.current = false
          setExportStatus(result.kind === 'done' ? 'Downloaded WebM'
            : result.kind === 'empty' ? 'Nothing to record: connect two processes first'
              : result.kind === 'unsupported' ? 'This browser cannot record video'
                : `Export failed: ${result.message}`)
        })
      return
    }
    if (choice.kind === 'share-card') {
      const { text, view } = shareCardRef.current
      if (!text || text.variant !== choice.variant || !view?.highlight) {
        setExportStatus(choice.variant === 'route' ? 'Read a route first, then export its card'
          : 'Trace upstream or downstream of a process first, then export its card')
        return
      }
      void exportShareCardRef.current(name, { text, nodeIds: view.highlight.nodeIds, edgeIds: view.highlight.edgeIds,
        theme: document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light' }).then((result: ExportOutcome) => {
        setExportStatus(result.kind === 'done' ? `Downloaded ${choice.variant} share card`
          : result.kind === 'empty' ? 'Nothing to export: add a process first'
            : result.kind === 'unsupported' ? 'This browser cannot draw the share card'
              : `Export failed: ${result.message}`)
      })
      return
    }
    const done = choice.kind === 'png-clipboard' ? 'Copied PNG to clipboard'
      : choice.kind === 'png' ? 'Downloaded PNG'
        : `Downloaded SVG · ${choice.theme[0].toUpperCase()}${choice.theme.slice(1)}`
    const outcome = choice.kind === 'svg'
      ? exportSvgRef.current(name, choice.theme)
      : exportPngRef.current(name, choice.kind === 'png-clipboard' ? 'clipboard' : 'download')
    void outcome.then((result: ExportOutcome) => {
      setExportStatus(result.kind === 'done' ? done
        : result.kind === 'empty' ? 'Nothing to export: add a process first'
          : result.kind === 'unsupported' ? 'Clipboard image write not supported by this browser'
            : `Export failed: ${result.message}`)
    })
  }, [canvas.workflow.workflowName])

  const exportJson = useCallback(() => {
    const payload = workflowJson(canvas)
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `${canvas.workflow.workflowName}.flowmat.json`
    a.click()
    URL.revokeObjectURL(a.href)
  }, [canvas])

  return {
    shareCard,
    exportPngRef,
    exportSvgRef,
    exportShareCardRef,
    exportMotionRef,
    exportStatus,
    motionAvailability,
    runExport,
    exportJson,
  }
}
