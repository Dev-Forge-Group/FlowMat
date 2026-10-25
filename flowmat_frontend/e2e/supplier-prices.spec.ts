import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/** An item's prices compared across suppliers and over time, from the orders placed (docs/domain/purchasing.md PO49), mocked. */
test.use({ timezoneId: 'UTC' })

test('an item shows what each supplier was paid for it', async ({ page }) => {
  const projectId = 'prj-supplier-prices'
  const order = (orderNumber: string, supplierName: string, status: string, orderedAt: string | null, unitPrice: number) => ({
    purchaseOrderId: orderNumber, projectId, orderNumber, supplierName, status, expectedDate: null, note: null, createdBy: 'demo-owner',
    createdAt: '2041-09-01T08:00:00Z', orderedBy: orderedAt ? 'demo-owner' : null, orderedAt, endedBy: null, endedAt: null, endReason: null,
    orderValue: unitPrice * 10, valueComplete: true, dateChanges: [], returns: [], receipts: [],
    lines: [{ lineId: `${orderNumber}-l1`, lineNo: 1, itemId: 'flour', itemCode: 'FLOUR', itemName: 'flour', unitCode: 'kg', lotTracked: false,
      quantity: 10, receivedQuantity: 0, remainingQuantity: 10, expectedDate: null, note: null, unitPrice, lineValue: unitPrice * 10 }] })
  const orders = [
    order('PO-0001', 'Mill Co', 'received', '2041-09-01T09:00:00Z', 2.5),
    order('PO-0002', 'Salt Works', 'ordered', '2041-09-05T09:00:00Z', 2.2),
    order('PO-0003', 'Mill Co', 'closed', '2041-09-10T09:00:00Z', 2.7),
    order('PO-0004', 'Gone Co', 'cancelled', '2041-09-06T09:00:00Z', 1),
    order('PO-0005', 'Mill Co', 'draft', null, 1.5),
  ]
  const usual = { supplierItemId: 'si1', projectId, itemId: 'flour', itemCode: 'FLOUR', supplierId: 's1', supplierName: 'Mill Co',
    supplierActive: true, supplierItemCode: 'MC-12', unitPrice: 2.4, version: 0, updatedBy: 'demo-owner', updatedAt: '2041-09-01T08:00:00Z' }
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/purchase-orders') return ok(route, orders)
    if (pathname === '/api/supplier-items') return ok(route, [usual])
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Prices', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Prices', ownerId: 'demo-owner' })
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=purchasing`)
  await page.getByLabel('Prices for').selectOption({ label: 'FLOUR' }, { timeout: 15_000 })

  // Salt Works charged less last time; Mill Co, the usual supplier, twice with 2.5 its lowest. Drafts and cancelled orders are left out.
  const rows = page.getByRole('table', { name: 'Item prices by supplier' }).getByRole('row')
  await expect(rows.nth(1).getByRole('cell')).toHaveText(['Salt Works', '2.2', '2041-09-05 · PO-0002', '2.2', '1'])
  await expect(rows.nth(2).getByRole('cell')).toHaveText(['Mill Co (usual)', '2.7', '2041-09-10 · PO-0003', '2.5', '2'])
  await expect(rows).toHaveCount(3)
  await expect(page.getByText('Usually from Mill Co at 2.4 agreed.')).toBeVisible()
  await expect(page.getByRole('list', { name: 'Price history' }).getByRole('listitem')).toHaveText([
    '2041-09-10 · Mill Co · PO-0003 · 2.7',
    '2041-09-05 · Salt Works · PO-0002 · 2.2',
    '2041-09-01 · Mill Co · PO-0001 · 2.5',
  ])
})
