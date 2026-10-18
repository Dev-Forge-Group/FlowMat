import type {
  WorkerCalendarDto, WorkerDayDto, WorkerDayOffDto, WorkerRotaDto, WorkerShiftDto,
} from '../../../entities/workforce/api/useWorkerCalendars'
import { dayLabel } from '../../inventory/model/equipmentScheduleModel'

export const DAY_NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']

/** How many weeks a rota can repeat over; 1 is no rota (docs/domain/worker-calendar.md WC44). */
export const ROTA_WEEKS = [1, 2, 3, 4, 5, 6, 7, 8]

/** ISO days as text: a run of three or more reads "Mon–Fri", others are listed ("Mon, Wed, Fri"). */
export function daysText(days: number[]): string {
  const sorted = [...new Set(days)].filter((day) => day >= 1 && day <= 7).sort((a, b) => a - b)
  const parts: string[] = []
  for (let index = 0; index < sorted.length;) {
    let end = index
    while (end + 1 < sorted.length && sorted[end + 1] === sorted[end] + 1) end += 1
    if (end - index >= 2) {
      parts.push(`${DAY_NAMES[sorted[index] - 1]}–${DAY_NAMES[sorted[end] - 1]}`)
    } else {
      for (let one = index; one <= end; one += 1) parts.push(DAY_NAMES[sorted[one] - 1])
    }
    index = end + 1
  }
  return parts.join(', ')
}

/** "Mon–Fri 09:00–17:00". */
export function shiftText(shift: Pick<WorkerShiftDto, 'shiftStart' | 'shiftEnd' | 'workDays'>): string {
  return `${daysText(shift.workDays)} ${shift.shiftStart}–${shift.shiftEnd}`
}

/** "40 h a week" ("on average" with a rota, WC47), or "No shifts set" when the worker's time is not known. */
export function weeklyText(worker: Pick<WorkerCalendarDto, 'calendarSet' | 'weeklyHours' | 'rota'>): string {
  if (!worker.calendarSet) return 'No shifts set'
  return `${Number(worker.weeklyHours.toFixed(2))} h a week${worker.rota ? ' on average' : ''}`
}

/** "2-week rota from 2041-09-02 · week 1 now" (WC47). */
export function rotaText(rota: WorkerRotaDto): string {
  return `${rota.weeks}-week rota from ${rota.startsOn} · week ${rota.currentWeek} now`
}

/** "Week 2: Mon–Thu 22:00–06:00" in a rota; the shift alone otherwise. */
export function rotaShiftText(shift: Pick<WorkerShiftDto, 'shiftStart' | 'shiftEnd' | 'workDays' | 'rotaWeek'>,
                              rota: WorkerRotaDto | null | undefined): string {
  return rota ? `Week ${shift.rotaWeek ?? 1}: ${shiftText(shift)}` : shiftText(shift)
}

/**
 * Checks a rota before it is sent (WC48): it needs the week it starts in, and each shift a week within it. Without a rota
 * (one week) the shifts' weeks are not sent, so there is nothing to check. Null when fine.
 */
export function rotaProblem(weeks: number, startsOn: string, shiftWeeks: number[]): string | null {
  if (weeks < 2) return null
  if (!/^\d{4}-\d{2}-\d{2}$/.test(startsOn)) return 'Choose the week the rota starts.'
  const beyond = shiftWeeks.findIndex((week) => week > weeks)
  return beyond >= 0 ? `Shift ${beyond + 1} is in week ${shiftWeeks[beyond]}, but the rota has ${weeks} weeks.` : null
}

/**
 * A date's own shifts (docs/domain/worker-calendar.md WC33): "Wed 2041-06-05 · Off" or "Tue 2041-06-04 · 06:00–14:00,
 * 14:00–18:00 · 12 h", with the reason after a dot.
 */
export function workerDayText(day: Pick<WorkerDayDto, 'date' | 'shifts' | 'off' | 'hours' | 'reason'>): string {
  const times = day.shifts.map((shift) => {
    if (shift.shiftStart === shift.shiftEnd) return 'All day'
    return `${shift.shiftStart}–${shift.shiftEnd}${shift.shiftEnd < shift.shiftStart ? ' (ends next day)' : ''}`
  })
  const what = day.off ? 'Off' : `${times.join(', ')} · ${Number(day.hours.toFixed(2))} h`
  return `${dayLabel(day.date)} · ${what}${day.reason ? ` · ${day.reason}` : ''}`
}

/** "2030-01-09" or "2030-01-09 – 2030-01-11", with the reason after a dot. */
export function dayOffText(dayOff: Pick<WorkerDayOffDto, 'firstDay' | 'lastDay' | 'reason'>): string {
  const days = dayOff.firstDay === dayOff.lastDay ? dayOff.firstDay : `${dayOff.firstDay} – ${dayOff.lastDay}`
  return dayOff.reason ? `${days} · ${dayOff.reason}` : days
}

/** A version that has not started yet; only those can be changed or deleted (docs/architecture/adr/ADR-013-rota-history.md). */
export function scheduleUpcoming(line: { effectiveFrom: string | null }, today: string): boolean {
  return line.effectiveFrom !== null && line.effectiveFrom > today
}

/** A version's line: from the start or a date, and whether it applies now, is still ahead or is history (ADR-013). */
export function scheduleText(line: { effectiveFrom: string | null; current: boolean }, today: string): string {
  const from = line.effectiveFrom === null ? 'From the start' : `From ${line.effectiveFrom}`
  if (line.current) return `${from} · now`
  return scheduleUpcoming(line, today) ? `${from} · upcoming` : `${from} · earlier`
}

/** Shifts start today or later; empty is today (ADR-013 decisions 3, 6). */
export function startsOnProblem(startsOn: string, today: string): string | null {
  return startsOn && startsOn < today ? 'Pick today or a later date; change a past date with a day change.' : null
}
