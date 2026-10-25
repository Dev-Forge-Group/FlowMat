import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/** Unit prices on purchase order lines and what the order is worth (docs/domain/purchasing.md PO14), mocked. */
test('a draft takes unit prices and shows what the order is worth', async ({ page }) => {
  const projectId = 'prj-po-value'
  const item = (itemId: string) => ({ itemId, projectId, itemCode: itemId.toUpperCase(), itemName: itemId, itemType: 'material',
    resourceCategory: 'material', unitId: 'unit_kg', itemStatus: 'active', lotManageYn: 'N', unitCost: null })
  const sent: unknown[] = []
  let orders: unknown[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/purchase-orders') {
      const body = request.postDataJSON() as { lines: { itemId: string; quantity: number; unitPrice?: number }[] }
      sent.push(body)
      const lines = body.lines.map((one, index) => ({ lineId: `l${index}`, lineNo: index + 1, itemId: one.itemId,
        itemCode: one.itemId.toUpperCase(), itemName: one.itemId, unitCode: 'kg', lotTracked: false, quantity: one.quantity,
        receivedQuantity: 0, remainingQuantity: one.quantity, expectedDate: null, note: null, unitPrice: one.unitPrice ?? null,
        lineValue: one.unitPrice == null ? null : one.quantity * one.unitPrice }))
      const order = { purchaseOrderId: 'po1', projectId, orderNumber: 'PO-0001', supplierName: 'Mill Co', status: 'draft',
        expectedDate: null, note: null, createdBy: 'demo-owner', createdAt: '2041-09-01T09:00:00Z', orderedBy: null, orderedAt: null,
        endedBy: null, endedAt: null, endReason: null, receipts: [], lines,
        orderValue: lines.reduce((sum, one) => sum + (one.lineValue ?? 0), 0), valueComplete: lines.every((one) => one.unitPrice != null) }
      orders = [order]
      return ok(route, order)
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/purchase-orders') return ok(route, orders)
    if (pathname === '/api/items') return ok(route, [item('flour'), item('salt')])
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Prices', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Prices', ownerId: 'demo-owner' })
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=purchasing`)
  const form = page.getByRole('form', { name: 'New purchase order' })
  await form.getByLabel('Supplier').fill('Mill Co', { timeout: 15_000 })
  await form.getByLabel('Line 1 item').selectOption('flour')
  await form.getByLabel('Line 1 quantity').fill('10')
  await form.getByLabel('Line 1 unit price').fill('1.25')
  await form.getByRole('button', { name: 'Add line' }).click()
  await form.getByLabel('Line 2 item').selectOption('salt')
  await form.getByLabel('Line 2 quantity').fill('3')
  await form.getByRole('button', { name: 'Save as draft' }).click()

  const detail = page.getByRole('region', { name: 'Purchase order PO-0001' })
  await expect(detail.getByRole('list', { name: 'Order lines' }).getByRole('listitem'))
    .toHaveText(['FLOUR · 10 kg · 1.25 each = 12.5', 'SALT · 3 kg'])
  await expect(detail.getByLabel('Order value')).toHaveText('Order value 12.5 (1 line without a price)')
  expect(sent).toEqual([{ projectId, supplierName: 'Mill Co', expectedDate: null, note: null, lines: [
    { itemId: 'flour', quantity: 10, expectedDate: null, note: null, unitPrice: 1.25 },
    { itemId: 'salt', quantity: 3, expectedDate: null, note: null }] }])
})
