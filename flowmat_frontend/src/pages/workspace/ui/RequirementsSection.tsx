import { useState, type FormEvent } from 'react'
import { useEquipmentQuery } from '../../../entities/catalog/api/useEquipment'
import {
  useProcessRequirementsQuery,
  useSaveProcessRequirementsMutation,
  type ProcessRequirementDto,
  type ProcessRequirementInput,
  type RequirementKind,
  type RequirementPer,
} from '../../../entities/workflow/api/useProcessRequirements'
import { errorMessage } from '../../../shared/lib/errorMessage'

type Row = { kind: RequirementKind; ref: string; amount: string; unit: string; per: RequirementPer }

const KINDS: { value: RequirementKind; label: string }[] = [
  { value: 'equipment', label: 'Equipment' },
  { value: 'crew', label: 'People' },
  { value: 'skill', label: 'Skill' },
  { value: 'compute', label: 'Compute' },
  { value: 'time', label: 'Time' },
]
const kindLabel = (kind: RequirementKind) => KINDS.find((one) => one.value === kind)?.label ?? kind
const perLabel = (per: RequirementPer) => per === 'output_unit' ? 'per output unit' : 'per batch'
const emptyRow = (): Row => ({ kind: 'time', ref: '', amount: '', unit: 'min', per: 'batch' })

/**
 * What running the node takes (docs/architecture/adr/ADR-043-plan-backwards-from-a-target.md decision 4): equipment, people,
 * skills, compute and time, per batch or per output unit. A plan reads them from the published revision.
 */
export function RequirementsSection({ processId, projectId }: { processId: string; projectId: string }) {
  const query = useProcessRequirementsQuery(processId)
  const save = useSaveProcessRequirementsMutation(processId)
  const equipment = useEquipmentQuery(projectId)
  const [rows, setRows] = useState<Row[] | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const names = new Map((equipment.data ?? []).map((one) => [one.equipmentId, one.equipmentCode ?? one.equipmentName]))
  const failure = problem ?? (save.isError ? errorMessage(save.error) : query.isError ? errorMessage(query.error) : null)

  const describe = (requirement: ProcessRequirementDto) => {
    const what = requirement.kind === 'equipment' && requirement.ref ? names.get(requirement.ref) ?? requirement.ref
      : requirement.ref ?? ''
    const amount = requirement.kind === 'time' ? `${requirement.amount} ${requirement.unit ?? 'min'}`
      : `${requirement.amount}${requirement.unit ? ` ${requirement.unit}` : ''}`
    return `${kindLabel(requirement.kind)}${what ? ` ${what}` : ''}: ${amount} ${perLabel(requirement.per)}`
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!rows) return
    const input: ProcessRequirementInput[] = []
    for (const row of rows) {
      const amount = Number(row.amount)
      if (!row.amount.trim() || !Number.isFinite(amount) || amount <= 0) {
        setProblem('Each requirement needs an amount above 0.')
        return
      }
      input.push({ kind: row.kind, ref: row.ref.trim() || null, amount, unit: row.unit.trim() || null, per: row.per })
    }
    setProblem(null)
    save.mutate(input, { onSuccess: () => { setRows(null); setSaved(true) } })
  }

  const set = (index: number, patch: Partial<Row>) =>
    setRows((current) => current?.map((row, at) => at === index ? { ...row, ...patch } : row) ?? null)

  return (
    <section aria-label="Requirements" className="inspector__section" style={{ marginTop: '16px' }}>
      <h4 style={{ marginTop: 0 }}>Requirements</h4>
      {query.isPending && <p>Loading...</p>}
      {query.data && !rows && (
        <>
          {query.data.length === 0
            ? <p className="inspector-hint" style={{ margin: '0 0 8px' }}>Nothing yet: add the equipment, people and time a run takes.</p>
            : <ul style={{ margin: '0 0 8px', paddingLeft: '18px' }}>{query.data.map((one) =>
              <li key={one.processRequirementId}>{describe(one)}</li>)}</ul>}
          <button type="button" onClick={() => {
            setRows(query.data.map((one) => ({ kind: one.kind, ref: one.ref ?? '', amount: String(one.amount),
              unit: one.unit ?? '', per: one.per })))
            setProblem(null)
            setSaved(false)
            save.reset()
          }}>Edit requirements</button>
        </>
      )}
      {saved && !rows && <p className="inspector-hint" style={{ margin: '8px 0 0' }}>Saved. Plans use them once a revision is published.</p>}
      {rows && (
        <form aria-label="Edit requirements" onSubmit={submit} style={{ display: 'grid', gap: '8px' }}>
          {rows.map((row, index) => (
            <fieldset key={index} aria-label={`Requirement ${index + 1}`} style={{ display: 'grid', gap: '4px', border: '1px solid #e2e8f0', padding: '6px' }}>
              <select aria-label="Kind" value={row.kind} onChange={(event) => {
                const kind = event.target.value as RequirementKind
                set(index, { kind, ref: '', unit: kind === 'time' ? 'min' : '' })
              }}>
                {KINDS.map((one) => <option key={one.value} value={one.value}>{one.label}</option>)}
              </select>
              {row.kind === 'equipment' && (
                <select aria-label="Equipment" value={row.ref} onChange={(event) => set(index, { ref: event.target.value })}>
                  <option value="">Any equipment</option>
                  {(equipment.data ?? []).map((one) =>
                    <option key={one.equipmentId} value={one.equipmentId}>{one.equipmentCode ?? one.equipmentName}</option>)}
                </select>
              )}
              {(row.kind === 'skill' || row.kind === 'compute') && (
                <input aria-label="Name" value={row.ref} placeholder="What" onChange={(event) => set(index, { ref: event.target.value })} />
              )}
              <input aria-label="Amount" inputMode="decimal" value={row.amount} placeholder="Amount"
                onChange={(event) => set(index, { amount: event.target.value })} />
              {row.kind === 'time' && (
                <select aria-label="Time unit" value={row.unit} onChange={(event) => set(index, { unit: event.target.value })}>
                  <option value="s">seconds</option>
                  <option value="min">minutes</option>
                  <option value="h">hours</option>
                </select>
              )}
              <select aria-label="Per" value={row.per} onChange={(event) => set(index, { per: event.target.value as RequirementPer })}>
                <option value="batch">per batch</option>
                <option value="output_unit">per output unit</option>
              </select>
              <button type="button" onClick={() => setRows((current) => current?.filter((_, at) => at !== index) ?? null)}>Remove</button>
            </fieldset>
          ))}
          <button type="button" disabled={rows.length >= 20} onClick={() => setRows((current) => [...(current ?? []), emptyRow()])}>
            Add requirement
          </button>
          <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
            <button type="submit" disabled={save.isPending}>{save.isPending ? 'Saving...' : 'Save requirements'}</button>
            <button type="button" onClick={() => { setRows(null); setProblem(null) }}>Cancel</button>
          </div>
        </form>
      )}
      {failure && <p role="alert" style={{ color: '#991b1b', margin: '8px 0 0' }}>{failure}</p>}
    </section>
  )
}
