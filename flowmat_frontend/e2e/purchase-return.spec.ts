import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/** Stock received on a purchase order goes back to the supplier (docs/domain/purchasing.md PO26-PO28), mocked. */
test.use({ timezoneId: 'UTC' })

test('received stock goes back to the supplier from a chosen stock record', async ({ page }) => {
  const projectId = 'prj-po-return'
  const flour = { lineId: 'l1', lineNo: 1, itemId: 'flour', itemCode: 'FLOUR', itemName: 'flour', unitCode: 'kg', lotTracked: false, quantity: 10,
    receivedQuantity: 10, remainingQuantity: 0, expectedDate: null, note: null, unitPrice: null, lineValue: null, returnedQuantity: 0 }
  const order = { purchaseOrderId: 'po1', projectId, orderNumber: 'PO-0001', supplierName: 'Mill Co', status: 'received', expectedDate: null,
    note: null, createdBy: 'demo-owner', createdAt: '2041-09-01T09:00:00Z', orderedBy: 'demo-owner', orderedAt: '2041-09-01T09:05:00Z',
    endedBy: 'demo-owner', endedAt: '2041-09-02T10:00:00Z', endReason: null, orderValue: 0, valueComplete: false, dateChanges: [], returns: [],
    lines: [flour], receipts: [{ receiptId: 'r1', lineId: 'l1', itemCode: 'FLOUR', quantity: 10, inventoryId: 'f1', location: 'WH-A', lotNo: null,
      receivedBy: 'demo-owner', receivedAt: '2041-09-02T10:00:00Z' }] }
  const stock = { inventoryId: 'f1', projectId, itemId: 'flour', quantity: 10, reservedQuantity: 3, availableQuantity: 7, inventoryStatus: 'available',
    location: 'WH-A', minThreshold: null, maxThreshold: null, stockLevel: 'ok', version: 1, lotId: null, lotNo: null }
  let current: Record<string, unknown> = order
  const sent: unknown[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/purchase-orders/po1/returns') {
      const body = request.postDataJSON() as { requestId: string }
      sent.push(body)
      current = { ...order, lines: [{ ...flour, returnedQuantity: 4 }], returns: [{ returnId: 'b1', lineId: 'l1', itemCode: 'FLOUR', quantity: 4,
        inventoryId: 'f1', location: 'WH-A', lotNo: null, reason: 'Wet bags', returnedBy: 'demo-owner', returnedAt: '2041-09-05T10:00:00Z' }] }
      return ok(route, current)
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/purchase-orders') return ok(route, [current])
    if (pathname === '/api/inventories') return ok(route, [sent.length ? { ...stock, quantity: 6, availableQuantity: 3 } : stock])
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Returns', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Returns', ownerId: 'demo-owner' })
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=purchasing`)
  await page.getByRole('table', { name: 'Purchase orders' }).getByRole('button', { name: 'PO-0001' }).click({ timeout: 15_000 })
  const detail = page.getByRole('region', { name: 'Purchase order PO-0001' })
  await detail.getByRole('button', { name: 'Return to supplier' }).click()
  const form = detail.getByRole('form', { name: 'Return to supplier' })
  await expect(form.getByLabel('Line')).toHaveText('FLOUR (10 can go back)')
  // 3 of the 10 are reserved.
  await expect(form.getByLabel('From stock')).toHaveText('WH-A · 7 free')
  await form.getByLabel('Return quantity').fill('8')
  await form.getByRole('button', { name: 'Save return' }).click()
  await expect(form.getByRole('alert')).toHaveText('Only 7 of FLOUR there is not reserved.')
  await form.getByLabel('Return quantity').fill('4')
  await form.getByRole('button', { name: 'Save return' }).click()
  await expect(form.getByRole('alert')).toHaveText('Give a reason for the return.')
  await form.getByLabel('Return reason').fill('Wet bags')
  // Not replaced: the order stays received (PO46).
  await expect(form.getByLabel('Replace on this order')).toBeChecked()
  await form.getByLabel('Replace on this order').uncheck()
  await form.getByRole('button', { name: 'Save return' }).click()
  await expect(form).toHaveCount(0)
  expect(sent).toEqual([{ requestId: expect.any(String), lineId: 'l1', inventoryId: 'f1', quantity: 4, reason: 'Wet bags', replacement: false }])
  // Not replaced, it is owed back; the line has no price (PO47).
  await expect(detail.getByRole('list', { name: 'Returns' }).getByRole('listitem'))
    .toHaveText('2041-09-05 10:00 · FLOUR 4 back from WH-A · Wet bags · by demo-owner · credit due (no price) Record credit')
  await expect(detail.getByRole('list', { name: 'Order lines' })).toContainText('FLOUR · 10 of 10 kg received · 4 returned')
})
