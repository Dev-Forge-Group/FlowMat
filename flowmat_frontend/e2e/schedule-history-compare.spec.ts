import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

async function fixture(page: Page, kind: 'worker' | 'equipment', single = false, longLabels = false) {
  await page.clock.install()
  const projectId = 'prj-history-access'
  let denied = false
  let reads = 0
  const historyPath = kind === 'worker' ? `/api/projects/${projectId}/workers/kim/schedule-history`
    : '/api/equipments/press/calendar/history'
  const shift = { shiftId: 'shift-1', shiftStart: '07:00', shiftEnd: '16:00', workDays: [1, 2, 3, 4, 5],
    hours: 9, shiftHours: 9, rotaWeek: 1 }
  const later = { historyId: 'h-new', scheduleId: 'v-next', effectiveFrom: '2041-09-09', action: 'saved',
    reason: 'Late shifts', shifts: [{ ...shift, shiftStart: '14:00', shiftEnd: '20:00', workDays: [1, 3], rotaWeek: 2 }],
    rota: kind === 'worker' ? { weeks: 2, startsOn: '2041-09-02' } : null,
    savedBy: 'demo-owner', savedAt: '2041-09-02T09:00:00Z' }
  const history = [
    { ...later, historyId: 'h-copy', savedBy: 'lee', savedAt: '2041-09-04T09:00:00Z' },
    { ...later, historyId: 'h-removed', action: 'removed', savedAt: '2041-09-03T09:00:00Z' },
    later,
    { ...later, historyId: 'h-old', scheduleId: 'v-old', effectiveFrom: null, reason: 'Day shifts',
      shifts: [{ ...shift, shiftStart: '09:00', shiftEnd: '17:00' }], rota: null, savedAt: '2041-09-01T09:00:00Z' },
  ].slice(0, single ? 1 : 4).map((entry) => longLabels
    ? { ...entry, savedBy: 'Operations review team '.repeat(8) + entry.savedBy } : entry)
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
      return ok(route, history)
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
    restore: () => { denied = false } }
}

for (const kind of ['worker', 'equipment'] as const) {
  test(`${kind} history compares saved settings locally, swaps direction and hides them after access is lost`, async ({ page }) => {
    const f = await fixture(page, kind)
    await f.details.locator(':scope > summary').click()
    await expect(f.details.getByRole('listitem')).toHaveCount(4)
    await f.details.getByRole('button', { name: 'Compare changes', exact: true }).click()
    const comparison = f.details.getByRole('group', { name: 'History comparison', exact: true })
    await comparison.getByLabel('Compare from', { exact: true }).selectOption('h-old')
    await comparison.getByLabel('Compare to', { exact: true }).selectOption('h-new')
    const shifts = comparison.getByRole('row', { name: /^Shifts/ })
    await expect(shifts).toContainText('09:00–17:00')
    await expect(shifts).toContainText('14:00–20:00')
    await expect(shifts).toContainText('Changed')
    await expect(comparison.getByRole('row', { name: /^Note/ })).toContainText('Day shifts')
    await expect(comparison.getByRole('row', { name: /^Note/ })).toContainText('Late shifts')
    if (kind === 'worker') await expect(comparison.getByRole('row', { name: /^Rota/ })).toContainText('Every 2 weeks from 2041-09-02')
    else await expect(comparison.getByRole('row', { name: /^Rota/ })).toHaveCount(0)
    await comparison.getByRole('button', { name: 'Swap entries', exact: true }).click()
    await expect(comparison.getByLabel('Compare from', { exact: true })).toHaveValue('h-new')
    await expect(comparison.getByLabel('Compare to', { exact: true })).toHaveValue('h-old')
    await comparison.getByLabel('Compare to', { exact: true }).selectOption('h-copy')
    await expect(comparison.getByRole('status')).toHaveText('Saved settings are identical.')
    expect(f.reads()).toBe(1)
    await page.setViewportSize({ width: 375, height: 812 })
    const bounds = await comparison.boundingBox()
    expect(bounds!.width).toBeLessThanOrEqual(375)
    f.revoke()
    await f.details.locator(':scope > summary').click()
    await page.clock.fastForward(31_000)
    const refused = page.waitForResponse((reply) => new URL(reply.url()).pathname === f.historyPath && reply.status() === 403)
    await f.details.locator(':scope > summary').click()
    await refused
    await expect(f.details.getByRole('alert')).toHaveText('History access revoked.')
    await expect(comparison).toHaveCount(0)
    await expect(f.details).not.toContainText('Late shifts')
  })

  test(`${kind} history with one entry keeps the history and has no comparison control`, async ({ page }) => {
    const f = await fixture(page, kind, true)
    await f.details.locator(':scope > summary').click()
    await expect(f.details.getByRole('listitem')).toHaveCount(1)
    await expect(f.details.getByRole('button', { name: 'Compare changes', exact: true })).toHaveCount(0)
  })
}


for (const kind of ['worker', 'equipment'] as const) {
  test(`${kind} history keeps long revision selectors within the phone viewport`, async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 })
    const f = await fixture(page, kind, false, true)
    await f.details.locator(':scope > summary').click()
    await f.details.getByRole('button', { name: 'Compare changes', exact: true }).click()
    const comparison = f.details.getByRole('group', { name: 'History comparison', exact: true })
    await comparison.getByLabel('Compare from', { exact: true }).selectOption('h-old')
    await comparison.getByLabel('Compare to', { exact: true }).selectOption('h-new')
    const bounds = await comparison.evaluate((element) => {
      const rect = element.getBoundingClientRect()
      return { left: rect.left, right: rect.right, width: rect.width, viewport: window.innerWidth,
        controls: [...element.querySelectorAll('select')].map((select) => {
          const control = select.getBoundingClientRect()
          return { left: control.left, right: control.right, width: control.width }
        }) }
    })
    expect(bounds.width).toBeLessThanOrEqual(bounds.viewport)
    expect(bounds.left).toBeGreaterThanOrEqual(0)
    expect(bounds.right).toBeLessThanOrEqual(bounds.viewport)
    expect(bounds.controls).toHaveLength(2)
    for (const control of bounds.controls) {
      expect(control.width).toBeGreaterThan(0)
      expect(control.left).toBeGreaterThanOrEqual(bounds.left)
      expect(control.right).toBeLessThanOrEqual(bounds.right)
    }
    await expect(comparison.getByRole('row', { name: /^Shifts/ })).toContainText('09:00–17:00')
    await expect(comparison.getByRole('row', { name: /^Shifts/ })).toContainText('14:00–20:00')
    await comparison.getByRole('button', { name: 'Swap entries', exact: true }).click()
    await expect(comparison.getByLabel('Compare from', { exact: true })).toHaveValue('h-new')
    await expect(comparison.getByLabel('Compare to', { exact: true })).toHaveValue('h-old')
    expect(f.reads()).toBe(1)
  })
}
