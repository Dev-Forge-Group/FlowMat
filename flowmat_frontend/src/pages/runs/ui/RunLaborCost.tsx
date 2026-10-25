import { useRunLaborCostQuery } from '../../../entities/production/api/useLaborCost'
import { useIsProjectOwner } from '../../../entities/project/api/useIsProjectOwner'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { laborCostPersonText, laborCostText } from '../model/laborCostModel'

/**
 * What the run's worked time cost, for the project owner only (docs/architecture/adr/ADR-025-worker-labor-cost.md); nobody
 * else asks for it.
 */
export function RunLaborCost({ projectId, runId }: { projectId: string; runId: string }) {
  const owner = useIsProjectOwner(projectId)
  const query = useRunLaborCostQuery(runId, owner)
  if (!owner) return null
  if (query.isError) return <p role="alert" style={{ fontSize: 12 }}>{errorMessage(query.error, 'Could not load the labor cost.')}</p>
  const data = query.data && Array.isArray(query.data.people) ? query.data : undefined
  if (!data || data.workedMinutes === 0) return null
  return (
    <section aria-label="Labor cost" style={{ fontSize: 12, marginTop: 8 }}>
      <p style={{ margin: 0 }}>{data.workedMinutes} min · {laborCostText(data)}</p>
      <ul style={{ margin: '4px 0', paddingLeft: 18 }}>
        {data.people.map((person) => <li key={person.userId}>{laborCostPersonText(person)}</li>)}
      </ul>
    </section>
  )
}
