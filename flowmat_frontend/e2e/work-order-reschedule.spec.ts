import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

test.use({ timezoneId: 'Asia/Seoul', locale: 'en-US' })

for (const scenario of ['approved-retry', 'running', 'viewer', 'stale'] as const) {
  test(`work order schedule: ${scenario}`, async ({ page }) => {
    const projectId = 'prj-reschedule'
    const order = { workOrderId: 'wo-reschedule', projectId, workflowId: null, workOrderNumber: 'WO-RESCHEDULE',
      workOrderTitle: 'Reschedule batch', workOrderStatus: scenario === 'running' ? 'in_progress' : 'approved', priority: 'normal',
      targetItemId: null, targetQuantity: 1, plannedStartAt: '2030-01-07T00:00:33.123456Z', plannedEndAt: '2030-01-07T08:00:00Z',
      actualStartAt: scenario === 'running' ? '2030-01-07T00:00:00Z' : null, actualEndAt: null, instruction: null,
      assignedTo: null, approvedBy: 'demo-owner', approvedAt: '2029-12-01T00:00:00Z', producedQuantity: 0, runCount: 0, bomId: null }
    const history: unknown[] = []
    const requests: Record<string, string | null>[] = []
    let release!: () => void
    const gate = new Promise<void>((resolve) => { release = resolve })
    await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
      const request = route.request()
      const { pathname } = new URL(request.url())
      if (await answerAuth(route, pathname)) return
      if (pathname === '/api/project-members') return ok(route, [{ projectMemberId: 'pm-owner', projectId,
        userId: 'demo-owner', projectRole: scenario === 'viewer' ? 'viewer' : 'owner', memberStatus: 'active', joinedAt: null }])
      if (pathname === '/api/work-orders') return ok(route, [order])
      if (pathname === '/api/work-orders/wo-reschedule/reschedules') {
        if (request.method() === 'GET') return ok(route, history)
        const body = request.postDataJSON() as (typeof requests)[number]
        requests.push(body)
        if (scenario === 'stale') {
          order.plannedEndAt = '2030-01-09T08:00:00Z'
          return route.fulfill({ status: 409, contentType: 'application/json',
            body: JSON.stringify({ success: false, data: null, message: 'The planned dates changed; reload the order before rescheduling.' }) })
        }
        if (!history.length) {
          history.push({ changeId: 'change-1', workOrderId: order.workOrderId, ...body,
            previousPlannedStartAt: order.plannedStartAt, previousPlannedEndAt: order.plannedEndAt,
            changedBy: 'demo-owner', changedAt: '2026-10-05T12:00:00Z' })
          order.plannedStartAt = body.plannedStartAt!
          order.plannedEndAt = body.plannedEndAt!
        }
        if (scenario === 'approved-retry' && requests.length === 1) { await gate; return route.abort('failed') }
        return ok(route, { workOrder: order, change: history[0] })
      }
      if (request.method() !== 'GET') throw new Error(`Unexpected write: ${request.method()} ${pathname}`)
      return ok(route, [])
    })
    await mockedLogin(page)
    await page.goto(`/projects/${projectId}/runs?view=work-orders`)
    await page.getByRole('row', { name: /Reschedule batch/ }).getByRole('button', { name: 'Schedule', exact: true }).click()
    const panel = page.getByRole('region', { name: 'Schedule WO-RESCHEDULE' })
    if (scenario === 'viewer') {
      await expect(panel).toContainText('Only the project owner')
      await expect(panel.getByRole('button', { name: 'Change schedule' })).toHaveCount(0)
      expect(requests).toHaveLength(0)
      return
    }
    const start = panel.getByLabel('New planned start')
    if (scenario === 'running') await expect(start).toBeDisabled()
    else await start.fill('2030-01-08T09:00')
    await panel.getByLabel('New planned end').fill('2030-01-08T17:00')
    await panel.getByLabel('Reason *').fill('Supplier delay')
    await panel.getByRole('button', { name: 'Change schedule', exact: true }).click()
    if (scenario === 'stale') {
      await expect(panel.getByRole('alert')).toContainText('planned dates changed')
      await expect(page.getByRole('row', { name: /Reschedule batch/ })).toContainText('1/9/30')
      await panel.getByRole('button', { name: 'Reload current plan' }).click()
      await expect(panel.getByLabel('New planned end')).toHaveValue('2030-01-09T17:00')
      return
    }
    if (scenario === 'approved-retry') {
      await expect(panel.getByLabel('Reason *')).toBeDisabled()
      release()
      await expect(panel).toContainText('The result is unconfirmed.')
      await expect(panel.getByLabel('Reason *')).toBeDisabled()
      await panel.getByRole('button', { name: 'Retry schedule change' }).click()
      await expect.poll(() => requests.length).toBe(2)
      expect(requests[0]).toEqual(requests[1])
    }
    await expect(panel.getByRole('status')).toHaveText('Schedule changed.')
    await expect(panel.getByLabel('Reason *')).toHaveValue('')
    await expect(panel.getByRole('listitem')).toHaveCount(1)
    await expect(panel.getByRole('listitem')).toContainText('demo-owner: Supplier delay')
    if (scenario === 'running') expect(requests[0].plannedStartAt).toBe('2030-01-07T00:00:33.123456Z')
  })
}
