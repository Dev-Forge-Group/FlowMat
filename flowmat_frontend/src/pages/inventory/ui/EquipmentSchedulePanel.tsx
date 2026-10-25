import { useMemo, useState, type FormEvent } from 'react'
import {
  fetchEquipmentCalendarVersion,
  useEquipmentAvailabilityQuery,
  useEquipmentCalendarHistoryQuery,
  useEquipmentDayHistoryQuery,
  useEquipmentScheduleMutations,
  useEquipmentScheduleQuery,
  type DowntimeType,
  type EquipmentCalendarVersionLineDto,
} from '../../../entities/catalog/api/useEquipment'
import { errorMessage } from '../../../shared/lib/errorMessage'
import type { EquipmentDto } from '../../../shared/types/api'
import {
  DAYS,
  DOWNTIME_TYPES,
  EMPTY_DAY_FORM,
  EMPTY_DOWNTIME_FORM,
  ALL_WEEK_DAYS,
  MAX_RANGE_DAYS,
  MAX_SHIFTS,
  availabilitySummary,
  calendarForm,
  calendarPayload,
  copyableDays,
  dayCount,
  dayLabel,
  dayPayload,
  daySummary,
  downtimePayload,
  equipmentLabel,
  formatHours,
  nextDayShift,
  nextShift,
  nextWeek,
  projectToday,
  shiftSummary,
  splitDowntimes,
  startsOnProblem,
  toggleDay,
  versionForm,
  versionText,
  versionUpcoming,
  type CalendarForm,
  type DayForm,
  type ShiftForm,
} from '../model/equipmentScheduleModel'
import { calendarHistoryText, equipmentDayHistoryText } from '../model/versionHistoryModel'
import { HistoryComparison } from '../../../features/schedule-history/ui/HistoryComparison'
import { OlderHistoryButton } from '../../../features/schedule-history/ui/OlderHistoryButton'
import { useOlderHistory } from '../../../features/schedule-history/model/useOlderHistory'
import { EquipmentChangeovers } from './EquipmentChangeovers'
import { EquipmentSetupPreviewPanel } from './EquipmentSetupPreviewPanel'
import { EquipmentSetupChangeoversPanel } from './EquipmentSetupChangeoversPanel'
import { EquipmentHourlyCostPanel } from './EquipmentHourlyCostPanel'

const cell = { padding: '4px 6px' } as const

function withShift(form: CalendarForm, index: number, change: Partial<ShiftForm>): CalendarForm {
  return { shifts: form.shifts.map((shift, one) => (one === index ? { ...shift, ...change } : shift)) }
}

function withDayShift(form: DayForm, index: number, change: Partial<DayForm['shifts'][number]>): DayForm {
  return { ...form, shifts: form.shifts.map((shift, one) => (one === index ? { ...shift, ...change } : shift)) }
}

function when(iso: string): string {
  return new Date(iso).toLocaleString(undefined, { dateStyle: 'short', timeStyle: 'short' })
}

/**
 * One equipment's shift and downtime (docs/domain/equipment-schedule.md). Work order readiness uses them to check whether
 * the equipment has the time an order needs in its planned window.
 */
