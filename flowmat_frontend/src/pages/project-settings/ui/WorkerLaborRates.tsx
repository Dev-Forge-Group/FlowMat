import { useState, type FormEvent } from 'react'
import { useWorkerLaborRateMutations, useWorkerLaborRatesQuery } from '../../../entities/workforce/api/useWorkerLaborRates'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { rateLineText } from '../../runs/model/laborCostModel'
import { WorkerAttendancePay } from './WorkerAttendancePay'
import { WorkerLaborPremiums } from './WorkerLaborPremiums'

/**
 * A worker's hourly rate in this project, for the project owner only (docs/architecture/adr/ADR-025-worker-labor-cost.md). A
 * new rate applies from today or a later project date and earlier dates keep theirs; one that has not started can be removed.
 * The first rate may cover the time before it instead (decision 11). Loaded when opened.
 */
export function WorkerLaborRates({ projectId, userId }: { projectId: string; userId: string }) {
  const [open, setOpen] = useState(false)
  const query = useWorkerLaborRatesQuery(projectId, userId, open)
  const { set, remove } = useWorkerLaborRateMutations(projectId, userId)
  const [rate, setRate] = useState('')
  const [from, setFrom] = useState('')
  const [reason, setReason] = useState('')
  const [fromStart, setFromStart] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const data = !query.isError && query.data && Array.isArray(query.data.lines) ? query.data : undefined
  const first = data ? data.lines.every((line) => line.state === 'removed') : false

  function submit(event: FormEvent) {
    event.preventDefault()
    const value = rate.trim() === '' ? null : Number(rate)
    if (value !== null && (!Number.isFinite(value) || value < 0)) {
      setProblem('Enter the rate per hour as a number, 0 or more, or leave it empty when unknown.')
      return
    }
    setProblem(null)
    const back = first && fromStart
    set.mutate({ hourlyRate: value, effectiveFrom: back ? undefined : from || undefined, reason: reason.trim() || undefined,
      fromStart: back || undefined }, { onSuccess: () => { setRate(''); setFrom(''); setReason(''); setFromStart(false) } })
  }

  return (
    <details onToggle={(event) => setOpen(event.currentTarget.open)}>
      <summary>Labor rate</summary>
      {query.isError && <p role="alert">{errorMessage(query.error, 'Could not load the labor rates.')}</p>}
      {data && <p aria-label="Current labor rate">{data.current ? rateLineText(data.current) : 'No rate yet: worked time has no cost.'}</p>}
      {data && data.lines.length > 0 && (
        <ul aria-label="Labor rate history" style={{ margin: '4px 0', paddingLeft: 18 }}>
          {data.lines.map((line) => (
            <li key={line.rateId}>
              {rateLineText(line)}
              {line.state === 'upcoming' && <>{' '}<button type="button" aria-label={`Remove rate from ${line.effectiveFrom}`}
                disabled={remove.isPending} onClick={() => remove.mutate(line.rateId)}>Remove</button></>}
            </li>
          ))}
        </ul>
      )}
      {data && (
        <form aria-label="Set labor rate" onSubmit={submit} style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'end' }}>
          <label style={{ display: 'grid', gap: 4 }}>Per hour
            <input aria-label="Hourly rate" inputMode="decimal" placeholder="unknown" value={rate}
              onChange={(event) => setRate(event.target.value)} />
          </label>
          <label style={{ display: 'grid', gap: 4 }}>Applies from
            <input aria-label="Rate applies from" type="date" min={data.today} value={from} disabled={first && fromStart}
              onChange={(event) => setFrom(event.target.value)} />
          </label>
          {first && (
            <label style={{ display: 'flex', gap: 4, alignItems: 'center' }}>
              <input type="checkbox" checked={fromStart} onChange={(event) => setFromStart(event.target.checked)} />
              From the start (worked time already recorded gets this rate)
            </label>
          )}
          <label style={{ display: 'grid', gap: 4 }}>Reason
            <input aria-label="Rate reason" maxLength={200} value={reason} onChange={(event) => setReason(event.target.value)} />
          </label>
          <button type="submit" disabled={set.isPending}>Save rate</button>
        </form>
      )}
      {problem && <p role="alert">{problem}</p>}
      {set.isError && <p role="alert">{errorMessage(set.error, 'Failed to save the rate.')}</p>}
      {remove.isError && <p role="alert">{errorMessage(remove.error, 'Failed to remove the rate.')}</p>}
      <p className="inspector-hint">Only the project owner sees rates and labor cost. Leave Applies from empty for today.</p>
      {data && <WorkerAttendancePay projectId={projectId} userId={userId} />}
      {data && <WorkerLaborPremiums projectId={projectId} userId={userId} />}
    </details>
  )
}
