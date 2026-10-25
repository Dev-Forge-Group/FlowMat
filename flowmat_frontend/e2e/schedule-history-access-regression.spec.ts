import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

async function fixture(page: Page, kind: 'worker' | 'equipment') {
  await page.clock.install()
  const projectId = 'prj-history-access'
  let denied = false
  let reads = 0
  let reason = 'PRIVATE-SHIFT'
  const historyPath = kind === 'worker' ? `/api/projects/${projectId}/workers/kim/schedule-history`
    : '/api/equipments/press/calendar/history'
  const shift = { shiftId: 'shift-1', shiftStart: '07:00', shiftEnd: '16:00', workDays: [1, 2, 3, 4, 5],
    hours: 9, shiftHours: 9, rotaWeek: 1 }
  const history = [{ historyId: 'history-1', scheduleId: 'version-1', effectiveFrom: null, action: 'saved',
    reason: 'PRIVATE-SHIFT', shifts: [shift], rota: null, savedBy: 'demo-owner', savedAt: '2026-10-19T09:00:00Z' }]
  const worker = { projectId, userId: 'kim', owner: false, calendarSet: true, weeklyHours: 45, daysOff: [], days: [],
    rota: null, shifts: [shift], schedules: [{ scheduleId: 'version-1', effectiveFrom: null, current: true }] }
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === historyPath) {
      reads += 1
      if (denied) return route.fulfill({ status: 403, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: 'History access revoked.' }) })
      return ok(route, history.map((entry) => ({ ...entry, reason })))
    }
    if (pathname === `/api/projects/${projectId}/workers`) return ok(route, [
      { projectId, userId: 'demo-owner', owner: true, calendarSet: false, weeklyHours: 0, shifts: [], daysOff: [], days: [] }, worker,
    ])
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    if (pathname === '/api/project-members') return ok(route, [
      { projectMemberId: 'member-1', projectId, userId: 'demo-owner', projectRole: 'owner', memberStatus: 'active' },
    ])
    if (pathname === '/api/equipments') return ok(route, [{ equipmentId: 'press', projectId, equipmentCode: 'PRESS',
      equipmentName: 'Press', equipmentType: 'machine', equipmentStatus: 'active', details: { capacityPerHour: 10 } }])
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
  await mockedLogin(page)
  if (kind === 'worker') {
    await page.goto(`/projects/${projectId}/settings`)
    await page.getByRole('region', { name: 'Workers' }).getByText('kim · 45 h a week').click()
  } else {
    await page.goto(`/projects/${projectId}/inventory?tab=equipment`)
    await page.getByRole('row', { name: /PRESS.*Press/ }).getByRole('button', { name: 'Schedule', exact: true }).click()
  }
  const panel = kind === 'worker' ? page.getByRole('group', { name: 'Worker kim' })
    : page.getByRole('region', { name: 'Schedule of PRESS' })
  const label = kind === 'worker' ? 'Version history' : 'Calendar history'
  const details = panel.locator('details').filter({ has: page.locator('summary').filter({ hasText: label }) })
  return { details, historyPath, reads: () => reads, revoke: () => { denied = true },
    restore: () => { denied = false; reason = 'RESTORED-SHIFT' } }
}

for (const kind of ['worker', 'equipment'] as const) {
  test(`${kind} history is lazy and hides previously cached rows after access is refused`, async ({ page }) => {
    const f = await fixture(page, kind)
    expect(f.reads()).toBe(0)
    await f.details.locator('summary').click()
    await expect(f.details.getByRole('listitem')).toContainText('PRIVATE-SHIFT')
    expect(f.reads()).toBe(1)
    f.revoke()
    await f.details.locator('summary').click()
    // The shared query client considers reads fresh for 30 seconds.
    await page.clock.fastForward(31_000)
    const refused = page.waitForResponse((reply) => new URL(reply.url()).pathname === f.historyPath && reply.status() === 403)
    await f.details.locator('summary').click()
    await refused
    await expect(f.details.getByRole('alert')).toHaveText('History access revoked.')
    await expect(f.details).not.toContainText('PRIVATE-SHIFT')
    await expect(f.details.getByRole('listitem')).toHaveCount(0)
    await f.details.locator('summary').click()
    f.restore()
    await page.clock.fastForward(31_000)
    const restored = page.waitForResponse((reply) => new URL(reply.url()).pathname === f.historyPath && reply.status() === 200)
    await f.details.locator('summary').click()
    await restored
    await expect(f.details.getByRole('listitem')).toContainText('RESTORED-SHIFT')
    await expect(f.details).not.toContainText('PRIVATE-SHIFT')
    await expect(f.details.getByRole('alert')).toHaveCount(0)
  })
}
