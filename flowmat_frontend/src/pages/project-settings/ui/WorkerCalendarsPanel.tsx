import { useState } from 'react'
import { useCurrentUserQuery } from '../../../entities/auth/api/useCurrentUserQuery'
import { useProjectTimeZone } from '../../../entities/project/api/useProjectTimeZone'
import {
  fetchWorkerSchedule, useAddWorkerDayOffMutation, useCopyWorkerDaysMutation, useCopyWorkerShiftsMutation, useRemoveWorkerDayMutation,
  useRemoveWorkerDayOffMutation, useRemoveWorkerScheduleMutation, useSetWorkerDayMutation,
  useSetWorkerShiftsMutation, useWorkerCalendarsQuery, useWorkerDayHistoryQuery, useWorkerScheduleHistoryQuery,
  type WorkerCalendarDto, type WorkerScheduleLineDto, type WorkerShiftInput,
} from '../../../entities/workforce/api/useWorkerCalendars'
import { errorMessage } from '../../../shared/lib/errorMessage'
import {
  DAY_NAMES, ROTA_WEEKS, dayOffText, rotaProblem, rotaShiftText, rotaText, scheduleText, scheduleUpcoming, startsOnProblem, weeklyText,
  workerDayText,
} from '../model/workerCalendarModel'
import { dateInputValue, weekStart } from '../../inventory/model/equipmentLoadModel'
import { EMPTY_DAY_FORM, copyableDays, dayPayload, nextDayShift, type DayForm } from '../../inventory/model/equipmentScheduleModel'
import { workerDayHistoryText, workerHistoryText } from '../../inventory/model/versionHistoryModel'
import { HistoryComparison } from '../../../features/schedule-history/ui/HistoryComparison'
import { OlderHistoryButton } from '../../../features/schedule-history/ui/OlderHistoryButton'
import { useOlderHistory } from '../../../features/schedule-history/model/useOlderHistory'
import { WorkerAttendance } from './WorkerAttendance'
import { WorkerLaborRates } from './WorkerLaborRates'
import { WorkerLoadBoard } from './WorkerLoadBoard'

/**
 * When each person in the project works (docs/domain/worker-calendar.md WC9): weekly shifts and days off; project holidays
 * apply to everyone. The owner changes anyone's; a member changes their own.
 */
export function WorkerCalendarsPanel({ projectId }: { projectId: string }) {
  const me = useCurrentUserQuery().data?.userId
  const query = useWorkerCalendarsQuery(projectId)
  const timeZone = useProjectTimeZone(projectId).query.data?.timeZone
  let today = dateInputValue(new Date())
  if (timeZone) {
    try {
      today = new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
    } catch {
      // Keep the browser date if this browser cannot read the project's zone.
    }
  }
  const workers = Array.isArray(query.data) ? query.data : []
  const iAmOwner = workers.some((worker) => worker.owner && worker.userId === me)
  const [loadOpen, setLoadOpen] = useState(false)

  return (
    <section aria-label="Workers" style={{ border: '1px solid var(--border)', borderRadius: 12, padding: 18, minWidth: 0 }}>
      <h2 style={{ margin: 0 }}>Workers</h2>
      <p className="inspector-hint">When each person in this project works. Project holidays apply to everyone.</p>
      {query.isError && <p role="alert">{errorMessage(query.error, 'Failed to load the workers.')}</p>}
      {workers.map((worker) => (
        <details key={worker.userId} aria-label={`Worker ${worker.userId}`} style={{ borderTop: '1px solid var(--border)', padding: '6px 0' }}>
          <summary style={{ cursor: 'pointer', fontSize: 13 }}>
            {worker.userId}{worker.owner ? ' (owner)' : ''} · {weeklyText(worker)}
            {worker.daysOff.length > 0 && ` · ${worker.daysOff.length} day${worker.daysOff.length === 1 ? '' : 's'} off listed`}
          </summary>
          <WorkerCalendarEditor projectId={projectId} worker={worker} today={today} editable={iAmOwner || worker.userId === me}
            copyTargets={iAmOwner ? workers.filter((one) => one.userId !== worker.userId).map((one) => one.userId) : []}
            ratesVisible={iAmOwner} />
        </details>
      ))}
      <details style={{ marginTop: 12 }} onToggle={(event) => setLoadOpen(event.currentTarget.open)}>
        <summary>Load by week</summary>
        {loadOpen && <WorkerLoadBoard projectId={projectId} />}
      </details>
    </section>
  )
}

