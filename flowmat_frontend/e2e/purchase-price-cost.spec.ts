import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/** A received purchase price is taken as the item's unit cost by hand (docs/domain/purchasing.md PO15), mocked. */
test('a received price can become the item unit cost after a confirmation', async ({ page }) => {
  const projectId = 'prj-po-cost'
  const flour = { itemId: 'flour', projectId, itemCode: 'FLOUR', itemName: 'flour', itemType: 'material', resourceCategory: 'material',
    unitId: 'unit_kg', itemStatus: 'active', lotManageYn: 'N', unitCost: 1.2 }
  const order = { purchaseOrderId: 'po1', projectId, orderNumber: 'PO-0001', supplierName: 'Mill Co', status: 'ordered', expectedDate: null,
    note: null, createdBy: 'demo-owner', createdAt: '2041-09-01T09:00:00Z', orderedBy: 'demo-owner', orderedAt: '2041-09-01T09:05:00Z',
    endedBy: null, endedAt: null, endReason: null, orderValue: 12.5, valueComplete: true,
    lines: [{ lineId: 'l1', lineNo: 1, itemId: 'flour', itemCode: 'FLOUR', itemName: 'flour', unitCode: 'kg', lotTracked: false, quantity: 10,
      receivedQuantity: 4, remainingQuantity: 6, expectedDate: null, note: null, unitPrice: 1.25, lineValue: 12.5 }],
    receipts: [{ receiptId: 'r1', lineId: 'l1', itemCode: 'FLOUR', quantity: 4, inventoryId: 'i1', location: 'WH-A', lotNo: null,
      receivedBy: 'demo-owner', receivedAt: '2041-09-02T10:00:00Z' }] }
  const updates: unknown[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'PUT' && pathname === '/api/items/flour') {
      updates.push(request.postDataJSON())
      return ok(route, { ...flour, unitCost: 1.25 })
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/purchase-orders') return ok(route, [order])
    if (pathname === '/api/items') return ok(route, [updates.length ? { ...flour, unitCost: 1.25 } : flour])
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Cost', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Cost', ownerId: 'demo-owner' })
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=purchasing`)
  await page.getByRole('table', { name: 'Purchase orders' }).getByRole('button', { name: 'PO-0001' }).click({ timeout: 15_000 })
  const detail = page.getByRole('region', { name: 'Purchase order PO-0001' })
  let asked = ''
  page.once('dialog', (dialog) => { asked = dialog.message(); void dialog.accept() })
  await detail.getByRole('button', { name: "Use 1.25 as FLOUR's unit cost" }).click()
  await expect(detail.getByRole('status')).toHaveText("FLOUR's unit cost is now 1.25.")
  expect(asked).toBe("Set FLOUR's unit cost from 1.2 to 1.25? Finished runs keep the costs they had.")
  expect(updates).toEqual([{ unitCost: 1.25 }])
  // Once the cost matches, the button goes.
  await expect(detail.getByRole('button', { name: "Use 1.25 as FLOUR's unit cost" })).toHaveCount(0)
})
