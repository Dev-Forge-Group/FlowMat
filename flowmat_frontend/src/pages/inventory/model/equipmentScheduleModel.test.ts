import { describe, expect, it } from 'vitest'
import type { EquipmentDayDto, EquipmentDowntimeDto, EquipmentScheduleDto, EquipmentShiftDto } from '../../../entities/catalog/api/useEquipment'
import type { EquipmentDto } from '../../../shared/types/api'
import { dateInputValue } from './equipmentLoadModel'
import {
  DEFAULT_CALENDAR_FORM,
  EMPTY_DAY_FORM,
  MAX_SHIFTS,
  availabilitySummary,
  calendarForm,
  calendarPayload,
  dayLabel,
  dayList,
  copyableDays,
  dayCount,
  dayPayload,
  rangeDates,
  daySummary,
  downtimePayload,
  equipmentChoices,
  equipmentLabel,
  nextDayShift,
  nextShift,
  nextWeek,
  shiftSummary,
  splitDowntimes,
  projectToday,
  startsOnProblem,
  toggleDay,
  versionForm,
  versionText,
  versionUpcoming,
} from './equipmentScheduleModel'

function shift(shiftStart: string, shiftEnd: string, workDays: number[], shiftHours: number): EquipmentShiftDto {
  return { shiftId: `s-${shiftStart}`, shiftStart, shiftEnd, workDays, shiftHours }
}

function calendar(shiftStart: string, shiftEnd: string, workDays: number[], shiftHours: number): EquipmentScheduleDto['calendar'] {
  return { shifts: [shift(shiftStart, shiftEnd, workDays, shiftHours)], weeklyHours: shiftHours * workDays.length, updatedBy: 'u', updatedAt: null }
}

function downtime(downtimeId: string, startsAt: string, endsAt: string): EquipmentDowntimeDto {
  return { downtimeId, downtimeType: 'maintenance', startsAt, endsAt, hours: 1, reason: null, createdBy: 'u', createdAt: null }
}

function equipment(equipmentId: string, equipmentStatus: string, equipmentCode: string | null = equipmentId.toUpperCase()): EquipmentDto {
  return {
    equipmentId, projectId: 'p', equipmentCode, equipmentName: `${equipmentId} name`, equipmentType: 'machine', equipmentStatus,
    details: { manufacturer: null, modelName: null, serialNo: null, capacityPerHour: null, powerKwh: null, waterLiter: null, location: null },
  }
}

describe('dayList and shiftSummary', () => {
  it('names runs of three or more days as a range', () => {
    expect(dayList([5, 1, 2, 3, 4])).toBe('Mon–Fri')
    expect(dayList([1, 3, 5])).toBe('Mon, Wed, Fri')
    expect(dayList([1, 2, 6, 7])).toBe('Mon, Tue, Sat, Sun')
    expect(dayList([1, 2, 3, 6])).toBe('Mon–Wed, Sat')
    expect(dayList([1, 2, 3, 4, 5, 6, 7])).toBe('Every day')
  })

  it('says when a shift runs past midnight or all day', () => {
    expect(shiftSummary(null)).toBe('No calendar: available around the clock.')
    expect(shiftSummary(calendar('09:00', '17:00', [1, 2, 3, 4, 5], 8))).toBe('09:00–17:00 · Mon–Fri · 8 h a shift')
    expect(shiftSummary(calendar('22:00', '06:00', [1], 8))).toBe('22:00–06:00 (ends next day) · Mon · 8 h a shift')
    expect(shiftSummary(calendar('00:00', '00:00', [6, 7], 24))).toBe('All day · Sat, Sun · 24 h a shift')
  })

  it('joins several shifts and ends with the week total', () => {
    const twoShifts = { shifts: [shift('06:00', '14:00', [1, 2, 3, 4, 5], 8), shift('14:00', '22:00', [1, 2, 3, 4, 5], 8)],
      weeklyHours: 80, updatedBy: 'u', updatedAt: null }
    expect(shiftSummary(twoShifts))
      .toBe('06:00–14:00 · Mon–Fri · 8 h a shift; 14:00–22:00 · Mon–Fri · 8 h a shift · 80 h a week')
    expect(shiftSummary({ ...twoShifts, shifts: [] })).toBe('No calendar: available around the clock.')
  })
})

