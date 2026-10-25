import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

// The suggested dates are shown and typed in the browser's zone.
test.use({ timezoneId: 'UTC' })

/** An order without equipment is planned by its step plan in its crew's time (docs/domain/worker-calendar.md LB25), mocked. */
test('a step plan dates an order that has no equipment', async ({ page }) => {
  const order = { workOrderId: 'wo-1', projectId: 'prj-e2e', workflowId: 'wf-1', workOrderNumber: 'WO-0042', workOrderTitle: 'Hand made',
    workOrderStatus: 'draft', priority: 'normal', targetItemId: null, targetQuantity: 10, plannedStartAt: '2041-05-06T09:00:00Z',
    plannedEndAt: null, actualStartAt: null, actualEndAt: null, instruction: null, assignedTo: null, approvedBy: null, approvedAt: null,
    producedQuantity: 0, runCount: 0, bomId: null, instructionUrl: null, equipmentId: null }
  const bare = { ...order, workOrderId: 'wo-2', workOrderNumber: 'WO-0043', workOrderTitle: 'No plan' }
  const comparison = (workOrderId: string, stepPlannedMinutes: number | null) => ({ workOrderId, plannedMinutes: null, plannedComplete: true,
    runs: 0, actualMinutes: 0, people: [], steps: stepPlannedMinutes ? [{ nodeId: 'p1', nodeName: 'Mix', sequenceNo: null, actualMinutes: 0,
      plannedMinutes: stepPlannedMinutes }] : [], wholeRunMinutes: 0, stepPlannedMinutes })
  const asked: string[] = []
  const saved: unknown[] = []

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const { pathname } = url
    if (await answerAuth(route, pathname)) return
    if (pathname === '/api/work-orders/wo-1' && request.method() === 'PUT') {
      saved.push(request.postDataJSON())
      return ok(route, { ...order, plannedEndAt: '2041-05-06T16:00:00Z' })
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/work-orders') return ok(route, [order, bare])
    if (pathname === '/api/work-orders/wo-1/labor-comparison') return ok(route, comparison('wo-1', 420))
    if (pathname === '/api/work-orders/wo-2/labor-comparison') return ok(route, comparison('wo-2', null))
    if (pathname === '/api/work-orders/wo-1/plan-suggestion') {
      asked.push(url.searchParams.get('from') ?? '')
      return ok(route, { workOrderId: 'wo-1', equipmentId: null, from: '2041-05-06T09:00:00Z', plannedStartAt: '2041-05-06T09:00:00Z',
        plannedEndAt: '2041-05-06T16:00:00Z', remainingQuantity: 10, capacityPerHour: null, productionHours: null, changeoverHours: 0,
        neededHours: 7, changeoverFrom: null, movedPast: [], crew: [{ userId: 'kim', calendarSet: true, hours: 7, sharePercent: 100 }],
        waitedForCrew: [], crewMovedPast: [], efficiencyPercent: null, laborMinutes: 420, laborHours: 7 })
    }
    if (pathname.endsWith('/crew')) return ok(route, { workOrderId: pathname.split('/')[3], plannedStartAt: null, plannedEndAt: null, members: [] })
    if (pathname.endsWith('/readiness')) {
      return ok(route, { workOrderId: pathname.split('/')[3], ready: true, remainingQuantity: 10, materials: [],
        checks: [{ code: 'bom', status: 'warn', message: 'No BOM; materials are neither planned nor checked.' }] })
    }
    if (pathname.endsWith('/waste-disposal-estimate')) {
      return ok(route, { workOrderId: 'wo-1', bomId: null, quantity: 10, disposalCost: 0, costComplete: true, lines: [], problem: null })
    }
    if (pathname.endsWith('/setup-comparison')) {
      return ok(route, { workOrderId: 'wo-1', equipmentId: null, plannedMinutes: null, plannedCost: null, runs: 0,
        actualMinutes: 0, actualCost: 0, actualCostComplete: true })
    }
    if (pathname.endsWith('/running-comparison')) {
      return ok(route, { workOrderId: 'wo-1', equipmentId: null, capacityPerHour: null, plannedMinutes: null, plannedCost: null,
        runs: 0, actualMinutes: 0, actualCost: 0, actualCostComplete: true })
    }
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/runs?view=work-orders')

  // Without equipment and without a step plan there is nothing to date by.
  await page.getByRole('row', { name: /No plan/ }).getByRole('button', { name: 'Readiness' }).click({ timeout: 15_000 })
  await expect(page.getByRole('region', { name: 'Labor planned and actual' })).toBeVisible()
  await expect(page.getByLabel('Plan dates')).toHaveCount(0)
  await page.getByRole('row', { name: /No plan/ }).getByRole('button', { name: 'Readiness' }).click()

  // With one, the crew's time decides.
  await page.getByRole('row', { name: /Hand made/ }).getByRole('button', { name: 'Readiness' }).click()
  const plan = page.getByLabel('Plan dates')
  await plan.getByRole('button', { name: 'Suggest dates' }).click()
  await expect(plan.getByRole('status')).toHaveText('Mon 2041-05-06 09:00 → Mon 2041-05-06 16:00 · needs 7 h · labor 7 h for 420 worker min')
  expect(asked).toEqual(['2041-05-06T09:00:00.000Z'])
  await plan.getByRole('button', { name: 'Use these dates' }).click()
  await expect.poll(() => saved.length).toBe(1)
  expect(saved[0]).toMatchObject({ plannedStartAt: '2041-05-06T09:00:00Z', plannedEndAt: '2041-05-06T16:00:00Z' })
})
