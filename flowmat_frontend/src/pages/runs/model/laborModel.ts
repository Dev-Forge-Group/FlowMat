import type { RunLabor, RunLaborPerson, RunLaborStep } from '../../../entities/production/api/useRunLabor'
import type { StepPlanStep, WorkOrderLaborComparisonDto } from '../../../entities/production/api/useWorkOrderLaborComparison'
import type { ProcessDto } from '../../../shared/types/api'
import { savedAtText } from '../../inventory/model/versionHistoryModel'

/**
 * Who can be picked as having worked on a run (docs/domain/worker-calendar.md LB2, LB7): the work order's crew first with
 * their share, then anyone else with records, then the project's other workers.
 */
export function laborChoices(people: RunLaborPerson[], workers: { userId: string }[]): { userId: string; label: string }[] {
  const seen = new Set<string>()
  const choices: { userId: string; label: string }[] = []
  const add = (userId: string, label: string) => {
    if (seen.has(userId)) return
    seen.add(userId)
    choices.push({ userId, label })
  }
  people.forEach((person) => { if (person.sharePercent != null) add(person.userId, `${person.userId} · crew ${person.sharePercent}%`) })
  people.forEach((person) => add(person.userId, person.userId))
  workers.forEach((worker) => add(worker.userId, worker.userId))
  return choices
}

/** The person a new record starts with: the first of the crew with time left to record, else the first of the crew. */
export function firstWorker(people: RunLaborPerson[]): string {
  const crew = people.filter((person) => person.sharePercent != null)
  return (crew.find((person) => person.suggestedMinutes != null) ?? crew[0])?.userId ?? ''
}

/** Where the suggested minutes come from (LB4); null without a suggestion. */
export function suggestionHint(person: RunLaborPerson | undefined): string | null {
  if (!person || person.suggestedMinutes == null || person.sharePercent == null) return null
  const share = person.sharePercent < 100 ? ` at ${person.sharePercent}%` : ''
  return person.suggestedFrom === 'shifts'
    ? `Suggested from ${person.userId}'s shifts during the run${share}, less what is recorded.`
    : `Suggested from the run's time${share}, less what is recorded (${person.userId} has no shifts).`
}

/** "75 min: kim 45, demo-owner 30", or "No worked time yet". */
export function laborSummary(labor: Pick<RunLabor, 'workedMinutes' | 'people'>): string {
  const counted = labor.people.filter((person) => person.workedMinutes > 0)
  if (counted.length === 0) return 'No worked time yet'
  return `${labor.workedMinutes} min: ${counted.map((person) => `${person.userId} ${person.workedMinutes}`).join(', ')}`
}

/**
 * The minutes a record on a step starts from (LB16): the step's time from its start to its end (or to now while it runs) at
 * the person's share, less what counts for them on that step already; null before the step starts or when nothing is left.
 */
export function stepSuggestion(step: RunLaborStep | undefined, person: RunLaborPerson | undefined, lines: RunLabor['lines'],
  now: Date = new Date()): number | null {
  if (!step?.startedAt || !person) return null
  const end = step.endedAt ? new Date(step.endedAt) : now
  const minutes = Math.max(0, (end.getTime() - new Date(step.startedAt).getTime()) / 60_000)
  const recorded = lines.filter((line) => !line.cancelled && line.userId === person.userId && line.stepId === step.stepId)
    .reduce((sum, line) => sum + line.workedMinutes, 0)
  const left = Math.round(minutes * (person.sharePercent ?? 100) / 100) - recorded
  return left < 1 ? null : Math.min(left, 10080)
}

/** Where a step suggestion comes from, e.g. "Suggested from Mix's time at 50%, less what is recorded." */
export function stepHint(step: RunLaborStep | undefined, person: RunLaborPerson | undefined): string | null {
  if (!step || !person) return null
  const share = person.sharePercent != null && person.sharePercent < 100 ? ` at ${person.sharePercent}%` : ''
  return `Suggested from ${step.nodeName ?? 'the step'}'s time${share}, less what is recorded.`
}

/** "By step: Mix 30, whole run 10" when any record names a step; empty otherwise. */
export function stepSummary(labor: Pick<RunLabor, 'lines'> & { steps?: RunLaborStep[] }): string {
  const counted = labor.lines.filter((line) => !line.cancelled)
  if (!counted.some((line) => line.stepId)) return ''
  const parts = (labor.steps ?? []).filter((step) => step.workedMinutes > 0)
    .map((step) => `${step.nodeName ?? step.stepId} ${step.workedMinutes}`)
  const whole = counted.filter((line) => !line.stepId).reduce((sum, line) => sum + line.workedMinutes, 0)
  return `By step: ${[...parts, ...(whole > 0 ? [`whole run ${whole}`] : [])].join(', ')}`
}