describe('calendar form', () => {
  it('starts from the saved calendar or a weekday shift, and checks what is sent', () => {
    const schedule = { equipmentId: 'e', timeZone: 'Asia/Seoul', calendar: calendar('08:30:00', '17:30', [1, 3], 9), downtimes: [], days: [] }
    expect(calendarForm(schedule)).toEqual({ shifts: [{ shiftStart: '08:30', shiftEnd: '17:30', workDays: [1, 3] }] })
    expect(calendarForm(undefined)).toEqual(DEFAULT_CALENDAR_FORM)
    expect(toggleDay([1, 3], 2)).toEqual([1, 2, 3])
    expect(toggleDay([1, 2, 3], 2)).toEqual([1, 3])
    expect(calendarPayload({ shifts: [{ shiftStart: '08:00', shiftEnd: '17:00', workDays: [3, 1, 3] }] }))
      .toEqual({ input: { shifts: [{ shiftStart: '08:00', shiftEnd: '17:00', workDays: [1, 3] }] }, error: null })
    expect(calendarPayload({ shifts: [{ shiftStart: '', shiftEnd: '17:00', workDays: [1] }] }).error)
      .toBe('Enter when the shift starts and ends.')
    expect(calendarPayload({ shifts: [{ shiftStart: '08:00', shiftEnd: '17:00', workDays: [] }] }).error)
      .toBe('Choose at least one work day.')
  })

  it('adds the next shift after the last one and numbers problems when there are several', () => {
    const day = { shiftStart: '06:00', shiftEnd: '14:00', workDays: [1, 2, 3, 4, 5] }
    expect(nextShift({ shifts: [day] })).toEqual({ shiftStart: '14:00', shiftEnd: '22:00', workDays: [1, 2, 3, 4, 5] })
    expect(nextShift({ shifts: [{ ...day, shiftEnd: '22:00' }] })).toEqual({ shiftStart: '22:00', shiftEnd: '06:00', workDays: [1, 2, 3, 4, 5] })
    expect(calendarPayload({ shifts: [day, { shiftStart: '14:00', shiftEnd: '22:00', workDays: [] }] }).error)
      .toBe('Shift 2: Choose at least one work day.')
    expect(calendarPayload({ shifts: [] }).error).toBe('Add at least one shift, or remove the calendar.')
    expect(calendarPayload({ shifts: Array.from({ length: MAX_SHIFTS + 1 }, () => day) }).error)
      .toBe(`A calendar can have at most ${MAX_SHIFTS} shifts.`)
  })
})

