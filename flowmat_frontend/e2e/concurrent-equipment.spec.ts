import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * Equipment that runs several work orders at once (docs/domain/equipment.md E12-E15) against a mocked API: the form sends
 * the switch, the list says "side by side", and the load board neither calls it overloaded nor marks its overlaps.
 */
test('equipment can be set to run work orders side by side', async ({ page }) => {
  const projectId = 'side-fixture'
  let equipment = { equipmentId: 'oven', projectId, equipmentCode: 'OVEN', equipmentName: 'Oven', equipmentType: 'machine',
    equipmentStatus: 'active', details: { manufacturer: null, modelName: null, serialNo: null, capacityPerHour: 10, powerKwh: null,
      waterLiter: null, location: null, efficiencyPercent: null, concurrentWorkOrders: false } }
  const sent: Record<string, unknown>[] = []

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request(); const url = new URL(request.url()); const { pathname } = url
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'PUT' && pathname === '/api/equipments/oven') {
      const body = request.postDataJSON() as { details: typeof equipment.details }
      sent.push(body)
      equipment = { ...equipment, details: body.details }
      return ok(route, equipment)
    }
    if (request.method() !== 'GET') throw new Error(`Unexpected write ${pathname}`)
    if (pathname === '/api/equipments') return ok(route, [equipment])
    if (pathname === '/api/equipment-load') {
      const from = url.searchParams.get('from')!
      const order = (workOrderId: string, workOrderNumber: string) => ({ workOrderId, workOrderNumber, workOrderTitle: 'Loaves',
        workOrderStatus: 'approved', targetItemId: 'loaf', targetItemCode: 'LOAF', plannedStartAt: from,
        plannedEndAt: new Date(Date.parse(from) + 2 * 3600_000).toISOString(), remainingQuantity: 20, changeoverMinutes: null,
        neededHours: 2, hoursInWindow: 2 })
      const sideBySide = equipment.details.concurrentWorkOrders
      return ok(route, { from, to: url.searchParams.get('to'), equipment: [{ equipmentId: 'oven', equipmentCode: 'OVEN',
        equipmentName: 'Oven', equipmentStatus: 'active', capacityPerHour: 10, efficiencyPercent: null, concurrentWorkOrders: sideBySide,
        calendarSet: true, availableHours: 2, downtimeHours: 0, plannedHours: 4, draftHours: 0, loadPercent: 200, overloaded: !sideBySide,
        unplannedOrders: 0, unmeasuredOrders: 0, changeovers: null, orders: [order('a', 'WO-A'), order('b', 'WO-B')] }] })
    }
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=equipment`)
  const row = page.getByRole('row', { name: /OVEN.*Oven/ })
  await expect(row).not.toContainText('side by side')
  await row.getByRole('button', { name: 'Edit', exact: true }).click()
  const sideBySide = page.getByRole('checkbox', { name: 'Runs several work orders at once' })
  await expect(sideBySide).not.toBeChecked()
  await sideBySide.check()
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(row).toContainText('side by side')
  expect(sent).toHaveLength(1)
  expect((sent[0].details as Record<string, unknown>).concurrentWorkOrders).toBe(true)

  await page.getByText('Load by week').click()
  const board = page.getByRole('region', { name: 'Equipment load' })
  const loadRow = board.getByRole('table', { name: 'Load by equipment' }).getByRole('row', { name: /OVEN/ })
  await expect(loadRow).toContainText('runs orders side by side')
  await expect(loadRow).toContainText('200%')
  await expect(loadRow).not.toContainText('Overloaded')
  const timeline = board.getByRole('list', { name: 'Timeline of OVEN' })
  await expect(timeline.getByRole('listitem')).toHaveCount(2)
  await expect(timeline.getByRole('listitem', { name: 'WO-A', exact: true })).toBeVisible()
  await expect(timeline.getByRole('listitem', { name: 'WO-B', exact: true })).toBeVisible()
})

/**
 * Side-by-side equipment with a limit (docs/domain/equipment.md E16-E18): the form takes at most how many at once, refuses
 * a limit below 2 before sending, and the list and load board say the limit.
 */
test('side-by-side equipment can take a limit of work orders at once', async ({ page }) => {
  const projectId = 'limit-fixture'
  let equipment = { equipmentId: 'oven', projectId, equipmentCode: 'OVEN', equipmentName: 'Oven', equipmentType: 'machine',
    equipmentStatus: 'active', details: { manufacturer: null, modelName: null, serialNo: null, capacityPerHour: 10, powerKwh: null,
      waterLiter: null, location: null, efficiencyPercent: null, concurrentWorkOrders: true, concurrentLimit: null as number | null } }
  const sent: Record<string, unknown>[] = []

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request(); const url = new URL(request.url()); const { pathname } = url
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'PUT' && pathname === '/api/equipments/oven') {
      const body = request.postDataJSON() as { details: typeof equipment.details }
      sent.push(body)
      equipment = { ...equipment, details: body.details }
      return ok(route, equipment)
    }
    if (request.method() !== 'GET') throw new Error(`Unexpected write ${pathname}`)
    if (pathname === '/api/equipments') return ok(route, [equipment])
    if (pathname === '/api/equipment-load') {
      const from = url.searchParams.get('from')!
      const limit = equipment.details.concurrentLimit
      return ok(route, { from, to: url.searchParams.get('to'), equipment: [{ equipmentId: 'oven', equipmentCode: 'OVEN',
        equipmentName: 'Oven', equipmentStatus: 'active', capacityPerHour: 10, efficiencyPercent: null, concurrentWorkOrders: true,
        concurrentLimit: limit, calendarSet: true, availableHours: 2, downtimeHours: 0, plannedHours: 4, draftHours: 0,
        loadPercent: limit ? 100 : 200, overloaded: false, unplannedOrders: 0, unmeasuredOrders: 0, changeovers: null, orders: [] }] })
    }
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=equipment`)
  const row = page.getByRole('row', { name: /OVEN.*Oven/ })
  await expect(row).toContainText('side by side')
  await expect(row).not.toContainText('up to')
  await row.getByRole('button', { name: 'Edit', exact: true }).click()
  const limit = page.getByRole('spinbutton', { name: 'At most at once' })
  await expect(limit).toHaveValue('')
  await limit.fill('1')
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(page.getByRole('alert').filter({ hasText: 'At most at once is a whole number from 2 to 99.' })).toBeVisible()
  expect(sent).toHaveLength(0)
  await limit.fill('2')
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(row).toContainText('side by side · up to 2')
  expect(sent).toHaveLength(1)
  expect((sent[0].details as Record<string, unknown>).concurrentLimit).toBe(2)

  await page.getByText('Load by week').click()
  const board = page.getByRole('region', { name: 'Equipment load' })
  const loadRow = board.getByRole('table', { name: 'Load by equipment' }).getByRole('row', { name: /OVEN/ })
  await expect(loadRow).toContainText('runs up to 2 orders side by side')
  await expect(loadRow).toContainText('100%')
})
