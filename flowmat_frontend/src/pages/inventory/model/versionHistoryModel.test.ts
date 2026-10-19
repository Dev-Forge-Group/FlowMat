import { describe, expect, it } from 'vitest'
import { calendarHistoryText, savedAtText, workerHistoryText } from './versionHistoryModel'

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
})
