import { readFile } from 'node:fs/promises'
import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/** A worker's attendance over a chosen period is downloaded as CSV (docs/domain/worker-calendar.md AT9), mocked. */
test.use({ timezoneId: 'UTC' })

const projectId = 'prj-attendance-csv'
const shift = { shiftId: 'shift-1', shiftStart: '07:00', shiftEnd: '16:00', workDays: [1, 2, 3, 4, 5], hours: 9, shiftHours: 9, rotaWeek: 1 }

test('the owner downloads a worker\'s attendance for a month', async ({ page }) => {
  const stretch = (attendanceId: string, day: string, extra = {}) => ({ attendanceId, workDate: day, clockIn: `${day}T08:00:00Z`,
    clockOut: `${day}T16:30:00Z`, minutes: 510, note: null, recordedBy: 'kim', recordedAt: `${day}T16:31:00Z`, cancelled: false,
    cancelledBy: null, cancelledAt: null, cancelReason: null, ...extra })
  const asked: string[] = []
  const base = `/api/projects/${projectId}/workers/kim/attendance`
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const { pathname } = url
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === base) {
      const from = url.searchParams.get('from')
      if (from) asked.push(`${from}..${url.searchParams.get('to')}`)
      const lines = from ? [stretch('a9', '2041-08-29'), stretch('a1', '2041-08-04', { note: 'Day', cancelled: true, cancelledBy: 'demo-owner',
        cancelReason: 'Wrong day' })] : [stretch('a1', '2041-09-02')]
      return ok(route, { projectId, userId: 'kim', from: from ?? '2041-08-28', to: url.searchParams.get('to') ?? '2041-09-03', today: '2041-09-03',
        open: null, days: [], lines })
    }
    if (pathname === `/api/projects/${projectId}/workers/kim/attendance-comparison`) return ok(route, { projectId, userId: 'kim',
      from: '2041-08-28', to: '2041-09-03', attendedMinutes: 0, laborMinutes: 0, days: [] })
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

  // The period starts as the 7 days shown; the first day must come first.
  const form = panel.getByRole('form', { name: 'Attendance CSV' })
  await expect(form.getByLabel('Download from')).toHaveValue('2041-08-28')
  await expect(form.getByLabel('Download to')).toHaveValue('2041-09-03')
  await form.getByLabel('Download from').fill('2041-09-05')
  await form.getByRole('button', { name: 'Download CSV' }).click()
  await expect(form.getByRole('alert')).toHaveText('The first day comes after the last.')
  expect(asked).toEqual([])

  await form.getByLabel('Download from').fill('2041-08-01')
  await form.getByLabel('Download to').fill('2041-08-31')
  const download = page.waitForEvent('download')
  await form.getByRole('button', { name: 'Download CSV' }).click()
  const file = await download
  expect(asked).toEqual(['2041-08-01..2041-08-31'])
  expect(file.suggestedFilename()).toBe('attendance-kim-2041-08-01-2041-08-31.csv')
  expect((await readFile(await file.path(), 'utf8')).split('\r\n')).toEqual([
    '\ufeffwork_date,clock_in,clock_out,minutes,note,recorded_by,cancelled,cancelled_by,cancel_reason',
    '2041-08-04,2041-08-04 08:00,2041-08-04 16:30,510,Day,kim,Y,demo-owner,Wrong day',
    '2041-08-29,2041-08-29 08:00,2041-08-29 16:30,510,,kim,N,,',
    '',
  ])
  await expect(form.getByRole('alert')).toHaveCount(0)
})
