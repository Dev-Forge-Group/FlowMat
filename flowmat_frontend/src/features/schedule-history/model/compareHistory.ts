export type HistoryKind = 'worker' | 'equipment'

export interface HistoryEntry {
  historyId: string
  effectiveFrom: string | null
  action: 'saved' | 'removed'
  reason: string | null
  savedAt: string
  savedBy: string
  shifts: readonly { shiftStart: string; shiftEnd: string; workDays: readonly number[]; rotaWeek?: number }[]
  rota?: { weeks: number; startsOn: string } | null
}

export interface HistoryDifference { field: string; before: string; after: string; changed: boolean }

const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']

function timeText(value: string): string {
  const parts = value.split(':')
  if (parts.length !== 3) return value
  const seconds = parts[2].includes('.') ? parts[2].replace(/0+$/, '').replace(/\.$/, '') : parts[2]
  return seconds === '00' ? parts.slice(0, 2).join(':') : `${parts[0]}:${parts[1]}:${seconds}`
}

function shiftsText(entry: HistoryEntry, kind: HistoryKind): string {
  if (entry.shifts.length === 0) return kind === 'equipment' ? 'Around the clock' : 'No shifts'
  return entry.shifts.map((shift) => {
    const week = kind === 'worker' && entry.rota ? `Week ${shift.rotaWeek ?? 1} · ` : ''
    const days = [...new Set(shift.workDays)].sort((a, b) => a - b).map((day) => DAYS[day - 1] ?? String(day)).join(', ')
    return `${week}${days} ${timeText(shift.shiftStart)}–${timeText(shift.shiftEnd)}`
  }).sort().join('; ')
}

/** Compare recorded settings; save metadata identifies the entries but does not change their settings. */
export function compareHistory(before: HistoryEntry, after: HistoryEntry, kind: HistoryKind): HistoryDifference[] {
  const row = (field: string, left: string, right: string): HistoryDifference => ({ field, before: left, after: right, changed: left !== right })
  const rota = (entry: HistoryEntry) => entry.rota ? `Every ${entry.rota.weeks} weeks from ${entry.rota.startsOn}` : 'Weekly'
  return [
    row('Action', before.action === 'removed' ? 'Removed' : 'Saved', after.action === 'removed' ? 'Removed' : 'Saved'),
    row('Applies from', before.effectiveFrom ?? 'From the start', after.effectiveFrom ?? 'From the start'),
    row('Shifts', shiftsText(before, kind), shiftsText(after, kind)),
    ...(kind === 'worker' ? [row('Rota', rota(before), rota(after))] : []),
    row('Note', before.reason ?? 'None', after.reason ?? 'None'),
  ]
}
