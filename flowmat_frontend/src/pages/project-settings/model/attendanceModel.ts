import type { AttendanceComparisonDto } from '../../../entities/production/api/useAttendanceComparison'
import type { WorkerAttendanceLineDto } from '../../../entities/workforce/api/useWorkerAttendance'
import { csvCell } from '../../inventory/model/ledgerModel'
import { savedAtText } from '../../inventory/model/versionHistoryModel'

/** "7 h 30 min", "45 min", "0 min". */
export function minutesText(minutes: number): string {
  const whole = Math.abs(Math.trunc(minutes))
  const hours = Math.floor(whole / 60)
  const rest = whole % 60
  const text = hours === 0 ? `${rest} min` : rest === 0 ? `${hours} h` : `${hours} h ${rest} min`
  return minutes < 0 ? `-${text}` : text
}

/**
 * One day of the comparison (docs/domain/worker-calendar.md AT8), e.g. "2041-09-02 · attended 8 h · on runs 6 h 30 min ·
 * 1 h 30 min not on runs".
 */
export function comparisonDayText(day: AttendanceComparisonDto['days'][number]): string {
  const difference = day.differenceMinutes === 0 ? 'matches'
    : day.differenceMinutes > 0 ? `${minutesText(day.differenceMinutes)} not on runs`
      : `${minutesText(-day.differenceMinutes)} more on runs than attended`
  return `${day.date} · attended ${minutesText(day.attendedMinutes)} · on runs ${minutesText(day.laborMinutes)} · ${difference}`
}

/**
 * A worker's attendance over a period as CSV (docs/domain/worker-calendar.md AT9), oldest first: a row per stretch with its work
 * date, clock-in and clock-out as shown on screen, whole minutes, note and who recorded it; cancelled ones stay, marked with who
 * cancelled them and why. It starts with a byte order mark so spreadsheet programs read it as UTF-8.
 */
export function attendanceCsv(lines: WorkerAttendanceLineDto[]): string {
  const header = ['work_date', 'clock_in', 'clock_out', 'minutes', 'note', 'recorded_by', 'cancelled', 'cancelled_by', 'cancel_reason']
  const rows = [...lines].sort((one, other) => one.clockIn.localeCompare(other.clockIn) || one.attendanceId.localeCompare(other.attendanceId))
    .map((line) => [line.workDate, savedAtText(line.clockIn), line.clockOut ? savedAtText(line.clockOut) : null, line.minutes, line.note,
      line.recordedBy, line.cancelled ? 'Y' : 'N', line.cancelledBy, line.cancelReason].map(csvCell).join(','))
  return '\ufeff' + [header.join(','), ...rows].join('\r\n') + '\r\n'
}

/** The period to download, or what is wrong (AT9): a first and a last project day, in order. The server keeps it to 62 days. */
export function exportRange(from: string, to: string): { from: string; to: string; error: null } | { from: null; to: null; error: string } {
  if (!from || !to) return { from: null, to: null, error: 'Choose the first and the last day.' }
  if (from > to) return { from: null, to: null, error: 'The first day comes after the last.' }
  return { from, to, error: null }
}

/** One stretch, e.g. "2041-09-02 08:00 – 16:30 · 8 h 30 min · Day · by kim", or "cancelled: Wrong day". */
export function attendanceLineText(line: WorkerAttendanceLineDto): string {
  const until = line.clockOut ? savedAtText(line.clockOut).slice(11) : 'still at work'
  const length = line.minutes == null ? '' : ` · ${minutesText(line.minutes)}`
  const cancelled = line.cancelled ? ` · cancelled by ${line.cancelledBy ?? 'someone'}: ${line.cancelReason ?? ''}` : ''
  return `${savedAtText(line.clockIn)} – ${until}${length}${line.note ? ` · ${line.note}` : ''} · by ${line.recordedBy}${cancelled}`
}
