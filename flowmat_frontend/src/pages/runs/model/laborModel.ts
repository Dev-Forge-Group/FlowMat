import type { RunLabor, RunLaborPerson, RunLaborStep } from '../../../entities/production/api/useRunLabor'
import type { WorkOrderLaborComparisonDto } from '../../../entities/production/api/useWorkOrderLaborComparison'
import type { ProcessDto } from '../../../shared/types/api'

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
 * "By step: Mix 300 of 240 planned, Bake 0 of 180 planned, whole run 20" when any step has actual time or a plan (LB18, LB23);
 * empty otherwise.
 */
export function comparisonSteps(value: Pick<WorkOrderLaborComparisonDto, 'steps' | 'wholeRunMinutes'>): string {
  const steps = (value.steps ?? []).filter((step) => step.actualMinutes > 0 || step.plannedMinutes != null)
  if (steps.length === 0) return ''
  const whole = value.wholeRunMinutes ?? 0
  return `By step: ${[...steps.map((step) => `${step.nodeName ?? step.nodeId} ${step.actualMinutes}`
    + `${step.plannedMinutes != null ? ` of ${step.plannedMinutes} planned` : ''}`),
  ...(whole > 0 ? [`whole run ${whole}`] : [])].join(', ')}`
}

/** "Planned by step: 420 min" once a step plan is set (LB23); empty otherwise. */
export function stepPlanTotal(value: Pick<WorkOrderLaborComparisonDto, 'stepPlannedMinutes'>): string {
  return value.stepPlannedMinutes == null ? '' : `Planned by step: ${value.stepPlannedMinutes} min`
}

/** The nodes a run passes itself; nobody works on them (docs/architecture/adr/ADR-011-production-steps.md). */
const GATEWAYS = new Set(['exclusive', 'join', 'split'])

/** A process of the workflow in the step plan form, with its minutes as typed (LB24). */
export interface StepPlanRow {
  nodeId: string
  nodeName: string
  minutes: string
}

/** A row for every process of the workflow but its gateways, in the workflow's order, starting at what is planned (LB24). */
export function stepPlanRows(processes: Pick<ProcessDto, 'processId' | 'processName' | 'nodeType'>[],
  value: Pick<WorkOrderLaborComparisonDto, 'steps'> | undefined): StepPlanRow[] {
  const planned = new Map((value?.steps ?? []).flatMap((step) => (step.plannedMinutes == null ? [] : [[step.nodeId, step.plannedMinutes] as const])))
  return processes.filter((one) => !GATEWAYS.has((one.nodeType ?? '').toLowerCase()))
    .map((one) => ({ nodeId: one.processId, nodeName: one.processName, minutes: planned.has(one.processId) ? String(planned.get(one.processId)) : '' }))
}

/** The step plan to send, a process left empty not planned, or what is wrong (LB23); the server checks again. */
export function stepPlanPayload(rows: StepPlanRow[]):
  { steps: { nodeId: string; plannedMinutes: number }[]; error: null } | { steps: null; error: string } {
  const steps: { nodeId: string; plannedMinutes: number }[] = []
  for (const row of rows) {
    const typed = row.minutes.trim()
    if (!typed) continue
    const minutes = Number(typed)
    if (!/^\d+$/.test(typed) || minutes < 1 || minutes > 100000) {
      return { steps: null, error: `${row.nodeName}: enter whole minutes from 1 to 100000, or leave it empty.` }
    }
    steps.push({ nodeId: row.nodeId, plannedMinutes: minutes })
  }
  return { steps, error: null }
}
