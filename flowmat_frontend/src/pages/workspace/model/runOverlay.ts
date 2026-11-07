import type { RunStepDto } from '../../../entities/production/api/useRunSteps'

/** How a process stands in the run shown over the canvas; each has its own mark shape as well as colour. */
export type RunMark = 'waiting' | 'running' | 'completed' | 'failed' | 'skipped' | 'cancelled'

const MARK_OF: Record<string, RunMark> = {
  planned: 'waiting', running: 'running', completed: 'completed', failed: 'failed', skipped: 'skipped', cancelled: 'cancelled',
}

/** The order the summary names them in. */
const ORDER: RunMark[] = ['completed', 'running', 'failed', 'waiting', 'skipped', 'cancelled']

export interface RunOverlay {
  /** Each process on the canvas with a step, marked by its latest step. */
  marks: ReadonlyMap<string, RunMark>
  /** Processes with steps in the run that are no longer on the canvas (removed after the revision was published). */
  missing: number
}

/**
 * A production run over the canvas (archify's run overlay): each process takes the status of its latest step, the one
 * with the highest sequence number, since a loop can enter a process more than once. Steps match processes by id, as the
 * published revision keeps the canvas's process ids.
 */
export function runOverlay(steps: Pick<RunStepDto, 'nodeId' | 'status' | 'sequenceNo'>[],
  known: ReadonlySet<string>): RunOverlay {
  const latest = new Map<string, { status: string; sequenceNo: number }>()
  for (const step of steps) {
    const seen = latest.get(step.nodeId)
    if (!seen || step.sequenceNo > seen.sequenceNo) latest.set(step.nodeId, step)
  }
  const marks = new Map<string, RunMark>()
  let missing = 0
  for (const [processId, step] of latest) {
    if (known.has(processId)) marks.set(processId, MARK_OF[step.status] ?? 'waiting')
    else missing += 1
  }
  return { marks, missing }
}

/** What the reading chip says about the run shown, e.g. "Run R-0003 (running): 2 completed, 1 running, 1 waiting". */
export function runSummary(runNumber: string, runStatus: string, overlay: RunOverlay): string {
  const counts = new Map<RunMark, number>()
  for (const mark of overlay.marks.values()) counts.set(mark, (counts.get(mark) ?? 0) + 1)
  const parts = ORDER.filter((mark) => counts.has(mark)).map((mark) => `${counts.get(mark)} ${mark}`)
  const gone = overlay.missing === 0 ? ''
    : `; ${overlay.missing} ${overlay.missing === 1 ? 'process' : 'processes'} no longer on the canvas`
  return `Run ${runNumber} (${runStatus}): ${parts.length > 0 ? parts.join(', ') : 'no steps yet'}${gone}`
}
