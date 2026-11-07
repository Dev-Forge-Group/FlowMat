import { useMemo, useState } from 'react'
import { useParams } from 'react-router-dom'
import { useProjectTimeZone } from '../../../entities/project/api/useProjectTimeZone'
import type { FlowRunEvent } from '../model/flowRunEventsJson'
import { eventTimeline } from '../model/eventTimeline'
import { usableZone, zoneCaption, zoneTimeText } from '../model/zoneTime'

type TimelineStep = { stepId: string; nodeId: string; sequenceNo: number }
type TimelineNode = { processId: string; processName: string }

const MARK_TEXT = { change: '●', alert: '▲', recovery: '◆' } as const

/** Along the lane with a little room at both ends, so a mark at the very start or end is not cut in half. */
const x = (at: number) => `${2 + at * 96}%`

/**
 * The events shown in the log, on one time axis on request (archify's timeline): a lane for the run and one for each step,
 * long quiet stretches folded and labelled with their real length, problems red and triangular, retries diamonds.
 */
export function FlowRunEventTimeline({ events, steps, nodes }: {
  events: readonly FlowRunEvent[]
  steps: readonly TimelineStep[]
  nodes: readonly TimelineNode[]
}) {
  const [shown, setShown] = useState(false)
  if (events.length === 0) return null
  return (
    <section aria-label="Event timeline" className="event-timeline">
      <button type="button" aria-expanded={shown} onClick={() => setShown(!shown)}>
        {shown ? 'Hide event timeline' : 'Show event timeline'}
      </button>
      {shown && <EventTimelineChart events={events} steps={steps} nodes={nodes} />}
    </section>
  )
}

function EventTimelineChart({ events, steps, nodes }: {
  events: readonly FlowRunEvent[]
  steps: readonly TimelineStep[]
  nodes: readonly TimelineNode[]
}) {
  const timeline = useMemo(() => eventTimeline([...events], [...steps], [...nodes]), [events, steps, nodes])
  // Read only once the timeline is shown, so the event log itself asks for nothing more.
  const { projectId = '' } = useParams<{ projectId: string }>()
  const zone = usableZone(useProjectTimeZone(projectId).query.data?.timeZone)
  if (!timeline) return <p className="inspector-hint">No event has a time to place.</p>
  const timeText = (time: number) => zoneTimeText(time, zone)

  return (
    <>
      <p className="inspector-hint">
        The events shown, on one time axis: ● a change, ▲ a problem, ◆ a retry. Long quiet stretches are folded (≈ their real length).
        {' '}{zoneCaption(zone)}
      </p>
      <table className="run-timing__table">
        <thead>
          <tr>
            <th scope="col">Lane</th>
            <th scope="col">Events</th>
            <th scope="col" className="run-timing__lane">
              <span className="run-timing__axis">
                <span style={{ left: 0 }}>{timeText(timeline.start)}</span>
                {timeline.breaks.map((fold) => (
                  <span key={fold.at} className="event-timeline__fold-label" style={{ left: x(fold.at) }}>{fold.label}</span>
                ))}
                {timeline.end > timeline.start && <span style={{ left: '100%' }}>{timeText(timeline.end)}</span>}
              </span>
            </th>
          </tr>
        </thead>
        <tbody>
          {timeline.lanes.map((lane) => (
            <tr key={lane.id}>
              <th scope="row">{lane.label}</th>
              <td>{lane.marks.length}</td>
              <td className="run-timing__lane event-timeline__lane" aria-hidden="true">
                {timeline.breaks.map((fold) => (
                  <span key={fold.at} className="event-timeline__fold" style={{ left: x(fold.at) }} />
                ))}
                {lane.marks.map((mark) => (
                  <span key={mark.eventId} className={`event-timeline__mark event-timeline__mark--${mark.kind}`}
                    style={{ left: x(mark.at), marginTop: -mark.stack * 7 }}
                    title={`${timeText(new Date(mark.occurredAt).getTime())} · ${mark.eventType}`}>
                    {MARK_TEXT[mark.kind]}
                  </span>
                ))}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  )
}