describe('a day of its own', () => {
  it('sends a closed day or its shifts, and checks the date and times', () => {
    expect(dayPayload({ ...EMPTY_DAY_FORM, date: '2030-01-09', closed: true, note: ' Stock take ' }))
      .toEqual({ date: '2030-01-09', input: { shifts: [], reason: 'Stock take' }, error: null })
    expect(dayPayload({ ...EMPTY_DAY_FORM, date: '2030-01-08', shifts: [{ shiftStart: '06:00', shiftEnd: '14:00' }] }))
      .toEqual({ date: '2030-01-08', input: { shifts: [{ shiftStart: '06:00', shiftEnd: '14:00' }], reason: null }, error: null })
    expect(dayPayload(EMPTY_DAY_FORM).error).toBe('Choose the day.')
    expect(dayPayload({ ...EMPTY_DAY_FORM, date: '2030-01-08', shifts: [] }).error).toBe('Add a shift, or mark the day closed.')
    expect(dayPayload({ ...EMPTY_DAY_FORM, date: '2030-01-08', shifts: [{ shiftStart: '06:00', shiftEnd: '14:00' }, { shiftStart: '', shiftEnd: '18:00' }] }).error)
      .toBe('Shift 2: Enter when the shift starts and ends.')
    expect(nextDayShift({ ...EMPTY_DAY_FORM, shifts: [{ shiftStart: '06:00', shiftEnd: '14:00' }] })).toEqual({ shiftStart: '14:00', shiftEnd: '18:00' })
  })

  it('changes a range of days the same way, and counts the days a copy takes along', () => {
    expect(dayPayload({ ...EMPTY_DAY_FORM, date: '2030-01-14', through: '2030-01-16', closed: true, note: 'Line move' }))
      .toEqual({ date: '2030-01-14', input: { shifts: [], reason: 'Line move', throughDate: '2030-01-16' }, error: null })
    expect(dayPayload({ ...EMPTY_DAY_FORM, date: '2030-01-14', through: '2030-01-14', closed: true }).input)
      .toEqual({ shifts: [], reason: null })
    expect(dayPayload({ ...EMPTY_DAY_FORM, date: '2030-01-14', through: '2030-01-13', closed: true }).error)
      .toBe('The last day must not be before the first.')
    // 2030-01-01 through 2030-03-03 is 62 days; one more is too many.
    expect(dayPayload({ ...EMPTY_DAY_FORM, date: '2030-01-01', through: '2030-03-03', closed: true }).error).toBeNull()
    expect(dayPayload({ ...EMPTY_DAY_FORM, date: '2030-01-01', through: '2030-03-04', closed: true }).error)
      .toBe('A range can cover at most 62 days.')
    expect(dayCount({ ...EMPTY_DAY_FORM, date: '2030-01-14', through: '2030-01-16' })).toBe(3)
    expect(dayCount({ ...EMPTY_DAY_FORM, date: '2030-01-14' })).toBe(1)
    expect(dayCount({ ...EMPTY_DAY_FORM, date: '2030-01-14', through: '2030-01-10' })).toBe(1)
    expect(copyableDays([{ date: '2029-12-31' }, { date: '2030-01-01' }, { date: '2030-01-09' }], '2030-01-01')).toBe(2)

    // Only some days of the week in a range: 2030-01-14 is a Monday.
    const weekly = { ...EMPTY_DAY_FORM, date: '2030-01-14', through: '2030-01-27', closed: true, weekDays: [5, 1, 3] }
    expect(dayCount(weekly)).toBe(6)
    expect(rangeDates(weekly).slice(0, 3)).toEqual(['2030-01-14', '2030-01-16', '2030-01-18'])
    expect(dayPayload(weekly).input).toEqual({ shifts: [], reason: null, throughDate: '2030-01-27', weekDays: [1, 3, 5] })
    expect(dayPayload({ ...weekly, weekDays: [1, 2, 3, 4, 5, 6, 7] }).input).toEqual({ shifts: [], reason: null, throughDate: '2030-01-27' })
    expect(dayPayload({ ...weekly, weekDays: [] }).error).toBe('Choose at least one day of the week.')
    expect(dayPayload({ ...weekly, through: '2030-01-15', weekDays: [3] }).error).toBe('None of the dates fall on the chosen days of the week.')
  })

  it('names the weekday and sums up the day', () => {
    expect(dayLabel('2030-01-09')).toBe('Wed 2030-01-09')
    expect(dayLabel('2030-01-13')).toBe('Sun 2030-01-13')
    const day = (patch: Partial<EquipmentDayDto>): EquipmentDayDto => ({
      date: '2030-01-08', shifts: [], closed: true, hours: 0, reason: null, updatedBy: 'u', updatedAt: null, ...patch,
    })
    expect(daySummary(day({}))).toBe('Closed')
    expect(daySummary(day({ closed: false, hours: 12, shifts: [
      { shiftStart: '06:00', shiftEnd: '14:00', shiftHours: 8 }, { shiftStart: '22:00', shiftEnd: '02:00', shiftHours: 4 },
    ] }))).toBe('06:00–14:00, 22:00–02:00 (ends next day) · 12 h')
  })
})

describe('downtimePayload', () => {
  it('sends instants and refuses an end that is not after the start', () => {
    const payload = downtimePayload({ downtimeType: 'breakdown', startsAt: '2030-01-08T12:00', endsAt: '2030-01-08T20:00', reason: ' belt ' })
    expect(payload).toEqual({
      input: {
        downtimeType: 'breakdown',
        startsAt: new Date('2030-01-08T12:00').toISOString(),
        endsAt: new Date('2030-01-08T20:00').toISOString(),
        reason: 'belt',
      },
      error: null,
    })
    expect(downtimePayload({ downtimeType: 'other', startsAt: '2030-01-08T12:00', endsAt: '2030-01-08T12:00', reason: '' }).error)
      .toBe('The downtime must end after it starts.')
    expect(downtimePayload({ downtimeType: 'other', startsAt: '', endsAt: '2030-01-08T12:00', reason: '' }).error)
      .toBe('Enter when the downtime starts and ends.')
  })
})

