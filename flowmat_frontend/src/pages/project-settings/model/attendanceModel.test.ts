import { describe, expect, it } from 'vitest'
import { attendanceCsv, attendanceLineText, comparisonDayText, exportRange, minutesText } from './attendanceModel'

describe('attendance', () => {
  it('says minutes as hours and minutes', () => {
    expect(minutesText(450)).toBe('7 h 30 min')
    expect(minutesText(45)).toBe('45 min')
    expect(minutesText(120)).toBe('2 h')
    expect(minutesText(0)).toBe('0 min')
    expect(minutesText(-15)).toBe('-15 min')
  })

  it('compares a day of attendance with the time recorded on runs', () => {
    expect(comparisonDayText({ date: '2041-09-02', attendedMinutes: 480, laborMinutes: 390, differenceMinutes: 90 }))
      .toBe('2041-09-02 · attended 8 h · on runs 6 h 30 min · 1 h 30 min not on runs')
    expect(comparisonDayText({ date: '2041-09-03', attendedMinutes: 60, laborMinutes: 75, differenceMinutes: -15 }))
      .toBe('2041-09-03 · attended 1 h · on runs 1 h 15 min · 15 min more on runs than attended')
    expect(comparisonDayText({ date: '2041-09-04', attendedMinutes: 60, laborMinutes: 60, differenceMinutes: 0 }))
      .toBe('2041-09-04 · attended 1 h · on runs 1 h · matches')
  })

  it('says what a stretch was', () => {
    const line = { attendanceId: 'a1', workDate: '2041-09-02', clockIn: '2041-09-02T08:00:00', clockOut: '2041-09-02T16:30:00',
      minutes: 510, note: 'Day', recordedBy: 'kim', recordedAt: '2041-09-02T16:31:00', cancelled: false, cancelledBy: null,
      cancelledAt: null, cancelReason: null }
    expect(attendanceLineText(line)).toBe('2041-09-02 08:00 – 16:30 · 8 h 30 min · Day · by kim')
    expect(attendanceLineText({ ...line, clockOut: null, minutes: null, note: null }))
      .toBe('2041-09-02 08:00 – still at work · by kim')
    expect(attendanceLineText({ ...line, cancelled: true, cancelledBy: 'demo-owner', cancelReason: 'Wrong day' }))
      .toBe('2041-09-02 08:00 – 16:30 · 8 h 30 min · Day · by kim · cancelled by demo-owner: Wrong day')
  })

  it('downloads a period as CSV, oldest first, cancelled stretches marked', () => {
    const stretch = (attendanceId: string, workDate: string, clockIn: string, clockOut: string | null, extra = {}) => ({ attendanceId, workDate,
      clockIn, clockOut, minutes: clockOut ? 480 : null, note: null, recordedBy: 'kim', recordedAt: clockIn, cancelled: false, cancelledBy: null,
      cancelledAt: null, cancelReason: null, ...extra })
    const csv = attendanceCsv([
      stretch('a2', '2041-09-03', '2041-09-03T08:00:00', null),
      stretch('a1', '2041-09-02', '2041-09-02T08:00:00', '2041-09-02T16:00:00', { note: 'Night, "late"', cancelled: true, cancelledBy: 'boss',
        cancelReason: '=wrong' }),
    ])
    expect(csv.split('\r\n')).toEqual([
      '\ufeffwork_date,clock_in,clock_out,minutes,note,recorded_by,cancelled,cancelled_by,cancel_reason',
      '2041-09-02,2041-09-02 08:00,2041-09-02 16:00,480,"Night, ""late""",kim,Y,boss,\'=wrong',
      '2041-09-03,2041-09-03 08:00,,,,kim,N,,',
      '',
    ])
    expect(exportRange('2041-09-01', '2041-09-30')).toEqual({ from: '2041-09-01', to: '2041-09-30', error: null })
    expect(exportRange('', '2041-09-30').error).toBe('Choose the first and the last day.')
    expect(exportRange('2041-09-05', '2041-09-01').error).toBe('The first day comes after the last.')
  })
})
