import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

// Planned dates are shown in the planning zone (Asia/Seoul by default).
test.use({ timezoneId: 'Asia/Seoul' })

/**
 * Several work orders planned at once (docs/domain/equipment-schedule.md P8-P12) against a mocked API: the orders go in
 * the order they were ticked, each line says its dates or why it has none, and only drafts take their dates.
 */
test('ticked orders are planned in sequence and the drafts take their dates', async ({ page }) => {
  const order = (workOrderId: string, workOrderNumber: string, workOrderTitle: string, workOrderStatus: string,
    equipmentId: string | null) => ({
    workOrderId, projectId: 'prj-e2e', workflowId: 'wf-1', workOrderNumber, workOrderTitle, workOrderStatus, priority: 'normal',
    targetItemId: null, targetQuantity: 100, plannedStartAt: null, plannedEndAt: null, actualStartAt: null, actualEndAt: null,
    instruction: null, assignedTo: null, approvedBy: null, approvedAt: null, producedQuantity: 0, runCount: 0, bomId: null,
    instructionUrl: null, equipmentId,
  })
  const orders = [
    order('wo-1', 'WO-0001', 'Red bread', 'draft', 'oven'),
    order('wo-2', 'WO-0002', 'Blue bread', 'draft', 'oven'),
    order('wo-3', 'WO-0003', 'Rye', 'approved', 'oven'),
    order('wo-4', 'WO-0004', 'Loose', 'draft', null),
    order('wo-5', 'WO-0005', 'No quantity', 'draft', 'oven'),
  ]
  const suggestion = (workOrderId: string, start: string, end: string, neededHours: number, movedPast: string[]) => ({
    workOrderId, equipmentId: 'oven', from: '2030-01-07T00:00:00+09:00', plannedStartAt: start, plannedEndAt: end,
    remainingQuantity: neededHours * 10, capacityPerHour: 10, productionHours: neededHours, changeoverHours: 0, neededHours,
    changeoverFrom: null, movedPast,
  })
  const asked: unknown[] = []
  const saved: Record<string, unknown>[] = []

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    const method = request.method()
    if (await answerAuth(route, pathname)) return
    if (method === 'POST' && pathname === '/api/work-orders/plan-suggestions') {
      asked.push(request.postDataJSON())
      return ok(route, { from: '2030-01-06T15:00:00Z', lines: [
        { workOrderId: 'wo-2', workOrderNumber: 'WO-0002', workOrderStatus: 'draft',
          suggestion: { ...suggestion('wo-2', '2030-01-07T09:00:00+09:00', '2030-01-07T14:00:00+09:00', 5, []),
            schedule: [{ nodeId: 'p1', nodeName: 'Mix', userId: 'kim', from: '2030-01-07T09:00:00+09:00', to: '2030-01-07T14:00:00+09:00' }] },
          problem: null },
        { workOrderId: 'wo-1', workOrderNumber: 'WO-0001', workOrderStatus: 'draft',
          suggestion: suggestion('wo-1', '2030-01-07T14:00:00+09:00', '2030-01-08T16:00:00+09:00', 10, ['WO-0002']), problem: null },
        { workOrderId: 'wo-3', workOrderNumber: 'WO-0003', workOrderStatus: 'approved',
          suggestion: suggestion('wo-3', '2030-01-08T16:00:00+09:00', '2030-01-09T10:00:00+09:00', 2, ['WO-0001']), problem: null },
        { workOrderId: 'wo-5', workOrderNumber: 'WO-0005', workOrderStatus: 'draft', suggestion: null,
          problem: 'Set a target quantity first.' },
      ] })
    }
    const put = pathname.match(/^\/api\/work-orders\/(wo-\d)$/)
    if (method === 'PUT' && put) {
      const body = request.postDataJSON() as Record<string, unknown>
      saved.push({ workOrderId: put[1], ...body })
      const found = orders.find((one) => one.workOrderId === put[1])
      return ok(route, { ...found, ...body })
    }
    if (method !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${method} ${pathname}` }) })
    }
    if (pathname === '/api/work-orders') return ok(route, orders)
    if (pathname === '/api/equipments') {
      return ok(route, [{ equipmentId: 'oven', projectId: 'prj-e2e', equipmentCode: 'OVEN-1', equipmentName: 'Oven',
        equipmentType: 'machine', equipmentStatus: 'active', details: { capacityPerHour: 10 } }])
    }
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/runs?view=work-orders')
  const batch = page.getByRole('region', { name: 'Plan several orders' })
  await batch.getByText('Plan several orders').click({ timeout: 15_000 })
  // The loose order has no equipment, so it is not offered.
  await expect(batch.getByRole('checkbox')).toHaveCount(4)
  await batch.getByRole('checkbox', { name: /WO-0002/ }).check()
  await batch.getByRole('checkbox', { name: /WO-0001/ }).check()
  await batch.getByRole('checkbox', { name: /WO-0003/ }).check()
  await batch.getByRole('checkbox', { name: /WO-0005/ }).check()
  await expect(batch).toContainText('1. WO-0002 Blue bread · draft')
  await expect(batch).toContainText('2. WO-0001 Red bread · draft')
  await batch.getByLabel('Plan several from').fill('2030-01-07T00:00')
  await batch.getByRole('button', { name: 'Suggest dates for 4 orders' }).click()

  const lines = batch.getByRole('list', { name: 'Suggested dates' }).getByRole('listitem')
  await expect(lines).toHaveCount(4)
  expect(asked).toEqual([{ projectId: 'prj-e2e', workOrderIds: ['wo-2', 'wo-1', 'wo-3', 'wo-5'], from: '2030-01-06T15:00:00.000Z' }])
  await expect(lines.nth(0)).toContainText('WO-0002 Mon 2030-01-07 09:00 → Mon 2030-01-07 14:00 · needs 5 h')
  // Who works on which process when (LB35), under its line.
  await expect(lines.nth(0).getByRole('group', { name: 'Who works when for WO-0002' })).toHaveText('Mix: kim Mon 2030-01-07 09:00–14:00')
  await expect(lines.nth(1).getByRole('group')).toHaveCount(0)
  await expect(lines.nth(1)).toHaveText('WO-0001 Mon 2030-01-07 14:00 → Tue 2030-01-08 16:00 · needs 10 h · after WO-0002')
  await expect(lines.nth(2)).toContainText('keeps its dates until the project owner reschedules it')
  await expect(lines.nth(3)).toHaveText('WO-0005 Set a target quantity first.')

  await batch.getByRole('button', { name: 'Use these dates for 2 drafts' }).click()
  await expect(batch.getByRole('status')).toHaveText('Saved the dates of 2 drafts.')
  expect(saved.map((one) => [one.workOrderId, one.plannedStartAt, one.plannedEndAt])).toEqual([
    ['wo-2', '2030-01-07T09:00:00+09:00', '2030-01-07T14:00:00+09:00'],
    ['wo-1', '2030-01-07T14:00:00+09:00', '2030-01-08T16:00:00+09:00'],
  ])
  await expect(batch.getByRole('button', { name: /Use these dates/ })).toHaveCount(0)
})

test('the project owner reschedules the approved orders to their suggestions with one reason', async ({ page }) => {
  const order = (workOrderId: string, workOrderNumber: string, workOrderStatus: string, start: string | null, end: string | null) => ({
    workOrderId, projectId: 'prj-e2e', workflowId: 'wf-1', workOrderNumber, workOrderTitle: workOrderNumber, workOrderStatus,
    priority: 'normal', targetItemId: null, targetQuantity: 10, plannedStartAt: start, plannedEndAt: end, actualStartAt: null,
    actualEndAt: null, instruction: null, assignedTo: null, approvedBy: null, approvedAt: null, producedQuantity: 0, runCount: 0,
    bomId: null, instructionUrl: null, equipmentId: 'oven',
  })
  const orders = [
    order('wo-a', 'WO-0021', 'approved', '2030-01-10T09:00:00+09:00', '2030-01-10T17:00:00+09:00'),
    order('wo-b', 'WO-0022', 'approved', '2030-01-11T09:00:00+09:00', '2030-01-11T17:00:00+09:00'),
    order('wo-d', 'WO-0023', 'draft', null, null),
  ]
  const suggestion = (workOrderId: string, start: string, end: string) => ({
    workOrderId, equipmentId: 'oven', from: '2030-01-07T00:00:00+09:00', plannedStartAt: start, plannedEndAt: end,
    remainingQuantity: 10, capacityPerHour: 10, productionHours: 1, changeoverHours: 0, neededHours: 1, changeoverFrom: null,
    movedPast: [],
  })
  const sent: { workOrderId: string; body: Record<string, unknown> }[] = []
  let refuseB = true

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    const method = request.method()
    if (await answerAuth(route, pathname)) return
    if (method === 'POST' && pathname === '/api/work-orders/plan-suggestions') {
      return ok(route, { from: null, lines: [
        { workOrderId: 'wo-a', workOrderNumber: 'WO-0021', workOrderStatus: 'approved',
          suggestion: suggestion('wo-a', '2030-01-07T09:00:00+09:00', '2030-01-07T10:00:00+09:00'), problem: null },
        { workOrderId: 'wo-b', workOrderNumber: 'WO-0022', workOrderStatus: 'approved',
          suggestion: suggestion('wo-b', '2030-01-07T10:00:00+09:00', '2030-01-07T11:00:00+09:00'), problem: null },
      ] })
    }
    const reschedule = pathname.match(/^\/api\/work-orders\/(wo-[a-z])\/reschedules$/)
    if (method === 'POST' && reschedule) {
      const body = request.postDataJSON() as Record<string, unknown>
      sent.push({ workOrderId: reschedule[1], body })
      if (reschedule[1] === 'wo-b' && refuseB) {
        refuseB = false
        return route.fulfill({ status: 409, contentType: 'application/json',
          body: JSON.stringify({ success: false, data: null, message: 'WO-0022 was rescheduled meanwhile; reload its plan.' }) })
      }
      const found = orders.find((one) => one.workOrderId === reschedule[1])
      return ok(route, { workOrder: { ...found, plannedStartAt: body.plannedStartAt, plannedEndAt: body.plannedEndAt },
        change: { changeId: `c-${reschedule[1]}`, workOrderId: reschedule[1], requestId: body.requestId, reason: body.reason } })
    }
    if (method !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${method} ${pathname}` }) })
    }
    if (pathname === '/api/work-orders') return ok(route, orders)
    if (pathname === '/api/project-members') {
      return ok(route, [{ projectMemberId: 'pm-1', projectId: 'prj-e2e', userId: 'demo-owner', projectRole: 'owner',
        memberStatus: 'active', joinedAt: null }])
    }
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/runs?view=work-orders')
  const batch = page.getByRole('region', { name: 'Plan several orders' })
  await batch.getByText('Plan several orders').click({ timeout: 15_000 })
  await batch.getByRole('checkbox', { name: /WO-0021/ }).check()
  await batch.getByRole('checkbox', { name: /WO-0022/ }).check()
  await batch.getByRole('button', { name: 'Suggest dates for 2 orders' }).click()

  const group = batch.getByRole('group', { name: 'Reschedule approved orders' })
  const button = group.getByRole('button', { name: 'Reschedule 2 approved orders' })
  await expect(button).toBeDisabled()
  await group.getByLabel('Reschedule reason').fill('Planned together')
  await button.click()
  // The second is refused: the first stays changed and the retry sends only the second.
  await expect(batch.getByRole('alert')).toContainText('WO-0022 was rescheduled meanwhile; reload its plan. 1 rescheduled before it.')
  await group.getByRole('button', { name: 'Reschedule 1 approved order' }).click()
  await expect(batch.getByRole('status', { name: 'Rescheduled' })).toHaveText('Rescheduled 2 approved orders.')
  await expect(group).toHaveCount(0)
  expect(sent.map((one) => one.workOrderId)).toEqual(['wo-a', 'wo-b', 'wo-b'])
  expect(sent[0].body).toEqual(expect.objectContaining({ expectedPlannedStartAt: '2030-01-10T09:00:00+09:00',
    expectedPlannedEndAt: '2030-01-10T17:00:00+09:00', plannedStartAt: '2030-01-07T09:00:00+09:00',
    plannedEndAt: '2030-01-07T10:00:00+09:00', reason: 'Planned together' }))
  expect(typeof sent[0].body.requestId).toBe('string')
})

