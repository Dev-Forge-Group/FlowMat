import type { EquipmentLoadOrderDto, EquipmentLoadRowDto } from '../../../entities/production/api/useEquipmentLoad'
import { formatMinutes } from './changeoverModel'
import { formatHours } from './equipmentScheduleModel'

/** Monday 00:00 (local) of the week the date falls in. */
export function weekStart(date: Date): Date {
  const sinceMonday = (date.getDay() + 6) % 7
  return new Date(date.getFullYear(), date.getMonth(), date.getDate() - sinceMonday)
}

export function shiftWeeks(start: Date, weeks: number): Date {
  return new Date(start.getFullYear(), start.getMonth(), start.getDate() + weeks * 7)
}

/** The seven days from the week's Monday as instants for the API. */
export function weekRange(start: Date): { from: string; to: string } {
  return { from: start.toISOString(), to: shiftWeeks(start, 1).toISOString() }
}

/** "2030-01-07" for a date input, in local time. */
export function dateInputValue(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** A date input's value as a local date, or null when it is not a date. */
export function parseDateInput(value: string): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (!match) return null
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]))
  return Number.isNaN(date.getTime()) ? null : date
}

export type LoadTone = 'over' | 'high' | 'ok' | 'idle'

/** Over 100 %, from 85 %, some load, or nothing planned (or no time at all). */
export function loadTone(row: EquipmentLoadRowDto): LoadTone {
  if (row.overloaded) return 'over'
  if (row.loadPercent === null || row.plannedHours === 0) return 'idle'
  return row.loadPercent >= 85 ? 'high' : 'ok'
}

/** "63.8%", or "—" when the equipment has no time in the window. */
export function loadLabel(row: EquipmentLoadRowDto): string {
  return row.loadPercent === null ? '—' : `${Number(row.loadPercent.toFixed(1))}%`
}

const DAY_NAMES = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

/** "Mon 01-07" for each of the seven days from the week's Monday, local time. */
export function dayLabels(start: Date): string[] {
  return Array.from({ length: 7 }, (_, offset) => {
    const day = new Date(start.getFullYear(), start.getMonth(), start.getDate() + offset)
    return `${DAY_NAMES[day.getDay()]} ${pad(day.getMonth() + 1)}-${pad(day.getDate())}`
  })
}

/** "Mon 01-07 09:00", local time. */
function stamp(iso: string): string {
  const time = new Date(iso)
  return `${DAY_NAMES[time.getDay()]} ${pad(time.getMonth() + 1)}-${pad(time.getDate())} ${pad(time.getHours())}:${pad(time.getMinutes())}`
}

/** An order's planned window inside the shown week, as a share of it. */
export interface TimelineBar {
  workOrderId: string
  workOrderNumber: string
  /** Percent of the week from its start, and of its length. */
  left: number
  width: number
  /** Row within the equipment's strip, so overlapping bars do not cover each other. */
  lane: number
  draft: boolean
  /** Approved or running, and overlapping another approved or running order on the equipment. */
  clash: boolean
  /** The order line and its full planned window. */
  title: string
}

/**
 * One equipment's orders as bars on the week [from, to) (docs/domain/equipment-load.md "타임라인"): each planned window
 * clipped to the week, earliest first, in the first lane that is free by then. Approved and running orders whose planned
 * windows overlap clash, as readiness's "schedule" check says; drafts are not planned work yet and never clash. On
 * equipment that runs work orders side by side (docs/domain/equipment.md E12) nothing clashes, unless more than its
 * {@code limit} run at once at some moment of the order (E18).
 */