export function EquipmentSchedulePanel({ equipment, others = [], onClose }: {
  equipment: EquipmentDto
  /** The project's other equipment, to copy day changes to. */
  others?: EquipmentDto[]
  onClose: () => void
}) {
  const scheduleQuery = useEquipmentScheduleQuery(equipment.equipmentId)
  const week = useMemo(() => nextWeek(new Date()), [])
  const availabilityQuery = useEquipmentAvailabilityQuery(equipment.equipmentId, week.from, week.to)
  const { setCalendar, clearCalendar, removeCalendarVersion, setDay, clearDay, clearDays, copyDays, addDowntime, removeDowntime } =
    useEquipmentScheduleMutations(equipment.equipmentId)
  // null: not edited, so the form follows the saved calendar.
  const [editedCalendar, setEditedCalendar] = useState<CalendarForm | null>(null)
  // Empty is today; a later date saves the calendar as the version from then (ADR-014).
  const [startsOn, setStartsOn] = useState('')
  // Why the version is saved, sent as its reason (ADR-017).
  const [note, setNote] = useState('')
  const [savedNote, setSavedNote] = useState<string | null>(null)
  const [downtime, setDowntime] = useState(EMPTY_DOWNTIME_FORM)
  const [calendarError, setCalendarError] = useState<string | null>(null)
  const [downtimeError, setDowntimeError] = useState<string | null>(null)
  const [dayForm, setDayForm] = useState(EMPTY_DAY_FORM)
  const [dayError, setDayError] = useState<string | null>(null)
  const [copyTo, setCopyTo] = useState('')
  // A range of dates going back to the calendar at once (D6).
  const [clearFrom, setClearFrom] = useState('')
  const [clearThrough, setClearThrough] = useState('')
  const [copied, setCopied] = useState<string | null>(null)
  const schedule = scheduleQuery.data
  const form = editedCalendar ?? calendarForm(schedule)
  const name = equipment.equipmentCode ?? equipment.equipmentName
  const { current, past } = splitDowntimes(schedule?.downtimes ?? [], new Date())
  const calendarProblem = calendarError
    ?? (setCalendar.isError ? errorMessage(setCalendar.error) : null)
    ?? (clearCalendar.isError ? errorMessage(clearCalendar.error) : null)
    ?? (removeCalendarVersion.isError ? errorMessage(removeCalendarVersion.error) : null)
  const downtimeProblem = downtimeError ?? (addDowntime.isError ? errorMessage(addDowntime.error) : null)
  const dayProblem = dayError
    ?? (setDay.isError ? errorMessage(setDay.error) : null)
    ?? (clearDay.isError ? errorMessage(clearDay.error) : null)
  // The project's date, as the server counts versions and copies (not the browser's).
  const today = projectToday(schedule?.timeZone, new Date())
  const upcoming = copyableDays(schedule?.days ?? [], today)
  const days = dayCount(dayForm)
  const versions = schedule?.versions ?? []
  // A date's own shifts need a calendar on that date (D2), so day changes need a version with shifts.
  const hasShifts = Boolean(schedule?.calendar) || versions.some((line) => !line.alwaysRunning)

  function copy(event: FormEvent) {
    event.preventDefault()
    const target = others.find((one) => one.equipmentId === copyTo)
    if (!target) return
    setCopied(null)
    copyDays.mutate({ toEquipmentId: target.equipmentId, fromDate: today }, {
      onSuccess: () => setCopied(`Copied ${upcoming} day change${upcoming === 1 ? '' : 's'} to ${target.equipmentCode ?? target.equipmentName}.`),
    })
  }

  function saveDay(event: FormEvent) {
    event.preventDefault()
    const payload = dayPayload(dayForm)
    setDayError(payload.error)
    if (payload.input) setDay.mutate({ date: payload.date, input: payload.input }, { onSuccess: () => setDayForm(EMPTY_DAY_FORM) })
  }

  function saveCalendar(event: FormEvent) {
    event.preventDefault()
    const payload = calendarPayload(form)
    const problem = payload.error ?? startsOnProblem(startsOn, today)
    setCalendarError(problem)
    if (!payload.input || problem) return
    const from = startsOn
    setCalendar.mutate({ ...payload.input, ...(from ? { effectiveFrom: from } : {}), ...(note.trim() ? { reason: note.trim() } : {}) },
      { onSuccess: () => changed(from, 'saved') })
  }

  /** Around the clock from Applies from, today when empty; earlier dates keep their shifts (ADR-014 decision 2). */
  function removeCalendar() {
    const problem = startsOnProblem(startsOn, today)
    setCalendarError(problem)
    if (problem) return
    const from = startsOn
    clearCalendar.mutate({ ...(from ? { effectiveFrom: from } : {}), ...(note.trim() ? { reason: note.trim() } : {}) },
      { onSuccess: () => changed(from, 'removed') })
  }

  function changed(from: string, what: 'saved' | 'removed') {
    setEditedCalendar(null)
    setStartsOn('')
    setNote('')
    setSavedNote(from && from > today ? `Calendar ${what} from ${from}; until then the current calendar stays.` : null)
  }

  /** Loads a version that has not started into the form, to change it (ADR-014). */
  function editVersion(line: EquipmentCalendarVersionLineDto) {
    setCalendarError(null)
    fetchEquipmentCalendarVersion(equipment.equipmentId, line.scheduleId).then((version) => {
      setEditedCalendar(versionForm(version))
      setStartsOn(line.effectiveFrom ?? '')
      setNote(version.reason ?? '')
    }, (error: unknown) => setCalendarError(errorMessage(error, 'Failed to load the version.')))
  }

  function saveDowntime(event: FormEvent) {
    event.preventDefault()
    const payload = downtimePayload(downtime)
    setDowntimeError(payload.error)
    if (payload.input) addDowntime.mutate(payload.input, { onSuccess: () => setDowntime(EMPTY_DOWNTIME_FORM) })
  }

  return (
    <section aria-label={`Schedule of ${name}`}
      style={{ marginTop: 16, border: '1px solid var(--border)', padding: 12, display: 'grid', gap: 12, fontSize: 12 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h3 style={{ margin: 0 }}>Schedule · {name}</h3>
        <button type="button" onClick={onClose}>Close</button>
      </div>
      {scheduleQuery.isPending && <p>Loading schedule...</p>}
      {scheduleQuery.isError && <p role="alert">{errorMessage(scheduleQuery.error)}</p>}
      {schedule && <>
        <div role="status" style={{ display: 'grid', gap: 2 }}>
          <strong>{shiftSummary(schedule.calendar)}</strong>
          {availabilityQuery.data && <span>{availabilitySummary(availabilityQuery.data)}</span>}
        </div>

        <form aria-label="Calendar" onSubmit={saveCalendar} style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'end' }}>
          {form.shifts.map((shift, index) => {
            // The first shift keeps the plain labels; later ones are numbered.
            const name = index === 0 ? 'Shift' : `Shift ${index + 1}`
            return <div key={index} role="group" aria-label={name}
              style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'end', flexBasis: '100%' }}>
              <label>{name} start<input type="time" value={shift.shiftStart} required
                onChange={(event) => setEditedCalendar(withShift(form, index, { shiftStart: event.target.value }))} /></label>
              <label>{name} end<input type="time" value={shift.shiftEnd} required
                onChange={(event) => setEditedCalendar(withShift(form, index, { shiftEnd: event.target.value }))} /></label>
              <fieldset style={{ display: 'flex', gap: 6, border: '1px solid var(--border)', padding: '2px 6px' }}>
                <legend>{index === 0 ? 'Work days' : `${name} days`}</legend>
                {DAYS.map(([day, label]) => <label key={day} style={{ whiteSpace: 'nowrap' }}>
                  <input type="checkbox" checked={shift.workDays.includes(day)}
                    onChange={() => setEditedCalendar(withShift(form, index, { workDays: toggleDay(shift.workDays, day) }))} />{label}
                </label>)}
              </fieldset>
              {form.shifts.length > 1 && <button type="button"
                onClick={() => setEditedCalendar({ shifts: form.shifts.filter((_, one) => one !== index) })}>Remove {name.toLowerCase()}</button>}
            </div>
          })}
          <button type="button" disabled={form.shifts.length >= MAX_SHIFTS}
            onClick={() => setEditedCalendar({ shifts: [...form.shifts, nextShift(form)] })}>Add shift</button>
          <label>Applies from<input aria-label="Calendar applies from" type="date" value={startsOn}
            onChange={(event) => setStartsOn(event.target.value)} /></label>
          <label>Note<input aria-label="Calendar version note" maxLength={200} value={note}
            onChange={(event) => setNote(event.target.value)} /></label>
          <button type="submit" disabled={setCalendar.isPending}>{setCalendar.isPending ? 'Saving...' : 'Save calendar'}</button>
          {hasShifts && <button type="button" disabled={clearCalendar.isPending}
            onClick={removeCalendar}>{startsOn ? `Remove calendar from ${startsOn}` : 'Remove calendar'}</button>}
          <span className="inspector-hint" style={{ flexBasis: '100%' }}>
            Times are in {schedule.timeZone}. A shift that ends at or before its start runs past midnight; the same time is all day.
            Shifts may touch (06:00–14:00 and 14:00–22:00) but not overlap; up to {MAX_SHIFTS} shifts.
            Leave Applies from empty for today; earlier dates keep the shifts they had.
          </span>
          {calendarProblem && <p role="alert" style={{ flexBasis: '100%', margin: 0 }}>{calendarProblem}</p>}
          {savedNote && <p role="status" aria-label="Calendar change" style={{ flexBasis: '100%', margin: 0 }}>{savedNote}</p>}
        </form>
        <CalendarVersions versions={versions} today={today} pending={removeCalendarVersion.isPending}
          onEdit={editVersion} onDelete={(line) => removeCalendarVersion.mutate(line.scheduleId)} />
        <CalendarHistory equipmentId={equipment.equipmentId} />
        <DayHistory equipmentId={equipment.equipmentId} others={others} />

        {hasShifts && <>
          <form aria-label="Change a day" onSubmit={saveDay} style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'end' }}>
            <label>Day<input type="date" value={dayForm.date} required
              onChange={(event) => setDayForm({ ...dayForm, date: event.target.value })} /></label>
            <label>Through<input type="date" value={dayForm.through} min={dayForm.date || undefined}
              onChange={(event) => setDayForm({ ...dayForm, through: event.target.value })} /></label>
            {dayForm.through && dayForm.through !== dayForm.date && (
              <span role="group" aria-label="Days of the week" style={{ display: 'flex', gap: 4, alignItems: 'center' }}>
                {DAYS.map(([number, label]) => (
                  <label key={number} style={{ whiteSpace: 'nowrap' }}><input type="checkbox"
                    checked={(dayForm.weekDays ?? ALL_WEEK_DAYS).includes(number)}
                    onChange={() => setDayForm({ ...dayForm, weekDays: toggleDay([...(dayForm.weekDays ?? ALL_WEEK_DAYS)], number) })} />
                    {label}</label>
                ))}
              </span>
            )}
            <label style={{ whiteSpace: 'nowrap' }}><input type="checkbox" checked={dayForm.closed}
              onChange={(event) => setDayForm({ ...dayForm, closed: event.target.checked })} />Closed all day</label>
            {!dayForm.closed && dayForm.shifts.map((shift, index) => {
              const number = index === 0 ? '' : ` ${index + 1}`
              return <span key={index} role="group" aria-label={`Day shift${number}`} style={{ display: 'flex', gap: 6, alignItems: 'end' }}>
                <label>From{number}<input type="time" value={shift.shiftStart} required
                  onChange={(event) => setDayForm(withDayShift(dayForm, index, { shiftStart: event.target.value }))} /></label>
                <label>Until{number}<input type="time" value={shift.shiftEnd} required
                  onChange={(event) => setDayForm(withDayShift(dayForm, index, { shiftEnd: event.target.value }))} /></label>
                {dayForm.shifts.length > 1 && <button type="button"
                  onClick={() => setDayForm({ ...dayForm, shifts: dayForm.shifts.filter((_, one) => one !== index) })}>
                  Remove day shift{number || ' 1'}</button>}
              </span>
            })}
            {!dayForm.closed && <button type="button" disabled={dayForm.shifts.length >= MAX_SHIFTS}
              onClick={() => setDayForm({ ...dayForm, shifts: [...dayForm.shifts, nextDayShift(dayForm)] })}>Add day shift</button>}
            <label>Note<input value={dayForm.note} maxLength={200}
              onChange={(event) => setDayForm({ ...dayForm, note: event.target.value })} /></label>
            <button type="submit" disabled={setDay.isPending}>
              {setDay.isPending ? 'Saving...' : days > 1 ? `Save ${days} days` : 'Save day'}</button>
            <span className="inspector-hint" style={{ flexBasis: '100%' }}>
              A day's own shifts replace the calendar's for that date, on a project holiday too: close it, shorten it or work a day off.
              Give a last day (Through) to change every date up to it the same way, up to {MAX_RANGE_DAYS} days; untick days of
              the week to leave them out.
            </span>
            {dayProblem && <p role="alert" style={{ flexBasis: '100%', margin: 0 }}>{dayProblem}</p>}
          </form>
          {schedule.days.length > 0 && <table aria-label="Day changes" style={{ borderCollapse: 'collapse', width: '100%', textAlign: 'left' }}>
            <thead><tr><th style={cell}>Day</th><th style={cell}>Shifts</th><th style={cell}>Note</th><th style={cell}>Actions</th></tr></thead>
            <tbody>{schedule.days.map((day) => <tr key={day.date}
              style={{ borderTop: '1px solid var(--border)', opacity: day.date < today ? 0.6 : 1 }}>
              <td style={cell}>{dayLabel(day.date)}</td><td style={cell}>{daySummary(day)}</td><td style={cell}>{day.reason ?? '—'}</td>
              <td style={cell}><button type="button" disabled={clearDay.isPending} onClick={() => clearDay.mutate(day.date)}>Remove</button></td>
            </tr>)}</tbody>
          </table>}
          {schedule.days.length > 1 && <form aria-label="Clear days" onSubmit={(event) => {
            event.preventDefault()
            if (!clearFrom || !clearThrough || clearThrough < clearFrom) return
            clearDays.mutate({ from: clearFrom, through: clearThrough }, { onSuccess: () => { setClearFrom(''); setClearThrough('') } })
          }} style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'end' }}>
            <label>Back to the calendar from<input type="date" value={clearFrom} required
              onChange={(event) => setClearFrom(event.target.value)} /></label>
            <label>through<input type="date" value={clearThrough} required min={clearFrom || undefined}
              onChange={(event) => setClearThrough(event.target.value)} /></label>
            <button type="submit" disabled={clearDays.isPending || !clearFrom || !clearThrough || clearThrough < clearFrom}>
              Clear these days</button>
            {clearDays.isError && <p role="alert" style={{ flexBasis: '100%', margin: 0 }}>{errorMessage(clearDays.error)}</p>}
          </form>}
          {schedule.days.length > 0 && others.length > 0 && <form aria-label="Copy day changes" onSubmit={copy}
            style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'end' }}>
            <label>Copy to<select aria-label="Copy to" value={copyTo} onChange={(event) => setCopyTo(event.target.value)}>
              <option value="">Choose equipment</option>
              {others.map((one) => <option key={one.equipmentId} value={one.equipmentId}>{equipmentLabel(one)}</option>)}
            </select></label>
            <button type="submit" disabled={!copyTo || upcoming === 0 || copyDays.isPending}>
              {copyDays.isPending ? 'Copying...' : `Copy ${upcoming} day change${upcoming === 1 ? '' : 's'}`}</button>
            <span className="inspector-hint">From today on; the same dates on the other equipment are replaced, its other dates stay.</span>
            {copied && <p role="status" style={{ flexBasis: '100%', margin: 0 }}>{copied}</p>}
            {copyDays.isError && <p role="alert" style={{ flexBasis: '100%', margin: 0 }}>{errorMessage(copyDays.error)}</p>}
          </form>}
        </>}

        <form aria-label="Add downtime" onSubmit={saveDowntime} style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'end' }}>
          <label>Type<select value={downtime.downtimeType}
            onChange={(event) => setDowntime({ ...downtime, downtimeType: event.target.value as DowntimeType })}>
            {DOWNTIME_TYPES.map((type) => <option key={type} value={type}>{type}</option>)}
          </select></label>
          <label>Starts<input type="datetime-local" value={downtime.startsAt} required
            onChange={(event) => setDowntime({ ...downtime, startsAt: event.target.value })} /></label>
          <label>Ends<input type="datetime-local" value={downtime.endsAt} required
            onChange={(event) => setDowntime({ ...downtime, endsAt: event.target.value })} /></label>
          <label>Reason<input value={downtime.reason} maxLength={500}
            onChange={(event) => setDowntime({ ...downtime, reason: event.target.value })} /></label>
          <button type="submit" disabled={addDowntime.isPending}>{addDowntime.isPending ? 'Adding...' : 'Add downtime'}</button>
          {downtimeProblem && <p role="alert" style={{ flexBasis: '100%', margin: 0 }}>{downtimeProblem}</p>}
        </form>

        {removeDowntime.isError && <p role="alert">{errorMessage(removeDowntime.error)}</p>}
        {current.length + past.length === 0
          ? <p className="inspector-hint">No downtime recorded.</p>
          : <table aria-label="Downtime list" style={{ borderCollapse: 'collapse', width: '100%', textAlign: 'left' }}>
            <thead><tr><th style={cell}>Type</th><th style={cell}>Starts</th><th style={cell}>Ends</th>
              <th style={{ ...cell, textAlign: 'right' }}>Hours</th><th style={cell}>Reason</th><th style={cell}>Actions</th></tr></thead>
            <tbody>{[...current, ...past].map((one) => <tr key={one.downtimeId}
              style={{ borderTop: '1px solid var(--border)', opacity: past.includes(one) ? 0.6 : 1 }}>
              <td style={cell}>{one.downtimeType}</td><td style={cell}>{when(one.startsAt)}</td><td style={cell}>{when(one.endsAt)}</td>
              <td style={{ ...cell, textAlign: 'right' }}>{formatHours(one.hours)}</td><td style={cell}>{one.reason ?? '—'}</td>
              <td style={cell}><button type="button" disabled={removeDowntime.isPending} onClick={() => {
                if (window.confirm(`Remove this ${one.downtimeType} downtime?`)) removeDowntime.mutate(one.downtimeId)
              }}>Remove</button></td>
            </tr>)}</tbody>
          </table>}

        <EquipmentChangeovers equipment={equipment} />
        <EquipmentSetupChangeoversPanel equipmentId={equipment.equipmentId} projectId={equipment.projectId} />
        <EquipmentSetupPreviewPanel equipmentId={equipment.equipmentId} projectId={equipment.projectId} />
        <EquipmentHourlyCostPanel equipmentId={equipment.equipmentId} projectId={equipment.projectId} />
      </>}
    </section>
  )
}

