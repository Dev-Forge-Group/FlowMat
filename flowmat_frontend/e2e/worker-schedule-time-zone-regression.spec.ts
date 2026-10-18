import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

test.use({ timezoneId: 'Asia/Seoul', trace: 'off' })

async function calendarInZone(page: Page, timeZone: string) {
  await page.clock.setFixedTime(new Date('2026-10-18T11:00:00Z'))
  const projectId = 'prj-schedule-zone'
  const owner = { projectId, userId: 'demo-owner', owner: true, calendarSet: true, weeklyHours: 40,
    shifts: [{ shiftId: 's1', shiftStart: '09:00', shiftEnd: '17:00', workDays: [1, 2, 3, 4, 5], hours: 8, rotaWeek: 1 }],
    daysOff: [], days: [], rota: null, schedules: [
      { scheduleId: 'current', effectiveFrom: '2026-10-17', current: true },
      { scheduleId: 'future', effectiveFrom: '2026-10-18', current: false },
    ] }
  const sent: unknown[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'PUT' && pathname === `/api/projects/${projectId}/workers/demo-owner/shifts`) {
      sent.push(request.postDataJSON())
      return ok(route, owner)
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404, body: '{}' })
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone, version: 0 })
    if (pathname === `/api/projects/${projectId}/workers`) return ok(route, [owner,
      { ...owner, userId: 'kim', owner: false, schedules: [] }])
    if (pathname === '/api/project-members') return ok(route, [
      { projectMemberId: 'pm', projectId, userId: 'demo-owner', projectRole: 'owner', memberStatus: 'active' },
    ])
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/settings`)
  const calendar = page.getByRole('region', { name: 'Workers' })
    .getByRole('group', { name: 'Worker demo-owner', exact: true })
  await page.getByRole('region', { name: 'Workers' }).getByText('demo-owner (owner) · 40 h a week', { exact: true }).click()
  await expect(page.getByLabel('IANA time zone', { exact: true })).toHaveValue(timeZone)
  return { calendar, sent }
}

test('a project date behind the browser is still a valid shift start', async ({ page }) => {
  const { calendar, sent } = await calendarInZone(page, 'Etc/GMT+12')
  await expect(calendar.getByLabel('Shifts start on', { exact: true })).toHaveAttribute('min', '2026-10-17')
  await calendar.getByLabel('Shifts start on', { exact: true }).fill('2026-10-17')
  await calendar.getByRole('button', { name: 'Save shifts', exact: true }).click()
  await expect(calendar.getByRole('status')).toHaveText('Shifts saved: 40 h a week.')
  expect(sent).toHaveLength(1)
  expect(sent[0]).toMatchObject({ effectiveFrom: '2026-10-17' })
})

test('a version starting on the browser date remains editable before the project date reaches it', async ({ page }) => {
  const { calendar } = await calendarInZone(page, 'Etc/GMT+12')
  const versions = calendar.getByRole('group', { name: 'Shift versions', exact: true })
  await expect(versions.getByRole('button', { name: 'Edit version from 2026-10-18', exact: true })).toBeVisible()
  await expect(versions.getByRole('button', { name: 'Delete version from 2026-10-18', exact: true })).toBeVisible()
  await expect(versions).toContainText('From 2026-10-18 · upcoming')
})

test('copying shifts uses the same project date as saving them', async ({ page }) => {
  const { calendar } = await calendarInZone(page, 'Etc/GMT+12')
  await expect(calendar.getByLabel('Copies start on', { exact: true })).toHaveAttribute('min', '2026-10-17')
})

test('a browser date already past in the project is refused before saving', async ({ page }) => {
  const { calendar, sent } = await calendarInZone(page, 'Etc/GMT-14')
  await expect(calendar.getByLabel('Shifts start on', { exact: true })).toHaveAttribute('min', '2026-10-19')
  await calendar.getByLabel('Shifts start on', { exact: true }).fill('2026-10-18')
  await calendar.getByRole('button', { name: 'Save shifts', exact: true }).click()
  await expect(calendar.getByRole('alert')).toHaveText('Pick today or a later date; change a past date with a day change.')
  expect(sent).toHaveLength(0)
})
