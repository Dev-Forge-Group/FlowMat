import { useState } from 'react'
import { usePlanActualQuery, usePlanConfirmationsQuery, type PlanActual } from '../../../entities/production/api/usePlanActuals'
import { errorMessage } from '../../../shared/lib/errorMessage'

interface Props {
  workflowId: string
  nameOf(processId: string): string
}

const amount = (value: number | null | undefined) => value == null ? '—' : String(Number(value.toFixed(4)))
const day = (iso: string) => new Date(iso).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })

function difference(percent: number | null, within: boolean | null) {
  if (percent == null) return '—'
  const text = `${percent > 0 ? '+' : ''}${percent}%`
  return within === false ? `${text} off` : text
}

/**
 * The workflow's confirmed plans and, for the one chosen, plan against actual (docs/domain/plan-vs-actual.md): what the
 * finished runs used and how long their steps took, beside the plan for what they made. Outside the targets is marked off.
 */
export function PlanActuals({ workflowId, nameOf }: Props) {
  const confirmations = usePlanConfirmationsQuery(workflowId)
  const [workOrderId, setWorkOrderId] = useState<string | null>(null)
  const actual = usePlanActualQuery(workOrderId)
  const rows = confirmations.data ?? []
  if (rows.length === 0) return null

  return (
    <div role="group" aria-label="Confirmed plans" style={{ marginTop: '10px', borderTop: '1px solid #e2e8f0', paddingTop: '8px' }}>
      <strong>Confirmed plans</strong>
      <ul style={{ margin: '4px 0 0', paddingLeft: '18px' }}>
        {rows.map((row) => (
          <li key={row.requestId}>
            {amount(row.targetQuantity)} on {day(row.confirmedAt)}{' '}
            <button type="button" onClick={() => setWorkOrderId(row.workOrderId)} aria-pressed={workOrderId === row.workOrderId}>
              Plan vs actual
            </button>
          </li>
        ))}
      </ul>
      {actual.isError && <p role="alert" style={{ color: '#991b1b' }}>{errorMessage(actual.error)}</p>}
      {actual.data && <Comparison data={actual.data} nameOf={nameOf} />}
    </div>
  )
}

function Comparison({ data, nameOf }: { data: PlanActual; nameOf(processId: string): string }) {
  return (
    <div role="group" aria-label="Plan vs actual" style={{ marginTop: '6px' }}>
      <p style={{ margin: '4px 0' }}>
        Made {amount(data.output)} of {amount(data.target)} {data.targetUnit ?? ''} in {data.finishedRuns} finished run(s).
        {' '}Targets: material within {data.materialTolerancePercent}%, time within {data.timeTolerancePercent}%.
      </p>
      <table style={{ borderCollapse: 'collapse' }}>
        <thead><tr><th align="left">Material</th><th align="right">Planned</th><th align="right">Used</th><th align="right">Difference</th></tr></thead>
        <tbody>
          {data.materials.map((row) => (
            <tr key={row.itemId} aria-label={row.itemName} style={{ color: row.within === false ? '#991b1b' : undefined }}>
              <td>{row.itemName}</td>
              <td align="right">{amount(row.planned)} {row.unit ?? ''}</td>
              <td align="right">{amount(row.used)} {row.unit ?? ''}</td>
              <td align="right">{difference(row.variancePercent, row.within)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {data.steps.length > 0 && (
        <table style={{ borderCollapse: 'collapse', marginTop: '6px' }}>
          <thead><tr><th align="left">Step</th><th align="right">Planned min</th><th align="right">Actual min</th><th align="right">Difference</th></tr></thead>
          <tbody>
            {data.steps.map((row) => (
              <tr key={row.processId} aria-label={row.processName ?? nameOf(row.processId)}
                style={{ color: row.within === false ? '#991b1b' : undefined }}>
                <td>{row.processName ?? nameOf(row.processId)}</td>
                <td align="right">{amount(row.planned)}</td>
                <td align="right">{amount(row.actual)}</td>
                <td align="right">{difference(row.variancePercent, row.within)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {data.notes.length > 0 && (
        <ul aria-label="Notes" style={{ margin: '6px 0 0', paddingLeft: '18px' }}>
          {data.notes.map((note) => <li key={note}>{note}</li>)}
        </ul>
      )}
    </div>
  )
}
