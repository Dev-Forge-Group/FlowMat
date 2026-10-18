import type {
  DowntimeType,
  EquipmentAvailabilityDto,
  EquipmentCalendarInput,
  EquipmentCalendarVersionDto,
  EquipmentCalendarVersionLineDto,
  EquipmentDayDto,
  EquipmentDayInput,
  EquipmentDowntimeDto,
  EquipmentDowntimeInput,
  EquipmentScheduleDto,
  EquipmentShiftDto,
} from '../../../entities/catalog/api/useEquipment'
import type { EquipmentDto } from '../../../shared/types/api'

/** ISO day numbers and their short names, Monday first. */
export const DAYS: readonly (readonly [number, string])[] = [
  [1, 'Mon'], [2, 'Tue'], [3, 'Wed'], [4, 'Thu'], [5, 'Fri'], [6, 'Sat'], [7, 'Sun'],
]

export const DOWNTIME_TYPES: DowntimeType[] = ['maintenance', 'breakdown', 'other']

export interface ShiftForm {
  shiftStart: string
  shiftEnd: string
  workDays: number[]
}

export interface CalendarForm {
  shifts: ShiftForm[]
}

/** The server takes at most this many shifts per equipment. */
export const MAX_SHIFTS = 6

export const DEFAULT_SHIFT: ShiftForm = { shiftStart: '09:00', shiftEnd: '18:00', workDays: [1, 2, 3, 4, 5] }

export const DEFAULT_CALENDAR_FORM: CalendarForm = { shifts: [DEFAULT_SHIFT] }

export interface DowntimeForm {
  downtimeType: DowntimeType
  /** datetime-local values, local time. */
  startsAt: string
  endsAt: string
  reason: string
}

export const EMPTY_DOWNTIME_FORM: DowntimeForm = { downtimeType: 'maintenance', startsAt: '', endsAt: '', reason: '' }

/** The saved shifts as a form, or a weekday day shift to start from. */
export function calendarForm(schedule: EquipmentScheduleDto | undefined): CalendarForm {
  return shiftsForm(schedule?.calendar?.shifts ?? [])
}

/** A saved calendar version in the form, to change one that has not started (ADR-014). */
export function versionForm(version: EquipmentCalendarVersionDto): CalendarForm {
  return shiftsForm(version.shifts)
}

function shiftsForm(shifts: EquipmentShiftDto[]): CalendarForm {
  return shifts.length > 0
    ? { shifts: shifts.map((shift) => ({
      shiftStart: shift.shiftStart.slice(0, 5), shiftEnd: shift.shiftEnd.slice(0, 5), workDays: [...shift.workDays],
    })) }
    : DEFAULT_CALENDAR_FORM
}

