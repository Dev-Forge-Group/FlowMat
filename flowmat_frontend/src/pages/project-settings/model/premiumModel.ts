import type { AttendancePayDayDto, AttendancePayDto, LaborPremiumInput, LaborPremiumLineDto } from '../../../entities/workforce/api/useLaborPremiums'
import { formatQty } from '../../../shared/lib/formatQty'
import { minutesText } from './attendanceModel'

/** ISO days, 1 Monday to 7 Sunday. */
export const WEEKDAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']

/**
 * One save of the pay premiums (docs/architecture/adr/ADR-028-pay-premiums.md), e.g. "From 2041-09-01 (current): overtime after
 * 8 h +50% · over 40 h a week from Monday +50% · night 22:00–06:00 +50% · holidays +100% · Labor law · set by demo-owner".
 */
export function premiumRuleText(line: LaborPremiumLineDto): string {
  const parts: string[] = []
  if (line.overtimeAfterMinutes != null && line.overtimePercent != null) {
    parts.push(`overtime after ${minutesText(line.overtimeAfterMinutes)} +${line.overtimePercent}%`)
  }
  if (line.weeklyOvertimeAfterMinutes != null && line.weeklyOvertimePercent != null) {
    parts.push(`over ${minutesText(line.weeklyOvertimeAfterMinutes)} a week from ${WEEKDAYS[(line.weekStartsOn ?? 1) - 1]}`
      + ` +${line.weeklyOvertimePercent}%`)
  }
  if (line.nightStart && line.nightEnd && line.nightPercent != null) parts.push(`night ${line.nightStart}–${line.nightEnd} +${line.nightPercent}%`)
  if (line.holidayPercent != null) {
    const past = line.holidayOvertimeAfterMinutes != null && line.holidayOvertimePercent != null
      ? `, past ${minutesText(line.holidayOvertimeAfterMinutes)} +${line.holidayOvertimePercent}%` : ''
    parts.push(`holidays${line.restDaysAsHolidays ? ' and rest days' : ''} +${line.holidayPercent}%${past}`)
  }
  const state = line.state === 'removed' ? `removed by ${line.removedBy ?? 'someone'}` : line.state
  const what = line.followProject ? "follows the project's premiums" : parts.length ? parts.join(' · ') : 'no premiums'
  return `From ${line.effectiveFrom} (${state}): ${what}${line.reason ? ` · ${line.reason}` : ''}`
    + ` · set by ${line.setBy}`
}

/** "9 h 15 min", or "45.5 min" when the premiums leave part of a minute. */
function paidText(minutes: number): string {
  return Number.isInteger(Number(minutes)) ? minutesText(Number(minutes)) : `${formatQty(minutes)} min`
}

/** One work day, e.g. "2041-09-03 · holiday · attended 10 h · overtime 2 h · paid 21 h · 1,260"; the premiums of the day only. */
export function payDayText(day: AttendancePayDayDto): string {
  const parts = [day.workDate]
  if (day.holiday) parts.push('holiday')
  if (day.restDayHoliday) parts.push('rest day')
  parts.push(`attended ${minutesText(day.attendedMinutes)}`)
  if (day.overtimeMinutes > 0) parts.push(`overtime ${minutesText(day.overtimeMinutes)}`)
  if ((day.weeklyOvertimeMinutes ?? 0) > 0) parts.push(`weekly overtime ${minutesText(day.weeklyOvertimeMinutes ?? 0)}`)
  if ((day.holidayOvertimeMinutes ?? 0) > 0) parts.push(`holiday overtime ${minutesText(day.holidayOvertimeMinutes ?? 0)}`)
  if (day.nightMinutes > 0) parts.push(`night ${minutesText(day.nightMinutes)}`)
  parts.push(`paid ${paidText(day.paidMinutes)}`)
  parts.push(day.amount == null ? 'no rate' : formatQty(day.amount))
  return parts.join(' · ')
}

/** "Paid 40 h for 26 h attended · 2,400", or "· 1,000 known (some days have no rate)". */
export function payTotalText(pay: AttendancePayDto): string {
  if (pay.days.length === 0) return 'No attendance in these days.'
  const amount = pay.costComplete ? formatQty(pay.amount) : `${formatQty(pay.amount)} known (some days have no rate)`
  return `Paid ${paidText(pay.paidMinutes)} for ${minutesText(pay.attendedMinutes)} attended · ${amount}`
}

/** The premiums form as typed: hours, percents and times as text. */
export interface PremiumForm {
  overtimeAfterHours: string
  overtimePercent: string
  weeklyOvertimeAfterHours: string
  weeklyOvertimePercent: string
  /** "1" Monday to "7" Sunday. */
  weekStartsOn: string
  nightStart: string
  nightEnd: string
  nightPercent: string
  holidayPercent: string
  effectiveFrom: string
  reason: string
  holidayOvertimeAfterHours: string
  holidayOvertimePercent: string
  /** Rest days get the holiday percent too. */
  restDaysAsHolidays: boolean
}

