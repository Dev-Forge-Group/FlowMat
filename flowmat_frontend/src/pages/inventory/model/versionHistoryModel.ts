import type { EquipmentCalendarHistoryDto, EquipmentDayHistoryDto } from '../../../entities/catalog/api/useEquipment'
import type { WorkerDayHistoryDto, WorkerScheduleHistoryDto } from '../../../entities/workforce/api/useWorkerCalendars'
import { daysText } from '../../project-settings/model/workerCalendarModel'
import { dayList } from './equipmentScheduleModel'

/** "2041-09-01 09:30" in the browser's time. */
export function savedAtText(iso: string): string {
  const at = new Date(iso)
  if (Number.isNaN(at.getTime())) return iso
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())} ${pad(at.getHours())}:${pad(at.getMinutes())}`
}

function head(entry: { savedAt: string; savedBy: string; action: string; effectiveFrom: string | null }): string {
  const from = entry.effectiveFrom ? `from ${entry.effectiveFrom}` : 'from the start'
  return `${savedAtText(entry.savedAt)} · ${entry.savedBy} ${entry.action === 'removed' ? 'removed' : 'saved'} the version ${from}`
}

/**
 * One save or removal of a worker's version (docs/domain/worker-calendar.md WC64), e.g. "2041-09-01 09:30 · kim saved the
 * version from 2041-09-09: week 2 Mon–Wed 22:00–06:00, every 2 weeks · Nights".
 */
export function workerHistoryText(entry: WorkerScheduleHistoryDto): string {
  const shifts = entry.shifts.length === 0 ? 'no shifts'
    : entry.shifts.map((shift) => `${entry.rota ? `week ${shift.rotaWeek} ` : ''}${daysText(shift.workDays)} ${shift.shiftStart}–${shift.shiftEnd}`)
      .join(', ')
  const rota = entry.rota ? `, every ${entry.rota.weeks} weeks` : ''
  return `${head(entry)}: ${shifts}${rota}${entry.reason ? ` · ${entry.reason}` : ''}`
}

/**
 * One save or removal of an equipment calendar version (docs/domain/equipment-schedule.md CV11), e.g. "2041-09-01 09:30 ·
 * demo-owner saved the version from 2041-09-16: around the clock · Overhaul".
 */
export function calendarHistoryText(entry: EquipmentCalendarHistoryDto): string {
  const shifts = entry.shifts.length === 0 ? 'around the clock'
    : entry.shifts.map((shift) => `${dayList(shift.workDays)} ${shift.shiftStart}–${shift.shiftEnd}`).join(', ')
  return `${head(entry)}: ${shifts}${entry.reason ? ` · ${entry.reason}` : ''}`
}

type DayChange = Pick<WorkerDayHistoryDto, 'action' | 'days' | 'reason' | 'savedBy' | 'savedAt'> & {
  firstDate: string | null; lastDate: string | null
}

/** "2041-09-02–2041-09-06 (3 dates)", or the one date. */
function datesText(first: string | null, last: string | null, count: number): string {
  const span = !first ? '' : !last || last === first ? first : `${first}–${last}`
  return count > 1 ? `${span} (${count} dates)` : span
}

/** The dates' shifts once when every date has the same, "no work" for none. */
function dateShiftsText(days: DayChange['days']): string {
  const texts = new Set(days.map((day) => day.shifts.length === 0 ? 'no work'
    : day.shifts.map((shift) => `${shift.shiftStart}–${shift.shiftEnd}`).join(', ')))
  return texts.size === 1 ? [...texts][0] : 'different shifts per date'
}

function dayChangeText(entry: DayChange, back: string, copiedFrom: string | null): string {
  const dates = datesText(entry.firstDate, entry.lastDate, entry.days.length)
  if (entry.action === 'removed') return `${savedAtText(entry.savedAt)} · ${entry.savedBy} removed ${dates}, back to ${back}`
  const copy = copiedFrom ? `, copied from ${copiedFrom}` : ''
  return `${savedAtText(entry.savedAt)} · ${entry.savedBy} saved ${dates}${copy}: ${dateShiftsText(entry.days)}`
    + `${entry.reason ? ` · ${entry.reason}` : ''}`
}

/**
 * One change of a worker's own dates or days off (docs/domain/worker-calendar.md WC66), e.g. "2041-09-01 09:30 · kim saved
 * 2041-09-02–2041-09-06 (3 dates): 06:00–14:00 · Overtime".
 */
export function workerDayHistoryText(entry: WorkerDayHistoryDto): string {
  if (entry.kind === 'day_off') {
    return `${savedAtText(entry.savedAt)} · ${entry.savedBy} ${entry.action === 'removed' ? 'removed' : 'added'} days off `
      + `${datesText(entry.firstDate, entry.lastDate, 1)}${entry.reason ? ` · ${entry.reason}` : ''}`
  }
  return dayChangeText(entry, 'the weekly shifts', entry.copiedFrom)
}

/**
 * One change of an equipment's own dates or downtimes (docs/domain/equipment-schedule.md CV13), e.g. "2041-09-01 11:00 ·
 * demo-owner added maintenance 2041-09-05 08:00–2041-09-05 12:00 · Oil change". {@code label} names the equipment a copy
 * came from.
 */
export function equipmentDayHistoryText(entry: EquipmentDayHistoryDto, label: (equipmentId: string) => string = (id) => id): string {
  if (entry.kind === 'downtime') {
    const span = entry.startsAt && entry.endsAt ? ` ${savedAtText(entry.startsAt)}–${savedAtText(entry.endsAt)}` : ''
    return `${savedAtText(entry.savedAt)} · ${entry.savedBy} ${entry.action === 'removed' ? 'removed' : 'added'} `
      + `${entry.downtimeType ?? 'downtime'}${span}${entry.reason ? ` · ${entry.reason}` : ''}`
  }
  return dayChangeText(entry, 'the calendar', entry.copiedFrom ? label(entry.copiedFrom) : null)
}