type ShiftRow = { shiftStart: string; shiftEnd: string; workDays: number[]; rotaWeek: number }

/**
 * {@code copyTargets}: the other workers this one's day changes may be copied to (the owner's view only, WC42).
 * {@code ratesVisible}: the project owner sees and sets the worker's labor rate (ADR-025).
 */
function WorkerCalendarEditor({ projectId, worker, today, editable, copyTargets, ratesVisible = false }: {
  projectId: string; worker: WorkerCalendarDto; today: string; editable: boolean; copyTargets: string[]; ratesVisible?: boolean
}) {
  const setShifts = useSetWorkerShiftsMutation(projectId)
  const addDayOff = useAddWorkerDayOffMutation(projectId)
  const removeDayOff = useRemoveWorkerDayOffMutation(projectId)
  const [rows, setRows] = useState<ShiftRow[]>(
    () => worker.shifts.map(({ shiftStart, shiftEnd, workDays, rotaWeek }) => ({ shiftStart, shiftEnd, workDays, rotaWeek: rotaWeek ?? 1 })),
  )
  // 1 is no rota (WC44).
  const [rotaWeeks, setRotaWeeks] = useState(worker.rota?.weeks ?? 1)
  const [rotaStart, setRotaStart] = useState(worker.rota?.startsOn ?? '')
  const [rotaIssue, setRotaIssue] = useState<string | null>(null)
  const [dayOff, setDayOff] = useState({ firstDay: '', lastDay: '', reason: '' })
  const { firstDay, lastDay, reason } = dayOff
  // Empty is today; a later date saves the shifts as the version from then (ADR-013).
  const [startsOn, setStartsOn] = useState('')
  // Why the version is saved, sent as its reason (ADR-017).
  const [note, setNote] = useState('')
  const savedFrom = setShifts.variables?.effectiveFrom

  function update(index: number, patch: Partial<ShiftRow>) {
    setRows((current) => current.map((row, one) => (one === index ? { ...row, ...patch } : row)))
  }

  function toggleDay(index: number, day: number, on: boolean) {
    const days = rows[index].workDays
    update(index, { workDays: on ? [...days, day].sort((a, b) => a - b) : days.filter((one) => one !== day) })
  }

  function save() {
    const problem = rotaProblem(rotaWeeks, rotaStart, rows.map((row) => row.rotaWeek)) ?? startsOnProblem(startsOn, today)
    setRotaIssue(problem)
    if (problem) return
    // Without a rota every shift is in its one week, so the weeks are not sent (WC44).
    const inRota = rotaWeeks > 1
    const shifts: WorkerShiftInput[] = rows.map((row) => ({ shiftStart: row.shiftStart, shiftEnd: row.shiftEnd, workDays: row.workDays,
      ...(inRota ? { rotaWeek: row.rotaWeek } : {}) }))
    setShifts.mutate({ userId: worker.userId, shifts, rota: inRota ? { weeks: rotaWeeks, startsOn: rotaStart } : null,
      ...(startsOn ? { effectiveFrom: startsOn } : {}), ...(note.trim() ? { reason: note.trim() } : {}) })
  }

  /** Loads a version that has not started into the form, to change it (ADR-013). */
  function edit(line: WorkerScheduleLineDto) {
    setRotaIssue(null)
    fetchWorkerSchedule(projectId, worker.userId, line.scheduleId).then((version) => {
      setRows(version.shifts.map(({ shiftStart, shiftEnd, workDays, rotaWeek }) => ({ shiftStart, shiftEnd, workDays, rotaWeek: rotaWeek ?? 1 })))
      setRotaWeeks(version.rota?.weeks ?? 1)
      setRotaStart(version.rota?.startsOn ?? '')
      setStartsOn(line.effectiveFrom ?? '')
      setNote(version.reason ?? '')
    }, (error: unknown) => setRotaIssue(errorMessage(error, 'Failed to load the version.')))
  }

  function chooseWeeks(weeks: number) {
    setRotaWeeks(weeks)
    // A new rota starts this week unless another week is chosen.
    if (weeks > 1 && !rotaStart) setRotaStart(dateInputValue(weekStart(new Date())))
  }

  function addOff() {
    const submitted = dayOff
    addDayOff.mutate({ userId: worker.userId, firstDay, lastDay: lastDay || firstDay, reason: reason.trim() || undefined }, {
      onSuccess: () => setDayOff(current => current === submitted ? { firstDay: '', lastDay: '', reason: '' } : current),
    })
  }

  if (!editable) {
    return (
      <div style={{ fontSize: 12, padding: '4px 0 4px 12px' }}>
        {worker.shifts.length === 0
          ? <p>No shifts set.</p>
          : <ul>{worker.shifts.map((shift) => <li key={shift.shiftId}>{rotaShiftText(shift, worker.rota)}</li>)}</ul>}
        {worker.rota && <p>{rotaText(worker.rota)}</p>}
        <ScheduleVersions projectId={projectId} worker={worker} today={today} />
        <ScheduleHistory projectId={projectId} userId={worker.userId} />
        <DayHistory projectId={projectId} userId={worker.userId} />
        {worker.daysOff.length > 0 && <ul>{worker.daysOff.map((off) => <li key={off.dayOffId}>Off {dayOffText(off)}</li>)}</ul>}
        {(worker.days ?? []).length > 0 && <ul>{(worker.days ?? []).map((day) => <li key={day.date}>{workerDayText(day)}</li>)}</ul>}
        <p className="inspector-hint">Only the project owner or {worker.userId} can change this calendar.</p>
      </div>
    )
  }
  return (
    <div style={{ fontSize: 12, padding: '4px 0 4px 12px', display: 'grid', gridTemplateColumns: 'minmax(0, 1fr)', minWidth: 0, gap: 8 }}>
      <div role="group" aria-label="Rota" style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
        <label>Repeats every <select aria-label="Rota weeks" value={rotaWeeks} onChange={(event) => chooseWeeks(Number(event.target.value))}>
          {ROTA_WEEKS.map((weeks) => <option key={weeks} value={weeks}>{weeks === 1 ? '1 week' : `${weeks} weeks`}</option>)}
        </select></label>
        {rotaWeeks > 1 && <label>Week 1 starts <input aria-label="Rota starts" type="date" value={rotaStart}
          onChange={(event) => setRotaStart(event.target.value)} /></label>}
        {worker.rota && <span className="inspector-hint">{rotaText(worker.rota)}</span>}
      </div>
      {rows.map((row, index) => (
        <div key={index} role="group" aria-label={`Shift ${index + 1}`} style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
          {rotaWeeks > 1 && <select aria-label={`Shift ${index + 1} week`} value={row.rotaWeek}
            onChange={(event) => update(index, { rotaWeek: Number(event.target.value) })}>
            {ROTA_WEEKS.slice(0, Math.max(rotaWeeks, row.rotaWeek)).map((week) => <option key={week} value={week}>Week {week}</option>)}
          </select>}
          <input aria-label={`Shift ${index + 1} start`} type="time" value={row.shiftStart}
            onChange={(event) => update(index, { shiftStart: event.target.value })} />
          <span>to</span>
          <input aria-label={`Shift ${index + 1} end`} type="time" value={row.shiftEnd}
            onChange={(event) => update(index, { shiftEnd: event.target.value })} />
          {DAY_NAMES.map((name, dayIndex) => (
            <label key={name}>
              <input type="checkbox" aria-label={`Shift ${index + 1} ${name}`} checked={row.workDays.includes(dayIndex + 1)}
                onChange={(event) => toggleDay(index, dayIndex + 1, event.target.checked)} />{name}
            </label>
          ))}
          <button type="button" onClick={() => setRows((current) => current.filter((_, one) => one !== index))}>Remove shift</button>
        </div>
      ))}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <button type="button"
          onClick={() => setRows((current) => [...current,
            { shiftStart: '09:00', shiftEnd: '17:00', workDays: [1, 2, 3, 4, 5], rotaWeek: current[current.length - 1]?.rotaWeek ?? 1 }])}>
          Add shift
        </button>
        <label>Starts on <input aria-label="Shifts start on" type="date" min={today} value={startsOn}
          onChange={(event) => setStartsOn(event.target.value)} /></label>
        <label>Note <input aria-label="Shift version note" maxLength={200} value={note}
          onChange={(event) => setNote(event.target.value)} /></label>
        <button type="button" onClick={save} disabled={setShifts.isPending}>{setShifts.isPending ? 'Saving...' : 'Save shifts'}</button>
      </div>
      <span className="inspector-hint">Leave Starts on empty for today. Earlier dates keep the shifts they had.</span>
      {rotaIssue && <p role="alert">{rotaIssue}</p>}
      {setShifts.isError && <p role="alert">{errorMessage(setShifts.error, 'Failed to save the shifts.')}</p>}
      {setShifts.isSuccess && <p role="status">{savedFrom && savedFrom > today
        ? `Shifts saved from ${savedFrom}; until then the current shifts stay.`
        : `Shifts saved: ${weeklyText(setShifts.data)}.`}</p>}
      <ScheduleVersions projectId={projectId} worker={worker} today={today} onEdit={edit} />
      <ScheduleHistory projectId={projectId} userId={worker.userId} />
      <DayHistory projectId={projectId} userId={worker.userId} />
      {ratesVisible && <WorkerLaborRates projectId={projectId} userId={worker.userId} />}
      <WorkerAttendance projectId={projectId} userId={worker.userId} />

      <div>
        <strong>Days off</strong>
        {worker.daysOff.length === 0 && <p className="inspector-hint">None listed.</p>}
        <ul style={{ margin: '4px 0', paddingLeft: 18 }}>
          {worker.daysOff.map((off) => (
            <li key={off.dayOffId}>
              {dayOffText(off)}{' '}
              <button type="button" disabled={removeDayOff.isPending}
                onClick={() => removeDayOff.mutate({ userId: worker.userId, dayOffId: off.dayOffId })}>Remove</button>
            </li>
          ))}
        </ul>
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
          <input aria-label="First day off" type="date" value={firstDay} onChange={(event) => setDayOff(current => ({ ...current, firstDay: event.target.value }))} />
          <input aria-label="Last day off" type="date" value={lastDay} onChange={(event) => setDayOff(current => ({ ...current, lastDay: event.target.value }))} />
          <input aria-label="Day off reason" maxLength={200} placeholder="Reason" value={reason}
            onChange={(event) => setDayOff(current => ({ ...current, reason: event.target.value }))} />
          <button type="button" onClick={addOff} disabled={!firstDay || addDayOff.isPending}>Add day off</button>
        </div>
        {addDayOff.isError && <p role="alert">{errorMessage(addDayOff.error, 'Failed to add the day off.')}</p>}
        {removeDayOff.isError && <p role="alert">{errorMessage(removeDayOff.error, 'Failed to remove the day off.')}</p>}
      </div>
      {worker.calendarSet && copyTargets.length > 0 && <WorkerShiftCopy projectId={projectId} worker={worker} today={today} targets={copyTargets} />}
      <WorkerDayChanges projectId={projectId} worker={worker} copyTargets={copyTargets} />
    </div>
  )
}