export const EMPTY_PREMIUM_FORM: PremiumForm = {
  overtimeAfterHours: '', overtimePercent: '', weeklyOvertimeAfterHours: '', weeklyOvertimePercent: '', weekStartsOn: '1', nightStart: '',
  nightEnd: '', nightPercent: '', holidayPercent: '', effectiveFrom: '', reason: '', holidayOvertimeAfterHours: '', holidayOvertimePercent: '',
  restDaysAsHolidays: false,
}

function percentOf(text: string, label: string): { value?: number; problem?: string } {
  if (text.trim() === '') return {}
  const value = Number(text)
  return Number.isInteger(value) && value >= 1 && value <= 500 ? { value } : { problem: `${label} is a whole percent from 1 to 500.` }
}

/**
 * What to save, or what is wrong (ADR-028 decision 3): overtime needs its hours and percent, the night its start, end and
 * percent, or neither; all empty stops the premiums from that date. The server checks again.
 */
export function premiumPayload(form: PremiumForm): { payload: LaborPremiumInput; problem: null } | { payload: null; problem: string } {
  const payload: LaborPremiumInput = {}
  const hours = form.overtimeAfterHours.trim()
  const overtime = percentOf(form.overtimePercent, 'Overtime')
  if (overtime.problem) return { payload: null, problem: overtime.problem }
  if ((hours === '') !== (overtime.value === undefined)) {
    return { payload: null, problem: 'Overtime needs both the hours after which it starts and its percent.' }
  }
  if (hours !== '') {
    const minutes = Number(hours) * 60
    if (!Number.isInteger(minutes) || minutes < 1 || minutes > 1440) {
      return { payload: null, problem: 'Overtime starts after up to 24 hours of a work day, in whole minutes.' }
    }
    payload.overtimeAfterMinutes = minutes
    payload.overtimePercent = overtime.value
  }
  const weeklyHours = form.weeklyOvertimeAfterHours.trim()
  const weekly = percentOf(form.weeklyOvertimePercent, 'Weekly overtime')
  if (weekly.problem) return { payload: null, problem: weekly.problem }
  if ((weeklyHours === '') !== (weekly.value === undefined)) {
    return { payload: null, problem: 'Weekly overtime needs both the hours a week after which it starts and its percent.' }
  }
  if (weeklyHours !== '') {
    const minutes = Number(weeklyHours) * 60
    if (!Number.isInteger(minutes) || minutes < 1 || minutes > 10080) {
      return { payload: null, problem: 'Weekly overtime starts after up to 168 hours a week, in whole minutes.' }
    }
    payload.weeklyOvertimeAfterMinutes = minutes
    payload.weeklyOvertimePercent = weekly.value
    payload.weekStartsOn = Number(form.weekStartsOn) || 1
  }
  const night = percentOf(form.nightPercent, 'Night')
  if (night.problem) return { payload: null, problem: night.problem }
  const nightGiven = [form.nightStart, form.nightEnd].filter((value) => value !== '').length + (night.value === undefined ? 0 : 1)
  if (nightGiven !== 0 && nightGiven !== 3) return { payload: null, problem: 'Night needs a start, an end and a percent.' }
  if (nightGiven === 3) {
    if (form.nightStart === form.nightEnd) return { payload: null, problem: 'Night must start and end at different times.' }
    payload.nightStart = form.nightStart
    payload.nightEnd = form.nightEnd
    payload.nightPercent = night.value
  }
  const holiday = percentOf(form.holidayPercent, 'Holiday')
  if (holiday.problem) return { payload: null, problem: holiday.problem }
  if (holiday.value !== undefined) payload.holidayPercent = holiday.value
  if (form.restDaysAsHolidays) {
    if (holiday.value === undefined) return { payload: null, problem: 'Rest days count as holidays only with a holiday percent.' }
    payload.restDaysAsHolidays = true
  }
  const holidayHours = form.holidayOvertimeAfterHours.trim()
  const holidayOvertime = percentOf(form.holidayOvertimePercent, 'Holiday overtime')
  if (holidayOvertime.problem) return { payload: null, problem: holidayOvertime.problem }
  if ((holidayHours === '') !== (holidayOvertime.value === undefined)) {
    return { payload: null, problem: 'Holiday overtime needs both the hours after which it starts and its percent.' }
  }
  if (holidayHours !== '') {
    if (holiday.value === undefined) return { payload: null, problem: 'Holiday overtime goes with a holiday percent.' }
    const minutes = Number(holidayHours) * 60
    if (!Number.isInteger(minutes) || minutes < 1 || minutes > 1440) {
      return { payload: null, problem: 'Holiday overtime starts after up to 24 hours of a holiday, in whole minutes.' }
    }
    payload.holidayOvertimeAfterMinutes = minutes
    payload.holidayOvertimePercent = holidayOvertime.value
  }
  if (form.effectiveFrom) payload.effectiveFrom = form.effectiveFrom
  if (form.reason.trim()) payload.reason = form.reason.trim()
  return { payload, problem: null }
}
