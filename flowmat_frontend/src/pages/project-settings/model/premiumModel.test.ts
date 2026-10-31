import { describe, expect, it } from 'vitest'
import type { AttendancePayDayDto, LaborPremiumLineDto } from '../../../entities/workforce/api/useLaborPremiums'
import { EMPTY_PREMIUM_FORM, payDayText, payTotalText, premiumPayload, premiumRuleText } from './premiumModel'

const rule = (extra: Partial<LaborPremiumLineDto> = {}): LaborPremiumLineDto => ({ ruleId: 'r1', effectiveFrom: '2041-09-01',
  overtimeAfterMinutes: 480, overtimePercent: 50, nightStart: '22:00', nightEnd: '06:00', nightPercent: 50, holidayPercent: 100,
  weeklyOvertimeAfterMinutes: null, weeklyOvertimePercent: null, weekStartsOn: null, restDaysAsHolidays: false,
  holidayOvertimeAfterMinutes: null, holidayOvertimePercent: null, reason: 'Labor law', setBy: 'demo-owner',
  setAt: '2041-09-01T00:00:00Z', state: 'current', removedBy: null, removedAt: null, ...extra })

const day = (extra: Partial<AttendancePayDayDto> = {}): AttendancePayDayDto => ({ workDate: '2041-09-01', attendedMinutes: 600,
  overtimeMinutes: 120, nightMinutes: 0, holiday: false, premiumMinutes: 60, paidMinutes: 660, ruleId: 'r1', hourlyRate: 60, amount: 660,
  ...extra })

describe('premiumRuleText', () => {
  it('says each premium of a save', () => {
    expect(premiumRuleText(rule())).toBe('From 2041-09-01 (current): overtime after 8 h +50% · night 22:00–06:00 +50% · holidays +100%'
      + ' · Labor law · set by demo-owner')
  })

  it('says when a save has none, and who removed one', () => {
    expect(premiumRuleText(rule({ overtimeAfterMinutes: null, overtimePercent: null, nightStart: null, nightEnd: null, nightPercent: null,
      holidayPercent: null, reason: null, state: 'removed', removedBy: 'demo-owner' })))
      .toBe('From 2041-09-01 (removed by demo-owner): no premiums · set by demo-owner')
  })
})

describe('payDayText and payTotalText', () => {
  it('names only the premiums of the day', () => {
    expect(payDayText(day())).toBe('2041-09-01 · attended 10 h · overtime 2 h · paid 11 h · 660')
    expect(payDayText(day({ workDate: '2041-09-03', holiday: true, paidMinutes: 1260, amount: 1260 })))
      .toBe('2041-09-03 · holiday · attended 10 h · overtime 2 h · paid 21 h · 1,260')
    expect(payDayText(day({ attendedMinutes: 61, overtimeMinutes: 0, nightMinutes: 61, paidMinutes: 91.5, amount: null })))
      .toBe('2041-09-01 · attended 1 h 1 min · night 1 h 1 min · paid 91.5 min · no rate')
  })

  it('adds the days up, saying when some have no rate', () => {
    const base = { projectId: 'p', userId: 'kim', from: '2041-09-01', to: '2041-09-07', attendedMinutes: 1560, premiumMinutes: 840 }
    expect(payTotalText({ ...base, days: [day()], paidMinutes: 2400, amount: 2400, costComplete: true }))
      .toBe('Paid 40 h for 26 h attended · 2,400')
    expect(payTotalText({ ...base, days: [day()], paidMinutes: 2400, amount: 1000, costComplete: false }))
      .toBe('Paid 40 h for 26 h attended · 1,000 known (some days have no rate)')
    expect(payTotalText({ ...base, days: [], attendedMinutes: 0, paidMinutes: 0, amount: 0, costComplete: true }))
      .toBe('No attendance in these days.')
  })
})

