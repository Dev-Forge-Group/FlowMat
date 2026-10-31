import { useRef, useState, type FormEvent } from 'react'
import {
  useCancelRunLaborMutation, useRecordRunLaborMutation, useRunLaborQuery, type RunLabor,
} from '../../../entities/production/api/useRunLabor'
import { useRequestRunCorrectionMutation } from '../../../entities/production/api/useRunCorrections'
import { useWorkerCalendarsQuery } from '../../../entities/workforce/api/useWorkerCalendars'
import type { RunCorrectionLineRequest } from '../../../shared/types/api'
import { errorMessage, errorStatus } from '../../../shared/lib/errorMessage'
import { newRequestId } from '../../../shared/lib/requestId'
import {
  firstWorker, laborChoices, laborSpan, laborSummary, laborTimes, laborTimesText, stepHint, stepSuggestion, stepSummary, suggestionHint,
} from '../model/laborModel'

/**
 * Who worked on a run and for how long (docs/domain/worker-calendar.md LB1-LB7). The minutes start from the person's share
 * of their shifts during the run less what is recorded, or from its clock times when they are given (LB31-LB32); there is no cost,
 * as there are no worker rates. A finished run's worked time changes through a run correction (LB9-LB11).
 */
export function RunLaborPanel({ projectId, runId, open }: { projectId: string; runId: string; open: boolean }) {
  const query = useRunLaborQuery(runId)
  const workers = useWorkerCalendarsQuery(projectId)
  const record = useRecordRunLaborMutation(runId)
  const cancel = useCancelRunLaborMutation(runId)
  // Null until picked or typed: the first of the crew with time left, and their suggestion, fill the form.
  const [userId, setUserId] = useState<string | null>(null)
  const [minutes, setMinutes] = useState<string | null>(null)
  const [note, setNote] = useState('')
  // '' is the whole run; otherwise a process step of the run (LB16).
  const [stepId, setStepId] = useState('')
  // Clock times as typed in the browser's time, both or neither (LB31).
  const [workedFrom, setWorkedFrom] = useState('')
  const [workedTo, setWorkedTo] = useState('')
  const [timeProblem, setTimeProblem] = useState<string | null>(null)
  // Counts edits, so a reply clears the form only when nothing was typed or picked after it was sent.
  const inputVersion = useRef(0)
  const edited = () => { inputVersion.current += 1 }
  // The key of an unconfirmed attempt: the same input after a lost reply is sent again under it (LB2).
  const [attempt, setAttempt] = useState<{ requestId: string; input: string } | null>(null)
  // Anything but the expected shape counts as no worked time rather than breaking the run page.
  const data = query.data && Array.isArray(query.data.lines) && Array.isArray(query.data.people) ? query.data : undefined
  const people = data?.people ?? []
  const chosen = userId ?? firstWorker(people)
  const person = people.find((one) => one.userId === chosen)
  const startedSteps = (Array.isArray(data?.steps) ? data.steps : []).filter((one) => one.startedAt)
  const step = startedSteps.find((one) => one.stepId === stepId)
  const suggested = step ? stepSuggestion(step, person, data?.lines ?? []) : person?.suggestedMinutes ?? null
  const span = laborSpan(workedFrom, workedTo)
  const shownMinutes = minutes ?? (span != null ? String(span) : suggested != null ? String(suggested) : '')
  const choices = laborChoices(people, Array.isArray(workers.data) ? workers.data : [])
  const hint = minutes !== null ? null : span != null ? 'From the clock times; fewer minutes leave out breaks.'
    : step ? (suggested != null ? stepHint(step, person) : null) : suggestionHint(person)
  const byStep = data ? stepSummary(data) : ''

  function submit(event: FormEvent) {
    event.preventDefault()
    const submittedVersion = inputVersion.current
    const times = laborTimes(workedFrom, workedTo)
    setTimeProblem(times.error)
    if (times.error) return
    const input = JSON.stringify([chosen, shownMinutes, note.trim(), step?.stepId ?? '', times.workedFrom ?? '', times.workedTo ?? ''])
    const requestId = attempt?.input === input ? attempt.requestId : newRequestId()
    setAttempt({ requestId, input })
    record.mutate(
      { requestId, userId: chosen, workedMinutes: Number(shownMinutes), note: note.trim() || undefined, stepId: step?.stepId,
        workedFrom: times.workedFrom, workedTo: times.workedTo },
      {
        onSuccess: () => {
          setAttempt(null)
          if (inputVersion.current === submittedVersion) {
            setMinutes(null)
            setNote('')
            setWorkedFrom('')
            setWorkedTo('')
          }
        },
        onError: (error) => {
          const status = errorStatus(error)
          if (status != null && status < 500) setAttempt(null)
        },
      },
    )
  }

  // A finished run shows its worked time when there is some, or time left to correct in (LB11).
  if (!open && !query.isError && !(data && (data.lines.length > 0 || people.some((one) => one.suggestedMinutes != null)))) return null
  return (
    <section aria-label="Labor" style={{ marginTop: 16 }}>
      <h4>Labor (actual)</h4>
      <p className="inspector-hint">Who worked on this run; not part of material cost.</p>
      {query.isError && (
        <div>
          <p role="alert">{errorMessage(query.error, 'Could not load the worked time.')}</p>
          <button type="button" disabled={query.isFetching} onClick={() => void query.refetch()}>Retry labor lookup</button>
        </div>
      )}
      {data && data.lines.length > 0 && (
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
          <thead><tr><th style={{ textAlign: 'left' }}>Worker</th><th>Minutes</th><th /></tr></thead>
          <tbody>
            {data.lines.map((line) => (
              <tr key={line.runLaborId} style={{ opacity: line.cancelled ? 0.5 : 1 }}>
                <td>
                  {line.userId}{line.stepName ? ` · ${line.stepName}` : ''}{laborTimesText(line)}{line.note ? ` · ${line.note}` : ''}
                  {line.cancelled && <span> · cancelled: {line.cancelReason}</span>}
                </td>
                <td style={{ textAlign: 'right' }}>{line.workedMinutes}</td>
                <td>
                  {open && !line.cancelled && (
                    <button
                      type="button"
                      disabled={cancel.isPending}
                      onClick={() => {
                        const reason = window.prompt('Why does this worked time not count?')
                        if (reason?.trim()) cancel.mutate({ runLaborId: line.runLaborId, reason: reason.trim() })
                      }}
                    >
                      Cancel worked time
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {data && <p>{laborSummary(data)}</p>}
      {byStep && <p className="inspector-hint">{byStep}</p>}
      {cancel.isError && <p role="alert">{errorMessage(cancel.error, 'Failed to cancel the worked time.')}</p>}
      {open && !query.isError && (
        <form onSubmit={submit} style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'end' }}>
          <label style={{ display: 'grid', gap: 4 }}>
            <span>Worker</span>
            <select aria-label="Worker" value={chosen} onChange={(e) => { edited(); setUserId(e.target.value); setMinutes(null) }}>
              <option value="">Choose who worked</option>
              {choices.map((one) => <option key={one.userId} value={one.userId}>{one.label}</option>)}
            </select>
          </label>
          {startedSteps.length > 0 && (
            <label style={{ display: 'grid', gap: 4 }}>
              <span>Step</span>
              <select aria-label="Labor step" value={step?.stepId ?? ''} onChange={(e) => { edited(); setStepId(e.target.value); setMinutes(null) }}>
                <option value="">Whole run</option>
                {startedSteps.map((one) => (
                  <option key={one.stepId} value={one.stepId}>{one.sequenceNo != null ? `#${one.sequenceNo} ` : ''}{one.nodeName ?? one.stepId}</option>
                ))}
              </select>
            </label>
          )}
          <label style={{ display: 'grid', gap: 4 }}>
            <span>Worked from</span>
            <input type="datetime-local" aria-label="Worked from" value={workedFrom}
              onChange={(e) => { edited(); setWorkedFrom(e.target.value); setMinutes(null) }} />
          </label>
          <label style={{ display: 'grid', gap: 4 }}>
            <span>Worked to</span>
            <input type="datetime-local" aria-label="Worked to" value={workedTo}
              onChange={(e) => { edited(); setWorkedTo(e.target.value); setMinutes(null) }} />
          </label>
          <label style={{ display: 'grid', gap: 4 }}>
            <span>Worked minutes</span>
            <input type="number" min={1} max={10080} step={1} required value={shownMinutes}
              onChange={(e) => { edited(); setMinutes(e.target.value) }} />
          </label>
          <label style={{ display: 'grid', gap: 4 }}>
            <span>Labor note</span>
            <input maxLength={500} value={note} onChange={(e) => { edited(); setNote(e.target.value) }} />
          </label>
          <button type="submit" disabled={record.isPending || !chosen || !shownMinutes}>
            {record.isPending ? 'Recording...' : attempt ? 'Retry worked time' : 'Record worked time'}
          </button>
          {hint && <span className="inspector-hint" style={{ flexBasis: '100%' }}>{hint}</span>}
        </form>
      )}
      {timeProblem && <p role="alert">{timeProblem}</p>}
      {record.isError && <p role="alert">{errorMessage(record.error, 'Failed to record the worked time.')}</p>}
      {!open && data && <LaborCorrectionRequest runId={runId} data={data} choices={choices} />}
    </section>
  )
}

/**
 * For a finished run: worked time changes through a run correction the project owner approves (worker-calendar.md LB9-LB11).
 * The added line starts from the first of the crew with time not yet counted, and their suggestion.
 */
function LaborCorrectionRequest({ runId, data, choices }: {
  runId: string
  data: RunLabor
  choices: { userId: string; label: string }[]
}) {
  const request = useRequestRunCorrectionMutation(runId)
  const [cancelIds, setCancelIds] = useState<string[]>([])
  // Null until chosen or typed: the first of the crew with time left, and their suggestion, fill the added line.
  const [userId, setUserId] = useState<string | null>(null)
  const [minutes, setMinutes] = useState<string | null>(null)
  const [reason, setReason] = useState('')
  // '' is the whole run; otherwise a started process step of the run (LB17).
  const [stepId, setStepId] = useState('')
  const inputVersion = useRef(0)
  const edited = () => { inputVersion.current += 1 }
  const chosen = userId ?? firstWorker(data.people)
  const person = data.people.find((one) => one.userId === chosen)
  const startedSteps = (Array.isArray(data.steps) ? data.steps : []).filter((one) => one.startedAt)
  const step = startedSteps.find((one) => one.stepId === stepId)
  const suggested = step ? stepSuggestion(step, person, data.lines) : person?.suggestedMinutes ?? null
  const shownMinutes = minutes ?? (suggested != null ? String(suggested) : '')
  const hint = minutes !== null ? null : step ? (suggested != null ? stepHint(step, person) : null) : suggestionHint(person)
  const lines: RunCorrectionLineRequest[] = [
    ...cancelIds.map((id) => ({ kind: 'cancel_labor' as const, targetRunLaborId: id })),
    ...(chosen && Number(shownMinutes) > 0
      ? [{ kind: 'add_labor' as const, userId: chosen, workedMinutes: Number(shownMinutes), ...(step ? { stepId: step.stepId } : {}) }]
      : []),
  ]
  return (
    <details style={{ marginTop: 8 }}>
      <summary>Request a worked time correction</summary>
      <p className="inspector-hint">The run is finished: worked time changes through a correction the project owner approves.</p>
      {data.lines.filter((line) => !line.cancelled).map((line) => (
        <label key={line.runLaborId} style={{ display: 'block', fontSize: 12 }}>
          <input
            type="checkbox"
            checked={cancelIds.includes(line.runLaborId)}
            onChange={(e) => { edited(); setCancelIds((ids) => (e.target.checked
              ? [...ids, line.runLaborId]
              : ids.filter((id) => id !== line.runLaborId))) }}
          />{' '}
          Cancel {line.userId} {line.workedMinutes} min
        </label>
      ))}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'end', marginTop: 6 }}>
        <label style={{ display: 'grid', gap: 4 }}>
          <span>Add worked time for</span>
          <select aria-label="Correction worker" value={chosen} onChange={(e) => { edited(); setUserId(e.target.value); setMinutes(null) }}>
            <option value="">No worked time to add</option>
            {choices.map((one) => <option key={one.userId} value={one.userId}>{one.label}</option>)}
          </select>
        </label>
        {startedSteps.length > 0 && (
          <label style={{ display: 'grid', gap: 4 }}>
            <span>Step</span>
            <select aria-label="Correction step" value={step?.stepId ?? ''} onChange={(e) => { edited(); setStepId(e.target.value); setMinutes(null) }}>
              <option value="">Whole run</option>
              {startedSteps.map((one) => (
                <option key={one.stepId} value={one.stepId}>{one.sequenceNo != null ? `#${one.sequenceNo} ` : ''}{one.nodeName ?? one.stepId}</option>
              ))}
            </select>
          </label>
        )}
        <label style={{ display: 'grid', gap: 4 }}>
          <span>Minutes</span>
          <input aria-label="Correction worked minutes" type="number" min={1} max={10080} step={1} value={shownMinutes}
            onChange={(e) => { edited(); setMinutes(e.target.value) }} />
        </label>
        <label style={{ display: 'grid', gap: 4, flex: 1 }}>
          <span>Reason</span>
          <input aria-label="Worked time correction reason" maxLength={500} value={reason}
            onChange={(e) => { edited(); setReason(e.target.value) }} />
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
                setUserId(null)
                setStepId('')
                setMinutes(null)
                setReason('')
              },
            })
          }}
        >
          Request correction
        </button>
        {hint && chosen && <span className="inspector-hint" style={{ flexBasis: '100%' }}>{hint}</span>}
      </div>
      {request.isError && <p role="alert">{errorMessage(request.error, 'Failed to request the correction.')}</p>}
      {request.isSuccess && (
        <p role="status">Requested correction #{request.data.correctionNo}; the project owner approves it under Corrections.</p>
      )}
    </details>
  )
}
