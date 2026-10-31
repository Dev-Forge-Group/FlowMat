import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/** A step plan's process split among the crew beside what each recorded on it (docs/domain/worker-calendar.md LB29-LB30), mocked. */
test('a process split among people stands beside each person\'s minutes', async ({ page }) => {
  const order = { workOrderId: 'wo-1', projectId: 'prj-e2e', workflowId: 'wf-1', workOrderNumber: 'WO-0042', workOrderTitle: 'Split steps',
    workOrderStatus: 'in_progress', priority: 'normal', targetItemId: null, targetQuantity: 10, plannedStartAt: null, plannedEndAt: null,
    actualStartAt: null, actualEndAt: null, instruction: null, assignedTo: null, approvedBy: null, approvedAt: null, producedQuantity: 0,
    runCount: 1, bomId: null, instructionUrl: null, equipmentId: null }
  const people = [{ userId: 'kim', sharePercent: 50, plannedMinutes: null, actualMinutes: 100 },
    { userId: 'lee', sharePercent: 100, plannedMinutes: null, actualMinutes: 0 },
    { userId: 'dan', sharePercent: null, plannedMinutes: null, actualMinutes: 20 }]
  const mix = { nodeId: 'p1', nodeName: 'Mix', sequenceNo: 1, actualMinutes: 120, plannedMinutes: null as number | null,
    people: [{ userId: 'kim', plannedMinutes: null, actualMinutes: 100 }, { userId: 'dan', plannedMinutes: null, actualMinutes: 20 }] }
  let comparison: Record<string, unknown> = { workOrderId: 'wo-1', plannedMinutes: null, plannedComplete: false, runs: 1, actualMinutes: 120,
    people, steps: [mix], wholeRunMinutes: 0, stepPlannedMinutes: null }
  const sent: unknown[] = []

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (pathname === '/api/work-orders/wo-1/step-plan' && request.method() === 'PUT') {
      sent.push(request.postDataJSON())
      comparison = { ...comparison, stepPlannedMinutes: 360, steps: [{ ...mix, plannedMinutes: 180,
        people: [{ userId: 'kim', plannedMinutes: 120, actualMinutes: 100 }, { userId: 'lee', plannedMinutes: 60, actualMinutes: 0 },
          { userId: 'dan', plannedMinutes: null, actualMinutes: 20 }] },
      { nodeId: 'p2', nodeName: 'Bake', sequenceNo: null, actualMinutes: 0, plannedMinutes: 180, people: [] }] }
      return ok(route, comparison)
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/work-orders') return ok(route, [order])
    if (pathname === '/api/work-orders/wo-1/labor-comparison') return ok(route, comparison)
    if (pathname === '/api/processes') {
      return ok(route, [{ processId: 'p1', processName: 'Mix', nodeType: 'process' }, { processId: 'p2', processName: 'Bake', nodeType: 'process' }])
    }
    if (pathname === '/api/work-orders/wo-1/crew') {
      return ok(route, { workOrderId: 'wo-1', plannedStartAt: null, plannedEndAt: null, members: [
        { userId: 'kim', sharePercent: 50, calendarSet: null, hoursInWindow: null },
        { userId: 'lee', sharePercent: 100, calendarSet: null, hoursInWindow: null }] })
    }
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
  await page.getByRole('row', { name: /Split steps/ }).getByRole('button', { name: 'Readiness' }).click({ timeout: 15_000 })
  const labor = page.getByRole('region', { name: 'Labor planned and actual' })
  // Without a split plan, who recorded time on a step is not listed by step.
  await expect(labor).toContainText('By step: Mix 120')
  await expect(labor).not.toContainText('kim 100')

  // Each process, and each person of the crew on it.
  await labor.getByRole('button', { name: 'Plan by step' }).click()
  const form = labor.getByRole('form', { name: 'Step plan' })
  await expect(form.getByRole('spinbutton')).toHaveCount(6)
  await form.getByLabel('Mix · kim minutes').fill('120')
  await form.getByLabel('Mix · lee minutes').fill('60')
  await expect(form.getByLabel('Mix planned minutes')).toHaveAttribute('placeholder', '180')
  await form.getByLabel('Mix planned minutes').fill('200')
  await form.getByRole('button', { name: 'Save step plan' }).click()
  await expect(form.getByRole('alert')).toHaveText("Mix: the people's minutes add up to 180, not 200; leave Mix's minutes empty or make them agree.")
  await form.getByLabel('Mix planned minutes').fill('')
  await form.getByLabel('Bake planned minutes').fill('180')
  await form.getByRole('button', { name: 'Save step plan' }).click()
  await expect(form).toHaveCount(0)
  expect(sent).toEqual([{ steps: [{ nodeId: 'p1', people: [{ userId: 'kim', plannedMinutes: 120 }, { userId: 'lee', plannedMinutes: 60 }] },
    { nodeId: 'p2', plannedMinutes: 180 }] }])
  await expect(labor).toContainText('By step: Mix 120 of 180 planned (kim 100 of 120, lee 0 of 60, dan 20), Bake 0 of 180 planned')
  await expect(labor).toContainText('Planned by step: 360 min')

  // Opened again, the split starts at each person's part.
  await labor.getByRole('button', { name: 'Plan by step' }).click()
  await expect(form.getByLabel('Mix planned minutes')).toHaveValue('')
  await expect(form.getByLabel('Mix · kim minutes')).toHaveValue('120')
  await expect(form.getByLabel('Mix · lee minutes')).toHaveValue('60')
  await expect(form.getByLabel('Bake planned minutes')).toHaveValue('180')
  await expect(form.getByLabel('Bake · kim minutes')).toHaveValue('')
})
