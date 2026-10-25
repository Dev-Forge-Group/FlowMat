import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * Following up purchase orders (docs/domain/purchasing.md PO10-PO11) against a mocked API: an ordered order past its expected
 * date is marked late, a LOT received on it opens its receipt checks, and the suppliers already used are suggested.
 */
test('a late order is marked, a received LOT opens its receipt checks and suppliers are suggested', async ({ page }) => {
  const projectId = 'prj-po-follow'
  const line = (lineId: string, itemId: string, quantity: number, received: number, lotTracked: boolean) => ({ lineId, lineNo: 1, itemId,
    itemCode: itemId.toUpperCase(), itemName: itemId, unitCode: 'kg', lotTracked, quantity, receivedQuantity: received,
    remainingQuantity: quantity - received, expectedDate: null, note: null })
  const base = { projectId, note: null, createdBy: 'demo-owner', createdAt: '2020-01-01T09:00:00Z', orderedBy: 'demo-owner',
    orderedAt: '2020-01-01T09:05:00Z', endedBy: null, endedAt: null, endReason: null }
  const late = { ...base, purchaseOrderId: 'po1', orderNumber: 'PO-0001', supplierName: 'Mill Co', status: 'ordered', expectedDate: '2020-01-02',
    lines: [line('l1', 'flour', 10, 0, false), line('l2', 'yeast', 2, 2, true)],
    receipts: [{ receiptId: 'r1', lineId: 'l2', itemCode: 'YEAST', quantity: 2, inventoryId: 'i2', location: null, lotNo: 'L-1',
      receivedBy: 'demo-owner', receivedAt: '2020-01-02T10:00:00Z' }] }
  const draft = { ...base, purchaseOrderId: 'po2', orderNumber: 'PO-0002', supplierName: 'Salt Ltd', status: 'draft', expectedDate: '2020-01-02',
    orderedBy: null, orderedAt: null, lines: [line('l3', 'salt', 5, 0, false)], receipts: [] }
  const standard = { standardId: 's1', projectId, itemId: 'yeast', itemCode: 'YEAST', itemName: 'yeast', inspectionType: 'Smell',
    stage: 'receipt', standardMin: null, standardMax: null, unit: null, required: true, active: true, note: null }
  const yeastStock = { inventoryId: 'i2', projectId, itemId: 'yeast', lotId: 'lot-1', lotNo: 'L-1', quantity: 2, reservedQuantity: 0,
    availableQuantity: 2, location: null, inventoryStatus: 'quarantined', minThreshold: null, version: 0 }
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/purchase-orders') return ok(route, [draft, late])
    if (pathname === '/api/inventories') return ok(route, [yeastStock])
    if (pathname === '/api/inspection-standards') return ok(route, [standard])
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Follow up', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Follow up', ownerId: 'demo-owner' })
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    if (pathname === '/api/items') return ok(route, [])
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=purchasing`)
  const table = page.getByRole('table', { name: 'Purchase orders' })
  await expect(table.getByRole('row', { name: /PO-0001/ })).toContainText('2020-01-02 · late', { timeout: 15_000 })
  await expect(table.getByRole('row', { name: /PO-0002/ })).not.toContainText('late')

  await table.getByRole('button', { name: 'PO-0001' }).click()
  const detail = page.getByRole('region', { name: 'Purchase order PO-0001' })
  await expect(detail.getByRole('note')).toHaveText('Expected 2020-01-02; not everything has arrived.')
  await detail.getByRole('button', { name: 'Receipt checks for LOT L-1' }).click()
  await expect(page.getByRole('region', { name: 'Receipt checks for LOT L-1' })).toContainText('Smell')

  await expect(page.locator('#purchase-supplier-options option')).toHaveCount(2)
  expect(await page.locator('#purchase-supplier-options option').evaluateAll((options) =>
    options.map((option) => (option as HTMLOptionElement).value))).toEqual(['Mill Co', 'Salt Ltd'])
})
