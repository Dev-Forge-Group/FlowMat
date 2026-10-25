import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

const projectId = 'prj-history-older'
const shift = { shiftId: 'shift-1', shiftStart: '07:00', shiftEnd: '16:00', workDays: [1, 2, 3, 4, 5], hours: 9, shiftHours: 9, rotaWeek: 1 }
const line = (n: number) => ({ historyId: `h${String(n).padStart(3, '0')}`, scheduleId: 'version-1', effectiveFrom: null,
  action: 'saved', reason: `Save ${n}`, shifts: [shift], rota: null, savedBy: 'demo-owner',
  savedAt: new Date(Date.UTC(2041, 8, 1, 0, 0, 0) + n * 60_000).toISOString() })

test('a worker\'s version history shows its older entries page by page beyond the latest 100', async ({ page }) => {
  const searches: string[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (await answerAuth(route, url.pathname)) return
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    // 103 saves: the list answers the newest 100 (103 down to 4), the search the rest after the cursor.
    if (url.pathname === `/api/projects/${projectId}/workers/kim/schedule-history`) {
      return ok(route, Array.from({ length: 100 }, (_, index) => line(103 - index)))
    }
    if (url.pathname === `/api/projects/${projectId}/workers/kim/schedule-history/search`) {
      searches.push(`${url.searchParams.get('cursor')} ${url.searchParams.get('limit')}`)
      return url.searchParams.get('cursor') === 'h004'
        ? ok(route, { entries: [line(3), line(2)], nextCursor: 'h002' })
        : ok(route, { entries: [line(1)], nextCursor: null })
    }
    if (url.pathname === `/api/projects/${projectId}/workers`) return ok(route, [
      { projectId, userId: 'demo-owner', owner: true, calendarSet: false, weeklyHours: 0, shifts: [], daysOff: [], days: [] },
      { projectId, userId: 'kim', owner: false, calendarSet: true, weeklyHours: 45, daysOff: [], days: [], rota: null,
        shifts: [shift], schedules: [{ scheduleId: 'version-1', effectiveFrom: null, current: true }] },
    ])
    if (url.pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    if (url.pathname === '/api/project-members') return ok(route, [
      { projectMemberId: 'member-1', projectId, userId: 'demo-owner', projectRole: 'owner', memberStatus: 'active' },
    ])
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/settings`)
  await page.getByRole('region', { name: 'Workers' }).getByText('kim · 45 h a week').click()
  const panel = page.getByRole('group', { name: 'Worker kim' })
  const history = panel.locator('details').filter({ has: page.locator(':scope > summary', { hasText: 'Version history' }) })
  await history.locator(':scope > summary').click()
  const items = history.getByRole('list', { name: 'Version history' }).getByRole('listitem')
  await expect(items).toHaveCount(100)
  await history.getByRole('button', { name: 'Show older' }).click()
  await expect(items).toHaveCount(102)
  await history.getByRole('button', { name: 'Show older' }).click()
  await expect(items).toHaveCount(103)
  await expect(items.last()).toContainText('Save 1')
  await expect(history.getByRole('button', { name: 'Show older' })).toHaveCount(0)
  expect(searches).toEqual(['h004 100', 'h002 100'])
})
