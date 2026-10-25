import { useState, type FormEvent } from 'react'
import {
  useSaveStepPlanMutation,
  useWorkOrderLaborComparisonQuery,
  useWorkflowStepsQuery,
} from '../../../entities/production/api/useWorkOrderLaborComparison'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { comparisonLine, comparisonSteps, comparisonTotal, stepPlanPayload, stepPlanRows, stepPlanTotal } from '../model/laborModel'

/**
 * The work order's planned crew time beside the time its runs recorded for each person (docs/domain/worker-calendar.md
 * LB6-LB7), and by process beside its step plan, which an open order on a workflow sets (LB18, LB23-LB24). Shown when it has a crew,
 * recorded or planned time, or a workflow to plan.
 */
export function WorkOrderLaborComparison({ workOrderId, workflowId, planOpen = false }:
  { workOrderId: string; workflowId?: string; planOpen?: boolean }) {
  const query = useWorkOrderLaborComparisonQuery(workOrderId)
  const save = useSaveStepPlanMutation(workOrderId)
  const [editing, setEditing] = useState(false)
  const [typed, setTyped] = useState<Record<string, string>>({})
  const [problem, setProblem] = useState<string | null>(null)
  const processes = useWorkflowStepsQuery(workflowId, editing)
  const value = query.data && Array.isArray(query.data.people) ? query.data : undefined
  const canPlan = planOpen && Boolean(workflowId)
  const rows = (editing ? stepPlanRows(processes.data ?? [], value) : [])
    .map((row) => (row.nodeId in typed ? { ...row, minutes: typed[row.nodeId] } : row))

  function submit(event: FormEvent) {
    event.preventDefault()
    const payload = stepPlanPayload(rows)
    setProblem(payload.error)
    if (payload.steps) save.mutate(payload.steps, { onSuccess: () => { setEditing(false); setTyped({}) } })
  }

  if (query.isError) return <p role="alert" style={{ fontSize: 12 }}>{errorMessage(query.error, 'Could not compare the labor time.')}</p>
  if (!value || (value.people.length === 0 && (value.steps ?? []).length === 0 && !canPlan)) return null
  return <section aria-label="Labor planned and actual" style={{ marginTop: 10, fontSize: 12 }}>
    <strong>Labor</strong>
    {value.people.map((person) => <div key={person.userId}>{comparisonLine(person, value.runs)}</div>)}
    {value.people.length > 0 && <div className="inspector-hint">{comparisonTotal(value)}</div>}
    {comparisonSteps(value) && <div className="inspector-hint">{comparisonSteps(value)}</div>}
    {stepPlanTotal(value) && <div className="inspector-hint">{stepPlanTotal(value)}</div>}
    {canPlan && !editing && <button type="button" style={{ fontSize: 11 }}
      onClick={() => { setEditing(true); setTyped({}); setProblem(null); save.reset() }}>Plan by step</button>}
    {editing && <form aria-label="Step plan" noValidate onSubmit={submit} style={{ display: 'grid', gap: 4, marginTop: 4, maxWidth: 360 }}>
      {processes.isLoading && <span className="inspector-hint">Reading the workflow...</span>}
      {processes.isError && <p role="alert">{errorMessage(processes.error, 'Could not read the workflow.')}</p>}
      {rows.map((row) => <label key={row.nodeId}>{row.nodeName}
        <input aria-label={`${row.nodeName} planned minutes`} type="number" min="1" step="1" value={row.minutes}
          style={{ width: 90, marginLeft: 6 }} onChange={(event) => setTyped({ ...typed, [row.nodeId]: event.target.value })} /> min
      </label>)}
      {processes.isSuccess && rows.length === 0 && <span className="inspector-hint">The workflow has no process to plan.</span>}
      <div style={{ display: 'flex', gap: 6 }}>
        <button type="submit" disabled={save.isPending || !processes.isSuccess}>Save step plan</button>
        <button type="button" onClick={() => { setEditing(false); setProblem(null) }}>Cancel</button>
      </div>
      <span className="inspector-hint">Worker minutes for everyone on the process together; leave a process empty not to plan it.</span>
      {problem && <p role="alert">{problem}</p>}
      {save.isError && <p role="alert">{errorMessage(save.error, 'The step plan was not saved.')}</p>}
    </form>}
  </section>
}
