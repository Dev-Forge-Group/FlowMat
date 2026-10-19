import { useRef, useState, type FormEvent } from 'react'
import { useEquipmentQuery } from '../../../entities/catalog/api/useEquipment'
import {
  useCancelRunEquipmentTimeMutation, useRecordRunEquipmentTimeMutation, useRunEquipmentTimeQuery, type RunEquipmentTime,
} from '../../../entities/production/api/useRunEquipmentTime'
import { useRequestRunCorrectionMutation } from '../../../entities/production/api/useRunCorrections'
import type { EquipmentDto, RunCorrectionLineRequest } from '../../../shared/types/api'
import { errorMessage, errorStatus } from '../../../shared/lib/errorMessage'
import { newRequestId } from '../../../shared/lib/requestId'
import { formatQty } from './runDisplay'

/**
 * Equipment running time of a run at the equipment rate when each was recorded (docs/domain/equipment-setup-cost.md
 * ER1-ER7). The minutes start from the run's own time less what setups and earlier records already count; shown apart
 * from material cost, like setups. A finished run's time changes through a run correction (ER10).
 */
export function RunEquipmentTimePanel({ projectId, runId, open }: { projectId: string; runId: string; open: boolean }) {
  const query = useRunEquipmentTimeQuery(runId)
  const equipmentQuery = useEquipmentQuery(projectId)
  const record = useRecordRunEquipmentTimeMutation(runId)
  const cancel = useCancelRunEquipmentTimeMutation(runId)
  const [equipmentId, setEquipmentId] = useState('')
  // Null until typed: the field shows the suggestion, which follows the run as it goes on.
  const [minutes, setMinutes] = useState<string | null>(null)
  const [note, setNote] = useState('')
  const inputVersion = useRef(0)
  const edited = () => { inputVersion.current += 1 }
  // The key of an unconfirmed attempt: the same input after a lost reply is sent again under it (ER2).
  const [attempt, setAttempt] = useState<{ requestId: string; input: string } | null>(null)
  // Anything but the expected shape counts as no running time rather than breaking the run page.
  const data = query.data && Array.isArray(query.data.lines) ? query.data : undefined
  const chosen = equipmentId || data?.defaultEquipmentId || ''
  const shownMinutes = minutes ?? (data?.suggestedMinutes != null ? String(data.suggestedMinutes) : '')

  function submit(event: FormEvent) {
    event.preventDefault()
    const submittedVersion = inputVersion.current
    const input = JSON.stringify([chosen, shownMinutes, note.trim()])
    const requestId = attempt?.input === input ? attempt.requestId : newRequestId()
    setAttempt({ requestId, input })
    record.mutate(
      { requestId, equipmentId: chosen || undefined, runningMinutes: Number(shownMinutes), note: note.trim() || undefined },
      {
        onSuccess: () => {
          setAttempt(null)
          if (inputVersion.current === submittedVersion) {
            setMinutes(null)
            setNote('')
          }
        },
        onError: (error) => {
          const status = errorStatus(error)
          if (status != null && status < 500) setAttempt(null)
        },
      },
    )
  }

  // A finished run shows its time when there is some, or time left to correct in (ER10).
  if (!open && !query.isError && !(data && (data.lines.length > 0 || data.suggestedMinutes != null))) return null
  return (
    <section aria-label="Equipment running cost" style={{ marginTop: 16 }}>
      <h4>Equipment time (actual)</h4>
      <p className="inspector-hint">At the equipment rate when each was recorded; not part of material cost.</p>
      {query.isError && (
        <div>
          <p role="alert">{errorMessage(query.error, 'Could not load the running time.')}</p>
          <button type="button" disabled={query.isFetching} onClick={() => void query.refetch()}>Retry running time lookup</button>
        </div>
      )}
      {data && data.lines.length > 0 && (
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
          <thead><tr><th style={{ textAlign: 'left' }}>Equipment</th><th>Minutes</th><th>Rate</th><th>Cost</th><th /></tr></thead>
          <tbody>
            {data.lines.map((line) => (
              <tr key={line.runEquipmentTimeId} style={{ opacity: line.cancelled ? 0.5 : 1 }}>
                <td>
                  {line.equipmentLabel}{line.note ? ` · ${line.note}` : ''}
                  {line.cancelled && <span> · cancelled: {line.cancelReason}</span>}
                  {line.rateBasis === 'historical' && <span className="inspector-hint"> · rate at the run's finish</span>}
                  {line.rateBasis === 'estimated' && <span className="inspector-hint"> · estimated rate</span>}
                </td>
                <td style={{ textAlign: 'right' }}>{line.runningMinutes}</td>
                <td style={{ textAlign: 'right' }}>{line.hourlyCost == null ? 'no rate' : `${formatQty(line.hourlyCost)}/h`}</td>
                <td style={{ textAlign: 'right' }}>{line.runningCost == null ? 'unknown' : formatQty(line.runningCost)}</td>
                <td>
                  {open && !line.cancelled && (
                    <button
                      type="button"
                      disabled={cancel.isPending}
                      onClick={() => {
                        const reason = window.prompt('Why does this running time not count?')
                        if (reason?.trim()) cancel.mutate({ runEquipmentTimeId: line.runEquipmentTimeId, reason: reason.trim() })
                      }}
                    >
                      Cancel time
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {data && (
        <p>
          {data.runningMinutes} min · {data.costComplete ? 'Running cost' : 'Known running cost'}: {formatQty(data.runningCost)}
          {!data.costComplete && <span className="inspector-hint"> (some equipment had no rate)</span>}
        </p>
      )}
      {cancel.isError && <p role="alert">{errorMessage(cancel.error, 'Failed to cancel the running time.')}</p>}
      {open && !query.isError && (
        <form onSubmit={submit} style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'end' }}>
          <label style={{ display: 'grid', gap: 4 }}>
            <span>Running equipment</span>
            <select aria-label="Running equipment" value={chosen} onChange={(e) => { edited(); setEquipmentId(e.target.value) }}>
              <option value="">Choose equipment</option>
              {(equipmentQuery.data ?? []).map((one) => (
                <option key={one.equipmentId} value={one.equipmentId}>{one.equipmentCode ?? one.equipmentName}</option>
              ))}
            </select>
          </label>
          <label style={{ display: 'grid', gap: 4 }}>
            <span>Running minutes</span>
            <input type="number" min={1} max={10080} step={1} required value={shownMinutes} onChange={(e) => { edited(); setMinutes(e.target.value) }} />
          </label>
          <label style={{ display: 'grid', gap: 4 }}>
            <span>Running note</span>
            <input maxLength={500} value={note} onChange={(e) => { edited(); setNote(e.target.value) }} />
          </label>
          <button type="submit" disabled={record.isPending || !chosen || !shownMinutes}>
            {record.isPending ? 'Recording...' : attempt ? 'Retry running time' : 'Record running time'}
          </button>
          {minutes === null && data?.suggestedMinutes != null && (
            <span className="inspector-hint" style={{ flexBasis: '100%' }}>
              Suggested from the run's time so far, less recorded setups and running time.
            </span>
          )}
        </form>
      )}
      {record.isError && <p role="alert">{errorMessage(record.error, 'Failed to record the running time.')}</p>}
      {!open && data && <RunningTimeCorrectionRequest runId={runId} data={data} equipment={equipmentQuery.data ?? []} />}
    </section>
  )
}

/**
 * For a finished run: running time changes through a run correction the project owner approves (equipment-setup-cost.md
 * ER10). The added line starts from the work order's equipment and the run's time not yet counted.
 */
function RunningTimeCorrectionRequest({ runId, data, equipment }: {
  runId: string
  data: RunEquipmentTime
  equipment: EquipmentDto[]
}) {
  const request = useRequestRunCorrectionMutation(runId)
  const [cancelIds, setCancelIds] = useState<string[]>([])
  // Null until chosen or typed: the run's equipment and its uncounted time fill the added line.
  const [equipmentId, setEquipmentId] = useState<string | null>(null)
  const [minutes, setMinutes] = useState<string | null>(null)
  const [reason, setReason] = useState('')
  const inputVersion = useRef(0)
  const edited = () => { inputVersion.current += 1 }
  const chosen = equipmentId ?? data.defaultEquipmentId ?? ''
  const shownMinutes = minutes ?? (data.suggestedMinutes != null ? String(data.suggestedMinutes) : '')
  const lines: RunCorrectionLineRequest[] = [
    ...cancelIds.map((id) => ({ kind: 'cancel_running_time' as const, targetRunEquipmentTimeId: id })),
    ...(chosen && Number(shownMinutes) > 0
      ? [{ kind: 'add_running_time' as const, equipmentId: chosen, runningMinutes: Number(shownMinutes) }]
      : []),
  ]
  return (
    <details style={{ marginTop: 8 }}>
      <summary>Request a running time correction</summary>
      <p className="inspector-hint">The run is finished: running time changes through a correction the project owner approves.</p>
      {data.lines.filter((line) => !line.cancelled).map((line) => (
        <label key={line.runEquipmentTimeId} style={{ display: 'block', fontSize: 12 }}>
          <input
            type="checkbox"
            checked={cancelIds.includes(line.runEquipmentTimeId)}
            onChange={(e) => { edited(); setCancelIds((ids) => (e.target.checked
              ? [...ids, line.runEquipmentTimeId]
              : ids.filter((id) => id !== line.runEquipmentTimeId))) }}
          />{' '}
          Cancel {line.equipmentLabel} {line.runningMinutes} min
        </label>
      ))}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'end', marginTop: 6 }}>
        <label style={{ display: 'grid', gap: 4 }}>
          <span>Add running time on</span>
          <select aria-label="Correction running equipment" value={chosen} onChange={(e) => { edited(); setEquipmentId(e.target.value) }}>
            <option value="">No running time to add</option>
            {equipment.map((one) => <option key={one.equipmentId} value={one.equipmentId}>{one.equipmentCode ?? one.equipmentName}</option>)}
          </select>
        </label>
        <label style={{ display: 'grid', gap: 4 }}>
          <span>Minutes</span>
          <input aria-label="Correction running minutes" type="number" min={1} max={10080} step={1} value={shownMinutes}
            onChange={(e) => { edited(); setMinutes(e.target.value) }} />
        </label>
        <label style={{ display: 'grid', gap: 4, flex: 1 }}>
          <span>Reason</span>
          <input aria-label="Running time correction reason" maxLength={500} value={reason} onChange={(e) => { edited(); setReason(e.target.value) }} />
        </label>
        <button
          type="button"
          disabled={request.isPending || lines.length === 0 || !reason.trim()}
          onClick={() => {
            const submittedVersion = inputVersion.current
            request.mutate({ reason: reason.trim(), lines }, {
              onSuccess: () => {
                if (inputVersion.current !== submittedVersion) return
                setCancelIds([])
                setEquipmentId(null)
                setMinutes(null)
                setReason('')
              },
            })
          }}
        >
          Request correction
        </button>
        {minutes === null && data.suggestedMinutes != null && chosen && (
          <span className="inspector-hint" style={{ flexBasis: '100%' }}>
            Suggested from the run's time, less recorded setups and running time.
          </span>
        )}
      </div>
      {request.isError && <p role="alert">{errorMessage(request.error, 'Failed to request the correction.')}</p>}
      {request.isSuccess && (
        <p role="status">Requested correction #{request.data.correctionNo}; the project owner approves it under Corrections.</p>
      )}
    </details>
  )
}