/**
 * The worker's saved weekly shifts and rota given to other workers (docs/domain/worker-calendar.md WC51-WC54): with a rota
 * each chosen worker can be some weeks further on, so teams take turns.
 */
function WorkerShiftCopy({ projectId, worker, today, targets }: { projectId: string; worker: WorkerCalendarDto; today: string; targets: string[] }) {
  const copy = useCopyWorkerShiftsMutation(projectId)
  const weeks = worker.rota?.weeks ?? 1
  // Each chosen worker and the rota week they work while this worker works week 1.
  const [chosen, setChosen] = useState<Record<string, number>>({})
  const [copied, setCopied] = useState<string | null>(null)
  // Empty is today; a later date gives them the shifts from then (ADR-013).
  const [copyFrom, setCopyFrom] = useState('')
  const picked = Object.keys(chosen)

  function toggle(userId: string, on: boolean) {
    setChosen((current) => {
      const next = { ...current }
      if (on) next[userId] = 1
      else delete next[userId]
      return next
    })
  }

  function send() {
    const names = picked
    setCopied(null)
    const from = copyFrom
    copy.mutate({ userId: worker.userId, targets: names.map((userId) => ({ userId, offsetWeeks: chosen[userId] - 1 })),
      ...(from ? { effectiveFrom: from } : {}) }, {
      onSuccess: () => {
        setCopied(`Copied ${worker.userId}'s shifts to ${names.join(', ')}${from ? ` from ${from}` : ''}.`)
        setChosen({})
      },
    })
  }

  return (
    <div role="group" aria-label="Copy shifts to other workers" style={{ display: 'grid', gap: 4 }}>
      <strong>Copy shifts to other workers</strong>
      <span className="inspector-hint">
        The saved weekly shifts{weeks > 1 ? ' and rota' : ''}. Their own weekly shifts are replaced; their day changes and days
        off stay.
      </span>
      {targets.map((userId) => (
        <div key={userId} style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
          <label>
            <input type="checkbox" aria-label={`Copy to ${userId}`} checked={userId in chosen}
              onChange={(event) => toggle(userId, event.target.checked)} /> {userId}
          </label>
          {weeks > 1 && userId in chosen && <label>works week <select aria-label={`${userId} rota week`} value={chosen[userId]}
            onChange={(event) => setChosen((current) => ({ ...current, [userId]: Number(event.target.value) }))}>
            {ROTA_WEEKS.slice(0, weeks).map((week) => <option key={week} value={week}>{week}</option>)}
          </select> when {worker.userId} works week 1</label>}
        </div>
      ))}
      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
        <label>Starting <input aria-label="Copies start on" type="date" min={today} value={copyFrom}
          onChange={(event) => setCopyFrom(event.target.value)} /></label>
        <button type="button" onClick={send} disabled={picked.length === 0 || copy.isPending}>
          {copy.isPending ? 'Copying...' : `Copy to ${picked.length} worker${picked.length === 1 ? '' : 's'}`}
        </button>
      </div>
      {copied && <p role="status">{copied}</p>}
      {copy.isError && <p role="alert">{errorMessage(copy.error, 'Failed to copy the shifts.')}</p>}
    </div>
  )
}

