import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * Versions of an equipment calendar on the equipment tab (docs/architecture/adr/ADR-014-equipment-calendar-history.md) against
 * a mocked API: the list says which version applies now and which is ahead, an upcoming one loads into the form and saves
 * back from its date, removing the calendar from a date sends that date, an upcoming version can be deleted, and a date
 * before today is refused before anything is sent.
 */
test('the owner changes, removes from a date and deletes upcoming calendar versions', async ({ page }) => {
  const projectId = 'prj-equipment-versions'
  const equipment = { equipmentId: 'press', projectId, equipmentCode: 'PRESS', equipmentName: 'Press', equipmentType: 'machine',
    equipmentStatus: 'active', details: { manufacturer: null, modelName: null, serialNo: null, capacityPerHour: 10,
      powerKwh: null, waterLiter: null, location: null } }
  const now = { scheduleId: 'v-now', effectiveFrom: null, current: true, alwaysRunning: false }
  const next = { scheduleId: 'v-next', effectiveFrom: '2041-09-09', current: false, alwaysRunning: false, reason: 'New press' }
  const off = { scheduleId: 'v-off', effectiveFrom: '2041-09-16', current: false, alwaysRunning: true }
  const calendar = { shifts: [{ shiftId: 's1', shiftStart: '08:00', shiftEnd: '16:00', workDays: [1, 2, 3, 4, 5], shiftHours: 8 }],
    weeklyHours: 40, updatedBy: 'demo-owner', updatedAt: null }
  let schedule = { equipmentId: 'press', timeZone: 'Asia/Seoul', calendar, downtimes: [], days: [],
    versions: [now, next] as { scheduleId: string; effectiveFrom: string | null; current: boolean; alwaysRunning: boolean; reason?: string }[] }
  const sent: { method: string; path: string; body: unknown }[] = []

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const { pathname } = url
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'PUT' && pathname === '/api/equipments/press/calendar') {
      sent.push({ method: 'PUT', path: pathname + url.search, body: request.postDataJSON() })
      return ok(route, schedule)
    }
    if (request.method() === 'DELETE' && pathname === '/api/equipments/press/calendar') {
      sent.push({ method: 'DELETE', path: pathname + url.search, body: null })
      schedule = { ...schedule, versions: [now, next, off] }
      return ok(route, schedule)
    }
    if (request.method() === 'DELETE' && pathname === '/api/equipments/press/calendar/versions/v-off') {
      sent.push({ method: 'DELETE', path: pathname, body: null })
      schedule = { ...schedule, versions: [now, next] }
      return ok(route, schedule)
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/equipments/press/calendar/versions/v-next') {
      return ok(route, { ...next, weeklyHours: 50,
        shifts: [{ shiftId: 's9', shiftStart: '06:00', shiftEnd: '16:00', workDays: [1, 2, 3, 4, 5], shiftHours: 10 }] })
    }
    if (pathname === '/api/equipments/press/calendar/history') {
      return ok(route, [
        { historyId: 'h2', scheduleId: 'v-off', effectiveFrom: '2041-09-16', action: 'saved', reason: null, shifts: [],
          savedBy: 'demo-owner', savedAt: '2041-09-01T09:30:00' },
        { historyId: 'h1', scheduleId: 'v-next', effectiveFrom: '2041-09-09', action: 'saved', reason: 'New press',
          shifts: [{ shiftStart: '06:00', shiftEnd: '16:00', workDays: [1, 2, 3, 4, 5] }], savedBy: 'demo-owner',
          savedAt: '2041-09-01T09:00:00' },
      ])
    }
    if (pathname === '/api/equipments') return ok(route, [equipment])
    if (pathname === '/api/equipments/press/schedule') return ok(route, schedule)
    if (pathname === '/api/equipments/press/availability') {
      return ok(route, { equipmentId: 'press', from: url.searchParams.get('from'), to: url.searchParams.get('to'), calendarSet: true,
        workingHours: 40, downtimeHours: 0, availableHours: 40, capacityPerHour: 10, capacity: 400 })
    }
    if (pathname === '/api/equipments/press/hourly-cost') {
      return ok(route, { equipmentId: 'press', hourlyCost: null, version: 0, updatedBy: null, updatedAt: null })
    }
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=equipment`)
  await page.getByRole('row', { name: /PRESS.*Press/ }).getByRole('button', { name: 'Schedule', exact: true }).click()
  const panel = page.getByRole('region', { name: 'Schedule of PRESS' })
  const form = panel.getByRole('form', { name: 'Calendar' })
  const versions = panel.getByRole('group', { name: 'Calendar versions' })
  await expect(versions.getByRole('listitem')).toHaveText(['From the start · now', 'From 2041-09-09 · upcoming · New press Edit Delete'])

  // A date before today is refused before anything is sent.
  await form.getByLabel('Calendar applies from').fill('2020-01-06')
  await form.getByRole('button', { name: 'Save calendar' }).click()
  await expect(form.getByRole('alert')).toHaveText('Pick today or a later date; change a past date with a day change.')
  expect(sent).toHaveLength(0)

  // The upcoming version loads into the form, its note too (ADR-017), and saves back from its date.
  await versions.getByRole('button', { name: 'Edit version from 2041-09-09' }).click()
  await expect(form.getByLabel('Shift start', { exact: true })).toHaveValue('06:00')
  await expect(form.getByLabel('Calendar applies from')).toHaveValue('2041-09-09')
  await expect(form.getByLabel('Calendar version note')).toHaveValue('New press')
  await form.getByLabel('Shift end', { exact: true }).fill('17:00')
  await form.getByLabel('Calendar version note').fill('New press, longer days')
  await form.getByRole('button', { name: 'Save calendar' }).click()
  await expect(form.getByRole('status', { name: 'Calendar change' }))
    .toHaveText('Calendar saved from 2041-09-09; until then the current calendar stays.')
  expect(sent[0]).toEqual({ method: 'PUT', path: '/api/equipments/press/calendar',
    body: { shifts: [{ shiftStart: '06:00', shiftEnd: '17:00', workDays: [1, 2, 3, 4, 5] }], effectiveFrom: '2041-09-09',
      reason: 'New press, longer days' } })

  // Removing the calendar from a date runs the press around the clock from then; earlier dates keep their shifts.
  await form.getByLabel('Calendar applies from').fill('2041-09-16')
  await form.getByRole('button', { name: 'Remove calendar from 2041-09-16' }).click()
  await expect(form.getByRole('status', { name: 'Calendar change' }))
    .toHaveText('Calendar removed from 2041-09-16; until then the current calendar stays.')
  expect(sent[1]).toEqual({ method: 'DELETE', path: '/api/equipments/press/calendar?effectiveFrom=2041-09-16', body: null })
  await expect(versions.getByRole('listitem')).toHaveText(['From the start · now', 'From 2041-09-09 · upcoming · New press Edit Delete',
    'From 2041-09-16 · around the clock · upcoming Delete'])

  // An upcoming version can go before it starts.
  await versions.getByRole('button', { name: 'Delete version from 2041-09-16' }).click()
  await expect(versions.getByRole('listitem')).toHaveCount(2)
  expect(sent[2]).toEqual({ method: 'DELETE', path: '/api/equipments/press/calendar/versions/v-off', body: null })

  // Every save stays in the calendar history, loaded when opened (ADR-024).
  await panel.getByText('Calendar history', { exact: true }).click()
  await expect(panel.getByRole('list', { name: 'Calendar history' }).getByRole('listitem')).toHaveText([
    '2041-09-01 09:30 · demo-owner saved the version from 2041-09-16: around the clock',
    '2041-09-01 09:00 · demo-owner saved the version from 2041-09-09: Mon–Fri 06:00–16:00 · New press',
  ])
})