/**
 * Every save and removal of the calendar's versions with what they held (docs/domain/equipment-schedule.md CV11), loaded
 * when opened.
 */
function CalendarHistory({ equipmentId }: { equipmentId: string }) {
  const [open, setOpen] = useState(false)
  const history = useEquipmentCalendarHistoryQuery(equipmentId, open)
  const latest = !history.isError && Array.isArray(history.data) ? history.data : []
  const older = useOlderHistory(`/equipments/${encodeURIComponent(equipmentId)}/calendar/history/search`, latest)
  const entries = latest.length > 0 ? [...latest, ...older.older] : latest
  return (
    <details onToggle={(event) => setOpen(event.currentTarget.open)}>
      <summary>Calendar history</summary>
      {history.isError && <p role="alert">{errorMessage(history.error, 'Could not load the calendar history.')}</p>}
      {open && history.isSuccess && entries.length === 0 && <p className="inspector-hint">No saves recorded yet.</p>}
      {entries.length > 1 && <HistoryComparison kind="equipment" entries={entries} />}
      {entries.length > 0 && (
        <ul aria-label="Calendar history" style={{ margin: '4px 0', paddingLeft: 18 }}>
          {entries.map((entry) => <li key={entry.historyId}>{calendarHistoryText(entry)}</li>)}
        </ul>
      )}
      <OlderHistoryButton canLoad={older.canLoad} pending={older.pending} error={older.error} onLoad={() => void older.load()} />
    </details>
  )
}

