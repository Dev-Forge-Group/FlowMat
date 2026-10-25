import { describe, expect, it } from 'vitest'
import type { EquipmentLoadOrderDto, EquipmentLoadRowDto } from '../../../entities/production/api/useEquipmentLoad'
import {
  changeoverPlanText,
  dateInputValue,
  dayLabels,
  loadLabel,
  localDateTimeInput,
  movedWindow,
  loadTone,
  orderLine,
  parseDateInput,
  shiftWeeks,
  timelineBars,
  weekRange,
  weekStart,
} from './equipmentLoadModel'

function row(patch: Partial<EquipmentLoadRowDto>): EquipmentLoadRowDto {
  return {
    equipmentId: 'e', equipmentCode: 'LINE', equipmentName: 'Line', equipmentStatus: 'active', capacityPerHour: 10,
    calendarSet: true, availableHours: 40, downtimeHours: 0, plannedHours: 10, draftHours: 0, loadPercent: 25, overloaded: false,
    unplannedOrders: 0, unmeasuredOrders: 0, orders: [], ...patch,
  }
}

function order(patch: Partial<EquipmentLoadOrderDto>): EquipmentLoadOrderDto {
  return {
    workOrderId: 'w', workOrderNumber: 'WO-1', workOrderTitle: 'Red', workOrderStatus: 'approved', targetItemId: 'i',
    targetItemCode: 'RED', plannedStartAt: '2030-01-07T00:00:00Z', plannedEndAt: '2030-01-08T00:00:00Z', remainingQuantity: 100,
    changeoverMinutes: null, neededHours: 10, hoursInWindow: 10, ...patch,
  }
}

describe('weeks', () => {
  it('starts on Monday at local midnight and moves by whole weeks', () => {
    const wednesday = new Date(2030, 0, 9, 15, 30)
    const monday = weekStart(wednesday)
    expect([monday.getFullYear(), monday.getMonth(), monday.getDate(), monday.getHours()]).toEqual([2030, 0, 7, 0])
    expect(weekStart(new Date(2030, 0, 13)).getDate()).toBe(7)
    expect(weekStart(new Date(2030, 0, 14)).getDate()).toBe(14)
    expect(dateInputValue(shiftWeeks(monday, -1))).toBe('2029-12-31')
    const range = weekRange(monday)
    expect(new Date(range.to).getTime() - new Date(range.from).getTime()).toBe(7 * 24 * 3600 * 1000)
    expect(dateInputValue(parseDateInput('2030-01-09')!)).toBe('2030-01-09')
    expect(parseDateInput('next week')).toBeNull()
  })
})

describe('timeline', () => {
  const at = (day: number, hour: number) => new Date(2030, 0, day, hour).toISOString()
  const week = { from: at(7, 0), to: at(14, 0) }

  it('names the seven days from the Monday', () => {
    expect(dayLabels(new Date(2030, 0, 7))).toEqual(['Mon 01-07', 'Tue 01-08', 'Wed 01-09', 'Thu 01-10', 'Fri 01-11', 'Sat 01-12', 'Sun 01-13'])
  })

  it('clips planned windows to the week, stacks overlaps and marks planned orders that overlap', () => {
    const { bars, lanes } = timelineBars([
      order({ workOrderId: 'b', workOrderNumber: 'WO-2', plannedStartAt: at(7, 9), plannedEndAt: at(11, 17) }),
      order({ workOrderId: 'a', workOrderNumber: 'WO-1', plannedStartAt: at(7, 9), plannedEndAt: at(8, 17) }),
      order({ workOrderId: 'c', workOrderNumber: 'WO-3', workOrderStatus: 'draft', plannedStartAt: at(9, 9), plannedEndAt: at(9, 17) }),
      order({ workOrderId: 'd', workOrderNumber: 'WO-4', workOrderStatus: 'in_progress', plannedStartAt: at(5, 9), plannedEndAt: at(7, 6) }),
      order({ workOrderId: 'e', workOrderNumber: 'WO-5', plannedStartAt: at(14, 9), plannedEndAt: at(15, 9) }),
    ], week.from, week.to)
    expect(lanes).toBe(2)
    expect(bars.map((bar) => [bar.workOrderNumber, bar.lane, bar.draft, bar.clash])).toEqual([
      ['WO-4', 0, false, false],
      ['WO-1', 0, false, true],
      ['WO-2', 1, false, true],
      ['WO-3', 0, true, false],
    ])
    const [carried, first] = bars
    expect(carried.left).toBe(0)
    expect(carried.width).toBeCloseTo((6 / 168) * 100)
    expect(first.left).toBeCloseTo((9 / 168) * 100)
    expect(first.width).toBeCloseTo((32 / 168) * 100)
    expect(first.title).toBe('WO-1 · RED · 10 h · Mon 01-07 09:00 → Tue 01-08 17:00')
  })

  it('marks no clash on equipment that runs work orders side by side (E12)', () => {
    const overlapping = [
      order({ workOrderId: 'a', workOrderNumber: 'WO-1', plannedStartAt: at(7, 9), plannedEndAt: at(8, 17) }),
      order({ workOrderId: 'b', workOrderNumber: 'WO-2', plannedStartAt: at(7, 12), plannedEndAt: at(8, 9) }),
    ]
    expect(timelineBars(overlapping, week.from, week.to).bars.map((bar) => bar.clash)).toEqual([true, true])
    const { bars, lanes } = timelineBars(overlapping, week.from, week.to, true)
    expect(bars.map((bar) => bar.clash)).toEqual([false, false])
    expect(lanes).toBe(2)
  })

  it('marks a clash only where more than its limit run at once (E18)', () => {
    const four = [
      order({ workOrderId: 'a', workOrderNumber: 'WO-1', plannedStartAt: at(7, 9), plannedEndAt: at(7, 17) }),
      order({ workOrderId: 'b', workOrderNumber: 'WO-2', plannedStartAt: at(7, 10), plannedEndAt: at(7, 12) }),
      order({ workOrderId: 'c', workOrderNumber: 'WO-3', plannedStartAt: at(7, 11), plannedEndAt: at(7, 15) }),
      order({ workOrderId: 'd', workOrderNumber: 'WO-4', plannedStartAt: at(7, 15), plannedEndAt: at(7, 16) }),
    ]
    // 11-12 has three at once: too many for 2, enough room with 3.
    expect(timelineBars(four, week.from, week.to, true, 2).bars.map((bar) => bar.clash)).toEqual([true, true, true, false])
    expect(timelineBars(four, week.from, week.to, true, 3).bars.map((bar) => bar.clash)).toEqual([false, false, false, false])
  })

  it('keeps one lane when there is nothing to show', () => {
    expect(timelineBars([], week.from, week.to)).toEqual({ bars: [], lanes: 1 })
  })
})

