import type { RunLabor } from '../../../entities/production/api/useRunLabor'
import type { RunStepDto } from '../../../entities/production/api/useRunSteps'

type TimedStep = Pick<RunStepDto, 'stepId' | 'nodeId' | 'nodeName' | 'sequenceNo' | 'status' | 'sourceStepId' | 'startedAt' | 'endedAt'>
type LaborLine = Pick<RunLabor['lines'][number], 'runLaborId' | 'userId' | 'cancelled' | 'stepId' | 'workedFrom' | 'workedTo'>

/** One row of a run's timing: a process step, or someone's worked time under the step it was on. */
export interface TimingRow {
  id: string
  label: string
  /** 0 for a step the run began with, one more for each step it came from; worked time sits one under its step. */
  depth: number
  kind: 'step' | 'labor'
  /** The step's status; `worked` for worked time. */
  status: string
  /** Where its bar starts and ends along the axis, 0 to 1; null for a step that has not started. */
  from: number | null
  to: number | null
  /** Still running: the bar runs to now and its length is only a lower bound. */
  open: boolean
  /** "12 min", "≥ 5 min" while running, or "not started". */
  length: string
}

export interface RunTiming {
  /** The axis, in epoch milliseconds. */
  start: number
  end: number
  rows: TimingRow[]
  /** The axis's start, middle and end, few enough that dated labels do not crowd. */
  ticks: number[]
  /** Worked time recorded as minutes only, which has no place on the axis. */
  untimed: number
}

const MINUTE = 60_000

/** "< 1 min", "12 min", "2 h 5 min", or from a day on "5 d 3 h". */
export function spanText(ms: number): string {
  if (ms < MINUTE) return '< 1 min'
  const minutes = Math.round(ms / MINUTE)
  if (minutes < 60) return `${minutes} min`
  const hours = Math.floor(minutes / 60)
  if (hours >= 24) return hours % 24 === 0 ? `${hours / 24} d` : `${Math.floor(hours / 24)} d ${hours % 24} h`
  return minutes % 60 === 0 ? `${hours} h` : `${hours} h ${minutes % 60} min`
}

/**
 * A run's steps and worked time as bars on one time axis (archify's waterfall rules): rows depth first, each step under the
 * step it came from in sequence order; a step still running is open, drawn to now with "≥" before its length; a step not
 * started has no bar. Only recorded times are used, nothing is inferred, so no critical path or waiting is drawn. Null
 * when nothing has a time yet.
 */
export function runTiming(steps: TimedStep[], labor: LaborLine[], now: number): RunTiming | null {
  const known = new Set(steps.map((step) => step.stepId))
  const children = new Map<string | null, TimedStep[]>()
  for (const step of steps) {
    const parent = step.sourceStepId && known.has(step.sourceStepId) ? step.sourceStepId : null
    children.set(parent, [...(children.get(parent) ?? []), step])
  }
  for (const list of children.values()) list.sort((left, right) => left.sequenceNo - right.sequenceNo)
  const worked = labor.filter((line) => !line.cancelled && line.workedFrom && line.workedTo)
  const workedOn = new Map<string | null, LaborLine[]>()
  for (const line of worked) {
    const on = line.stepId && known.has(line.stepId) ? line.stepId : null
    workedOn.set(on, [...(workedOn.get(on) ?? []), line])
  }

  type Span = { at: number; until: number; open: boolean } | null
  const spans: { row: Omit<TimingRow, 'from' | 'to'>; span: Span }[] = []
  const laborRow = (line: LaborLine, depth: number, label: string) => {
    const at = Date.parse(line.workedFrom!)
    const until = Date.parse(line.workedTo!)
    spans.push({ row: { id: `labor-${line.runLaborId}`, label, depth, kind: 'labor', status: 'worked', open: false,
      length: spanText(until - at) }, span: { at, until, open: false } })
  }
  const seen = new Set<string>()
  const visit = (step: TimedStep, depth: number) => {
    if (seen.has(step.stepId)) return
    seen.add(step.stepId)
    const at = step.startedAt ? Date.parse(step.startedAt) : null
    const ended = step.endedAt ? Date.parse(step.endedAt) : null
    const open = at != null && ended == null && step.status === 'running'
    const until = ended ?? (open ? now : at)
    const span: Span = at != null && until != null ? { at, until, open } : ended != null ? { at: ended, until: ended, open: false } : null
    const length = !span ? (step.status === 'planned' ? 'not started' : step.status) : `${open ? '≥ ' : ''}${spanText(span.until - span.at)}`
    spans.push({ row: { id: step.stepId, label: step.nodeName ?? step.nodeId, depth, kind: 'step', status: step.status, open,
      length }, span })
    for (const line of workedOn.get(step.stepId) ?? []) laborRow(line, depth + 1, line.userId)
    for (const child of children.get(step.stepId) ?? []) visit(child, depth + 1)
  }
  for (const root of children.get(null) ?? []) visit(root, 0)
  // Steps whose sources only lead back to each other still show, from the earliest.
  for (const step of [...steps].sort((left, right) => left.sequenceNo - right.sequenceNo)) visit(step, 0)
  for (const line of workedOn.get(null) ?? []) laborRow(line, 0, `${line.userId} (whole run)`)

  const times = spans.flatMap(({ span }) => (span ? [span.at, span.until] : []))
  if (times.length === 0) return null
  const start = Math.min(...times)
  const end = Math.max(start + MINUTE, ...times)
  const place = (time: number) => (time - start) / (end - start)
  return {
    start,
    end,
    rows: spans.map(({ row, span }) => ({ ...row, from: span ? place(span.at) : null, to: span ? place(span.until) : null })),
    ticks: [0, 1, 2].map((index) => start + ((end - start) * index) / 2),
    untimed: labor.filter((line) => !line.cancelled && !(line.workedFrom && line.workedTo)).length,
  }
}