/**
 * The versions of the worker's weekly shifts (docs/architecture/adr/ADR-013-rota-history.md): shown once there is more than one
 * or one still ahead. One that has not started can be loaded into the form ({@code onEdit}) or deleted.
 */
function ScheduleVersions({ projectId, worker, today, onEdit }: {
  projectId: string; worker: WorkerCalendarDto; today: string; onEdit?: (line: WorkerScheduleLineDto) => void
}) {
  const remove = useRemoveWorkerScheduleMutation(projectId)
  const lines = Array.isArray(worker.schedules) ? worker.schedules : []
  if (lines.length < 2 && !lines.some((line) => scheduleUpcoming(line, today))) return null
  return (
    <div role="group" aria-label="Shift versions">
      <strong>Shift versions</strong>
      <ul style={{ margin: '4px 0', paddingLeft: 18 }}>
        {lines.map((line) => (
          <li key={line.scheduleId}>
            {scheduleText(line, today)}{line.reason ? ` · ${line.reason}` : ''}
            {onEdit && scheduleUpcoming(line, today) && <>
              {' '}<button type="button" aria-label={`Edit version from ${line.effectiveFrom}`} onClick={() => onEdit(line)}>Edit</button>
              {' '}<button type="button" aria-label={`Delete version from ${line.effectiveFrom}`} disabled={remove.isPending}
                onClick={() => remove.mutate({ userId: worker.userId, scheduleId: line.scheduleId })}>Delete</button>
            </>}
          </li>
        ))}
      </ul>
      {remove.isError && <p role="alert">{errorMessage(remove.error, 'Failed to delete the version.')}</p>}
    </div>
  )
}

