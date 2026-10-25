import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

// The history lines show times in the browser's zone (versionHistoryModel savedAtText).
test.use({ timezoneId: 'UTC' })

const projectId = 'prj-day-history'
const shift = { shiftId: 'shift-1', shiftStart: '07:00', shiftEnd: '16:00', workDays: [1, 2, 3, 4, 5], hours: 9, shiftHours: 9, rotaWeek: 1 }
const overtime = { shifts: [{ shiftStart: '06:00', shiftEnd: '14:00' }], reason: 'Overtime' }

async function mockApi(page: Page, reads: string[]) {
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === `/api/projects/${projectId}/workers/kim/day-history`) {
      reads.push(pathname)
      return ok(route, [
        { historyId: 'h2', kind: 'day_off', action: 'saved', firstDate: '2041-10-01', lastDate: '2041-10-03', days: [],
          dayOffId: 'off-1', reason: 'Holiday', copiedFrom: null, savedBy: 'demo-owner', savedAt: '2041-09-01T10:00:00Z' },
        { historyId: 'h1', kind: 'day', action: 'saved', firstDate: '2041-09-02', lastDate: '2041-09-04',
          days: [{ date: '2041-09-02', ...overtime }, { date: '2041-09-04', ...overtime }], dayOffId: null, reason: 'Overtime',
          copiedFrom: null, savedBy: 'kim', savedAt: '2041-09-01T09:00:00Z' },
      ])
    }
    if (pathname === '/api/equipments/press/day-history') {
      reads.push(pathname)
      return ok(route, [
        { historyId: 'e2', kind: 'downtime', action: 'removed', firstDate: null, lastDate: null, days: [], downtimeId: 'down-1',
          downtimeType: 'maintenance', startsAt: '2041-09-05T08:00:00Z', endsAt: '2041-09-05T12:00:00Z', reason: 'Oil change',
          copiedFrom: null, savedBy: 'demo-owner', savedAt: '2041-09-01T11:00:00Z' },
        { historyId: 'e1', kind: 'day', action: 'saved', firstDate: '2041-09-03', lastDate: '2041-09-03',
          days: [{ date: '2041-09-03', shifts: [], reason: 'Closed' }], downtimeId: null, downtimeType: null, startsAt: null,
          endsAt: null, reason: null, copiedFrom: 'drill', savedBy: 'demo-owner', savedAt: '2041-09-01T10:30:00Z' },
      ])
    }
    if (pathname === `/api/projects/${projectId}/workers`) return ok(route, [
      { projectId, userId: 'demo-owner', owner: true, calendarSet: false, weeklyHours: 0, shifts: [], daysOff: [], days: [] },
      { projectId, userId: 'kim', owner: false, calendarSet: true, weeklyHours: 45, daysOff: [], days: [], rota: null,
        shifts: [shift], schedules: [{ scheduleId: 'version-1', effectiveFrom: null, current: true }] },
    ])
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    if (pathname === '/api/project-members') return ok(route, [
      { projectMemberId: 'member-1', projectId, userId: 'demo-owner', projectRole: 'owner', memberStatus: 'active' },
    ])
    if (pathname === '/api/equipments') return ok(route, ['press', 'drill'].map((id) => ({ equipmentId: id, projectId,
      equipmentCode: id.toUpperCase(), equipmentName: id === 'press' ? 'Press' : 'Drill', equipmentType: 'machine',
      equipmentStatus: 'active', details: { capacityPerHour: 10 } })))
    if (pathname === '/api/equipments/press/schedule') return ok(route, { equipmentId: 'press', timeZone: 'UTC',
      calendar: { shifts: [shift], weeklyHours: 45, updatedBy: 'demo-owner', updatedAt: null }, downtimes: [], days: [],
      versions: [{ scheduleId: 'version-1', effectiveFrom: null, current: true, alwaysRunning: false }] })
    if (pathname === '/api/equipments/press/hourly-cost') return ok(route, {
      equipmentId: 'press', hourlyCost: null, version: 0, updatedBy: null, updatedAt: null,
    })
    if (pathname === '/api/equipments/press/availability') return ok(route, {
      equipmentId: 'press', from: '', to: '', calendarSet: true, workingHours: 45, downtimeHours: 0,
      availableHours: 45, capacityPerHour: 10, capacity: 450,
    })
    return ok(route, [])
  })
}

test('a worker\'s date and day-off history loads when opened', async ({ page }) => {
  const reads: string[] = []
  await mockApi(page, reads)
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/settings`)
  await page.getByRole('region', { name: 'Workers' }).getByText('kim · 45 h a week').click()
  const panel = page.getByRole('group', { name: 'Worker kim' })
  await expect(panel.locator('summary').filter({ hasText: 'Date and day-off history' })).toBeVisible()
  expect(reads).toEqual([])
  await panel.locator('summary').filter({ hasText: 'Date and day-off history' }).click()
  await expect(panel.getByRole('list', { name: 'Date and day-off history' }).getByRole('listitem')).toHaveText([
    '2041-09-01 10:00 · demo-owner added days off 2041-10-01–2041-10-03 · Holiday',
    '2041-09-01 09:00 · kim saved 2041-09-02–2041-09-04 (2 dates): 06:00–14:00 · Overtime',
  ])
  expect(reads).toEqual([`/api/projects/${projectId}/workers/kim/day-history`])
})

test('an equipment\'s date and downtime history names the equipment a copy came from', async ({ page }) => {
  const reads: string[] = []
  await mockApi(page, reads)
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=equipment`)
  await page.getByRole('row', { name: /PRESS.*Press/ }).getByRole('button', { name: 'Schedule', exact: true }).click()
  const panel = page.getByRole('region', { name: 'Schedule of PRESS' })
  await panel.locator('summary').filter({ hasText: 'Date and downtime history' }).click()
  await expect(panel.getByRole('list', { name: 'Date and downtime history' }).getByRole('listitem')).toHaveText([
    '2041-09-01 11:00 · demo-owner removed maintenance 2041-09-05 08:00–2041-09-05 12:00 · Oil change',
    '2041-09-01 10:30 · demo-owner saved 2041-09-03, copied from DRILL: no work',
  ])
  expect(reads).toEqual(['/api/equipments/press/day-history'])
})
