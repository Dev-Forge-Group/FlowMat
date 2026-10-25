import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/** A purchase order opens as a page to print or hand to the supplier (docs/domain/purchasing.md PO16), mocked. */
test.use({ timezoneId: 'UTC' })

test('a purchase order opens as a page to print', async ({ page }) => {
  const projectId = 'prj-po-print'
  const order = { purchaseOrderId: 'po1', projectId, orderNumber: 'PO-0001', supplierName: 'Mill & Co', status: 'ordered', expectedDate: '2041-09-10',
    note: 'Gate 2', createdBy: 'demo-owner', createdAt: '2041-09-01T09:00:00Z', orderedBy: 'demo-owner', orderedAt: '2041-09-01T09:05:00Z',
    endedBy: null, endedAt: null, endReason: null, orderValue: 12.5, valueComplete: true, receipts: [],
    lines: [{ lineId: 'l1', lineNo: 1, itemId: 'flour', itemCode: 'FLOUR', itemName: 'flour', unitCode: 'kg', lotTracked: false, quantity: 10,
      receivedQuantity: 0, remainingQuantity: 10, expectedDate: null, note: null, unitPrice: 1.25, lineValue: 12.5 }] }
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/purchase-orders') return ok(route, [order])
    if (pathname === '/api/supplier-items') {
      return ok(route, [{ supplierItemId: 'si1', projectId, itemId: 'flour', itemCode: 'FLOUR', supplierId: 's1', supplierName: 'mill & co',
        supplierActive: true, supplierItemCode: 'MC-77', version: 0, updatedBy: 'demo-owner', updatedAt: '2041-09-01T10:00:00Z' }])
    }
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Print', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Print', ownerId: 'demo-owner' })
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=purchasing`)
  await page.getByRole('table', { name: 'Purchase orders' }).getByRole('button', { name: 'PO-0001' }).click({ timeout: 15_000 })
  const detail = page.getByRole('region', { name: 'Purchase order PO-0001' })
  const [sheet] = await Promise.all([page.waitForEvent('popup'), detail.getByRole('button', { name: 'Print', exact: true }).click()])
  await expect(sheet.getByRole('heading', { level: 1 })).toHaveText('Purchase order PO-0001')
  await expect(sheet.getByText('Supplier: Mill & Co')).toBeVisible()
  // The supplier's own code for flour, as flour's usual supplier is this one (PO32).
  await expect(sheet.getByRole('row').nth(0)).toHaveText('#ItemNameTheir codeQuantityUnit priceValue')
  await expect(sheet.getByRole('row').nth(1)).toHaveText('1FLOURflourMC-7710 kg1.2512.5')
  await expect(sheet.getByText('Order value 12.5')).toBeVisible()
  await expect(sheet.getByText('Note: Gate 2')).toBeVisible()
})
