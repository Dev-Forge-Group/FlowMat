import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/** The owner sets pay premiums and sees a worker's attendance as pay with them (docs/architecture/adr/ADR-028-pay-premiums.md), mocked. */
test.use({ timezoneId: 'UTC' })

const projectId = 'prj-premiums'
const shift = { shiftId: 'shift-1', shiftStart: '08:00', shiftEnd: '17:00', workDays: [1, 2, 3, 4, 5], hours: 9, shiftHours: 9, rotaWeek: 1 }

test('the owner sets premiums and reads a worker\'s pay with them', async ({ page }) => {
  const saved: unknown[] = []
  const asked: string[] = []
  let premiums: Record<string, unknown> | null = null
  const rule = (body: Record<string, unknown>) => ({ ruleId: 'rule-1', effectiveFrom: '2041-09-03', overtimeAfterMinutes: null,
    overtimePercent: null, nightStart: null, nightEnd: null, nightPercent: null, holidayPercent: null, reason: null, setBy: 'demo-owner',
    setAt: '2041-09-03T00:00:00Z', state: 'current', removedBy: null, removedAt: null, ...body })
  const day = (workDate: string, extra = {}) => ({ workDate, attendedMinutes: 600, overtimeMinutes: 120, nightMinutes: 0, holiday: false,
    premiumMinutes: 60, paidMinutes: 660, ruleId: 'rule-1', hourlyRate: 60, amount: 660, ...extra })
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const { pathname } = url
    if (await answerAuth(route, pathname)) return
    if (pathname === `/api/projects/${projectId}/labor-premiums`) {
      if (request.method() === 'PUT') {
        const body = request.postDataJSON() as Record<string, unknown>
        saved.push(body)
        premiums = rule(body)
      }
      return ok(route, { projectId, today: '2041-09-03', current: premiums, lines: premiums ? [premiums] : [] })
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === `/api/projects/${projectId}/workers/kim/attendance-pay`) {
      const from = url.searchParams.get('from')
      if (from) asked.push(`${from}..${url.searchParams.get('to')}`)
      const days = from ? [day('2041-08-29', { overtimeMinutes: 0, premiumMinutes: 0, paidMinutes: 600, amount: 600 })]
        : [day('2041-09-01'), day('2041-09-02', { overtimeMinutes: 0, nightMinutes: 240, premiumMinutes: 120, paidMinutes: 720, amount: 720 }),
          day('2041-09-03', { holiday: true, premiumMinutes: 660, paidMinutes: 1260, amount: null })]
      return ok(route, { projectId, userId: 'kim', from: from ?? '2041-08-28', to: url.searchParams.get('to') ?? '2041-09-03', days,
        attendedMinutes: 1800, premiumMinutes: 840, paidMinutes: 2640, amount: 1380, costComplete: from !== null })
    }
    if (pathname === `/api/projects/${projectId}/workers/kim/labor-rates`) return ok(route, { projectId, userId: 'kim', today: '2041-09-03',
      current: { rateId: 'rate-1', hourlyRate: 60, effectiveFrom: null, reason: null, setBy: 'demo-owner', setAt: '2041-09-01T00:00:00Z',
        state: 'current', removedBy: null, removedAt: null }, lines: [] })
    if (pathname === `/api/projects/${projectId}/workers`) return ok(route, [
      { projectId, userId: 'demo-owner', owner: true, calendarSet: false, weeklyHours: 0, shifts: [], daysOff: [], days: [] },
      { projectId, userId: 'kim', owner: false, calendarSet: true, weeklyHours: 45, daysOff: [], days: [], rota: null,
        shifts: [shift], schedules: [{ scheduleId: 'version-1', effectiveFrom: null, current: true }] },
    ])
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    if (pathname === '/api/project-members') return ok(route, [
      { projectMemberId: 'm1', projectId, userId: 'demo-owner', projectRole: 'owner', memberStatus: 'active' },
    ])
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/settings`)

  // Half a premium is caught before sending; a whole set is saved in minutes.
  const panel = page.getByRole('region', { name: 'Pay premiums' })
  await expect(panel.getByLabel('Current pay premiums')).toHaveText('No premiums: attendance is paid at the hourly rate.')
  const form = panel.getByRole('form', { name: 'Set pay premiums' })
  await form.getByLabel('Overtime after hours').fill('8')
  await form.getByRole('button', { name: 'Save premiums' }).click()
  await expect(panel.getByRole('alert')).toHaveText('Overtime needs both the hours after which it starts and its percent.')
  expect(saved).toEqual([])
  await form.getByLabel('Overtime percent').fill('50')
  await form.getByLabel('Night start').fill('22:00')
  await form.getByLabel('Night end').fill('06:00')
  await form.getByLabel('Night percent').fill('50')
  await form.getByLabel('Holiday percent').fill('100')
  await form.getByLabel('Premiums reason').fill('Labor law')
  await form.getByRole('button', { name: 'Save premiums' }).click()
  await expect(panel.getByLabel('Current pay premiums')).toHaveText('From 2041-09-03 (current): overtime after 8 h +50% · night 22:00–06:00'
    + ' +50% · holidays +100% · Labor law · set by demo-owner')
  expect(saved).toEqual([{ overtimeAfterMinutes: 480, overtimePercent: 50, nightStart: '22:00', nightEnd: '06:00', nightPercent: 50,
    holidayPercent: 100, reason: 'Labor law' }])
  await expect(form.getByLabel('Overtime after hours')).toHaveValue('')

  // The worker's Labor rate card shows the 7 days to today as pay, then a chosen period.
  await page.getByRole('region', { name: 'Workers' }).getByText('kim · 45 h a week').click()
  const worker = page.getByRole('group', { name: 'Worker kim' })
  await worker.locator('summary').filter({ hasText: 'Labor rate' }).click()
  const pay = worker.getByRole('region', { name: 'Pay with premiums' })
  await expect(pay.getByRole('list', { name: 'Pay by day' }).getByRole('listitem')).toHaveText([
    '2041-09-01 · attended 10 h · overtime 2 h · paid 11 h · 660',
    '2041-09-02 · attended 10 h · night 4 h · paid 12 h · 720',
    '2041-09-03 · holiday · attended 10 h · overtime 2 h · paid 21 h · no rate',
  ])
  await expect(pay.getByLabel('Total pay')).toHaveText('Paid 44 h for 30 h attended · 1,380 known (some days have no rate)')
  await expect(pay.getByLabel('Pay from')).toHaveValue('2041-08-28')
  await pay.getByLabel('Pay from').fill('2041-09-05')
  await pay.getByRole('button', { name: 'Show pay' }).click()
  await expect(pay.getByRole('alert')).toHaveText('The first day comes after the last.')
  expect(asked).toEqual([])
  await pay.getByLabel('Pay from').fill('2041-08-29')
  await pay.getByLabel('Pay to').fill('2041-08-29')
  await pay.getByRole('button', { name: 'Show pay' }).click()
  await expect(pay.getByRole('list', { name: 'Pay by day' }).getByRole('listitem')).toHaveText(['2041-08-29 · attended 10 h · paid 10 h · 600'])
  expect(asked).toEqual(['2041-08-29..2041-08-29'])

  // Weekly overtime goes with its hours a week, its percent and the day the week starts on.
  await form.getByLabel('Weekly hours before overtime').fill('40')
  await form.getByLabel('Percent past the weekly hours').fill('50')
  await form.getByLabel('Week starts on').selectOption('7')
  await form.getByRole('button', { name: 'Save premiums' }).click()
  await expect(panel.getByLabel('Current pay premiums')).toHaveText('From 2041-09-03 (current): over 40 h a week from Sunday +50% · set by demo-owner')
  expect(saved[1]).toEqual({ weeklyOvertimeAfterMinutes: 2400, weeklyOvertimePercent: 50, weekStartsOn: 7 })

  // Rest days go with the holiday percent: alone they are caught before sending.
  await form.getByLabel('Rest days as holidays').check()
  await form.getByRole('button', { name: 'Save premiums' }).click()
  await expect(panel.getByRole('alert')).toHaveText('Rest days count as holidays only with a holiday percent.')
  await form.getByLabel('Holiday percent').fill('100')
  await form.getByRole('button', { name: 'Save premiums' }).click()
  await expect(panel.getByLabel('Current pay premiums')).toHaveText('From 2041-09-03 (current): holidays and rest days +100% · set by demo-owner')
  expect(saved[2]).toEqual({ holidayPercent: 100, restDaysAsHolidays: true })

  // Holiday overtime goes with the holiday percent, in minutes.
  await form.getByLabel('Holiday percent').fill('50')
  await form.getByLabel('Holiday hours before more').fill('8')
  await form.getByLabel('Percent past the holiday hours').fill('100')
  await form.getByRole('button', { name: 'Save premiums' }).click()
  await expect(panel.getByLabel('Current pay premiums')).toHaveText('From 2041-09-03 (current): holidays +50%, past 8 h +100% · set by demo-owner')
  expect(saved[3]).toEqual({ holidayPercent: 50, holidayOvertimeAfterMinutes: 480, holidayOvertimePercent: 100 })
})
