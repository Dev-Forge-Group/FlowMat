import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

const projectId = 'prj-labor-cost'
const shift = { shiftId: 'shift-1', shiftStart: '07:00', shiftEnd: '16:00', workDays: [1, 2, 3, 4, 5], hours: 9, shiftHours: 9, rotaWeek: 1 }

/** demo-owner is the owner unless {@code role} says otherwise; {@code calls} collects every labor-rate request. */
async function mockApi(page: Page, role: 'owner' | 'editor', calls: string[]) {
  let lines = [{ rateId: 'r1', hourlyRate: 30 as number | null, effectiveFrom: '2041-09-01', reason: 'Base' as string | null,
    setBy: 'demo-owner', setAt: '2041-08-30T09:00:00Z', state: 'current', removedBy: null, removedAt: null }]
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (pathname === `/api/projects/${projectId}/workers/kim/labor-rates`) {
      calls.push(`${request.method()} ${pathname}`)
      if (request.method() === 'PUT') {
        const body = request.postDataJSON() as { hourlyRate: number | null; effectiveFrom?: string; reason?: string }
        lines = [{ rateId: 'r2', hourlyRate: body.hourlyRate, effectiveFrom: body.effectiveFrom ?? '2041-09-01',
          reason: body.reason ?? null, setBy: 'demo-owner', setAt: '2041-09-01T10:00:00Z', state: 'upcoming', removedBy: null,
          removedAt: null }, ...lines]
      }
      return ok(route, { projectId, userId: 'kim', today: '2041-09-01', current: lines[lines.length - 1], lines })
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === `/api/projects/${projectId}/workers`) return ok(route, [
      { projectId, userId: 'demo-owner', owner: role === 'owner', calendarSet: false, weeklyHours: 0, shifts: [], daysOff: [], days: [] },
      { projectId, userId: 'kim', owner: false, calendarSet: true, weeklyHours: 45, daysOff: [], days: [], rota: null,
        shifts: [shift], schedules: [{ scheduleId: 'version-1', effectiveFrom: null, current: true }] },
    ])
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    if (pathname === '/api/project-members') return ok(route, [
      { projectMemberId: 'member-1', projectId, userId: 'demo-owner', projectRole: role, memberStatus: 'active' },
    ])
    return ok(route, [])
  })
}

test('the project owner sees a worker\'s labor rate and saves a new one from a date', async ({ page }) => {
  const calls: string[] = []
  await mockApi(page, 'owner', calls)
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/settings`)
  await page.getByRole('region', { name: 'Workers' }).getByText('kim · 45 h a week').click()
  const panel = page.getByRole('group', { name: 'Worker kim' })
  await panel.locator('summary').filter({ hasText: 'Labor rate' }).click()
  await expect(panel.getByLabel('Current labor rate')).toHaveText('30/h from 2041-09-01 (current) · Base · set by demo-owner')
  await panel.getByLabel('Hourly rate').fill('-3')
  await panel.getByRole('button', { name: 'Save rate' }).click()
  await expect(panel.getByRole('alert')).toHaveText('Enter the rate per hour as a number, 0 or more, or leave it empty when unknown.')
  await panel.getByLabel('Hourly rate').fill('33.5')
  await panel.getByLabel('Rate applies from').fill('2041-09-08')
  await panel.getByLabel('Rate reason').fill('Raise')
  await panel.getByRole('button', { name: 'Save rate' }).click()
  await expect(panel.getByRole('list', { name: 'Labor rate history' }).getByRole('listitem').first())
    .toHaveText('33.5/h from 2041-09-08 (upcoming) · Raise · set by demo-owner Remove')
  expect(calls).toEqual([`GET /api/projects/${projectId}/workers/kim/labor-rates`, `PUT /api/projects/${projectId}/workers/kim/labor-rates`])
})

test('a worker\'s first rate can cover the time before it', async ({ page }) => {
  const sent: unknown[] = []
  await mockApi(page, 'owner', [])
  // Registered last, so it answers kim's rates before the general mock.
  await page.route(/^https?:\/\/[^/]+\/api\/projects\/prj-labor-cost\/workers\/kim\/labor-rates$/, async (route) => {
    if (route.request().method() === 'PUT') sent.push(route.request().postDataJSON())
    const lines = sent.length === 0 ? [] : [{ rateId: 'r1', hourlyRate: 20, effectiveFrom: null, reason: null, setBy: 'demo-owner',
      setAt: '2041-09-01T10:00:00Z', state: 'current', removedBy: null, removedAt: null }]
    return ok(route, { projectId, userId: 'kim', today: '2041-09-01', current: lines[0] ?? null, lines })
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/settings`)
  await page.getByRole('region', { name: 'Workers' }).getByText('kim · 45 h a week').click()
  const panel = page.getByRole('group', { name: 'Worker kim' })
  await panel.locator('summary').filter({ hasText: 'Labor rate' }).click()
  await expect(panel.getByLabel('Current labor rate')).toHaveText('No rate yet: worked time has no cost.')
  await panel.getByLabel('Hourly rate').fill('20')
  await panel.getByLabel('From the start (worked time already recorded gets this rate)').check()
  await expect(panel.getByLabel('Rate applies from')).toBeDisabled()
  await panel.getByRole('button', { name: 'Save rate' }).click()
  await expect(panel.getByLabel('Current labor rate')).toHaveText('20/h from the start (current) · set by demo-owner')
  await expect(panel.getByLabel('From the start (worked time already recorded gets this rate)')).toHaveCount(0)
  expect(sent).toEqual([{ hourlyRate: 20, fromStart: true }])
})

test('nobody but the project owner asks for labor rates', async ({ page }) => {
  const calls: string[] = []
  await mockApi(page, 'editor', calls)
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/settings`)
  const own = page.getByRole('group', { name: 'Worker demo-owner' })
  await own.locator(':scope > summary').click()
  await expect(own.locator('summary').filter({ hasText: 'Version history' })).toBeVisible()
  await expect(page.locator('summary').filter({ hasText: 'Labor rate' })).toHaveCount(0)
  expect(calls).toEqual([])
})
