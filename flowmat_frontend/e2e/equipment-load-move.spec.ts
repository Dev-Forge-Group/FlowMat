import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

// The new start is typed in the browser's time.
test.use({ timezoneId: 'UTC' })

const projectId = 'prj-load-move'
const base = { projectId, workflowId: 'wf', targetItemId: null, targetQuantity: 10, priority: 'normal', actualStartAt: null,
  actualEndAt: null, instruction: 'Mix well', assignedTo: null, approvedBy: null, approvedAt: null, producedQuantity: 0,
  runCount: 0, bomId: null, instructionUrl: null, equipmentId: 'oven' }

async function mockApi(page: Page, role: 'owner' | 'editor', writes: { method: string; path: string; body: unknown }[]) {
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (await answerAuth(route, url.pathname)) return
    if (request.method() !== 'GET') {
      writes.push({ method: request.method(), path: url.pathname, body: request.postDataJSON() })
      return ok(route, url.pathname.endsWith('/reschedules') ? { workOrder: {}, change: {} } : {})
    }
    if (url.pathname === '/api/project-members') return ok(route, [
      { projectMemberId: 'm1', projectId, userId: 'demo-owner', projectRole: role, memberStatus: 'active' },
    ])
    if (url.pathname === '/api/work-orders') return ok(route, [
      { ...base, workOrderId: 'd', workOrderNumber: 'WO-D', workOrderTitle: 'Draft loaves', workOrderStatus: 'draft',
        plannedStartAt: '2030-01-07T09:00:00Z', plannedEndAt: '2030-01-07T13:00:00Z' },
      { ...base, workOrderId: 'a', workOrderNumber: 'WO-A', workOrderTitle: 'Approved loaves', workOrderStatus: 'approved',
        plannedStartAt: '2030-01-08T09:00:00Z', plannedEndAt: '2030-01-08T11:00:00Z' },
    ])
    if (url.pathname === '/api/equipments') return ok(route, [{ equipmentId: 'oven', projectId, equipmentCode: 'OVEN',
      equipmentName: 'Oven', equipmentType: 'machine', equipmentStatus: 'active', details: { capacityPerHour: 10 } }])
    if (url.pathname === '/api/equipment-load') {
      const order = (id: string, status: string, start: string, end: string) => ({ workOrderId: id, workOrderNumber: `WO-${id.toUpperCase()}`,
        workOrderTitle: 'Loaves', workOrderStatus: status, targetItemId: null, targetItemCode: null, plannedStartAt: start,
        plannedEndAt: end, remainingQuantity: 10, changeoverMinutes: null, neededHours: 1, hoursInWindow: 1 })
      return ok(route, { from: url.searchParams.get('from'), to: url.searchParams.get('to'), equipment: [{ equipmentId: 'oven',
        equipmentCode: 'OVEN', equipmentName: 'Oven', equipmentStatus: 'active', capacityPerHour: 10, calendarSet: true,
        availableHours: 40, downtimeHours: 0, plannedHours: 1, draftHours: 1, loadPercent: 2.5, overloaded: false,
        unplannedOrders: 0, unmeasuredOrders: 0, changeovers: null, orders: [
          order('d', 'draft', '2030-01-07T09:00:00Z', '2030-01-07T13:00:00Z'),
          order('a', 'approved', '2030-01-08T09:00:00Z', '2030-01-08T11:00:00Z')] }] })
    }
    return ok(route, [])
  })
}

async function openBoard(page: Page) {
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=equipment`)
  await page.getByText('Load by week', { exact: true }).click()
  return page.getByRole('region', { name: 'Equipment load', exact: true })
}

test('the owner moves a draft and an approved order from the load board, keeping their length', async ({ page }) => {
  const writes: { method: string; path: string; body: unknown }[] = []
  await mockApi(page, 'owner', writes)
  const board = await openBoard(page)
  await board.getByRole('button', { name: 'Move WO-D' }).click()
  await expect(board.getByLabel('New start of WO-D')).toHaveValue('2030-01-07T09:00')
  await board.getByLabel('New start of WO-D').fill('2030-01-09T08:00')
  await board.getByRole('button', { name: 'Save move' }).click()
  await expect(board.getByRole('button', { name: 'Move WO-D' })).toBeVisible()

  await board.getByRole('button', { name: 'Move WO-A' }).click()
  await board.getByLabel('New start of WO-A').fill('2030-01-10T09:00')
  await board.getByRole('button', { name: 'Save move' }).click()
  await expect(board.getByRole('alert')).toHaveText('Give a reason for moving an approved order.')
  await board.getByLabel('Reason for moving WO-A').fill('Oven service on Tuesday')
  await board.getByRole('button', { name: 'Save move' }).click()
  await expect(board.getByRole('button', { name: 'Move WO-A' })).toBeVisible()

  expect(writes).toHaveLength(2)
  expect(writes[0]).toMatchObject({ method: 'PUT', path: '/api/work-orders/d', body: { workOrderTitle: 'Draft loaves',
    instruction: 'Mix well', targetQuantity: 10, plannedStartAt: '2030-01-09T08:00:00.000Z', plannedEndAt: '2030-01-09T12:00:00.000Z' } })
  expect(writes[1]).toMatchObject({ method: 'POST', path: '/api/work-orders/a/reschedules', body: {
    expectedPlannedStartAt: '2030-01-08T09:00:00Z', expectedPlannedEndAt: '2030-01-08T11:00:00Z',
    plannedStartAt: '2030-01-10T09:00:00.000Z', plannedEndAt: '2030-01-10T11:00:00.000Z', reason: 'Oven service on Tuesday' } })
})

test('an editor moves drafts but not approved orders', async ({ page }) => {
  await mockApi(page, 'editor', [])
  const board = await openBoard(page)
  await expect(board.getByRole('button', { name: 'Move WO-D' })).toBeVisible()
  await expect(board.getByRole('button', { name: 'Move WO-A' })).toHaveCount(0)
})
