import { useRef, useState, type FormEvent } from 'react'
import { useEquipmentQuery } from '../../../entities/catalog/api/useEquipment'
import {
  useCancelRunSetupMutation, useRecordRunSetupMutation, useRunSetupsQuery, type RunSetupCost,
} from '../../../entities/production/api/useRunSetups'
import { useRequestRunCorrectionMutation } from '../../../entities/production/api/useRunCorrections'
import type { EquipmentDto, RunCorrectionLineRequest } from '../../../shared/types/api'
import { errorMessage, errorStatus } from '../../../shared/lib/errorMessage'
import { newRequestId } from '../../../shared/lib/requestId'
import { formatQty } from './runDisplay'

/**
 * Actual setups of a run at the equipment rate when each was recorded (docs/domain/equipment-setup-cost.md AS1-AS6).
 * Shown apart from material cost; the planned estimate stays on the equipment load.
 */
export function RunSetupPanel({ projectId, runId, open }: { projectId: string; runId: string; open: boolean }) {
  const query = useRunSetupsQuery(runId)
  const equipmentQuery = useEquipmentQuery(projectId)
  const record = useRecordRunSetupMutation(runId)
  const cancel = useCancelRunSetupMutation(runId)
  const [equipmentId, setEquipmentId] = useState('')
  const [minutes, setMinutes] = useState('')
  const [note, setNote] = useState('')
  const inputVersion = useRef(0)
  const edited = () => { inputVersion.current += 1 }
  // The key of an unconfirmed attempt: the same input after a lost reply is sent again under it (AS3).
  const [attempt, setAttempt] = useState<{ requestId: string; input: string } | null>(null)
  // Anything but the expected shape counts as no setups rather than breaking the run page.
  const data = query.data && Array.isArray(query.data.lines) ? query.data : undefined
  const chosen = equipmentId || data?.defaultEquipmentId || ''

  function submit(event: FormEvent) {
    event.preventDefault()
    const submittedVersion = inputVersion.current
    const input = JSON.stringify([chosen, minutes, note.trim()])
    const requestId = attempt?.input === input ? attempt.requestId : newRequestId()
    setAttempt({ requestId, input })
    record.mutate(
      { requestId, equipmentId: chosen || undefined, setupMinutes: Number(minutes), note: note.trim() || undefined },
      {
        onSuccess: () => {
          setAttempt(null)
          if (inputVersion.current === submittedVersion) {
            setMinutes('')
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

  if (!open && !query.isError && !data) return null
  return (
    <section aria-label="Setup cost" style={{ marginTop: 16 }}>
      <h4>Setup (actual)</h4>
      <p className="inspector-hint">At the equipment rate when each setup was recorded; not part of material cost.</p>
      {query.isError && (
        <div>
          <p role="alert">{errorMessage(query.error, 'Could not load the setups.')}</p>
          <button type="button" disabled={query.isFetching} onClick={() => void query.refetch()}>Retry setup lookup</button>
        </div>
      )}
      {data && data.lines.length > 0 && (
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
          <thead><tr><th style={{ textAlign: 'left' }}>Equipment</th><th>Minutes</th><th>Rate</th><th>Cost</th><th /></tr></thead>
          <tbody>
            {data.lines.map((line) => (
              <tr key={line.runSetupId} style={{ opacity: line.cancelled ? 0.5 : 1 }}>
                <td>
                  {line.equipmentLabel}{line.note ? ` · ${line.note}` : ''}
                  {line.cancelled && <span> · cancelled: {line.cancelReason}</span>}
                  {line.rateBasis === 'historical' && <span className="inspector-hint"> · rate at the run's finish</span>}
                  {line.rateBasis === 'estimated' && <span className="inspector-hint"> · estimated rate</span>}
                </td>
                <td style={{ textAlign: 'right' }}>{line.setupMinutes}</td>
                <td style={{ textAlign: 'right' }}>{line.hourlyCost == null ? 'no rate' : `${formatQty(line.hourlyCost)}/h`}</td>
                <td style={{ textAlign: 'right' }}>{line.setupCost == null ? 'unknown' : formatQty(line.setupCost)}</td>
                <td>
                  {open && !line.cancelled && (
                    <button
                      type="button"
                      disabled={cancel.isPending}
                      onClick={() => {
                        const reason = window.prompt('Why does this setup not count?')
                        if (reason?.trim()) cancel.mutate({ runSetupId: line.runSetupId, reason: reason.trim() })
                      }}
                    >
                      Cancel setup
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
          {data.setupMinutes} min · {data.costComplete ? 'Setup cost' : 'Known setup cost'}: {formatQty(data.setupCost)}
          {!data.costComplete && <span className="inspector-hint"> (some equipment had no rate)</span>}
        </p>
      )}
      {cancel.isError && <p role="alert">{errorMessage(cancel.error, 'Failed to cancel the setup.')}</p>}
      {open && !query.isError && (
        <form onSubmit={submit} style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'end' }}>
          <label style={{ display: 'grid', gap: 4 }}>
            <span>Setup equipment</span>
            <select aria-label="Setup equipment" value={chosen} onChange={(e) => { edited(); setEquipmentId(e.target.value) }}>
              <option value="">Choose equipment</option>
              {(equipmentQuery.data ?? []).map((one) => (
                <option key={one.equipmentId} value={one.equipmentId}>{one.equipmentCode ?? one.equipmentName}</option>
              ))}
            </select>
          </label>
          <label style={{ display: 'grid', gap: 4 }}>
            <span>Setup minutes</span>
            <input type="number" min={1} max={1440} step={1} required value={minutes} onChange={(e) => { edited(); setMinutes(e.target.value) }} />
          </label>
          <label style={{ display: 'grid', gap: 4 }}>
            <span>Setup note</span>
            <input maxLength={500} value={note} onChange={(e) => { edited(); setNote(e.target.value) }} />
          </label>
          <button type="submit" disabled={record.isPending || !chosen}>
            {record.isPending ? 'Recording...' : attempt ? 'Retry setup' : 'Record setup'}
          </button>
        </form>
      )}
      {record.isError && <p role="alert">{errorMessage(record.error, 'Failed to record the setup.')}</p>}
      {!open && data && <SetupCorrectionRequest runId={runId} data={data} equipment={equipmentQuery.data ?? []} />}
    </section>
  )
}

/** For a finished run: setups change through a run correction the project owner approves (equipment-setup-cost.md AS7). */
function SetupCorrectionRequest({ runId, data, equipment }: { runId: string; data: RunSetupCost; equipment: EquipmentDto[] }) {
  const request = useRequestRunCorrectionMutation(runId)
  const [cancelIds, setCancelIds] = useState<string[]>([])
  const [equipmentId, setEquipmentId] = useState('')
  const [minutes, setMinutes] = useState('')
  const [reason, setReason] = useState('')
  const inputVersion = useRef(0)
  const edited = () => { inputVersion.current += 1 }
  const lines: RunCorrectionLineRequest[] = [
    ...cancelIds.map((id) => ({ kind: 'cancel_setup' as const, targetRunSetupId: id })),
    ...(equipmentId && Number(minutes) > 0 ? [{ kind: 'add_setup' as const, equipmentId, setupMinutes: Number(minutes) }] : []),
  ]
  return (
    <details style={{ marginTop: 8 }}>
      <summary>Request a setup correction</summary>
      <p className="inspector-hint">The run is finished: setups change through a correction the project owner approves.</p>
      {data.lines.filter((line) => !line.cancelled).map((line) => (
        <label key={line.runSetupId} style={{ display: 'block', fontSize: 12 }}>
          <input
            type="checkbox"
            checked={cancelIds.includes(line.runSetupId)}
            onChange={(e) => { edited(); setCancelIds((ids) => (e.target.checked ? [...ids, line.runSetupId] : ids.filter((id) => id !== line.runSetupId))) }}
          />{' '}
          Cancel {line.equipmentLabel} {line.setupMinutes} min
        </label>
      ))}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'end', marginTop: 6 }}>
        <label style={{ display: 'grid', gap: 4 }}>
          <span>Add a setup on</span>
          <select aria-label="Correction setup equipment" value={equipmentId} onChange={(e) => { edited(); setEquipmentId(e.target.value) }}>
            <option value="">No setup to add</option>
            {equipment.map((one) => <option key={one.equipmentId} value={one.equipmentId}>{one.equipmentCode ?? one.equipmentName}</option>)}
          </select>
        </label>
        <label style={{ display: 'grid', gap: 4 }}>
          <span>Minutes</span>
          <input aria-label="Correction setup minutes" type="number" min={1} max={1440} step={1} value={minutes}
            onChange={(e) => { edited(); setMinutes(e.target.value) }} />
        </label>
        <label style={{ display: 'grid', gap: 4, flex: 1 }}>
          <span>Reason</span>
          <input aria-label="Setup correction reason" maxLength={500} value={reason} onChange={(e) => { edited(); setReason(e.target.value) }} />
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
                setEquipmentId('')
                setMinutes('')
                setReason('')
              },
            })
          }}
        >
          Request correction
        </button>
      </div>
      {request.isError && <p role="alert">{errorMessage(request.error, 'Failed to request the correction.')}</p>}
      {request.isSuccess && (
        <p role="status">Requested correction #{request.data.correctionNo}; the project owner approves it under Corrections.</p>
      )}
    </details>
  )
}
