import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

test.use({ timezoneId: 'UTC' })

async function cancellationFixture(page: Page, fails = false) {
  await page.clock.install({ time: new Date('2041-09-05T12:00:00Z') })
  const projectId = 'prj-attendance-cancel'
  const base = `/api/projects/${projectId}/workers/kim/attendance`
  const writes: { attendanceId: string; body: Record<string, string> }[] = []
  const original = {
    attendanceId: 'a1', workDate: '2041-09-02', clockIn: '2041-09-02T08:00:00Z', clockOut: '2041-09-02T16:00:00Z',
    minutes: 480, note: 'First stretch', recordedBy: 'kim', recordedAt: '2041-09-02T16:01:00Z', cancelled: false,
    cancelledBy: null as string | null, cancelledAt: null as string | null, cancelReason: null as string | null,
  }
  let lines = [original, { ...original, attendanceId: 'a2', workDate: '2041-09-03', clockIn: '2041-09-03T09:00:00Z',
    clockOut: '2041-09-03T17:00:00Z', note: 'Second stretch' }]
  const activeDays = () => lines.filter((line) => !line.cancelled)
    .map((line) => ({ date: line.workDate, attendedMinutes: line.minutes }))
  const attendance = () => ({ projectId, userId: 'kim', from: '2041-08-30', to: '2041-09-05', today: '2041-09-05',
    open: null, days: activeDays(), lines })
  let release!: () => void
  const firstResponse = new Promise<void>((resolve) => { release = resolve })
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname.startsWith(base + '/') && pathname.endsWith('/cancel')) {
      const attendanceId = pathname.slice(base.length + 1).split('/')[0]
      const body = request.postDataJSON() as Record<string, string>
      writes.push({ attendanceId, body })
      if (writes.length === 1) await firstResponse
      if (fails) return route.fulfill({ status: 500, contentType: 'application/json',
        body: JSON.stringify({ success: false, message: 'Cancellation unavailable.' }) })
      if (lines.find((line) => line.attendanceId === attendanceId)?.cancelled) return route.fulfill({ status: 409,
        contentType: 'application/json', body: JSON.stringify({ success: false, message: 'This attendance was already cancelled.' }) })
      lines = lines.map((line) => line.attendanceId === attendanceId ? { ...line, cancelled: true,
        cancelledBy: 'demo-owner', cancelledAt: '2041-09-05T12:00:00Z', cancelReason: body.reason } : line)
      return ok(route, attendance())
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === base) return ok(route, attendance())
    if (pathname === `/api/projects/${projectId}/workers/kim/attendance-comparison`) return ok(route, { projectId, userId: 'kim',
      from: '2041-08-30', to: '2041-09-05', attendedMinutes: activeDays().reduce((sum, day) => sum + day.attendedMinutes, 0), laborMinutes: 0,
      days: activeDays().map((day) => ({ ...day, laborMinutes: 0, differenceMinutes: day.attendedMinutes })) })
    if (pathname === `/api/projects/${projectId}/workers`) return ok(route, [
      { projectId, userId: 'demo-owner', owner: true, calendarSet: false, weeklyHours: 0, shifts: [], daysOff: [], days: [] },
      { projectId, userId: 'kim', owner: false, calendarSet: true, weeklyHours: 40, daysOff: [], days: [], rota: null,
        shifts: [{ shiftId: 's1', shiftStart: '08:00', shiftEnd: '16:00', workDays: [1, 2, 3, 4, 5],
          hours: 8, shiftHours: 8, rotaWeek: 1 }], schedules: [{ scheduleId: 'v1', effectiveFrom: null, current: true }] },
    ])
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    if (pathname === '/api/project-members') return ok(route, [
      { projectMemberId: 'owner', projectId, userId: 'demo-owner', projectRole: 'owner', memberStatus: 'active' },
    ])
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/settings`)
  await page.getByRole('region', { name: 'Workers' }).getByText('kim · 40 h a week').click()
  const panel = page.getByRole('group', { name: 'Worker kim' })
  await panel.locator('summary').filter({ hasText: 'Attendance' }).click()
  await expect(panel.getByRole('list', { name: 'Attendance records' }).getByRole('listitem')).toHaveCount(2)
  const choose = async (which: 'a1' | 'a2') => panel.getByRole('button', {
    name: `Cancel attendance from ${which === 'a1' ? '2041-09-02 08:00' : '2041-09-03 09:00'}`,
  }).click()
  return { panel, writes, release, choose }
}

test('an older cancellation response preserves the next target and reason opened after Keep', async ({ page }) => {
  const f = await cancellationFixture(page)
  try {
    await f.choose('a1')
    await f.panel.getByLabel('Cancel reason', { exact: true }).fill('  Wrong first stretch  ')
    await f.panel.getByRole('button', { name: 'Confirm cancel', exact: true }).click()
    await expect.poll(() => f.writes.length).toBe(1)
    await expect(f.panel.getByRole('button', { name: 'Confirm cancel', exact: true })).toBeDisabled()
    await f.panel.getByRole('button', { name: 'Keep', exact: true }).click()
    await f.choose('a2')
    await f.panel.getByLabel('Cancel reason', { exact: true }).fill('  Wrong second stretch  ')
    f.release()
    await expect(f.panel.getByRole('button', { name: 'Confirm cancel', exact: true })).toBeEnabled()
    await expect(f.panel.getByLabel('Cancel reason', { exact: true })).toHaveValue('  Wrong second stretch  ')
    await expect(f.panel.getByRole('list', { name: 'Attendance records' }).getByRole('listitem').first())
      .toContainText('cancelled by demo-owner: Wrong first stretch')
    await f.panel.getByRole('button', { name: 'Confirm cancel', exact: true }).click()
    await expect.poll(() => f.writes.length).toBe(2)
    expect(f.writes).toEqual([
      { attendanceId: 'a1', body: { reason: 'Wrong first stretch' } },
      { attendanceId: 'a2', body: { reason: 'Wrong second stretch' } },
    ])
  } finally {
    f.release()
  }
})

test('an unchanged cancellation closes after success and requires a nonblank reason', async ({ page }) => {
  const f = await cancellationFixture(page)
  try {
    await f.choose('a1')
    await f.panel.getByLabel('Cancel reason', { exact: true }).fill('   ')
    await f.panel.getByRole('button', { name: 'Confirm cancel', exact: true }).click()
    await expect(f.panel.getByRole('alert')).toHaveText('Give a reason when cancelling attendance.')
    expect(f.writes).toHaveLength(0)
    await f.panel.getByLabel('Cancel reason', { exact: true }).fill('  Wrong first stretch  ')
    await f.panel.getByRole('button', { name: 'Confirm cancel', exact: true }).click()
    await expect.poll(() => f.writes.length).toBe(1)
    await expect(f.panel.getByRole('button', { name: 'Confirm cancel', exact: true })).toBeDisabled()
    f.release()
    await expect(f.panel.getByLabel('Cancel reason', { exact: true })).toHaveCount(0)
    await expect(f.panel.getByRole('button', { name: 'Keep', exact: true })).toHaveCount(0)
    await expect(f.panel.getByRole('alert')).toHaveCount(0)
    await expect(f.panel.getByRole('list', { name: 'Attendance records' }).getByRole('listitem').first())
      .toContainText('cancelled by demo-owner: Wrong first stretch')
    expect(f.writes).toEqual([{ attendanceId: 'a1', body: { reason: 'Wrong first stretch' } }])
  } finally {
    f.release()
  }
})

test('a failed cancellation preserves its target and the current reason', async ({ page }) => {
  const f = await cancellationFixture(page, true)
  try {
    await f.choose('a1')
    await f.panel.getByLabel('Cancel reason', { exact: true }).fill('Original reason')
    await f.panel.getByRole('button', { name: 'Confirm cancel', exact: true }).click()
    await expect.poll(() => f.writes.length).toBe(1)
    await f.panel.getByLabel('Cancel reason', { exact: true }).fill('  Revised reason  ')
    f.release()
    await expect(f.panel.getByRole('alert')).toHaveText('Cancellation unavailable.')
    await expect(f.panel.getByRole('list', { name: 'Attendance records' }).getByRole('listitem').first()
      .getByLabel('Cancel reason', { exact: true })).toHaveValue('  Revised reason  ')
    await expect(f.panel.getByRole('button', { name: 'Confirm cancel', exact: true })).toBeEnabled()
    expect(f.writes).toEqual([{ attendanceId: 'a1', body: { reason: 'Original reason' } }])
  } finally {
    f.release()
  }
})

test('editing and restoring a cancellation reason preserves the newer draft on the same target', async ({ page }) => {
  const f = await cancellationFixture(page)
  try {
    await f.choose('a1')
    const reason = f.panel.getByLabel('Cancel reason', { exact: true })
    await reason.fill('Original reason')
    await f.panel.getByRole('button', { name: 'Confirm cancel', exact: true }).click()
    await expect.poll(() => f.writes.length).toBe(1)
    await reason.fill('Temporary reason')
    await reason.fill('Original reason')
    f.release()
    await expect(f.panel.getByRole('button', { name: 'Confirm cancel', exact: true })).toBeEnabled()
    const row = f.panel.getByRole('list', { name: 'Attendance records' }).getByRole('listitem').first()
    await expect(row.getByLabel('Cancel reason', { exact: true })).toHaveValue('Original reason')
    await expect(row).toContainText('cancelled by demo-owner: Original reason')
    expect(f.writes).toEqual([{ attendanceId: 'a1', body: { reason: 'Original reason' } }])
  } finally {
    f.release()
  }
})