/** "14:00" + 8 h = "22:00", wrapping past midnight. */
function addHours(time: string, hours: number): string {
  const [hour, minute] = time.split(':').map(Number)
  const total = ((hour * 60 + minute + hours * 60) % 1440 + 1440) % 1440
  return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`
}

/** A shift to add: it starts when the last one ends and runs eight hours on the same days. */
export function nextShift(form: CalendarForm): ShiftForm {
  const last = form.shifts[form.shifts.length - 1] ?? DEFAULT_SHIFT
  const start = /^\d{2}:\d{2}$/.test(last.shiftEnd) ? last.shiftEnd : DEFAULT_SHIFT.shiftStart
  return { shiftStart: start, shiftEnd: addHours(start, 8), workDays: [...last.workDays] }
}

/** A date's change being written: closed, or its own shifts. */
export interface DayForm {
  /** YYYY-MM-DD */
  date: string
  /** The last day of a range changed the same way (D6); blank for one day. */
  through: string
  /** In a range, only these days of the week (ISO, 1 = Monday); every day when left out. */
  weekDays?: number[]
  closed: boolean
  shifts: { shiftStart: string; shiftEnd: string }[]
  note: string
}

export const EMPTY_DAY_FORM: DayForm = { date: '', through: '', closed: false, shifts: [{ shiftStart: '09:00', shiftEnd: '13:00' }], note: '' }

/** Dates one change can cover (D6). */
export const MAX_RANGE_DAYS = 62

export const ALL_WEEK_DAYS: readonly number[] = [1, 2, 3, 4, 5, 6, 7]

function daysBetween(first: string, last: string): number {
  const utc = (date: string) => {
    const [year, month, day] = date.split('-').map(Number)
    return Date.UTC(year, month - 1, day)
  }
  return Math.round((utc(last) - utc(first)) / 86_400_000)
}

/** The dates from the form's first day through its last on the chosen days of the week; empty without a valid range. */
export function rangeDates(form: DayForm): string[] {
  const dated = /^\d{4}-\d{2}-\d{2}$/
  if (!dated.test(form.date) || !dated.test(form.through) || form.through < form.date) return []
  const [year, month, day] = form.date.split('-').map(Number)
  const chosen = form.weekDays ?? ALL_WEEK_DAYS
  const dates: string[] = []
  for (let offset = 0; offset <= Math.min(daysBetween(form.date, form.through), MAX_RANGE_DAYS); offset++) {
    const at = new Date(Date.UTC(year, month - 1, day + offset))
    if (chosen.includes(((at.getUTCDay() + 6) % 7) + 1)) dates.push(at.toISOString().slice(0, 10))
  }
  return dates
}

/** How many dates the form changes: 1, or the range's dates on the chosen days of the week. */
export function dayCount(form: DayForm): number {
  const dated = /^\d{4}-\d{2}-\d{2}$/
  if (!dated.test(form.date) || !dated.test(form.through) || form.through < form.date) return 1
  return rangeDates(form).length
}

/** Day changes from today on: what a copy to other equipment takes along (D7). */
export function copyableDays(days: { date: string }[], today: string): number {
  return days.filter((day) => day.date >= today).length
}

/** Another of a day's shifts: from when the last one ends, four hours. */
export function nextDayShift(form: DayForm): DayForm['shifts'][number] {
  const last = form.shifts[form.shifts.length - 1]
  const start = last && /^\d{2}:\d{2}$/.test(last.shiftEnd) ? last.shiftEnd : '09:00'
  return { shiftStart: start, shiftEnd: addHours(start, 4) }
}

/** Checks a day's change before it is sent; overlaps between its shifts are left to the server. */
export function dayPayload(
  form: DayForm,
): { date: string; input: EquipmentDayInput; error: null } | { date: null; input: null; error: string } {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(form.date)) return { date: null, input: null, error: 'Choose the day.' }
  if (form.through) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(form.through)) return { date: null, input: null, error: 'Enter the last day as a date.' }
    if (form.through < form.date) return { date: null, input: null, error: 'The last day must not be before the first.' }
    if (daysBetween(form.date, form.through) >= MAX_RANGE_DAYS) {
      return { date: null, input: null, error: `A range can cover at most ${MAX_RANGE_DAYS} days.` }
    }
  }
  const ranged = Boolean(form.through) && form.through !== form.date
  const weekDays = form.weekDays ?? ALL_WEEK_DAYS
  if (ranged && weekDays.length === 0) return { date: null, input: null, error: 'Choose at least one day of the week.' }
  if (ranged && rangeDates(form).length === 0) {
    return { date: null, input: null, error: 'None of the dates fall on the chosen days of the week.' }
  }
  const range = ranged
    ? { throughDate: form.through, ...(weekDays.length < 7 ? { weekDays: [...weekDays].sort((a, b) => a - b) } : {}) }
    : {}
  const reason = form.note.trim() || null
  if (form.closed) return { date: form.date, input: { shifts: [], reason, ...range }, error: null }
  if (form.shifts.length === 0) return { date: null, input: null, error: 'Add a shift, or mark the day closed.' }
  if (form.shifts.length > MAX_SHIFTS) return { date: null, input: null, error: `A day can have at most ${MAX_SHIFTS} shifts.` }
  const time = /^\d{2}:\d{2}$/
  for (const [index, shift] of form.shifts.entries()) {
    if (!time.test(shift.shiftStart) || !time.test(shift.shiftEnd)) {
      const prefix = form.shifts.length === 1 ? '' : `Shift ${index + 1}: `
      return { date: null, input: null, error: `${prefix}Enter when the shift starts and ends.` }
    }
  }
  return {
    date: form.date,
    input: { shifts: form.shifts.map(({ shiftStart, shiftEnd }) => ({ shiftStart, shiftEnd })), reason, ...range },
    error: null,
  }
}

/** "Wed 2030-01-09" for a YYYY-MM-DD date. */
export function dayLabel(date: string): string {
  const [year, month, day] = date.split('-').map(Number)
  const iso = ((new Date(year, month - 1, day).getDay() + 6) % 7) + 1
  return `${DAYS.find(([number]) => number === iso)?.[1] ?? ''} ${date}`
}

/** "Closed", or "06:00–14:00, 14:00–18:00 · 12 h". */
export function daySummary(day: EquipmentDayDto): string {
  if (day.closed) return 'Closed'
  const times = day.shifts.map((shift) => {
    const start = shift.shiftStart.slice(0, 5)
    const end = shift.shiftEnd.slice(0, 5)
    return start === end ? 'All day' : `${start}–${end}${end < start ? ' (ends next day)' : ''}`
  })
  return `${times.join(', ')} · ${formatHours(day.hours)}`
}

export function toggleDay(days: number[], day: number): number[] {
  return days.includes(day) ? days.filter((one) => one !== day) : [...days, day].sort((a, b) => a - b)
}

/** Checks each shift; overlaps between shifts are left to the server, which names the day. */
export function calendarPayload(form: CalendarForm): { input: EquipmentCalendarInput; error: null } | { input: null; error: string } {
  if (form.shifts.length === 0) return { input: null, error: 'Add at least one shift, or remove the calendar.' }
  if (form.shifts.length > MAX_SHIFTS) return { input: null, error: `A calendar can have at most ${MAX_SHIFTS} shifts.` }
  const time = /^\d{2}:\d{2}$/
  const shifts: EquipmentCalendarInput['shifts'] = []
  for (const [index, shift] of form.shifts.entries()) {
    const prefix = form.shifts.length === 1 ? '' : `Shift ${index + 1}: `
    if (!time.test(shift.shiftStart) || !time.test(shift.shiftEnd)) {
      return { input: null, error: `${prefix}Enter when the shift starts and ends.` }
    }
    if (shift.workDays.length === 0) return { input: null, error: `${prefix}Choose at least one work day.` }
    shifts.push({ shiftStart: shift.shiftStart, shiftEnd: shift.shiftEnd, workDays: [...new Set(shift.workDays)].sort((a, b) => a - b) })
  }
  return { input: { shifts }, error: null }
}

/** "Mon–Fri", "Mon, Wed, Fri", "Mon–Wed, Sat" or "Every day"; three or more days in a row become a range. */
export function dayList(days: number[]): string {
  const sorted = [...new Set(days)].sort((a, b) => a - b)
  if (sorted.length === 7) return 'Every day'
  const name = (day: number) => DAYS.find(([number]) => number === day)?.[1] ?? String(day)
  const parts: string[] = []
  for (let index = 0; index < sorted.length; ) {
    let end = index
    while (end + 1 < sorted.length && sorted[end + 1] === sorted[end] + 1) end += 1
    if (end - index >= 2) parts.push(`${name(sorted[index])}–${name(sorted[end])}`)
    else for (let one = index; one <= end; one += 1) parts.push(name(sorted[one]))
    index = end + 1
  }
  return parts.join(', ')
}

export function formatHours(hours: number): string {
  return `${Number(hours.toFixed(2))} h`
}

function oneShift(shift: EquipmentShiftDto): string {
  const start = shift.shiftStart.slice(0, 5)
  const end = shift.shiftEnd.slice(0, 5)
  const times = start === end ? 'All day' : `${start}–${end}${end < start ? ' (ends next day)' : ''}`
  return `${times} · ${dayList(shift.workDays)} · ${formatHours(shift.shiftHours)} a shift`
}

/**
 * One line for the calendar, e.g. "09:00–17:00 · Mon–Fri · 8 h a shift"; several shifts are joined with "; " and end
 * with the week's total, e.g. "… ; 14:00–22:00 · Mon–Fri · 8 h a shift · 80 h a week".
 */
export function shiftSummary(calendar: EquipmentScheduleDto['calendar']): string {
  if (!calendar || calendar.shifts.length === 0) return 'No calendar: available around the clock.'
  const shifts = calendar.shifts.map(oneShift).join('; ')
  return calendar.shifts.length === 1 ? shifts : `${shifts} · ${formatHours(calendar.weeklyHours)} a week`
}

export function downtimePayload(form: DowntimeForm): { input: EquipmentDowntimeInput; error: null } | { input: null; error: string } {
  if (!form.startsAt || !form.endsAt) return { input: null, error: 'Enter when the downtime starts and ends.' }
  const startsAt = new Date(form.startsAt)
  const endsAt = new Date(form.endsAt)
  if (Number.isNaN(startsAt.getTime()) || Number.isNaN(endsAt.getTime())) {
    return { input: null, error: 'Enter when the downtime starts and ends.' }
  }
  if (endsAt <= startsAt) return { input: null, error: 'The downtime must end after it starts.' }
  const reason = form.reason.trim()
  return {
    input: { downtimeType: form.downtimeType, startsAt: startsAt.toISOString(), endsAt: endsAt.toISOString(), reason: reason || null },
    error: null,
  }
}

/** From midnight today (local) for seven days. */
export function nextWeek(now: Date): { from: string; to: string } {
  const from = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const to = new Date(from.getFullYear(), from.getMonth(), from.getDate() + 7)
  return { from: from.toISOString(), to: to.toISOString() }
}

export function availabilitySummary(availability: EquipmentAvailabilityDto): string {
  const down = availability.downtimeHours > 0 ? ` (${formatHours(availability.downtimeHours)} down)` : ''
  const capacity = availability.capacity !== null ? ` · can make ${Number(availability.capacity.toFixed(2))}` : ''
  const holidays = availability.holidays?.length ?? 0
  const off = holidays > 0 ? ` · ${holidays} holiday${holidays === 1 ? '' : 's'} off` : ''
  return `Next 7 days: ${formatHours(availability.availableHours)} available${down}${capacity}${off}.`
}

/** Downtime not over yet (soonest first), and what is over (latest first). */
export function splitDowntimes(downtimes: EquipmentDowntimeDto[], now: Date): { current: EquipmentDowntimeDto[]; past: EquipmentDowntimeDto[] } {
  const ended = (one: EquipmentDowntimeDto) => new Date(one.endsAt).getTime() <= now.getTime()
  return {
    current: downtimes.filter((one) => !ended(one)).sort((a, b) => a.startsAt.localeCompare(b.startsAt)),
    past: downtimes.filter(ended),
  }
}

/** Equipment a work order can be put on: everything but inactive, plus whatever it is on now. */
export function equipmentChoices(equipment: EquipmentDto[], currentId: string | null): EquipmentDto[] {
  return equipment.filter((one) => one.equipmentStatus !== 'inactive' || one.equipmentId === currentId)
}

export function equipmentLabel(equipment: EquipmentDto): string {
  const name = equipment.equipmentCode ? `${equipment.equipmentCode} · ${equipment.equipmentName}` : equipment.equipmentName
  return equipment.equipmentStatus === 'active' ? name : `${name} (${equipment.equipmentStatus})`
}

/** A calendar version that has not started yet; only those can be changed or deleted (docs/architecture/adr/ADR-014-equipment-calendar-history.md). */
export function versionUpcoming(line: { effectiveFrom: string | null }, today: string): boolean {
  return line.effectiveFrom !== null && line.effectiveFrom > today
}

/** A version's line: from the start or a date, around the clock without shifts, and whether it applies now, is ahead or is history. */
export function versionText(line: EquipmentCalendarVersionLineDto, today: string): string {
  const from = line.effectiveFrom === null ? 'From the start' : `From ${line.effectiveFrom}`
  const what = line.alwaysRunning ? `${from} · around the clock` : from
  if (line.current) return `${what} · now`
  return versionUpcoming(line, today) ? `${what} · upcoming` : `${what} · earlier`
}

/**
 * Today (YYYY-MM-DD) in the project's planning zone, as the server counts it for calendar versions and copies; the browser's
 * date when this browser cannot read the zone.
 */
export function projectToday(timeZone: string | undefined, now: Date): string {
  const format = (zone: string | undefined) =>
    new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(now)
  try {
    return format(timeZone)
  } catch {
    return format(undefined)
  }
}

/** A calendar applies from today or later; empty is today (ADR-014, as ADR-013 decisions 3 and 6). */
export function startsOnProblem(startsOn: string, today: string): string | null {
  return startsOn && startsOn < today ? 'Pick today or a later date; change a past date with a day change.' : null
}
