import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/** An open count plan is given to a member (docs/domain/stock-count.md P9-P10), mocked. */
test('an open count plan is given to a member', async ({ page }) => {
  const projectId = 'prj-count-assignee'
  const plan = { planId: 'plan-1', projectId, blind: true, status: 'open', note: 'Aisle 3', createdBy: 'demo-owner', createdAt: '2041-09-01T09:00:00Z',
    submittedBy: null, submittedAt: null, countId: null, assignedTo: null as string | null,
    lines: [{ lineId: 'l1', inventoryId: 'i1', itemId: 'flour', lotId: null, location: 'WH-A', baselineQuantity: 5, checkpointQuantity: 5,
      countedQuantity: null, requiresRecount: false, countedBy: null, countedAt: null, entryVersion: 0 }] }
  const sent: unknown[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/inventory-count-plans/plan-1/assignee') {
      const body = request.postDataJSON() as { assignedTo: string | null }
      sent.push(body)
      plan.assignedTo = body.assignedTo
      return ok(route, plan)
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/inventory-count-plans') return ok(route, [plan])
    if (pathname === '/api/project-members') {
      return ok(route, [{ projectMemberId: 'm1', projectId, userId: 'demo-owner', projectRole: 'owner', memberStatus: 'active', joinedAt: null },
        { projectMemberId: 'm2', projectId, userId: 'counter-kim', projectRole: 'editor', memberStatus: 'active', joinedAt: null },
        { projectMemberId: 'm3', projectId, userId: 'gone-lee', projectRole: 'editor', memberStatus: 'removed', joinedAt: null }])
    }
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Count', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Count', ownerId: 'demo-owner' })
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=count-plans`)
  await page.getByLabel('Open count plan').selectOption({ label: 'Aisle 3 · open' }, { timeout: 15_000 })
  const selected = page.getByRole('region', { name: 'Selected count plan' })
  const assigned = selected.getByLabel('Assigned to')
  // Only active members are offered.
  await expect(assigned.getByRole('option')).toHaveText(['No one', 'demo-owner (me)', 'counter-kim'])
  await assigned.selectOption('counter-kim')
  await expect(page.getByRole('status')).toHaveText('Count plan saved.')
  expect(sent).toEqual([{ assignedTo: 'counter-kim' }])
  await expect(assigned).toHaveValue('counter-kim')
  await assigned.selectOption('demo-owner')
  await expect(page.getByLabel('Open count plan').getByRole('option', { name: 'Aisle 3 · open · yours' })).toHaveCount(1)
})
