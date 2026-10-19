import { useWorkOrderLaborComparisonQuery } from '../../../entities/production/api/useWorkOrderLaborComparison'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { comparisonLine, comparisonSteps, comparisonTotal } from '../model/laborModel'

/**
 * The work order's planned crew time beside the time its runs recorded for each person (docs/domain/worker-calendar.md
 * LB6-LB7). Shown when it has a crew or recorded time.
 */
export function WorkOrderLaborComparison({ workOrderId }: { workOrderId: string }) {
  const query = useWorkOrderLaborComparisonQuery(workOrderId)
  const value = query.data && Array.isArray(query.data.people) ? query.data : undefined
  if (query.isError) return <p role="alert" style={{ fontSize: 12 }}>{errorMessage(query.error, 'Could not compare the labor time.')}</p>
  if (!value || value.people.length === 0) return null
  return <section aria-label="Labor planned and actual" style={{ marginTop: 10, fontSize: 12 }}>
    <strong>Labor</strong>
    {value.people.map((person) => <div key={person.userId}>{comparisonLine(person, value.runs)}</div>)}
    <div className="inspector-hint">{comparisonTotal(value)}</div>
    {comparisonSteps(value) && <div className="inspector-hint">{comparisonSteps(value)}</div>}
  </section>
}
