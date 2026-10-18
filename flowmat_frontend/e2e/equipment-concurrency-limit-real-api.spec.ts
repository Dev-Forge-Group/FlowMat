import { expect, test } from '@playwright/test'
import { disposableProject } from './support/disposableProject'

test.skip(process.env.REAL_API_E2E !== '1' || process.env.FLOWMAT_FEATURE_E2E_DISPOSABLE !== '1',
  'Requires an explicitly disposable backend database')
test.use({ timezoneId: 'Asia/Seoul', trace: 'off' })

type Load = { equipment: { equipmentId: string; concurrentLimit: number; availableHours: number;
  plannedHours: number; loadPercent: number; overloaded: boolean }[] }

test('real equipment with two slots plans the third order later and warns at 150 percent load', async ({ page }) => {
  test.setTimeout(150_000)
  const fixture = await disposableProject(page, 'Equipment-limit')
  const { projectId, api, request } = fixture
  const orders: string[] = []
  let completed = false
  const at = (hour: number) => `2041-09-02T${String(hour).padStart(2, '0')}:00:00+09:00`
  try {
    const workflowId = (await api<{ workflowId: string }>('POST', '/workflows', { projectId, workflowName: 'Two slots' })).workflowId
    const code = `TWIN-${Date.now()}`
    const equipmentId = (await api<{ equipmentId: string }>('POST', '/equipments', {
      projectId, equipmentCode: code, equipmentName: 'Twin oven', equipmentType: 'machine',
      details: { capacityPerHour: 10, concurrentWorkOrders: true, concurrentLimit: 2 },
    })).equipmentId
    for (const [title, targetQuantity, end] of [['First', 20, 10], ['Second', 30, 11], ['Third', 20, 10]] as const) {
      const order = await api<{ workOrderId: string; bomId: string | null }>('POST', '/work-orders', {
        projectId, workflowId, workOrderTitle: title, targetQuantity, plannedStartAt: at(8), plannedEndAt: at(end),
      })
      expect(order.bomId).toBeNull()
      orders.push(order.workOrderId)
      await api('PUT', `/work-orders/${order.workOrderId}/equipment`, { equipmentId })
    }
    for (const order of orders.slice(0, 2)) await api('POST', `/work-orders/${order}/approve`, {})
    const query = new URLSearchParams({ projectId, from: at(8), to: at(10) })
    const load = async () => (await api<Load>('GET', `/equipment-load?${query}`)).equipment.find((row) => row.equipmentId === equipmentId)!
    expect(await load()).toMatchObject({ concurrentLimit: 2, availableHours: 2, plannedHours: 4, loadPercent: 100, overloaded: false })
    const plan = await api<{ plannedStartAt: string; plannedEndAt: string }>('GET',
      `/work-orders/${orders[2]}/plan-suggestion?${new URLSearchParams({ from: at(8) })}`)
    expect(Date.parse(plan.plannedStartAt)).toBe(Date.parse(at(10)))
    expect(Date.parse(plan.plannedEndAt)).toBe(Date.parse(at(12)))
    const readiness = await api<{ checks: { code: string; message: string }[] }>('GET', `/work-orders/${orders[2]}/readiness`)
    expect(readiness.checks).toEqual(expect.arrayContaining([
      expect.objectContaining({ code: 'schedule', message: expect.stringContaining('runs at most 2 work orders at once') }),
    ]))
    await api('POST', `/work-orders/${orders[2]}/approve`, {})
    expect(await load()).toMatchObject({ concurrentLimit: 2, plannedHours: 6, loadPercent: 150, overloaded: true })
    await page.goto(`/projects/${projectId}/inventory?tab=equipment`)
    const row = page.getByRole('row', { name: new RegExp(code) })
    await expect(row).toContainText('up to 2')
    await row.getByRole('button', { name: 'Edit', exact: true }).click()
    await expect(page.getByRole('spinbutton', { name: 'At most at once', exact: true })).toHaveValue('2')
    for (const value of [2.5, '3']) {
      expect((await request('PUT', `/equipments/${equipmentId}`, {
        details: { capacityPerHour: 10, concurrentWorkOrders: true, concurrentLimit: value },
      })).status()).toBe(400)
    }
    expect(await api('GET', `/equipments/${equipmentId}`)).toMatchObject({
      details: { capacityPerHour: 10, concurrentWorkOrders: true, concurrentLimit: 2 },
    })
    expect(await api('GET', `/boms?projectId=${projectId}`)).toEqual([])
    completed = true
  } finally {
    for (const order of orders) await request('POST', `/work-orders/${order}/cancel`, {}).catch(() => undefined)
    await fixture.dispose(completed)
  }
})