/**
 * Every change of the equipment's own dates and downtimes with what they held (docs/domain/equipment-schedule.md CV13),
 * loaded when opened. A copy names the equipment it came from.
 */
function DayHistory({ equipmentId, others }: { equipmentId: string; others: EquipmentDto[] }) {
  const [open, setOpen] = useState(false)
  const history = useEquipmentDayHistoryQuery(equipmentId, open)
  const latest = !history.isError && Array.isArray(history.data) ? history.data : []
  const older = useOlderHistory(`/equipments/${encodeURIComponent(equipmentId)}/day-history/search`, latest)
  const entries = latest.length > 0 ? [...latest, ...older.older] : latest
  const label = (id: string) => {
    const found = others.find((one) => one.equipmentId === id)
    return found ? found.equipmentCode || found.equipmentName : 'another equipment'
  }
  return (
    <details onToggle={(event) => setOpen(event.currentTarget.open)}>
      <summary>Date and downtime history</summary>
      {history.isError && <p role="alert">{errorMessage(history.error, 'Could not load the date history.')}</p>}
      {open && history.isSuccess && entries.length === 0 && <p className="inspector-hint">No changes recorded yet.</p>}
      {entries.length > 0 && (
        <ul aria-label="Date and downtime history" style={{ margin: '4px 0', paddingLeft: 18 }}>
          {entries.map((entry) => <li key={entry.historyId}>{equipmentDayHistoryText(entry, label)}</li>)}
        </ul>
      )}
      <OlderHistoryButton canLoad={older.canLoad} pending={older.pending} error={older.error} onLoad={() => void older.load()} />
    </details>
  )
}

