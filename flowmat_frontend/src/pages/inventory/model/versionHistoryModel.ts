import type { EquipmentCalendarHistoryDto } from '../../../entities/catalog/api/useEquipment'
import type { WorkerScheduleHistoryDto } from '../../../entities/workforce/api/useWorkerCalendars'
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
