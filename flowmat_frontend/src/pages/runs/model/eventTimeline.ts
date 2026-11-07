import type { FlowRunEvent } from './flowRunEventsJson'
import { spanText } from './runTiming'

/** A problem (failure, timeout, lateness, unavailable steps), a retry, or any other change; each has its own mark shape. */
export type EventKind = 'change' | 'alert' | 'recovery'

export function eventKind(eventType: string): EventKind {
  if (/failed|timed_out|overdue|unavailable|error/.test(eventType)) return 'alert'
  if (/retry/.test(eventType)) return 'recovery'
  return 'change'
}

export interface TimelineMark {
  eventId: string
  eventType: string
  kind: EventKind
  occurredAt: string
  /** Where it sits along the axis, 0 to 1. */
  at: number
  /** How many marks just before it in its lane it would overlap; drawn that many places higher. */
  stack: number
}

export interface TimelineLane {
  id: string
  label: string
  marks: TimelineMark[]
}

/** A long quiet stretch folded to a narrow gap, with how long it really was. */
export interface TimelineBreak {
  at: number
  label: string
}

export interface EventTimeline {
  start: number
  end: number
  lanes: TimelineLane[]
  breaks: TimelineBreak[]
}

/** The width a folded quiet stretch keeps on the axis. */
const FOLD = 0.04
/** Marks closer than this along the axis stack instead of overlapping. */
const CLOSE = 0.015

/**
 * A run's events on one time axis (archify's timeline rules): a lane for the run and one for each step, in step order; a
 * quiet stretch at least 8 times the median gap and a tenth of the whole is folded, at most 8 of them, labelled with its
 * real length ("≈ 1 d 11 h"); marks that would overlap stack. Null when no event has a time.
 */
export function eventTimeline(events: Pick<FlowRunEvent, 'eventId' | 'eventType' | 'stepId' | 'occurredAt'>[],
  steps: { stepId: string; nodeId: string; sequenceNo: number }[],
  nodes: { processId: string; processName: string }[]): EventTimeline | null {
  const timed = events.map((event) => ({ event, time: Date.parse(event.occurredAt) }))
    .filter((entry) => Number.isFinite(entry.time))
    .sort((left, right) => left.time - right.time)
  if (timed.length === 0) return null
  const times = [...new Set(timed.map((entry) => entry.time))]
  const start = times[0]
  const end = times[times.length - 1]
  const span = end - start

  const gaps = times.slice(1).map((time, index) => ({ from: times[index], to: time, size: time - times[index] }))
  const sizes = gaps.map((gap) => gap.size).sort((left, right) => left - right)
  const median = sizes.length > 0 ? sizes[Math.floor((sizes.length - 1) / 2)] : 0
  const folded = gaps.filter((gap) => median > 0 && gap.size >= 8 * median && gap.size >= 0.1 * span)
    .sort((left, right) => right.size - left.size).slice(0, 8)
    .sort((left, right) => left.from - right.from)
  const kept = span - folded.reduce((total, gap) => total + gap.size, 0)
  const keptShare = 1 - FOLD * folded.length
  const place = (time: number): number => {
    if (span === 0) return 0.5
    let position = 0
    let cursor = start
    for (const gap of folded) {
      if (time < gap.to) break
      position += ((gap.from - cursor) / kept) * keptShare + FOLD
      cursor = gap.to
    }
    return position + (kept > 0 ? ((time - cursor) / kept) * keptShare : 0)
  }

  const stepOf = new Map(steps.map((step) => [step.stepId, step]))
  const nameOf = new Map(nodes.map((node) => [node.processId, node.processName]))
  const lanes = new Map<string, TimelineLane>()
  const laneFor = (stepId: string | null): TimelineLane => {
    const step = stepId ? stepOf.get(stepId) : undefined
    const id = step ? step.stepId : 'run'
    let lane = lanes.get(id)
    if (!lane) {
      lane = { id, label: step ? `Step ${step.sequenceNo} · ${nameOf.get(step.nodeId) ?? step.nodeId}` : 'Run', marks: [] }
      lanes.set(id, lane)
    }
    return lane
  }
  for (const { event, time } of timed) {
    const lane = laneFor(event.stepId)
    const at = place(time)
    const previous = lane.marks[lane.marks.length - 1]
    const stack = previous && at - previous.at < CLOSE ? previous.stack + 1 : 0
    lane.marks.push({ eventId: event.eventId, eventType: event.eventType, kind: eventKind(event.eventType),
      occurredAt: event.occurredAt, at, stack })
  }
  const order = (lane: TimelineLane) => (lane.id === 'run' ? -1 : stepOf.get(lane.id)?.sequenceNo ?? 0)
  return {
    start,
    end,
    lanes: [...lanes.values()].sort((left, right) => order(left) - order(right)),
    breaks: folded.map((gap) => ({ at: place(gap.from) + FOLD / 2, label: `≈ ${spanText(gap.size)}` })),
  }
}