/**
 * Every save and removal of the worker's versions with what they held (docs/domain/worker-calendar.md WC64), loaded when
 * opened.
 */
function ScheduleHistory({ projectId, userId }: { projectId: string; userId: string }) {
  const [open, setOpen] = useState(false)
  const history = useWorkerScheduleHistoryQuery(projectId, userId, open)
  const latest = !history.isError && Array.isArray(history.data) ? history.data : []
  const older = useOlderHistory(`/projects/${encodeURIComponent(projectId)}/workers/${encodeURIComponent(userId)}/schedule-history/search`, latest)
  const entries = latest.length > 0 ? [...latest, ...older.older] : latest
  return (
    <details onToggle={(event) => setOpen(event.currentTarget.open)}>
      <summary>Version history</summary>
      {history.isError && <p role="alert">{errorMessage(history.error, 'Could not load the version history.')}</p>}
      {open && history.isSuccess && entries.length === 0 && <p className="inspector-hint">No saves recorded yet.</p>}
      {entries.length > 1 && <HistoryComparison kind="worker" entries={entries} />}
      {entries.length > 0 && (
        <ul aria-label="Version history" style={{ margin: '4px 0', paddingLeft: 18 }}>
          {entries.map((entry) => <li key={entry.historyId}>{workerHistoryText(entry)}</li>)}
        </ul>
      )}
      <OlderHistoryButton canLoad={older.canLoad} pending={older.pending} error={older.error} onLoad={() => void older.load()} />
    </details>
  )
}

