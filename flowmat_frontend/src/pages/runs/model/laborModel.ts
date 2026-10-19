import type { RunLabor, RunLaborPerson, RunLaborStep } from '../../../entities/production/api/useRunLabor'
import type { WorkOrderLaborComparisonDto } from '../../../entities/production/api/useWorkOrderLaborComparison'

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

/** "By step: Mix 300, Bake 120, whole run 20" when any actual time names a step (LB18); empty otherwise. */
export function comparisonSteps(value: Pick<WorkOrderLaborComparisonDto, 'steps' | 'wholeRunMinutes'>): string {
  const steps = (value.steps ?? []).filter((step) => step.actualMinutes > 0)
  if (steps.length === 0) return ''
  const whole = value.wholeRunMinutes ?? 0
  return `By step: ${[...steps.map((step) => `${step.nodeName ?? step.nodeId} ${step.actualMinutes}`),
    ...(whole > 0 ? [`whole run ${whole}`] : [])].join(', ')}`
}