describe('premiumPayload', () => {
  it('sends the premiums given, in minutes', () => {
    expect(premiumPayload({ ...EMPTY_PREMIUM_FORM, overtimeAfterHours: '8', overtimePercent: '50', nightStart: '22:00', nightEnd: '06:00',
      nightPercent: '50', holidayPercent: '100', effectiveFrom: '2041-09-08', reason: ' Labor law ' })).toEqual({ problem: null, payload: {
      overtimeAfterMinutes: 480, overtimePercent: 50, nightStart: '22:00', nightEnd: '06:00', nightPercent: 50, holidayPercent: 100,
      effectiveFrom: '2041-09-08', reason: 'Labor law' } })
    expect(premiumPayload({ ...EMPTY_PREMIUM_FORM, overtimeAfterHours: '7.5', overtimePercent: '25' }).payload)
      .toEqual({ overtimeAfterMinutes: 450, overtimePercent: 25 })
  })

  it('sends nothing to stop the premiums', () => {
    expect(premiumPayload(EMPTY_PREMIUM_FORM)).toEqual({ payload: {}, problem: null })
  })

  it('says what is missing or wrong', () => {
    expect(premiumPayload({ ...EMPTY_PREMIUM_FORM, overtimeAfterHours: '8' }).problem)
      .toBe('Overtime needs both the hours after which it starts and its percent.')
    expect(premiumPayload({ ...EMPTY_PREMIUM_FORM, overtimeAfterHours: '25', overtimePercent: '50' }).problem)
      .toBe('Overtime starts after up to 24 hours of a work day, in whole minutes.')
    expect(premiumPayload({ ...EMPTY_PREMIUM_FORM, holidayPercent: '2.5' }).problem).toBe('Holiday is a whole percent from 1 to 500.')
    expect(premiumPayload({ ...EMPTY_PREMIUM_FORM, nightStart: '22:00', nightPercent: '50' }).problem)
      .toBe('Night needs a start, an end and a percent.')
    expect(premiumPayload({ ...EMPTY_PREMIUM_FORM, nightStart: '22:00', nightEnd: '22:00', nightPercent: '50' }).problem)
      .toBe('Night must start and end at different times.')
  })
})

describe('weekly overtime', () => {
  it('says the hours a week, their percent and the day the week starts on', () => {
    expect(premiumRuleText(rule({ weeklyOvertimeAfterMinutes: 2400, weeklyOvertimePercent: 50, weekStartsOn: 7, nightStart: null,
      nightEnd: null, nightPercent: null, holidayPercent: null })))
      .toBe('From 2041-09-01 (current): overtime after 8 h +50% · over 40 h a week from Sunday +50% · Labor law · set by demo-owner')
    expect(payDayText(day({ attendedMinutes: 240, overtimeMinutes: 0, weeklyOvertimeMinutes: 240, paidMinutes: 360, amount: 360 })))
      .toBe('2041-09-01 · attended 4 h · weekly overtime 4 h · paid 6 h · 360')
  })

  it('sends the hours a week in minutes with the day the week starts on, or says what is wrong', () => {
    expect(premiumPayload({ ...EMPTY_PREMIUM_FORM, weeklyOvertimeAfterHours: '40', weeklyOvertimePercent: '50', weekStartsOn: '7' }))
      .toEqual({ problem: null, payload: { weeklyOvertimeAfterMinutes: 2400, weeklyOvertimePercent: 50, weekStartsOn: 7 } })
    expect(premiumPayload({ ...EMPTY_PREMIUM_FORM, weeklyOvertimeAfterHours: '40' }).problem)
      .toBe('Weekly overtime needs both the hours a week after which it starts and its percent.')
    expect(premiumPayload({ ...EMPTY_PREMIUM_FORM, weeklyOvertimeAfterHours: '169', weeklyOvertimePercent: '50' }).problem)
      .toBe('Weekly overtime starts after up to 168 hours a week, in whole minutes.')
    expect(premiumPayload({ ...EMPTY_PREMIUM_FORM, weeklyOvertimeAfterHours: '40', weeklyOvertimePercent: '0' }).problem)
      .toBe('Weekly overtime is a whole percent from 1 to 500.')
  })
})