describe('load display', () => {
  it('tells an overloaded, busy, normal and idle equipment apart', () => {
    expect(loadTone(row({ overloaded: true, loadPercent: 113.8 }))).toBe('over')
    expect(loadTone(row({ loadPercent: 90 }))).toBe('high')
    expect(loadTone(row({}))).toBe('ok')
    expect(loadTone(row({ plannedHours: 0, loadPercent: 0 }))).toBe('idle')
    expect(loadLabel(row({ loadPercent: 63.8 }))).toBe('63.8%')
    expect(loadLabel(row({ loadPercent: null }))).toBe('—')
  })

  it('writes each order with its hours in the window', () => {
    expect(orderLine(order({}))).toBe('WO-1 · RED · 10 h')
    expect(orderLine(order({ workOrderStatus: 'draft', targetItemCode: null, neededHours: 5, hoursInWindow: 5 }))).toBe('WO-1 · draft · 5 h')
    expect(orderLine(order({ neededHours: 31, hoursInWindow: 15.5, changeoverMinutes: 60 })))
      .toBe('WO-1 · RED · 15.5 of 31 h · incl. 60 min changeover')
    expect(orderLine(order({ neededHours: null, hoursInWindow: null }))).toBe('WO-1 · RED · hours unknown')
  })
})

describe('changeoverPlanText', () => {
  it('adds up the planned changeovers and names an order with fewer', () => {
    expect(changeoverPlanText({ plannedMinutes: 180, suggestedMinutes: 60, suggestedOrder: ['WO-2', 'WO-1', 'WO-3'] }))
      .toBe('Changeovers 3 h as planned · 1 h as WO-2, WO-1, WO-3')
    expect(changeoverPlanText({ plannedMinutes: 45, suggestedMinutes: null, suggestedOrder: null })).toBe('Changeovers 45 min as planned')
    expect(changeoverPlanText({ plannedMinutes: 30, suggestedMinutes: 0, suggestedOrder: ['WO-2', 'WO-1'] }))
      .toBe('Changeovers 30 min as planned · 0 min as WO-2, WO-1')
  })

  it('moves an order to a new start and keeps its length', () => {
    const order = { plannedStartAt: '2030-01-07T09:00:00Z', plannedEndAt: '2030-01-07T13:30:00Z' }
    const start = localDateTimeInput('2030-01-08T10:00:00Z')
    expect(movedWindow(order, start)).toEqual({ plannedStartAt: '2030-01-08T10:00:00.000Z', plannedEndAt: '2030-01-08T14:30:00.000Z' })
    expect(movedWindow(order, localDateTimeInput(order.plannedStartAt))).toBeNull()
    expect(movedWindow(order, '')).toBeNull()
    expect(movedWindow(order, 'not a time')).toBeNull()
  })
})
