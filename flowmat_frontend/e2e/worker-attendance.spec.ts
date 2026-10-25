import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

// Times are shown in the browser's zone.
test.use({ timezoneId: 'UTC' })

const projectId = 'prj-attendance'
const shift = { shiftId: 'shift-1', shiftStart: '07:00', shiftEnd: '16:00', workDays: [1, 2, 3, 4, 5], hours: 9, shiftHours: 9, rotaWeek: 1 }

test('a worker\'s attendance clocks in and out, stands beside the time on runs and cancels with a reason', async ({ page }) => {
  const writes: { path: string; body: Record<string, string> }[] = []
  const done = { attendanceId: 'a1', workDate: '2041-09-02', clockIn: '2041-09-02T08:00:00Z', clockOut: '2041-09-02T16:30:00Z' as string | null,
    minutes: 510 as number | null, note: 'Day' as string | null, recordedBy: 'kim', recordedAt: '2041-09-02T16:31:00Z', cancelled: false,
    cancelledBy: null as string | null, cancelledAt: null, cancelReason: null as string | null }
  let open: typeof done | null = null
  let lines = [done]
  const attendance = () => ({ projectId, userId: 'kim', from: '2041-08-28', to: '2041-09-03', today: '2041-09-03', open,
    days: [{ date: '2041-09-02', attendedMinutes: 510 }], lines })
  const base = `/api/projects/${projectId}/workers/kim/attendance`
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname.startsWith(base)) {
      const body = request.postDataJSON() as Record<string, string>
      writes.push({ path: pathname.slice(base.length) || '/', body })
      if (pathname === base) {
        open = { ...done, attendanceId: 'a2', workDate: '2041-09-03', clockIn: body.clockIn, clockOut: null, minutes: null, note: null }
        lines = [done, open]
      } else if (pathname.endsWith('/clock-out')) {
        lines = lines.map((line) => line.attendanceId === 'a2' ? { ...line, clockOut: '2041-09-03T10:00:00Z', minutes: 60 } : line)
        open = null
      } else if (pathname.endsWith('/cancel')) {
        lines = lines.map((line) => line.attendanceId === 'a1' ? { ...line, cancelled: true, cancelledBy: 'demo-owner', cancelReason: body.reason } : line)
      }
      return ok(route, attendance())
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === base) return ok(route, attendance())
    if (pathname === `/api/projects/${projectId}/workers/kim/attendance-comparison`) return ok(route, { projectId, userId: 'kim',
      from: '2041-08-28', to: '2041-09-03', attendedMinutes: 510, laborMinutes: 390, days: [
        { date: '2041-09-01', attendedMinutes: 0, laborMinutes: 0, differenceMinutes: 0 },
        { date: '2041-09-02', attendedMinutes: 510, laborMinutes: 390, differenceMinutes: 120 }] })
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
  const panel = page.getByRole('group', { name: 'Worker kim' })
  await panel.locator('summary').filter({ hasText: 'Attendance' }).click()
  await expect(panel.getByRole('list', { name: 'Attendance against worked time' }).getByRole('listitem'))
    .toHaveText(['2041-09-02 · attended 8 h 30 min · on runs 6 h 30 min · 2 h not on runs'])
  await expect(panel.getByLabel('At work')).toContainText('Not clocked in.')

  await panel.getByRole('button', { name: 'Clock in now' }).click()
  await expect(panel.getByLabel('At work')).toContainText('Clocked in since')
  await panel.getByRole('button', { name: 'Clock out now' }).click()
  await expect(panel.getByLabel('At work')).toContainText('Not clocked in.')

  await panel.getByRole('button', { name: 'Cancel attendance from 2041-09-02 08:00' }).click()
  await panel.getByRole('button', { name: 'Confirm cancel' }).click()
  await expect(panel.getByRole('alert')).toHaveText('Give a reason when cancelling attendance.')
  await panel.getByLabel('Cancel reason').fill('Wrong day')
  await panel.getByRole('button', { name: 'Confirm cancel' }).click()
  await expect(panel.getByRole('list', { name: 'Attendance records' }).getByRole('listitem').first())
    .toHaveText('2041-09-02 08:00 – 16:30 · 8 h 30 min · Day · by kim · cancelled by demo-owner: Wrong day')

  expect(writes.map((write) => write.path)).toEqual(['/', '/a2/clock-out', '/a1/cancel'])
  expect(writes[0].body).toMatchObject({ requestId: expect.any(String), clockIn: expect.any(String) })
  expect(writes[2].body).toEqual({ reason: 'Wrong day' })
})