describe('rest days', () => {
  it('says rest days go with the holiday percent, and marks a rest day paid like one', () => {
    expect(premiumRuleText(rule({ overtimeAfterMinutes: null, overtimePercent: null, nightStart: null, nightEnd: null, nightPercent: null,
      restDaysAsHolidays: true, reason: null })))
      .toBe('From 2041-09-01 (current): holidays and rest days +100% · set by demo-owner')
    expect(payDayText(day({ workDate: '2041-09-07', attendedMinutes: 240, overtimeMinutes: 0, restDayHoliday: true, paidMinutes: 480,
      amount: 480 }))).toBe('2041-09-07 · rest day · attended 4 h · paid 8 h · 480')
  })

  it('sends rest days only with a holiday percent', () => {
    expect(premiumPayload({ ...EMPTY_PREMIUM_FORM, holidayPercent: '100', restDaysAsHolidays: true }))
      .toEqual({ problem: null, payload: { holidayPercent: 100, restDaysAsHolidays: true } })
    expect(premiumPayload({ ...EMPTY_PREMIUM_FORM, restDaysAsHolidays: true }).problem)
      .toBe('Rest days count as holidays only with a holiday percent.')
  })
})

describe('a worker\'s own premiums', () => {
  it('says when a worker follows the project\'s premiums again', () => {
    expect(premiumRuleText(rule({ overtimeAfterMinutes: null, overtimePercent: null, nightStart: null, nightEnd: null, nightPercent: null,
      holidayPercent: null, followProject: true, reason: null, state: 'upcoming' })))
      .toBe('From 2041-09-01 (upcoming): follows the project\'s premiums · set by demo-owner')
  })
})

describe('holiday overtime', () => {
  it('says the hours of a holiday past which its percent is added, and the minutes of a day', () => {
    expect(premiumRuleText(rule({ overtimeAfterMinutes: null, overtimePercent: null, nightStart: null, nightEnd: null, nightPercent: null,
      holidayPercent: 50, holidayOvertimeAfterMinutes: 480, holidayOvertimePercent: 100, reason: null })))
      .toBe('From 2041-09-01 (current): holidays +50%, past 8 h +100% · set by demo-owner')
    expect(payDayText(day({ holiday: true, overtimeMinutes: 0, holidayOvertimeMinutes: 120, paidMinutes: 1020, amount: 1020 })))
      .toBe('2041-09-01 · holiday · attended 10 h · holiday overtime 2 h · paid 17 h · 1,020')
  })

  it('sends the hours in minutes with a holiday percent, or says what is wrong', () => {
    expect(premiumPayload({ ...EMPTY_PREMIUM_FORM, holidayPercent: '50', holidayOvertimeAfterHours: '8', holidayOvertimePercent: '100' }))
      .toEqual({ problem: null, payload: { holidayPercent: 50, holidayOvertimeAfterMinutes: 480, holidayOvertimePercent: 100 } })
    expect(premiumPayload({ ...EMPTY_PREMIUM_FORM, holidayOvertimeAfterHours: '8', holidayOvertimePercent: '100' }).problem)
      .toBe('Holiday overtime goes with a holiday percent.')
    expect(premiumPayload({ ...EMPTY_PREMIUM_FORM, holidayPercent: '50', holidayOvertimeAfterHours: '8' }).problem)
      .toBe('Holiday overtime needs both the hours after which it starts and its percent.')
    expect(premiumPayload({ ...EMPTY_PREMIUM_FORM, holidayPercent: '50', holidayOvertimeAfterHours: '25', holidayOvertimePercent: '100' })
      .problem).toBe('Holiday overtime starts after up to 24 hours of a holiday, in whole minutes.')
  })
})
