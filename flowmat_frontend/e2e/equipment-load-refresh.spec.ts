import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

for (const action of ['efficiency', 'concurrent', 'delete'] as const) {
  test(`an open load board refreshes after equipment ${action}`, async ({ page }) => {
    const projectId = 'equipment-cache'
    let deleted = false
    let reads = 0
    let equipment = {
      equipmentId: 'oven-cache', projectId, equipmentCode: 'OVEN-CACHE', equipmentName: 'Oven',
      equipmentType: 'machine', equipmentStatus: 'active', details: {
        manufacturer: null, modelName: null, serialNo: null, location: null, powerKwh: null, waterLiter: null,
        capacityPerHour: 10, efficiencyPercent: null as number | null, concurrentWorkOrders: false,
      },
    }
    await page.addInitScript(() => { window.confirm = () => true })
    await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      const { pathname } = url
      if (await answerAuth(route, pathname)) return
      if (request.method() === 'PUT' && pathname === '/api/equipments/oven-cache') {
        const body = request.postDataJSON() as { details: typeof equipment.details }
        equipment = { ...equipment, details: body.details }
        return ok(route, equipment)
      }
      if (request.method() === 'DELETE' && pathname === '/api/equipments/oven-cache') {
        deleted = true
        return ok(route, null)
      }
      if (request.method() !== 'GET') throw new Error(`Unexpected write: ${request.method()} ${pathname}`)
      if (pathname === '/api/equipments') return ok(route, deleted ? [] : [equipment])
      if (pathname === '/api/equipment-load') {
        reads++
        const from = url.searchParams.get('from')!
        const efficiency = equipment.details.efficiencyPercent ?? 100
        const needed = 100 / efficiency
        const orders = action === 'delete' ? [] : ['a', 'b'].map((workOrderId) => ({
          workOrderId, workOrderNumber: `WO-${workOrderId}`, workOrderTitle: 'Loaves', workOrderStatus: 'approved',
          targetItemId: null, targetItemCode: null, plannedStartAt: from,
          plannedEndAt: new Date(Date.parse(from) + 3_600_000).toISOString(), remainingQuantity: 10,
          changeoverMinutes: null, neededHours: needed, hoursInWindow: needed,
        }))
        return ok(route, { from, to: url.searchParams.get('to'), equipment: deleted ? [] : [{
          equipmentId: equipment.equipmentId, equipmentCode: equipment.equipmentCode, equipmentName: equipment.equipmentName,
          equipmentStatus: 'active', capacityPerHour: 10, efficiencyPercent: equipment.details.efficiencyPercent,
          concurrentWorkOrders: equipment.details.concurrentWorkOrders, calendarSet: true, availableHours: 1,
          downtimeHours: 0, plannedHours: orders.length * needed, draftHours: 0,
          loadPercent: orders.length * needed * 100,
          overloaded: orders.length * needed > 1 && !equipment.details.concurrentWorkOrders,
          unplannedOrders: 0, unmeasuredOrders: 0, changeovers: null, orders,
        }] })
      }
      return ok(route, [])
    })
    await mockedLogin(page)
    await page.goto(`/projects/${projectId}/inventory?tab=equipment`)
    await page.getByText('Load by week', { exact: true }).click()
    const board = page.getByRole('region', { name: 'Equipment load', exact: true })
    const loadRow = board.getByRole('row', { name: /OVEN-CACHE/ })
    await expect(loadRow).toBeVisible()
    if (action !== 'delete') await expect(loadRow).toContainText('200%')
    const previousReads = reads
    const listRow = page.getByRole('row', { name: /OVEN-CACHE.*Oven/ })
    if (action === 'delete') {
      const removed = page.waitForResponse((response) => response.request().method() === 'DELETE'
        && new URL(response.url()).pathname === '/api/equipments/oven-cache')
      await listRow.getByRole('button', { name: 'Delete', exact: true }).click()
      expect((await removed).ok()).toBe(true)
      await expect(page.getByText('No equipment has been added.', { exact: true }).first()).toBeVisible()
    } else {
      await listRow.getByRole('button', { name: 'Edit', exact: true }).click()
      if (action === 'efficiency') await page.getByRole('spinbutton', { name: 'Efficiency %', exact: true }).fill('50')
      else await page.getByRole('checkbox', { name: 'Runs several work orders at once', exact: true }).check()
      const saved = page.waitForResponse((response) => response.request().method() === 'PUT'
        && new URL(response.url()).pathname === '/api/equipments/oven-cache')
      await page.getByRole('button', { name: 'Save', exact: true }).click()
      expect((await saved).ok()).toBe(true)
      await expect(page.getByRole('button', { name: 'Cancel', exact: true })).toHaveCount(0)
    }
    // No manual refresh, navigation or window focus: an already visible board must follow the successful write.
    await expect.poll(() => reads, { timeout: 3_000 }).toBeGreaterThan(previousReads)
    if (action === 'delete') {
      await expect(loadRow).toHaveCount(0)
      await expect(board.getByText('No equipment has been added.', { exact: true })).toBeVisible()
    } else if (action === 'efficiency') {
      await expect(loadRow).toContainText('50% efficient')
      await expect(loadRow).toContainText('400%')
      await expect(loadRow).toContainText('Overloaded')
    } else {
      await expect(loadRow).toContainText('runs orders side by side')
      await expect(loadRow).not.toContainText('Overloaded')
      const timeline = board.getByRole('list', { name: 'Timeline of OVEN-CACHE', exact: true })
      await expect(timeline.getByRole('listitem')).toHaveCount(2)
      await expect(timeline.getByRole('listitem', { name: /clashes/ })).toHaveCount(0)
    }
  })
}
