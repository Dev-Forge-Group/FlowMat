import { describe, expect, it } from 'vitest'
import {
  calendarHistoryText, equipmentDayHistoryText, savedAtText, workerDayHistoryText, workerHistoryText,
} from './versionHistoryModel'

describe('version history', () => {
  it('reads when it was saved in the browser\'s time', () => {
    expect(savedAtText('2041-09-01T09:30:00')).toBe('2041-09-01 09:30')
    expect(savedAtText('not a time')).toBe('not a time')
  })

  it('says what a worker\'s version held when it was saved or removed', () => {
    expect(workerHistoryText({ historyId: 'h1', scheduleId: 's1', effectiveFrom: '2041-09-09', action: 'removed', reason: 'Nights',
      shifts: [{ shiftStart: '22:00', shiftEnd: '06:00', workDays: [1, 2, 3], rotaWeek: 2 }], rota: { weeks: 2, startsOn: '2041-09-09' },
      savedBy: 'demo-owner', savedAt: '2041-09-01T09:30:00' }))
      .toBe('2041-09-01 09:30 · demo-owner removed the version from 2041-09-09: week 2 Mon–Wed 22:00–06:00, every 2 weeks · Nights')
    expect(workerHistoryText({ historyId: 'h2', scheduleId: 's0', effectiveFrom: null, action: 'saved', reason: null,
      shifts: [{ shiftStart: '06:00', shiftEnd: '14:00', workDays: [1, 2, 3, 4, 5], rotaWeek: 1 }], rota: null,
      savedBy: 'kim', savedAt: '2041-09-01T08:00:00' }))
      .toBe('2041-09-01 08:00 · kim saved the version from the start: Mon–Fri 06:00–14:00')
  })

  it('says what an equipment calendar version held, none for around the clock', () => {
    expect(calendarHistoryText({ historyId: 'h3', scheduleId: 'v2', effectiveFrom: '2041-09-16', action: 'saved', reason: 'Overhaul',
      shifts: [], savedBy: 'demo-owner', savedAt: '2041-09-01T09:30:00' }))
      .toBe('2041-09-01 09:30 · demo-owner saved the version from 2041-09-16: around the clock · Overhaul')
    expect(calendarHistoryText({ historyId: 'h4', scheduleId: 'v1', effectiveFrom: '2041-09-09', action: 'saved', reason: null,
      shifts: [{ shiftStart: '06:00', shiftEnd: '14:00', workDays: [1, 2, 3, 4, 5] }], savedBy: 'demo-owner',
      savedAt: '2041-09-01T09:00:00' }))
      .toBe('2041-09-01 09:00 · demo-owner saved the version from 2041-09-09: Mon–Fri 06:00–14:00')
  })

  it('says what a worker\'s own dates held when saved, copied or removed, and their days off', () => {
    const day = { historyId: 'd1', kind: 'day' as const, action: 'saved' as const, firstDate: '2041-09-02', lastDate: '2041-09-06',
      days: ['2041-09-02', '2041-09-04', '2041-09-06'].map((date) => ({ date, shifts: [{ shiftStart: '06:00', shiftEnd: '14:00' }],
        reason: 'Overtime' })), dayOffId: null, reason: 'Overtime', copiedFrom: null, savedBy: 'kim', savedAt: '2041-09-01T09:30:00' }
    expect(workerDayHistoryText(day)).toBe('2041-09-01 09:30 · kim saved 2041-09-02–2041-09-06 (3 dates): 06:00–14:00 · Overtime')
    expect(workerDayHistoryText({ ...day, firstDate: '2041-09-06', lastDate: '2041-09-06', days: [day.days[2]], reason: null,
      copiedFrom: 'lee' })).toBe('2041-09-01 09:30 · kim saved 2041-09-06, copied from lee: 06:00–14:00')
    expect(workerDayHistoryText({ ...day, action: 'removed', lastDate: '2041-09-04',
      days: [day.days[0], { date: '2041-09-04', shifts: [], reason: 'No work' }] }))
      .toBe('2041-09-01 09:30 · kim removed 2041-09-02–2041-09-04 (2 dates), back to the weekly shifts')
    expect(workerDayHistoryText({ ...day, days: [day.days[0], { date: '2041-09-04', shifts: [], reason: null }] }))
      .toBe('2041-09-01 09:30 · kim saved 2041-09-02–2041-09-06 (2 dates): different shifts per date · Overtime')
    expect(workerDayHistoryText({ ...day, kind: 'day_off', firstDate: '2041-10-01', lastDate: '2041-10-03', days: [],
      dayOffId: 'off-1', reason: 'Holiday' })).toBe('2041-09-01 09:30 · kim added days off 2041-10-01–2041-10-03 · Holiday')
  })

  it('says what an equipment\'s own dates and downtimes held, naming where a copy came from', () => {
    const closed = { historyId: 'e1', kind: 'day' as const, action: 'saved' as const, firstDate: '2041-09-03', lastDate: '2041-09-03',
      days: [{ date: '2041-09-03', shifts: [], reason: 'Closed' }], downtimeId: null, downtimeType: null, startsAt: null,
      endsAt: null, reason: null, copiedFrom: 'drill', savedBy: 'demo-owner', savedAt: '2041-09-01T10:30:00' }
    expect(equipmentDayHistoryText(closed, (id) => id.toUpperCase()))
      .toBe('2041-09-01 10:30 · demo-owner saved 2041-09-03, copied from DRILL: no work')
    expect(equipmentDayHistoryText({ ...closed, action: 'removed', copiedFrom: null }))
      .toBe('2041-09-01 10:30 · demo-owner removed 2041-09-03, back to the calendar')
    expect(equipmentDayHistoryText({ ...closed, kind: 'downtime', firstDate: null, lastDate: null, days: [], downtimeId: 'down-1',
      downtimeType: 'maintenance', startsAt: '2041-09-05T08:00:00', endsAt: '2041-09-05T12:00:00', reason: 'Oil change',
      copiedFrom: null })).toBe('2041-09-01 10:30 · demo-owner added maintenance 2041-09-05 08:00–2041-09-05 12:00 · Oil change')
  })
})