describe('availability', () => {
  it('covers seven days from local midnight and sums up the hours', () => {
    const week = nextWeek(new Date(2030, 0, 7, 15, 30))
    expect(new Date(week.from).getHours()).toBe(0)
    expect(new Date(week.from).getDate()).toBe(7)
    expect(new Date(week.to).getDate()).toBe(14)
    expect(availabilitySummary({
      equipmentId: 'e', from: week.from, to: week.to, calendarSet: true, workingHours: 40, downtimeHours: 5, availableHours: 35,
      capacityPerHour: 10, capacity: 350,
    })).toBe('Next 7 days: 35 h available (5 h down) · can make 350.')
    expect(availabilitySummary({
      equipmentId: 'e', from: week.from, to: week.to, calendarSet: true, workingHours: 32, downtimeHours: 0, availableHours: 32,
      capacityPerHour: null, capacity: null, holidays: ['2032-03-03'],
    })).toBe('Next 7 days: 32 h available · 1 holiday off.')
    expect(availabilitySummary({
      equipmentId: 'e', from: week.from, to: week.to, calendarSet: false, workingHours: 168, downtimeHours: 0, availableHours: 168,
      capacityPerHour: null, capacity: null,
    })).toBe('Next 7 days: 168 h available.')
  })

  it('splits downtime into what is still ahead and what is over', () => {
    const now = new Date('2030-01-08T00:00:00Z')
    const split = splitDowntimes([
      downtime('later', '2030-01-10T00:00:00Z', '2030-01-11T00:00:00Z'),
      downtime('now', '2030-01-07T00:00:00Z', '2030-01-09T00:00:00Z'),
      downtime('over', '2030-01-01T00:00:00Z', '2030-01-02T00:00:00Z'),
    ], now)
    expect(split.current.map((one) => one.downtimeId)).toEqual(['now', 'later'])
    expect(split.past.map((one) => one.downtimeId)).toEqual(['over'])
  })
})

describe('equipment for a work order', () => {
  it('leaves out inactive equipment unless the order is on it', () => {
    const list = [equipment('mix', 'active'), equipment('old', 'inactive'), equipment('oven', 'maintenance', null)]
    expect(equipmentChoices(list, null).map((one) => one.equipmentId)).toEqual(['mix', 'oven'])
    expect(equipmentChoices(list, 'old').map((one) => one.equipmentId)).toEqual(['mix', 'old', 'oven'])
    expect(equipmentLabel(list[0])).toBe('MIX · mix name')
    expect(equipmentLabel(list[2])).toBe('oven name (maintenance)')
  })
})

describe('calendar versions', () => {
  const line = (effectiveFrom: string | null, current = false, alwaysRunning = false) =>
    ({ scheduleId: `v-${effectiveFrom}`, effectiveFrom, current, alwaysRunning })

  it('says which version applies now, which is ahead and which is history', () => {
    expect(versionText(line(null, true), '2041-09-01')).toBe('From the start · now')
    expect(versionText(line('2041-09-09'), '2041-09-01')).toBe('From 2041-09-09 · upcoming')
    expect(versionText(line('2041-09-16', false, true), '2041-09-01')).toBe('From 2041-09-16 · around the clock · upcoming')
    expect(versionText(line('2041-08-01'), '2041-09-01')).toBe('From 2041-08-01 · earlier')
    expect(versionUpcoming(line('2041-09-01'), '2041-09-01')).toBe(false)
    expect(versionUpcoming(line(null), '2041-09-01')).toBe(false)
  })

  it('refuses a start before today and loads a version into the form', () => {
    expect(startsOnProblem('', '2041-09-01')).toBeNull()
    expect(startsOnProblem('2041-09-01', '2041-09-01')).toBeNull()
    expect(startsOnProblem('2041-08-31', '2041-09-01')).toBe('Pick today or a later date; change a past date with a day change.')
    expect(versionForm({ ...line('2041-09-09'), shifts: [shift('06:00:00', '14:00:00', [1, 2], 8)], weeklyHours: 16 }))
      .toEqual({ shifts: [{ shiftStart: '06:00', shiftEnd: '14:00', workDays: [1, 2] }] })
    expect(versionForm({ ...line('2041-09-16', false, true), shifts: [], weeklyHours: 0 })).toEqual(DEFAULT_CALENDAR_FORM)
  })

  it('counts today in the project zone, not the browser', () => {
    // 05:00 on the 9th in Seoul is still the 8th in New York.
    const now = new Date('2041-09-08T20:00:00Z')
    expect(projectToday('Asia/Seoul', now)).toBe('2041-09-09')
    expect(projectToday('America/New_York', now)).toBe('2041-09-08')
    expect(projectToday('Not/A_Zone', now)).toBe(dateInputValue(now))
    expect(projectToday(undefined, now)).toBe(dateInputValue(now))
  })
})
