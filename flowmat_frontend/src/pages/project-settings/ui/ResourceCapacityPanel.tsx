import { useState } from 'react'
import { useResourceCapacities, type ResourceCapacity } from '../../../entities/production/api/useResourceCapacities'
import { useProjectMembersQuery } from '../../../entities/project/api/useProjectMembersQuery'
import { useCurrentUserQuery } from '../../../entities/auth/api/useCurrentUserQuery'
import { errorMessage } from '../../../shared/lib/errorMessage'

type Row = { resourceType: ResourceCapacity['resourceType']; unit: string; amount: string; per: ResourceCapacity['per'] }

/**
 * The factory's energy and water limits (docs/architecture/adr/ADR-046-resources-first-class.md decision 5). A plan whose
 * average draw is above one shows it among its limits. The owner edits the whole list; everyone else reads it.
 */
export function ResourceCapacityPanel({ projectId }: { projectId: string }) {
  const { query, save } = useResourceCapacities(projectId)
  const { data: members = [] } = useProjectMembersQuery(projectId)
  const { data: user } = useCurrentUserQuery()
  const owner = members.some((m) => m.userId === user?.userId && m.projectRole === 'owner')
  const [draft, setDraft] = useState<Row[] | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const rows: Row[] = draft ?? (query.data ?? []).map((c) => ({ resourceType: c.resourceType, unit: c.unit, amount: String(c.amount), per: c.per }))

  function change(index: number, patch: Partial<Row>) {
    setDraft(rows.map((row, at) => (at === index ? { ...row, ...patch } : row)))
  }

  async function submit() {
    const capacities = rows.map((row) => ({ resourceType: row.resourceType, unit: row.unit.trim(), amount: Number(row.amount), per: row.per }))
    if (capacities.some((c) => !c.unit || !Number.isFinite(c.amount) || c.amount <= 0)) {
      return setProblem('Each limit needs a unit and an amount above 0.')
    }
    setProblem(null)
    try {
      await save.mutateAsync(capacities)
      setDraft(null)
    } catch (error) {
      setProblem(errorMessage(error, 'Resource limits could not be saved.'))
    }
  }

  return <section aria-label="Resource limits" style={{ border: '1px solid var(--border)', borderRadius: 16, padding: 24, minWidth: 0 }}>
    <h2>Resource limits</h2>
    <p>How much energy or water the factory can draw. A plan whose average use is above a limit shows it among its limits. Use the unit the flows count it in.</p>
    {query.error && <p role="alert">{errorMessage(query.error, 'Resource limits could not be loaded.')}</p>}
    {rows.length === 0 && query.data && <p>No limits set.</p>}
    {rows.length > 0 && <table style={{ borderCollapse: 'collapse' }}>
      <thead><tr><th align="left">Resource</th><th align="left">Amount</th><th align="left">Unit</th><th align="left">Per</th><th /></tr></thead>
      <tbody>
        {rows.map((row, index) => <tr key={index} aria-label={`Limit ${index + 1}`}>
          <td><select aria-label="Resource" value={row.resourceType} disabled={!owner}
            onChange={(event) => change(index, { resourceType: event.target.value as Row['resourceType'] })}>
            <option value="energy">Energy</option><option value="water">Water</option>
          </select></td>
          <td><input aria-label="Amount" inputMode="decimal" value={row.amount} disabled={!owner} onChange={(event) => change(index, { amount: event.target.value })} /></td>
          <td><input aria-label="Unit" value={row.unit} maxLength={30} disabled={!owner} onChange={(event) => change(index, { unit: event.target.value })} /></td>
          <td><select aria-label="Per" value={row.per} disabled={!owner} onChange={(event) => change(index, { per: event.target.value as Row['per'] })}>
            <option value="hour">hour</option><option value="day">day</option>
          </select></td>
          <td>{owner && <button type="button" onClick={() => setDraft(rows.filter((_, at) => at !== index))}>Remove</button>}</td>
        </tr>)}
      </tbody>
    </table>}
    {owner && <div style={{ display: 'flex', gap: 8, marginTop: 8, flexWrap: 'wrap' }}>
      <button type="button" onClick={() => setDraft([...rows, { resourceType: 'energy', unit: 'kWh', amount: '', per: 'hour' }])}>Add limit</button>
      <button type="button" disabled={save.isPending || draft === null} onClick={() => void submit()}>{save.isPending ? 'Saving...' : 'Save limits'}</button>
    </div>}
    {!owner && query.data && <p>Only the project owner can change the limits.</p>}
    {problem && <p role="alert">{problem}</p>}
  </section>
}
