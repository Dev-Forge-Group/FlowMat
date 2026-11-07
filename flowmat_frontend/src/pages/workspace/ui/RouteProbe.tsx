import { useEffect, useMemo, useState } from 'react'
import { routeTitle } from '../model/canvasReadingModel'
import type { CanvasReading } from '../model/useCanvasReading'

/** archify's journey pace: one route position every 1,100 ms. */
const JOURNEY_STEP_MS = 1100
const NO_STEPS: string[] = []

/**
 * archify's Route Probe (adapted from archify route-probe.js and template.source.html, Apache-2.0): while a route is
 * picked it says where to click next and offers the finder; once traced it shows the route as chips from start (cyan)
 * to end (rose), "N processes · N steps · shortest path", a Journey that steps along it, Copy link and Clear.
 */
export function RouteProbe({ reading, onFind }: { reading: CanvasReading; onFind(): void }) {
  const route = reading.state?.kind === 'route' ? reading.state : null
  // A new route starts its journey from the overview.
  return <RouteProbePanel key={route ? `${route.from}~${route.to}` : 'picking'} reading={reading} onFind={onFind} />
}

function RouteProbePanel({ reading, onFind }: { reading: CanvasReading; onFind(): void }) {
  const picking = reading.picking
  const route = reading.state?.kind === 'route' ? reading.state : null
  const view = reading.view
  const steps = useMemo(() => (route ? view?.steps ?? NO_STEPS : NO_STEPS), [route, view])
  // -1 is the overview; 0..n-1 a route position.
  const [at, setAt] = useState(-1)
  const [playing, setPlaying] = useState(false)
  const [copied, setCopied] = useState<string | null>(null)

  useEffect(() => {
    if (!playing) return
    const timer = window.setTimeout(() => {
      if (at + 1 >= steps.length) setPlaying(false)
      else setAt(at + 1)
    }, JOURNEY_STEP_MS)
    return () => window.clearTimeout(timer)
  }, [playing, at, steps.length])

  const { reveal } = reading
  useEffect(() => {
    if (at >= 0 && steps[at]) reveal([steps[at]])
  }, [at, steps, reveal])

  if (!picking && !route) return null

  const error = picking?.error ?? null
  const title = picking ? picking.title : routeTitle(route!.from, route!.to, reading.nameOf)
  const status = picking ? (error ?? picking.text)
    : at >= 0 ? `Step ${at + 1} of ${steps.length} · ${playing ? 'Playing' : at === steps.length - 1 ? 'Complete' : 'Inspecting'} · ${reading.nameOf(steps[at])}`
      : reading.view?.text ?? ''

  function play() {
    if (playing) {
      setPlaying(false)
      return
    }
    if (at < 0 || at >= steps.length - 1) setAt(0)
    setPlaying(true)
  }

  function overview() {
    setPlaying(false)
    setAt(-1)
    reading.reveal(steps)
  }

  async function copyLink() {
    try {
      await navigator.clipboard.writeText(window.location.href)
      setCopied('Traced route link copied')
    } catch {
      setCopied('Could not copy traced route link')
    }
    window.setTimeout(() => setCopied(null), 1500)
  }

  return (
    <div className="route-probe" role="region" aria-labelledby="route-probe-title"
      data-state={error ? 'error' : route ? 'result' : 'picking'}>
      <div className="route-probe-head">
        <span className="route-probe-copy">
          <span className="route-probe-eyebrow">Path</span>
          <strong className="route-probe-title" id="route-probe-title">{title}</strong>
        </span>
        <span className="route-probe-actions">
          {picking && (
            <button type="button" className="route-probe-find" onClick={onFind}
              aria-label={picking.from == null ? 'Find a route start' : 'Find a reachable route destination'}>
              {picking.from == null ? 'Find start' : 'Find target'}
            </button>
          )}
          {route && steps.length > 0 && (
            <button type="button" className="route-probe-copy-link" aria-label="Copy link to traced route"
              onClick={() => void copyLink()}>Copy link</button>
          )}
          <button type="button" aria-label="Clear route probe" onClick={() => {
            if (picking) reading.cancelRoute()
            if (route) reading.clear()
          }}>Clear</button>
        </span>
      </div>
      {steps.length > 0 && (
        <>
          <div className="route-probe-path" aria-label="Path">
            {steps.map((processId, index) => (
              <span key={processId} className="route-probe-step">
                {index > 0 && <span className="route-probe-arrow" aria-hidden="true">→</span>}
                <button type="button" className="route-probe-node"
                  data-endpoint={index === 0 ? 'start' : index === steps.length - 1 ? 'end' : undefined}
                  data-route-journey-state={at < 0 ? undefined : index < at ? 'past' : index > at ? 'future' : undefined}
                  aria-current={index === at ? 'step' : undefined}
                  aria-label={`Route position ${index + 1} of ${steps.length}: ${reading.nameOf(processId)}`}
                  onClick={() => {
                    setPlaying(false)
                    setAt(index)
                  }}>
                  {reading.nameOf(processId)}
                </button>
              </span>
            ))}
          </div>
          <div className="route-journey-controls" role="group" aria-label="Route journey controls" data-playing={playing}>
            <button type="button" aria-label="Previous route position" title="Previous route position" disabled={at <= 0}
              onClick={() => { setPlaying(false); setAt((current) => Math.max(0, current - 1)) }}>&#8592;</button>
            <button type="button" className="route-journey-play" aria-pressed={playing}
              aria-label={playing ? 'Pause route journey' : at >= steps.length - 1 ? 'Replay route journey' : 'Play route journey'}
              onClick={play}>
              <span aria-hidden="true">{playing ? '❚❚' : '▶'}</span>{' '}
              {playing ? 'Pause' : at >= steps.length - 1 ? 'Replay' : 'Journey'}
            </button>
            <button type="button" aria-label="Next route position" title="Next route position" disabled={at >= steps.length - 1}
              onClick={() => { setPlaying(false); setAt((current) => Math.min(steps.length - 1, current + 1)) }}>&#8594;</button>
            <button type="button" className="route-journey-overview" aria-label="Show complete route overview"
              onClick={overview}>Overview</button>
          </div>
        </>
      )}
      <small className="route-probe-status" role={error ? 'alert' : 'status'}>{copied ?? status}</small>
    </div>
  )
}