/**
 * Every change of the worker's own dates and days off with what they held (docs/domain/worker-calendar.md WC66), loaded when
 * opened.
 */
function DayHistory({ projectId, userId }: { projectId: string; userId: string }) {
  const [open, setOpen] = useState(false)
  const history = useWorkerDayHistoryQuery(projectId, userId, open)
  const latest = !history.isError && Array.isArray(history.data) ? history.data : []
  const older = useOlderHistory(`/projects/${encodeURIComponent(projectId)}/workers/${encodeURIComponent(userId)}/day-history/search`, latest)
  const entries = latest.length > 0 ? [...latest, ...older.older] : latest
  return (
    <details onToggle={(event) => setOpen(event.currentTarget.open)}>
      <summary>Date and day-off history</summary>
      {history.isError && <p role="alert">{errorMessage(history.error, 'Could not load the date history.')}</p>}
      {open && history.isSuccess && entries.length === 0 && <p className="inspector-hint">No changes recorded yet.</p>}
      {entries.length > 0 && (
        <ul aria-label="Date and day-off history" style={{ margin: '4px 0', paddingLeft: 18 }}>
          {entries.map((entry) => <li key={entry.historyId}>{workerDayHistoryText(entry)}</li>)}
        </ul>
      )}
      <OlderHistoryButton canLoad={older.canLoad} pending={older.pending} error={older.error} onLoad={() => void older.load()} />
    </details>
  )
}

/**
 * Dates with their own shifts in place of the weekly ones (docs/domain/worker-calendar.md WC33): overtime, a short day or
 * a day without work, for one date or a range.
 */