test('Suggest order puts the ticked orders in the order with the fewest changeovers', async ({ page }) => {
  const order = (workOrderId: string, workOrderNumber: string, workOrderTitle: string) => ({
    workOrderId, projectId: 'prj-e2e', workflowId: 'wf-1', workOrderNumber, workOrderTitle, workOrderStatus: 'draft',
    priority: 'normal', targetItemId: null, targetQuantity: 10, plannedStartAt: null, plannedEndAt: null, actualStartAt: null,
    actualEndAt: null, instruction: null, assignedTo: null, approvedBy: null, approvedAt: null, producedQuantity: 0, runCount: 0,
    bomId: null, instructionUrl: null, equipmentId: 'press',
  })
  const orders = [order('wo-a', 'WO-0011', 'A'), order('wo-b', 'WO-0012', 'B'), order('wo-c', 'WO-0013', 'C')]
  const asked: Record<string, unknown>[] = []

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    const method = request.method()
    if (await answerAuth(route, pathname)) return
    if (method === 'POST' && pathname === '/api/work-orders/plan-order') {
      asked.push(request.postDataJSON() as Record<string, unknown>)
      return ok(route, { workOrderIds: ['wo-a', 'wo-b', 'wo-c'], changeoverMinutes: 20, givenChangeoverMinutes: 120,
        groups: [{ equipmentId: 'press', after: null, workOrderIds: ['wo-a', 'wo-b', 'wo-c'], changeoverMinutes: 20,
          givenChangeoverMinutes: 120 }] })
    }
    if (method !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${method} ${pathname}` }) })
    }
    if (pathname === '/api/work-orders') return ok(route, orders)
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/runs?view=work-orders')
  const batch = page.getByRole('region', { name: 'Plan several orders' })
  await batch.getByText('Plan several orders').click({ timeout: 15_000 })
  const suggestOrder = batch.getByRole('button', { name: 'Suggest order' })
  await batch.getByRole('checkbox', { name: /WO-0013/ }).check()
  // One order has no order to choose.
  await expect(suggestOrder).toBeDisabled()
  await batch.getByRole('checkbox', { name: /WO-0012/ }).check()
  await batch.getByRole('checkbox', { name: /WO-0011/ }).check()
  await expect(batch).toContainText('1. WO-0013 C · draft')
  await suggestOrder.click()

  await expect(batch.getByRole('status', { name: 'Suggested order' }))
    .toHaveText('Reordered for fewer changeovers: 20 min instead of 120 min.')
  expect(asked).toEqual([expect.objectContaining({ projectId: 'prj-e2e', workOrderIds: ['wo-c', 'wo-b', 'wo-a'] })])
  await expect(batch).toContainText('1. WO-0011 A · draft')
  await expect(batch).toContainText('2. WO-0012 B · draft')
  await expect(batch).toContainText('3. WO-0013 C · draft')
  // Changing the ticks drops the note.
  await batch.getByRole('checkbox', { name: /WO-0012/ }).uncheck()
  await expect(batch.getByRole('status', { name: 'Suggested order' })).toHaveCount(0)
})
