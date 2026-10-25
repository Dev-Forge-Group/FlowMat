import { useState, type FormEvent } from 'react'
import { useAttendanceComparisonQuery } from '../../../entities/production/api/useAttendanceComparison'
import {
  fetchWorkerAttendance,
  useWorkerAttendanceMutations,
  useWorkerAttendanceQuery,
} from '../../../entities/workforce/api/useWorkerAttendance'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { newRequestId } from '../../../shared/lib/requestId'
import { savedAtText } from '../../inventory/model/versionHistoryModel'
import { attendanceCsv, attendanceLineText, comparisonDayText, exportRange } from '../model/attendanceModel'

/**
 * A worker's attendance, for the worker themselves and the project owner (docs/architecture/adr/ADR-026-attendance.md): clock
 * in and out, or record a stretch afterwards; a mistake is cancelled with a reason. The last 7 days stand beside the worked
 * time recorded on runs, and a period of up to 62 days is downloaded as CSV (AT9). Loaded when opened.
 */
export function WorkerAttendance({ projectId, userId }: { projectId: string; userId: string }) {
  const [open, setOpen] = useState(false)
  const query = useWorkerAttendanceQuery(projectId, userId, open)
  const comparison = useAttendanceComparisonQuery(projectId, userId, open)
  const { record, clockOut, cancel } = useWorkerAttendanceMutations(projectId, userId)
  const [clockIn, setClockIn] = useState('')
  const [clockOutAt, setClockOutAt] = useState('')
  const [note, setNote] = useState('')
  const [cancelling, setCancelling] = useState<string | null>(null)
  const [reason, setReason] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  // The download period starts as the 7 days shown until changed (AT9).
  const [exportFrom, setExportFrom] = useState<string | null>(null)
  const [exportTo, setExportTo] = useState<string | null>(null)
  const [exporting, setExporting] = useState(false)
  const [exportError, setExportError] = useState<string | null>(null)
  const data = !query.isError && query.data && Array.isArray(query.data.lines) ? query.data : undefined
  const days = !comparison.isError && comparison.data && Array.isArray(comparison.data.days)
    ? comparison.data.days.filter((day) => day.attendedMinutes > 0 || day.laborMinutes > 0) : []
  const failure = record.error ?? clockOut.error ?? cancel.error

  function submit(event: FormEvent) {
    event.preventDefault()
    const from = new Date(clockIn)
    const until = clockOutAt ? new Date(clockOutAt) : null
    if (!clockIn || Number.isNaN(from.getTime()) || (until && Number.isNaN(until.getTime()))) {
      setProblem('Enter when the stretch began, and when it ended if it has.')
      return
    }
    setProblem(null)
    record.mutate({ requestId: newRequestId(), clockIn: from.toISOString(), clockOut: until?.toISOString(), note: note.trim() || undefined },
      { onSuccess: () => { setClockIn(''); setClockOutAt(''); setNote('') } })
  }

  async function downloadCsv(event: FormEvent) {
    event.preventDefault()
    if (!data) return
    const range = exportRange(exportFrom ?? data.from, exportTo ?? data.today)
    setExportError(range.error)
    if (!range.from) return
    setExporting(true)
    try {
      const period = await fetchWorkerAttendance(projectId, userId, range.from, range.to)
      const url = URL.createObjectURL(new Blob([attendanceCsv(period.lines)], { type: 'text/csv;charset=utf-8' }))
      const link = document.createElement('a')
      link.href = url
      link.download = `attendance-${userId}-${range.from}-${range.to}.csv`
      link.click()
      URL.revokeObjectURL(url)
    } catch (error) {
      setExportError(errorMessage(error, 'Could not read the attendance for that period.'))
    } finally {
      setExporting(false)
    }
  }

  function confirmCancel(attendanceId: string) {
    if (!reason.trim()) {
      setProblem('Give a reason when cancelling attendance.')
      return
    }
    setProblem(null)
    cancel.mutate({ attendanceId, reason: reason.trim() }, { onSuccess: () => { setCancelling(null); setReason('') } })
  }

  return (
    <details onToggle={(event) => setOpen(event.currentTarget.open)}>
      <summary>Attendance</summary>
      {query.isError && <p role="alert">{errorMessage(query.error, 'Could not load the attendance.')}</p>}
      {data && (data.open
        ? <p aria-label="At work">Clocked in since {savedAtText(data.open.clockIn)}.{' '}
          <button type="button" disabled={clockOut.isPending}
            onClick={() => data.open && clockOut.mutate({ attendanceId: data.open.attendanceId })}>Clock out now</button></p>
        : <p aria-label="At work">Not clocked in.{' '}
          <button type="button" disabled={record.isPending}
            onClick={() => record.mutate({ requestId: newRequestId(), clockIn: new Date().toISOString() })}>Clock in now</button></p>)}
      {days.length > 0 && (
        <ul aria-label="Attendance against worked time" style={{ margin: '4px 0', paddingLeft: 18 }}>
          {days.map((day) => <li key={day.date}>{comparisonDayText(day)}</li>)}
        </ul>
      )}
      {data && days.length === 0 && comparison.isSuccess && <p className="inspector-hint">No attendance or worked time in the last 7 days.</p>}
      {data && data.lines.length > 0 && (
        <ul aria-label="Attendance records" style={{ margin: '4px 0', paddingLeft: 18 }}>
          {data.lines.map((line) => (
            <li key={line.attendanceId} style={{ opacity: line.cancelled ? 0.6 : 1 }}>
              {attendanceLineText(line)}
              {!line.cancelled && cancelling !== line.attendanceId && <>{' '}
                <button type="button" aria-label={`Cancel attendance from ${savedAtText(line.clockIn)}`}
                  onClick={() => { setCancelling(line.attendanceId); setReason('') }}>Cancel</button></>}
              {cancelling === line.attendanceId && <>{' '}
                <input aria-label="Cancel reason" maxLength={500} value={reason} onChange={(event) => setReason(event.target.value)} />
                <button type="button" disabled={cancel.isPending} onClick={() => confirmCancel(line.attendanceId)}>Confirm cancel</button>
                <button type="button" onClick={() => setCancelling(null)}>Keep</button></>}
            </li>
          ))}
        </ul>
      )}
      {data && (
        <form aria-label="Record attendance" onSubmit={submit} style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'end' }}>
          <label style={{ display: 'grid', gap: 4 }}>Clock in
            <input type="datetime-local" aria-label="Clock in" value={clockIn} onChange={(event) => setClockIn(event.target.value)} />
          </label>
          <label style={{ display: 'grid', gap: 4 }}>Clock out
            <input type="datetime-local" aria-label="Clock out" value={clockOutAt} onChange={(event) => setClockOutAt(event.target.value)} />
          </label>
          <label style={{ display: 'grid', gap: 4 }}>Note
            <input aria-label="Attendance note" maxLength={200} value={note} onChange={(event) => setNote(event.target.value)} />
          </label>
          <button type="submit" disabled={record.isPending}>Save attendance</button>
        </form>
      )}
      {problem && <p role="alert">{problem}</p>}
      {failure && <p role="alert">{errorMessage(failure, 'Failed to save the attendance.')}</p>}
      {data && (
        <form aria-label="Attendance CSV" onSubmit={(event) => void downloadCsv(event)}
          style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'end', marginTop: 6 }}>
          <label style={{ display: 'grid', gap: 4 }}>From
            <input type="date" aria-label="Download from" value={exportFrom ?? data.from} onChange={(event) => setExportFrom(event.target.value)} />
          </label>
          <label style={{ display: 'grid', gap: 4 }}>To
            <input type="date" aria-label="Download to" value={exportTo ?? data.today} onChange={(event) => setExportTo(event.target.value)} />
          </label>
          <button type="submit" disabled={exporting}>{exporting ? 'Reading...' : 'Download CSV'}</button>
          {exportError && <p role="alert" style={{ margin: 0 }}>{exportError}</p>}
        </form>
      )}
      <p className="inspector-hint">Only the worker and the project owner see this. Worked time counts on the day it was recorded.</p>
    </details>
  )
}