function WorkerDayChanges({ projectId, worker, copyTargets }: { projectId: string; worker: WorkerCalendarDto; copyTargets: string[] }) {
  const setDay = useSetWorkerDayMutation(projectId)
  const removeDay = useRemoveWorkerDayMutation(projectId)
  const copyDays = useCopyWorkerDaysMutation(projectId)
  const [copyTo, setCopyTo] = useState('')
  const [copied, setCopied] = useState<string | null>(null)
  const [form, setForm] = useState<DayForm>(EMPTY_DAY_FORM)
  const [problem, setProblem] = useState<string | null>(null)
  const days = Array.isArray(worker.days) ? worker.days : []
  const today = dateInputValue(new Date())
  const upcoming = copyableDays(days, today)

  function updateShift(index: number, patch: Partial<DayForm['shifts'][number]>) {
    setForm((current) => ({ ...current, shifts: current.shifts.map((shift, one) => (one === index ? { ...shift, ...patch } : shift)) }))
  }

  function save() {
    const payload = dayPayload(form)
    setProblem(payload.error)
    if (!payload.input) return
    const submitted = form
    setDay.mutate({ userId: worker.userId, date: payload.date, input: payload.input }, {
      onSuccess: () => setForm((current) => (current === submitted ? EMPTY_DAY_FORM : current)),
    })
  }

  /** From today on, as for equipment (WC42). */
  function copy() {
    const target = copyTo
    if (!target) return
    setCopied(null)
    copyDays.mutate({ userId: worker.userId, toUserId: target, fromDate: today }, {
      onSuccess: () => setCopied(`Copied ${upcoming} day change${upcoming === 1 ? '' : 's'} to ${target}.`),
    })
  }

  return (
    <div role="group" aria-label="Day changes">
      <strong>Day changes</strong>
      {!worker.calendarSet && <p className="inspector-hint">Set weekly shifts first; a date&apos;s shifts take their place.</p>}
      {days.length === 0 && <p className="inspector-hint">No day changes.</p>}
      <ul style={{ margin: '4px 0', paddingLeft: 18 }}>
        {days.map((day) => (
          <li key={day.date}>
            {workerDayText(day)}{' '}
            <button type="button" disabled={removeDay.isPending}
              onClick={() => removeDay.mutate({ userId: worker.userId, date: day.date })}>Remove change</button>
          </li>
        ))}
      </ul>
      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
        <input aria-label="Changed day" type="date" value={form.date}
          onChange={(event) => setForm((current) => ({ ...current, date: event.target.value }))} />
        <input aria-label="Changed through" type="date" value={form.through}
          onChange={(event) => setForm((current) => ({ ...current, through: event.target.value }))} />
        <label>
          <input type="checkbox" checked={form.closed}
            onChange={(event) => setForm((current) => ({ ...current, closed: event.target.checked }))} /> Off that day
        </label>
        {!form.closed && form.shifts.map((shift, index) => (
          <span key={index} style={{ display: 'inline-flex', gap: 4, alignItems: 'center' }}>
            <input aria-label={`Day shift ${index + 1} start`} type="time" value={shift.shiftStart}
              onChange={(event) => updateShift(index, { shiftStart: event.target.value })} />
            <span>to</span>
            <input aria-label={`Day shift ${index + 1} end`} type="time" value={shift.shiftEnd}
              onChange={(event) => updateShift(index, { shiftEnd: event.target.value })} />
            <button type="button"
              onClick={() => setForm((current) => ({ ...current, shifts: current.shifts.filter((_, one) => one !== index) }))}>
              Remove day shift
            </button>
          </span>
        ))}
        {!form.closed && (
          <button type="button" onClick={() => setForm((current) => ({ ...current, shifts: [...current.shifts, nextDayShift(current)] }))}>
            Add day shift
          </button>
        )}
        <input aria-label="Day change reason" maxLength={200} placeholder="Reason" value={form.note}
          onChange={(event) => setForm((current) => ({ ...current, note: event.target.value }))} />
        <button type="button" onClick={save} disabled={setDay.isPending}>{setDay.isPending ? 'Saving...' : 'Save day'}</button>
      </div>
      {problem && <p role="alert">{problem}</p>}
      {setDay.isError && <p role="alert">{errorMessage(setDay.error, 'Failed to save the day.')}</p>}
      {removeDay.isError && <p role="alert">{errorMessage(removeDay.error, 'Failed to remove the day change.')}</p>}
      {copyTargets.length > 0 && days.length > 0 && (
        <div role="group" aria-label="Copy to another worker" style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
          <label>Copy to <select aria-label="Copy day changes to" value={copyTo} onChange={(event) => setCopyTo(event.target.value)}>
            <option value="">Choose a person</option>
            {copyTargets.map((userId) => <option key={userId} value={userId}>{userId}</option>)}
          </select></label>
          <button type="button" onClick={copy} disabled={!copyTo || upcoming === 0 || copyDays.isPending}>
            {copyDays.isPending ? 'Copying...' : `Copy ${upcoming} day change${upcoming === 1 ? '' : 's'}`}</button>
          <span className="inspector-hint">From today on; the same dates of theirs are replaced, their other dates stay.</span>
          {copied && <p role="status" style={{ flexBasis: '100%', margin: 0 }}>{copied}</p>}
          {copyDays.isError && <p role="alert" style={{ flexBasis: '100%', margin: 0 }}>
            {errorMessage(copyDays.error, 'Failed to copy the day changes.')}</p>}
        </div>
      )}
    </div>
  )
}
