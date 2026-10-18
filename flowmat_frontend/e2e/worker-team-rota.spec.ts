import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * A team rota in Project Settings (docs/domain/worker-calendar.md WC51-WC54) against a mocked API: the owner gives Kim's
 * two-week rota to Lee in step with Kim and to Park a week further on.
 */
test('the owner gives a rota to a team, each member some weeks further on', async ({ page }) => {
  const projectId = 'prj-team'
  const shifts = [
    { shiftId: 's1', shiftStart: '06:00', shiftEnd: '14:00', workDays: [1, 2, 3, 4, 5], hours: 8, rotaWeek: 1 },
    { shiftId: 's2', shiftStart: '14:00', shiftEnd: '22:00', workDays: [1, 2, 3, 4, 5], hours: 8, rotaWeek: 2 },
  ]
  const kim = { projectId, userId: 'kim', owner: false, calendarSet: true, weeklyHours: 40, daysOff: [], days: [], shifts,
    rota: { weeks: 2, startsOn: '2041-09-02', currentWeek: 1 } }
  const worker = (userId: string) => ({ projectId, userId, owner: false, calendarSet: false, weeklyHours: 0, daysOff: [], days: [],
    shifts: [], rota: null })
  const owner = { ...worker('demo-owner'), owner: true }
  const sent: unknown[] = []

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === `/api/projects/${projectId}/workers/kim/shifts/copy`) {
      const body = request.postDataJSON() as { targets: { userId: string; offsetWeeks: number }[] }
      sent.push(body)
      return ok(route, body.targets.map((target) => ({ ...kim, userId: target.userId })))
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === `/api/projects/${projectId}/workers`) return ok(route, [owner, kim, worker('lee'), worker('park')])
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'Asia/Seoul', version: 0 })
    if (pathname === '/api/project-members') {
      return ok(route, [{ projectMemberId: 'pm', projectId, userId: 'demo-owner', projectRole: 'owner', memberStatus: 'active' }])
    }
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/settings`)
  const panel = page.getByRole('region', { name: 'Workers' })
  await panel.getByText('kim · 40 h a week on average').click({ timeout: 15_000 })
  const team = panel.getByRole('group', { name: 'Worker kim' }).getByRole('group', { name: 'Copy shifts to other workers' })
  const button = team.getByRole('button', { name: 'Copy to 0 workers' })
  await expect(button).toBeDisabled()
  await team.getByRole('checkbox', { name: 'Copy to lee' }).check()
  await team.getByRole('checkbox', { name: 'Copy to park' }).check()
  await expect(team.getByRole('combobox', { name: 'lee rota week' })).toHaveValue('1')
  await team.getByRole('combobox', { name: 'park rota week' }).selectOption('2')
  await team.getByRole('button', { name: 'Copy to 2 workers' }).click()
  await expect(team.getByRole('status')).toHaveText("Copied kim's shifts to lee, park.")
  expect(sent).toEqual([{ targets: [{ userId: 'lee', offsetWeeks: 0 }, { userId: 'park', offsetWeeks: 1 }] }])
  await expect(team.getByRole('button', { name: 'Copy to 0 workers' })).toBeDisabled()
})