export function timelineBars(orders: EquipmentLoadOrderDto[], from: string, to: string, sideBySide = false,
  limit: number | null = null): { bars: TimelineBar[]; lanes: number } {
  const weekStartAt = Date.parse(from)
  const length = Date.parse(to) - weekStartAt
  const planned = orders.filter((order) => order.workOrderStatus !== 'draft')
  const overlaps = (left: EquipmentLoadOrderDto, right: EquipmentLoadOrderDto) =>
    Date.parse(left.plannedStartAt) < Date.parse(right.plannedEndAt) && Date.parse(right.plannedStartAt) < Date.parse(left.plannedEndAt)
  // The most planned orders running at once at some moment of this one, itself included.
  const busiest = (order: EquipmentLoadOrderDto) => {
    const start = Date.parse(order.plannedStartAt)
    const end = Date.parse(order.plannedEndAt)
    const moments = [start, ...planned.map((other) => Date.parse(other.plannedStartAt)).filter((at) => at > start && at < end)]
    return Math.max(...moments.map((at) => planned
      .filter((other) => Date.parse(other.plannedStartAt) <= at && at < Date.parse(other.plannedEndAt)).length))
  }
  const shown = orders
    .map((order) => ({
      order,
      start: Math.max(Date.parse(order.plannedStartAt), weekStartAt),
      end: Math.min(Date.parse(order.plannedEndAt), weekStartAt + length),
    }))
    .filter((one) => one.end > one.start)
    .sort((left, right) => left.start - right.start || left.end - right.end)
  const laneEnds: number[] = []
  const bars = shown.map(({ order, start, end }) => {
    let lane = laneEnds.findIndex((laneEnd) => laneEnd <= start)
    if (lane === -1) {
      lane = laneEnds.length
      laneEnds.push(end)
    } else {
      laneEnds[lane] = end
    }
    const draft = order.workOrderStatus === 'draft'
    return {
      workOrderId: order.workOrderId,
      workOrderNumber: order.workOrderNumber,
      left: ((start - weekStartAt) / length) * 100,
      width: Math.max(((end - start) / length) * 100, 0.5),
      lane,
      draft,
      clash: !draft && (sideBySide
        ? limit !== null && busiest(order) > limit
        : planned.some((other) => other.workOrderId !== order.workOrderId && overlaps(order, other))),
      title: `${orderLine(order)} · ${stamp(order.plannedStartAt)} → ${stamp(order.plannedEndAt)}`,
    }
  })
  return { bars, lanes: Math.max(laneEnds.length, 1) }
}

/** One line per order, e.g. "WO-1 · RED · 10 h", "WO-2 · draft · 5 h", "WO-3 · 15.5 of 31 h", "WO-4 · hours unknown". */
export function orderLine(order: EquipmentLoadOrderDto): string {
  const parts = [order.workOrderNumber]
  if (order.targetItemCode) parts.push(order.targetItemCode)
  if (order.workOrderStatus === 'draft') parts.push('draft')
  if (order.hoursInWindow === null || order.neededHours === null) {
    parts.push('hours unknown')
  } else if (order.hoursInWindow !== order.neededHours) {
    parts.push(`${Number(order.hoursInWindow.toFixed(2))} of ${formatHours(order.neededHours)}`)
  } else {
    parts.push(formatHours(order.hoursInWindow))
  }
  if (order.changeoverMinutes) parts.push(`incl. ${order.changeoverMinutes} min changeover`)
  return parts.join(' · ')
}

/** "Changeovers 3 h as planned · 1 h as WO-2, WO-1, WO-3", without the suggestion when none changes over less. */
export function changeoverPlanText(plan: NonNullable<EquipmentLoadRowDto['changeovers']>): string {
  const planned = `Changeovers ${formatMinutes(plan.plannedMinutes)} as planned`
  return plan.suggestedMinutes === null || !plan.suggestedOrder
    ? planned
    : `${planned} · ${formatMinutes(plan.suggestedMinutes)} as ${plan.suggestedOrder.join(', ')}`
}

/** "2030-01-07T09:00" in the browser's time, for a datetime-local input. */
export function localDateTimeInput(iso: string): string {
  const at = new Date(iso)
  if (Number.isNaN(at.getTime())) return ''
  return new Date(at.getTime() - at.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
}

/**
 * The order's planned window moved to start at {@code start} (a datetime-local value in the browser's time), keeping its
 * length (docs/domain/equipment-load.md M1); null when the start cannot be read or does not move it.
 */
export function movedWindow(order: Pick<EquipmentLoadOrderDto, 'plannedStartAt' | 'plannedEndAt'>, start: string):
  { plannedStartAt: string; plannedEndAt: string } | null {
  const from = new Date(start)
  const was = Date.parse(order.plannedStartAt)
  const until = Date.parse(order.plannedEndAt)
  if (!start || Number.isNaN(from.getTime()) || Number.isNaN(was) || Number.isNaN(until) || from.getTime() === was) return null
  return {
    plannedStartAt: from.toISOString(),
    plannedEndAt: new Date(from.getTime() + (until - was)).toISOString(),
  }
}
