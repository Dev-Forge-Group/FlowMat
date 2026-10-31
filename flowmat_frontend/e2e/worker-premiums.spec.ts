import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/** The owner gives a worker premiums of their own and returns them to the project's (docs/architecture/adr/ADR-028-pay-premiums.md), mocked. */
test.use({ timezoneId: 'UTC' })

const projectId = 'prj-worker-premiums'
const shift = { shiftId: 'shift-1', shiftStart: '08:00', shiftEnd: '17:00', workDays: [1, 2, 3, 4, 5], hours: 9, shiftHours: 9, rotaWeek: 1 }

test('the owner sets a worker\'s own premiums and then the project\'s again', async ({ page }) => {
  const saved: { path: string; body: unknown }[] = []
  let current: Record<string, unknown> | null = null
  const rule = (body: Record<string, unknown>) => ({ ruleId: `rule-${saved.length}`, effectiveFrom: '2041-09-03', overtimeAfterMinutes: null,
    overtimePercent: null, nightStart: null, nightEnd: null, nightPercent: null, holidayPercent: null, weeklyOvertimeAfterMinutes: null,
    weeklyOvertimePercent: null, weekStartsOn: null, restDaysAsHolidays: false, holidayOvertimeAfterMinutes: null,
    holidayOvertimePercent: null, followProject: false, reason: null, setBy: 'demo-owner', setAt: '2041-09-03T00:00:00Z', state: 'current',
    removedBy: null, removedAt: null, ...body })
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (pathname === `/api/projects/${projectId}/workers/kim/labor-premiums`) {
      if (request.method() === 'PUT') {
        const body = request.postDataJSON() as Record<string, unknown>
        saved.push({ path: pathname, body })
        current = rule(body)
      }
      return ok(route, { projectId, userId: 'kim', today: '2041-09-03', current, lines: current ? [current] : [] })
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === `/api/projects/${projectId}/labor-premiums`) return ok(route, { projectId, userId: null, today: '2041-09-03',
      current: null, lines: [] })
    if (pathname === `/api/projects/${projectId}/workers/kim/labor-rates`) return ok(route, { projectId, userId: 'kim', today: '2041-09-03',
      current: null, lines: [] })
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
  await page.getByRole('region', { name: 'Workers' }).getByText('kim · 45 h a week').click()
  const worker = page.getByRole('group', { name: 'Worker kim' })
  await worker.locator('summary').filter({ hasText: 'Labor rate' }).click()
  await worker.locator('summary').filter({ hasText: 'Own pay premiums' }).click()
  await expect(worker.getByLabel('Worker pay premiums')).toHaveText("kim follows the project's pay premiums.")

  // No premiums of kim's own, as for a manager: an empty save with a reason.
  const form = worker.getByRole('form', { name: "Set kim's pay premiums" })
  await form.getByLabel('Premiums reason').fill('Manager')
  await form.getByRole('button', { name: 'Save premiums' }).click()
  await expect(worker.getByLabel('Worker pay premiums')).toHaveText('From 2041-09-03 (current): no premiums · Manager · set by demo-owner')
  expect(saved).toEqual([{ path: `/api/projects/${projectId}/workers/kim/labor-premiums`, body: { reason: 'Manager' } }])

  // Back to the project's premiums.
  await worker.getByRole('button', { name: "Follow the project's premiums" }).click()
  await expect(worker.getByLabel('Worker pay premiums')).toHaveText("kim follows the project's pay premiums.")
  expect(saved[1]).toEqual({ path: `/api/projects/${projectId}/workers/kim/labor-premiums`, body: { followProject: true } })
  await expect(worker.getByRole('button', { name: "Follow the project's premiums" })).toHaveCount(0)
})
