import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * Versions of a worker's weekly shifts in Project Settings (docs/architecture/adr/ADR-013-rota-history.md) against a mocked
 * API: the list says which version applies now and which is ahead, an upcoming one loads into the form and saves back from
 * its date, it can be deleted, and a start before today is refused before anything is sent.
 */
test('the owner changes and deletes an upcoming version of the shifts', async ({ page }) => {
  const projectId = 'prj-versions'
  const now = { scheduleId: 'sc-now', effectiveFrom: null, current: true }
  const next = { scheduleId: 'sc-next', effectiveFrom: '2041-09-09', current: false }
  let kim = { projectId, userId: 'kim', owner: false, calendarSet: true, weeklyHours: 40, daysOff: [], days: [], rota: null,
    shifts: [{ shiftId: 's1', shiftStart: '09:00', shiftEnd: '17:00', workDays: [1, 2, 3, 4, 5], hours: 8, rotaWeek: 1 }],
    schedules: [now, next] as { scheduleId: string; effectiveFrom: string | null; current: boolean }[] }
  const owner = { projectId, userId: 'demo-owner', owner: true, calendarSet: false, weeklyHours: 0, shifts: [], daysOff: [], days: [] }
  const sent: { method: string; path: string; body: unknown }[] = []

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    const base = `/api/projects/${projectId}/workers/kim`
    if (request.method() === 'PUT' && pathname === `${base}/shifts`) {
      sent.push({ method: 'PUT', path: pathname, body: request.postDataJSON() })
      return ok(route, kim)
    }
    if (request.method() === 'DELETE' && pathname === `${base}/schedules/sc-next`) {
      sent.push({ method: 'DELETE', path: pathname, body: null })
      kim = { ...kim, schedules: [now] }
      return ok(route, kim)
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === `${base}/schedules/sc-next`) {
      return ok(route, { ...next, calendarSet: true, weeklyHours: 30, rota: null,
        shifts: [{ shiftId: 's9', shiftStart: '14:00', shiftEnd: '20:00', workDays: [1, 2, 3, 4, 5], hours: 6, rotaWeek: 1 }] })
    }
    if (pathname === `/api/projects/${projectId}/workers`) return ok(route, [owner, kim])
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'Asia/Seoul', version: 0 })
    if (pathname === '/api/project-members') {
      return ok(route, [{ projectMemberId: 'pm', projectId, userId: 'demo-owner', projectRole: 'owner', memberStatus: 'active' }])
    }
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/settings`)
  const panel = page.getByRole('region', { name: 'Workers' })
  await panel.getByText('kim · 40 h a week').click({ timeout: 15_000 })
  const calendar = panel.getByRole('group', { name: 'Worker kim' })
  const versions = calendar.getByRole('group', { name: 'Shift versions' })
  await expect(versions.getByRole('listitem')).toHaveText(['From the start · now', 'From 2041-09-09 · upcoming Edit Delete'])

  // A start before today is refused before anything is sent.
  await calendar.getByLabel('Shifts start on').fill('2020-01-06')
  await calendar.getByRole('button', { name: 'Save shifts' }).click()
  await expect(calendar.getByRole('alert')).toHaveText('Pick today or a later date; change a past date with a day change.')
  expect(sent).toHaveLength(0)

  // The upcoming version loads into the form and saves back from its date.
  await versions.getByRole('button', { name: 'Edit version from 2041-09-09' }).click()
  await expect(calendar.getByLabel('Shift 1 start', { exact: true })).toHaveValue('14:00')
  await expect(calendar.getByLabel('Shifts start on')).toHaveValue('2041-09-09')
  await calendar.getByLabel('Shift 1 end', { exact: true }).fill('21:00')
  await calendar.getByRole('button', { name: 'Save shifts' }).click()
  await expect(calendar.getByRole('status')).toHaveText('Shifts saved from 2041-09-09; until then the current shifts stay.')
  expect(sent[0]).toEqual({ method: 'PUT', path: `/api/projects/${projectId}/workers/kim/shifts`,
    body: { shifts: [{ shiftStart: '14:00', shiftEnd: '21:00', workDays: [1, 2, 3, 4, 5] }], effectiveFrom: '2041-09-09' } })

  // It can go before it starts; with one version left the list is not shown.
  await versions.getByRole('button', { name: 'Delete version from 2041-09-09' }).click()
  await expect(calendar.getByRole('group', { name: 'Shift versions' })).toHaveCount(0)
  expect(sent[1]).toEqual({ method: 'DELETE', path: `/api/projects/${projectId}/workers/kim/schedules/sc-next`, body: null })
})