/**
 * One person's planned and actual time on a work order (LB6-LB7), e.g. "kim: planned 480 min at 50% · actual 300 min · 180
 * min less than planned".
 */
export function comparisonLine(person: WorkOrderLaborComparisonDto['people'][number], runs: number): string {
  const share = person.sharePercent != null && person.sharePercent < 100 ? ` at ${person.sharePercent}%` : ''
  const planned = person.sharePercent == null ? 'not in the crew'
    : person.plannedMinutes == null ? 'no planned time' : `planned ${person.plannedMinutes} min${share}`
  const actual = runs === 0 ? 'no run yet' : `actual ${person.actualMinutes} min`
  const gap = person.plannedMinutes == null ? 0 : person.actualMinutes - person.plannedMinutes
  const diff = runs === 0 || person.plannedMinutes == null ? ''
    : gap === 0 ? ' · as planned' : ` · ${Math.abs(gap)} min ${gap > 0 ? 'more' : 'less'} than planned`
  return `${person.userId}: ${planned} · ${actual}${diff}`
}

/** The whole crew in a line: "All: planned 480 min known so far · actual 320 min over 1 run". */
export function comparisonTotal(value: Pick<WorkOrderLaborComparisonDto, 'plannedMinutes' | 'plannedComplete' | 'runs' | 'actualMinutes'>): string {
  const planned = value.plannedMinutes == null ? 'no planned time'
    : `planned ${value.plannedMinutes} min${value.plannedComplete ? '' : ' known so far'}`
  const actual = value.runs === 0 ? 'no run yet' : `actual ${value.actualMinutes} min over ${value.runs} run${value.runs === 1 ? '' : 's'}`
  return `All: ${planned} · ${actual}`
}

/**
 * "By step: Mix 300 of 240 planned, Bake 0 of 180 planned, whole run 20" when any step has actual time or a plan (LB18, LB23),
 * each person beside their part where a step's plan is split: "Mix 120 of 180 planned (kim 100 of 120, lee 0 of 60, dan 20)"
 * (LB29); empty otherwise.
 */
export function comparisonSteps(value: Pick<WorkOrderLaborComparisonDto, 'steps' | 'wholeRunMinutes'>): string {
  const steps = (value.steps ?? []).filter((step) => step.actualMinutes > 0 || step.plannedMinutes != null)
  if (steps.length === 0) return ''
  const whole = value.wholeRunMinutes ?? 0
  return `By step: ${[...steps.map((step) => `${step.nodeName ?? step.nodeId} ${step.actualMinutes}`
    + `${step.plannedMinutes != null ? ` of ${step.plannedMinutes} planned` : ''}${stepPeople(step)}`),
  ...(whole > 0 ? [`whole run ${whole}`] : [])].join(', ')}`
}

/** " (kim 100 of 120, lee 0 of 60, dan 20)" when the step's plan is split among people (LB29); empty otherwise. */
function stepPeople(step: NonNullable<WorkOrderLaborComparisonDto['steps']>[number]): string {
  const people = step.people ?? []
  if (!people.some((person) => person.plannedMinutes != null)) return ''
  return ` (${people.map((person) => `${person.userId} ${person.actualMinutes}`
    + `${person.plannedMinutes != null ? ` of ${person.plannedMinutes}` : ''}`).join(', ')})`
}

/** "Planned by step: 420 min" once a step plan is set (LB23); empty otherwise. */
export function stepPlanTotal(value: Pick<WorkOrderLaborComparisonDto, 'stepPlannedMinutes'>): string {
  return value.stepPlannedMinutes == null ? '' : `Planned by step: ${value.stepPlannedMinutes} min`
}

/** The nodes a run passes itself; nobody works on them (docs/architecture/adr/ADR-011-production-steps.md). */
const GATEWAYS = new Set(['exclusive', 'join', 'split'])

/** A process of the workflow in the step plan form, with its minutes as typed, and the people it can be split among (LB24, LB30). */
export interface StepPlanRow {
  nodeId: string
  nodeName: string
  minutes: string
  /** The crew, then anyone else its plan names, with their minutes as typed. */
  people: { userId: string; minutes: string }[]
}

/**
 * A row for every process of the workflow but its gateways, in the workflow's order, starting at what is planned (LB24). Each can be
 * split among the crew and anyone else its plan names (LB30); a split process starts with its own minutes empty, as they are the
 * people's added up.
 */