/**
 * The versions of the equipment's calendar (docs/architecture/adr/ADR-014-equipment-calendar-history.md): shown once there is
 * more than one or one still ahead. One that has not started can be loaded into the form (when it has shifts) or deleted.
 */
function CalendarVersions({ versions, today, pending, onEdit, onDelete }: {
  versions: EquipmentCalendarVersionLineDto[]; today: string; pending: boolean
  onEdit: (line: EquipmentCalendarVersionLineDto) => void; onDelete: (line: EquipmentCalendarVersionLineDto) => void
}) {
  if (versions.length < 2 && !versions.some((line) => versionUpcoming(line, today))) return null
  return (
    <div role="group" aria-label="Calendar versions">
      <strong>Calendar versions</strong>
      <ul style={{ margin: '4px 0', paddingLeft: 18 }}>
        {versions.map((line) => (
          <li key={line.scheduleId}>
            {versionText(line, today)}{line.reason ? ` · ${line.reason}` : ''}
            {versionUpcoming(line, today) && <>
              {!line.alwaysRunning && <>{' '}<button type="button" aria-label={`Edit version from ${line.effectiveFrom}`}
                onClick={() => onEdit(line)}>Edit</button></>}
              {' '}<button type="button" aria-label={`Delete version from ${line.effectiveFrom}`} disabled={pending}
                onClick={() => onDelete(line)}>Delete</button>
            </>}
          </li>
        ))}
      </ul>
    </div>
  )
}
