import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/** A work order's planned worker minutes by process beside what its runs recorded (docs/domain/worker-calendar.md LB23-LB24), mocked. */
test('the step plan stands beside the minutes each process took', async ({ page }) => {
  const order = { workOrderId: 'wo-1', projectId: 'prj-e2e', workflowId: 'wf-1', workOrderNumber: 'WO-0041', workOrderTitle: 'Planned steps',
    workOrderStatus: 'in_progress', priority: 'normal', targetItemId: null, targetQuantity: 10, plannedStartAt: null, plannedEndAt: null,
    actualStartAt: null, actualEndAt: null, instruction: null, assignedTo: null, approvedBy: null, approvedAt: null, producedQuantity: 0,
    runCount: 1, bomId: null, instructionUrl: null, equipmentId: null }
  const mix = { nodeId: 'p1', nodeName: 'Mix', sequenceNo: 1, actualMinutes: 300, plannedMinutes: null as number | null }
  let comparison: Record<string, unknown> = { workOrderId: 'wo-1', plannedMinutes: null, plannedComplete: true, runs: 1, actualMinutes: 320,
    people: [], steps: [mix], wholeRunMinutes: 20, stepPlannedMinutes: null }
  const sent: unknown[] = []

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (pathname === '/api/work-orders/wo-1/step-plan' && request.method() === 'PUT') {
      const body = request.postDataJSON() as { steps: { nodeId: string; plannedMinutes: number }[] }
      sent.push(body)
      comparison = { ...comparison, stepPlannedMinutes: 420,
        steps: [{ ...mix, plannedMinutes: 240 }, { nodeId: 'p2', nodeName: 'Bake', sequenceNo: null, actualMinutes: 0, plannedMinutes: 180 }] }
      return ok(route, comparison)
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/work-orders') return ok(route, [order])
    if (pathname === '/api/work-orders/wo-1/labor-comparison') return ok(route, comparison)
    if (pathname === '/api/processes') {
      return ok(route, [{ processId: 'p1', processName: 'Mix', nodeType: 'process' }, { processId: 'g1', processName: 'Which?', nodeType: 'exclusive' },
        { processId: 'p2', processName: 'Bake', nodeType: 'process' }])
    }
    if (pathname === '/api/work-orders/wo-1/crew') return ok(route, { workOrderId: 'wo-1', plannedStartAt: null, plannedEndAt: null, members: [] })
    if (pathname === '/api/work-orders/wo-1/readiness') {
      return ok(route, { workOrderId: 'wo-1', ready: true, remainingQuantity: 10, materials: [],
        checks: [{ code: 'bom', status: 'warn', message: 'No BOM; materials are neither planned nor checked.' }] })
    }
    if (pathname === '/api/work-orders/wo-1/waste-disposal-estimate') {
      return ok(route, { workOrderId: 'wo-1', bomId: null, quantity: 10, disposalCost: 0, costComplete: true, lines: [], problem: null })
    }
    if (pathname === '/api/work-orders/wo-1/setup-comparison') {
      return ok(route, { workOrderId: 'wo-1', equipmentId: null, plannedMinutes: null, plannedCost: null, runs: 0,
        actualMinutes: 0, actualCost: 0, actualCostComplete: true })
    }
    if (pathname === '/api/work-orders/wo-1/running-comparison') {
      return ok(route, { workOrderId: 'wo-1', equipmentId: null, capacityPerHour: null, plannedMinutes: null, plannedCost: null,
        runs: 0, actualMinutes: 0, actualCost: 0, actualCostComplete: true })
    }
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/runs?view=work-orders')
  await page.getByRole('row', { name: /Planned steps/ }).getByRole('button', { name: 'Readiness' }).click({ timeout: 15_000 })
  const labor = page.getByRole('region', { name: 'Labor planned and actual' })
  await expect(labor).toContainText('By step: Mix 300, whole run 20')

  // Every process but the gateway; minutes are whole.
  await labor.getByRole('button', { name: 'Plan by step' }).click()
  const form = labor.getByRole('form', { name: 'Step plan' })
  await expect(form.getByRole('spinbutton')).toHaveCount(2)
  await form.getByLabel('Mix planned minutes').fill('2.5')
  await form.getByRole('button', { name: 'Save step plan' }).click()
  await expect(form.getByRole('alert')).toHaveText('Mix: enter whole minutes from 1 to 100000, or leave it empty.')
  await form.getByLabel('Mix planned minutes').fill('240')
  await form.getByLabel('Bake planned minutes').fill('180')
  await form.getByRole('button', { name: 'Save step plan' }).click()
  await expect(form).toHaveCount(0)
  expect(sent).toEqual([{ steps: [{ nodeId: 'p1', plannedMinutes: 240 }, { nodeId: 'p2', plannedMinutes: 180 }] }])
  await expect(labor).toContainText('By step: Mix 300 of 240 planned, Bake 0 of 180 planned, whole run 20')
  await expect(labor).toContainText('Planned by step: 420 min')

  // Opened again, it starts at the plan.
  await labor.getByRole('button', { name: 'Plan by step' }).click()
  await expect(form.getByLabel('Mix planned minutes')).toHaveValue('240')
  await expect(form.getByLabel('Bake planned minutes')).toHaveValue('180')
})