export function stepPlanRows(processes: Pick<ProcessDto, 'processId' | 'processName' | 'nodeType'>[],
  value: (Pick<WorkOrderLaborComparisonDto, 'steps'> & Partial<Pick<WorkOrderLaborComparisonDto, 'people'>>) | undefined): StepPlanRow[] {
  const byNode = new Map((value?.steps ?? []).map((step) => [step.nodeId, step] as const))
  const crew = (value?.people ?? []).filter((person) => person.sharePercent != null).map((person) => person.userId)
  return processes.filter((one) => !GATEWAYS.has((one.nodeType ?? '').toLowerCase()))
    .map((one) => {
      const step = byNode.get(one.processId)
      const split = (step?.people ?? []).filter((person) => person.plannedMinutes != null)
      const planned = new Map(split.map((person) => [person.userId, String(person.plannedMinutes)] as const))
      const people = [...new Set([...crew, ...split.map((person) => person.userId)])]
        .map((userId) => ({ userId, minutes: planned.get(userId) ?? '' }))
      return { nodeId: one.processId, nodeName: one.processName,
        minutes: split.length === 0 && step?.plannedMinutes != null ? String(step.plannedMinutes) : '', people }
    })
}

/** The people's whole minutes on a row added up, what its own minutes come to when split (LB30); empty when none is typed. */
export function splitTotal(row: StepPlanRow): string {
  const minutes = row.people.map((person) => wholeMinutes(person.minutes.trim())).filter((one): one is number => one != null)
  return minutes.length === 0 ? '' : String(minutes.reduce((sum, one) => sum + one, 0))
}

/**
 * The step plan to send, a process left empty not planned, or what is wrong (LB23). A process with people's minutes is split among
 * them, its own minutes left empty or agreeing (LB29). The server checks again.
 */
export function stepPlanPayload(rows: StepPlanRow[]):
  { steps: StepPlanStep[]; error: null } | { steps: null; error: string } {
  const steps: StepPlanStep[] = []
  for (const row of rows) {
    const typed = row.minutes.trim()
    const minutes = wholeMinutes(typed)
    if (typed && minutes == null) {
      return { steps: null, error: `${row.nodeName}: enter whole minutes from 1 to 100000, or leave it empty.` }
    }
    const people: { userId: string; plannedMinutes: number }[] = []
    for (const person of row.people) {
      const own = person.minutes.trim()
      if (!own) continue
      const part = wholeMinutes(own)
      if (part == null) {
        return { steps: null, error: `${row.nodeName} · ${person.userId}: enter whole minutes from 1 to 100000, or leave it empty.` }
      }
      people.push({ userId: person.userId, plannedMinutes: part })
    }
    if (people.length > 0) {
      const total = people.reduce((sum, person) => sum + person.plannedMinutes, 0)
      if (total > 100000) return { steps: null, error: `${row.nodeName}: the people's minutes add up to more than 100000.` }
      if (minutes != null && minutes !== total) {
        return { steps: null,
          error: `${row.nodeName}: the people's minutes add up to ${total}, not ${minutes}; leave ${row.nodeName}'s minutes empty or make them agree.` }
      }
      steps.push({ nodeId: row.nodeId, people })
    } else if (minutes != null) {
      steps.push({ nodeId: row.nodeId, plannedMinutes: minutes })
    }
  }
  return { steps, error: null }
}

/** Whole minutes from 1 to 100000, or null. */
function wholeMinutes(typed: string): number | null {
  const minutes = Number(typed)
  return /^\d+$/.test(typed) && minutes >= 1 && minutes <= 100000 ? minutes : null
}

/** The clock times to send with worked time, as typed in the browser's time (LB31): both or neither, the end later; the server checks again. */
export function laborTimes(from: string, to: string): { workedFrom?: string; workedTo?: string; error: string | null } {
  if (!from && !to) return { error: null }
  if (!from || !to) return { error: 'Give both Worked from and Worked to, or neither.' }
  const start = new Date(from)
  const end = new Date(to)
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) return { error: 'Enter the clock times as a date and time.' }
  if (end.getTime() <= start.getTime()) return { error: 'Worked to must be after Worked from.' }
  return { workedFrom: start.toISOString(), workedTo: end.toISOString(), error: null }
}

/** Whole minutes between the clock times as typed; null unless both are there and at least a minute apart (LB32). */
export function laborSpan(from: string, to: string): number | null {
  const times = laborTimes(from, to)
  if (!times.workedFrom || !times.workedTo) return null
  const minutes = Math.floor((Date.parse(times.workedTo) - Date.parse(times.workedFrom)) / 60_000)
  return minutes >= 1 ? minutes : null
}

/** " · 2041-09-01 09:00–11:00", the date again only when it ends on another day, for a record with clock times (LB32); empty otherwise. */
export function laborTimesText(line: { workedFrom?: string | null; workedTo?: string | null }): string {
  if (!line.workedFrom || !line.workedTo) return ''
  const from = savedAtText(line.workedFrom)
  const to = savedAtText(line.workedTo)
  return ` · ${from}–${to.slice(0, 10) === from.slice(0, 10) ? to.slice(11) : to}`
}
