import { expect, test } from '@playwright/test'

test.skip(process.env.REAL_API_E2E !== '1' || process.env.FLOWMAT_EQUIPMENT_E2E_DISPOSABLE !== '1',
  'Requires an explicitly disposable backend database')
test.use({ timezoneId: 'Asia/Seoul', trace: 'off' })

type Order = { workOrderId: string; workOrderNumber: string; bomId: string | null }
type Plan = { plannedStartAt: string; plannedEndAt: string; productionHours: number; efficiencyPercent: number | null;
  movedPast: string[]; crewMovedPast: { workOrderNumber: string; userIds: string[] }[] }
type Load = { equipment: { equipmentId: string; plannedHours: number; availableHours: number; loadPercent: number;
  overloaded: boolean; concurrentWorkOrders: boolean; efficiencyPercent: number | null }[] }

test('real efficiency and concurrent settings refresh an open load board while crew bookings still constrain plans', async ({ page }) => {
  test.setTimeout(150_000)
  let token = ''
  let projectId = ''
  let completed = false
  const orders: Order[] = []
  const cancelled = new Set<string>()
  const headers = () => ({ Authorization: `Bearer ${token}` })
  const api = async <T>(method: string, path: string, body?: unknown): Promise<T> => {
    const response = await page.request.fetch(`/api${path}`, { method, headers: headers(), data: body })
    const envelope = await response.json()
    expect(response.ok(), `${method} ${path}: ${envelope.message}`).toBe(true)
    expect(envelope.success).toBe(true)
    return envelope.data as T
  }
  const from = '2041-09-02T08:00:00+09:00'
  const to = '2041-09-02T12:00:00+09:00'
  const dates = (plan: Plan, start: string, end: string) => {
    expect(Date.parse(plan.plannedStartAt)).toBe(Date.parse(start))
    expect(Date.parse(plan.plannedEndAt)).toBe(Date.parse(end))
  }
  try {
    await page.goto('/')
    await page.locator('input').nth(0).fill('demo-owner')
    await page.locator('input[type="password"]').fill('demo1234')
    const login = page.waitForResponse((response) => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/auth/login')
    await page.getByRole('button', { name: 'Log in', exact: true }).click()
    const loggedIn = await login
    expect(loggedIn.ok()).toBe(true)
    token = (await loggedIn.json()).data.accessToken
    await expect(page.getByRole('button', { name: 'Logout', exact: true })).toBeVisible()
    projectId = (await api<{ projectId: string }>('POST', '/projects', {
      projectName: `Equipment-E2E-${Date.now()}`, ownerId: 'demo-owner',
    })).projectId
    const workflow = await api<{ workflowId: string }>('POST', '/workflows', { projectId, workflowName: 'Capacity check' })
    const code = `EQ-CAP-${Date.now()}`
    const equipment = await api<{ equipmentId: string }>('POST', '/equipments', {
      projectId, equipmentCode: code, equipmentName: 'Capacity oven', equipmentType: 'machine', details: { capacityPerHour: 10 },
    })
    const equipmentId = equipment.equipmentId
    await api('PUT', `/equipments/${equipmentId}/calendar`, { shiftStart: '08:00', shiftEnd: '12:00', workDays: [1] })
    for (const title of ['First', 'Second']) {
      const order = await api<Order>('POST', '/work-orders', { projectId, workflowId: workflow.workflowId,
        workOrderTitle: `${title} capacity order`, targetQuantity: 20, plannedStartAt: from, plannedEndAt: to })
      expect(order.bomId).toBeNull()
      orders.push(order)
      await api('PUT', `/work-orders/${order.workOrderId}/equipment`, { equipmentId })
      await api('POST', `/work-orders/${order.workOrderId}/approve`, {})
    }
    const plan = () => api<Plan>('GET', `/work-orders/${orders[1].workOrderId}/plan-suggestion?${new URLSearchParams({ from })}`)
    const load = async () => {
      const result = await api<Load>('GET', `/equipment-load?${new URLSearchParams({ projectId, from, to })}`)
      expect(result.equipment).toHaveLength(1)
      return result.equipment[0]
    }
    const readiness = () => api<{ checks: { code: string }[] }>('GET', `/work-orders/${orders[1].workOrderId}/readiness`)
    await page.goto(`/projects/${projectId}/inventory?tab=equipment`)
    await page.getByText('Load by week', { exact: true }).click()
    const board = page.getByRole('region', { name: 'Equipment load', exact: true })
    await board.getByLabel('Week of', { exact: true }).fill('2041-09-02')
    const boardRow = board.getByRole('row', { name: new RegExp(code) })
    const equipmentRow = page.getByRole('row', { name: new RegExp(code) })
      .filter({ has: page.getByRole('button', { name: 'Edit', exact: true }) })
    await expect(boardRow).toContainText('100%')
    await expect(boardRow).not.toContainText('Overloaded')
    expect((await load()).plannedHours).toBe(4)
    await test.step('saving 50% efficiency updates the visible board and real planning consumers', async () => {
      await equipmentRow.getByRole('button', { name: 'Edit', exact: true }).click()
      await page.getByRole('spinbutton', { name: 'Efficiency %', exact: true }).fill('50')
      const saved = page.waitForResponse((response) => response.request().method() === 'PUT'
        && new URL(response.url()).pathname === `/api/equipments/${equipmentId}`)
      await page.getByRole('button', { name: 'Save', exact: true }).click()
      expect((await saved).ok()).toBe(true)
      await expect(equipmentRow).toContainText('50%')
      await expect(boardRow).toContainText('50% efficient')
      await expect(boardRow).toContainText('200%')
      await expect(boardRow).toContainText('Overloaded')
      expect(await load()).toMatchObject({ plannedHours: 8, availableHours: 4, loadPercent: 200, overloaded: true, efficiencyPercent: 50 })
      const suggestion = await plan()
      expect(suggestion.productionHours).toBe(4)
      dates(suggestion, '2041-09-09T08:00:00+09:00', '2041-09-09T12:00:00+09:00')
      expect(suggestion.movedPast).toContain(orders[0].workOrderNumber)
      expect((await readiness()).checks.map((check) => check.code)).toContain('schedule')
    })
    await test.step('saving concurrent work removes equipment clashes without manual refresh', async () => {
      await equipmentRow.getByRole('button', { name: 'Edit', exact: true }).click()
      await page.getByRole('checkbox', { name: 'Runs several work orders at once', exact: true }).check()
      await page.getByRole('button', { name: 'Save', exact: true }).click()
      await expect(equipmentRow).toContainText('side by side')
      await expect(boardRow).toContainText('runs orders side by side')
      await expect(boardRow).toContainText('200%')
      await expect(boardRow).not.toContainText('Overloaded')
      expect(await load()).toMatchObject({ concurrentWorkOrders: true, overloaded: false, plannedHours: 8 })
      const timeline = board.getByRole('list', { name: `Timeline of ${code}`, exact: true })
      await expect(timeline.getByRole('listitem')).toHaveCount(2)
      await expect(timeline.getByRole('listitem', { name: /clashes/ })).toHaveCount(0)
      dates(await plan(), from, to)
      expect((await readiness()).checks.map((check) => check.code)).not.toContain('schedule')
    })
    await test.step('the same person at 100% still blocks overlap, while two 50% shares fit', async () => {
      await api('PUT', `/projects/${projectId}/workers/demo-owner/shifts`, {
        shifts: [{ shiftStart: '08:00', shiftEnd: '12:00', workDays: [1] }],
      })
      for (const order of orders) await api('PUT', `/work-orders/${order.workOrderId}/crew`, {
        members: [{ userId: 'demo-owner', sharePercent: 100 }],
      })
      const booked = await plan()
      dates(booked, '2041-09-09T08:00:00+09:00', '2041-09-09T12:00:00+09:00')
      expect(booked.crewMovedPast).toEqual(expect.arrayContaining([expect.objectContaining({
        workOrderNumber: orders[0].workOrderNumber, userIds: ['demo-owner'],
      })]))
      for (const order of orders) await api('PUT', `/work-orders/${order.workOrderId}/crew`, {
        members: [{ userId: 'demo-owner', sharePercent: 50 }],
      })
      dates(await plan(), from, to)
    })
    await test.step('deleting unused equipment also removes it from the already open board', async () => {
      for (const order of orders) {
        await api('POST', `/work-orders/${order.workOrderId}/cancel`, {})
        cancelled.add(order.workOrderId)
      }
      page.once('dialog', (dialog) => { void dialog.accept() })
      const removed = page.waitForResponse((response) => response.request().method() === 'DELETE'
        && new URL(response.url()).pathname === `/api/equipments/${equipmentId}`)
      await equipmentRow.getByRole('button', { name: 'Delete', exact: true }).click()
      expect((await removed).ok()).toBe(true)
      await expect(boardRow).toHaveCount(0)
      await expect(board.getByText('No equipment has been added.', { exact: true })).toBeVisible()
      expect(await api<unknown[]>('GET', `/boms?projectId=${projectId}`)).toEqual([])
    })
    completed = true
  } finally {
    if (projectId && token) {
      try {
        for (const order of orders) if (!cancelled.has(order.workOrderId)) {
          await api('POST', `/work-orders/${order.workOrderId}/cancel`, {})
        }
        const removed = await page.request.delete(`/api/projects/${projectId}`, { headers: headers() })
        if (completed) expect(removed.ok(), `Project cleanup returned ${removed.status()}`).toBe(true)
        else if (!removed.ok()) test.info().annotations.push({ type: 'cleanup', description: `Project cleanup returned ${removed.status()}` })
      } catch (error) {
        // eslint-disable-next-line no-unsafe-finally -- The primary test succeeded before completed became true.
        if (completed) throw error
        test.info().annotations.push({ type: 'cleanup', description: 'Owned fixture cleanup failed after the primary failure' })
      }
    }
  }
})
