import { useWorkOrderLaborCostQuery } from '../../../entities/production/api/useLaborCost'
import { useIsProjectOwner } from '../../../entities/project/api/useIsProjectOwner'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { laborCostPersonText, laborCostText } from '../model/laborCostModel'

/** What the worked time of the work order's runs cost, for the project owner only (docs/architecture/adr/ADR-025-worker-labor-cost.md). */
export function WorkOrderLaborCost({ projectId, workOrderId }: { projectId: string; workOrderId: string }) {
  const owner = useIsProjectOwner(projectId)
  const query = useWorkOrderLaborCostQuery(workOrderId, owner)
  if (!owner) return null
  if (query.isError) return <p role="alert" style={{ fontSize: 12 }}>{errorMessage(query.error, 'Could not load the labor cost.')}</p>
  const data = query.data && Array.isArray(query.data.people) ? query.data : undefined
  if (!data || data.workedMinutes === 0) return null
  return (
    <section aria-label="Labor cost of the runs" style={{ marginTop: 6, fontSize: 12 }}>
      <div>{data.runs} run{data.runs === 1 ? '' : 's'} · {data.workedMinutes} min · {laborCostText(data)}</div>
      {data.people.map((person) => <div key={person.userId} className="inspector-hint">{laborCostPersonText(person)}</div>)}
    </section>
  )
}
