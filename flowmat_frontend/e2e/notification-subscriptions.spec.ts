import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * A person's notification choices in Project Settings (docs/domain/notification.md NS2, NS8) against a mocked API: each
 * box sends one choice, the days go with work orders due, and a refusal is shown.
 */
test('a member chooses what they hear about in a project', async ({ page }) => {
  const projectId = 'prj-subscribe'
  let rows = [
    { projectId, topic: 'stock_alert', subscribed: false, daysAhead: null as number | null },
    { projectId, topic: 'ncr_check_overdue', subscribed: false, daysAhead: null as number | null },
    { projectId, topic: 'work_order_due', subscribed: false, daysAhead: null as number | null },
  ]
  const sent: Record<string, unknown>[] = []

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (pathname === '/api/notification-subscriptions' && request.method() === 'PUT') {
      const body = request.postDataJSON() as { topic: string; subscribed: boolean; daysAhead?: number }
      sent.push(body)
      if ((body.daysAhead ?? 0) > 30) {
        return route.fulfill({ status: 400, contentType: 'application/json',
          body: JSON.stringify({ success: false, data: null, message: 'daysAhead must be whole days from 0 to 30.' }) })
      }
      rows = rows.map((row) => row.topic === body.topic
        ? { ...row, subscribed: body.subscribed, daysAhead: body.subscribed ? (body.daysAhead ?? null) : null } : row)
      return ok(route, rows)
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/notification-subscriptions') return ok(route, rows)
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'Asia/Seoul', version: 0 })
    if (pathname === '/api/project-members') {
      return ok(route, [{ projectMemberId: 'pm', projectId, userId: 'demo-owner', projectRole: 'owner', memberStatus: 'active' }])
    }
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Subscribe fixture', projectStatus: 'active' }])
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/settings`)
  const panel = page.getByRole('region', { name: 'Notifications' })
  const stock = panel.getByRole('checkbox', { name: 'Stock alerts' })
  await expect(stock).not.toBeChecked({ timeout: 15_000 })
  await expect(panel.getByLabel('Days before the planned end')).toHaveValue('3')

  await stock.check()
  await expect(stock).toBeChecked()
  await panel.getByLabel('Days before the planned end').fill('5')
  await panel.getByRole('checkbox', { name: 'Work orders due' }).check()
  await expect(panel.getByRole('checkbox', { name: 'Work orders due' })).toBeChecked()
  // A box shows the new choice at once; the days are saved only for a choice the server has, and the boxes stay disabled
  // until a save and the refetch after it are done.
  await expect(panel.getByRole('checkbox', { name: 'Work orders due' })).toBeEnabled()
  // Changing the days of a chosen topic sends them when the field is left.
  await panel.getByLabel('Days before the planned end').fill('7')
  await panel.getByLabel('Days before the planned end').blur()
  await expect.poll(() => sent.length).toBe(3)
  await expect(panel.getByRole('checkbox', { name: 'Work orders due' })).toBeEnabled()
  await panel.getByLabel('Days before the planned end').fill('40')
  await panel.getByLabel('Days before the planned end').blur()
  await expect(panel.getByRole('alert')).toHaveText('daysAhead must be whole days from 0 to 30.')
  await stock.uncheck()
  await expect(stock).not.toBeChecked()

  await expect.poll(() => sent).toEqual([
    { projectId, topic: 'stock_alert', subscribed: true },
    { projectId, topic: 'work_order_due', subscribed: true, daysAhead: 5 },
    { projectId, topic: 'work_order_due', subscribed: true, daysAhead: 7 },
    { projectId, topic: 'work_order_due', subscribed: true, daysAhead: 40 },
    { projectId, topic: 'stock_alert', subscribed: false },
  ])
})
