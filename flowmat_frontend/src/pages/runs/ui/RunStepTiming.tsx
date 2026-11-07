import { useEffect, useMemo, useState } from 'react'
import { useParams } from 'react-router-dom'
import { useProjectTimeZone } from '../../../entities/project/api/useProjectTimeZone'
import { useRunLaborQuery } from '../../../entities/production/api/useRunLabor'
import { useRunStepsQuery } from '../../../entities/production/api/useRunSteps'
import { runTiming } from '../model/runTiming'
import { usableZone, zoneCaption, zoneTimeText } from '../model/zoneTime'

/**
 * The run's steps and worked time as bars on one time axis (archify's waterfall), shown on request under the step list.
 * Measured from recorded times only; a step still running is dashed and its length is a lower bound; red is for a failure.
 */
export function RunStepTiming({ runId }: { runId: string }) {
  const [shown, setShown] = useState(false)
  // Shares the step list's query; a run without process steps has nothing to draw.
  const hasSteps = (useRunStepsQuery(runId).data?.length ?? 0) > 0
  if (!hasSteps) return null
  return (
    <section aria-label="Step timing" className="run-timing">
      <button type="button" aria-expanded={shown} onClick={() => setShown(!shown)}>
        {shown ? 'Hide step timing' : 'Show step timing'}
      </button>
      {shown && <RunTimingChart runId={runId} />}
    </section>
  )
}

function RunTimingChart({ runId }: { runId: string }) {
  const steps = useRunStepsQuery(runId).data
  const labor = useRunLaborQuery(runId).data
  // Read only once the chart is shown, so the run page itself asks for nothing more.
  const { projectId = '' } = useParams<{ projectId: string }>()
  const zone = usableZone(useProjectTimeZone(projectId).query.data?.timeZone)
  const [now, setNow] = useState(() => Date.now())
  const timing = useMemo(() => runTiming(steps ?? [], labor?.lines ?? [], now), [steps, labor, now])
  const open = timing?.rows.some((row) => row.open) ?? false

  // A running step's bar keeps growing while it is shown.
  useEffect(() => {
    if (!open) return
    const timer = window.setInterval(() => setNow(Date.now()), 30_000)
    return () => window.clearInterval(timer)
  }, [open])

  if (!timing) return <p className="inspector-hint">No step has started yet.</p>
  const longSpan = timing.end - timing.start >= 24 * 60 * 60_000
  const tickText = (time: number) => zoneTimeText(time, zone,
    longSpan ? { dateStyle: 'short', timeStyle: 'short' } : { timeStyle: 'short' })

  return (
    <>
      <p className="inspector-hint">
        Measured from the recorded step and worked times on one time axis. A dashed bar is still running; its length is at least what it shows.
        {' '}{zoneCaption(zone)}
      </p>
      <table className="run-timing__table">
        <thead>
          <tr>
            <th scope="col">Step</th>
            <th scope="col">Time</th>
            <th scope="col" className="run-timing__lane">
              <span className="run-timing__axis">
                {timing.ticks.map((tick, index) => (
                  <span key={tick} style={{ left: `${index * 50}%` }}>{tickText(tick)}</span>
                ))}
              </span>
            </th>
          </tr>
        </thead>
        <tbody>
          {timing.rows.map((row) => (
            <tr key={row.id} className={`run-timing__row--${row.kind}`}>
              <th scope="row" style={{ paddingLeft: 4 + row.depth * 14 }}>
                {row.label} <span className="inspector-hint">{row.status}</span>
              </th>
              <td>{row.length}</td>
              <td className="run-timing__lane" aria-hidden="true">
                {row.from != null && row.to != null && (
                  <span className={`run-timing__bar run-timing__bar--${row.status}${row.open ? ' run-timing__bar--open' : ''}`}
                    style={{ left: `${row.from * 100}%`, width: `max(2px, ${(row.to - row.from) * 100}%)` }} />
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {timing.untimed > 0 && (
        <p className="inspector-hint">
          {timing.untimed} worked-time {timing.untimed === 1 ? 'record has' : 'records have'} minutes only and {timing.untimed === 1 ? 'is' : 'are'} not drawn.
        </p>
      )}
    </>
  )
}
