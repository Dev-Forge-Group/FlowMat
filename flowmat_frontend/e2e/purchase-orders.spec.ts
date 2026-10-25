import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * Purchase orders (docs/architecture/adr/ADR-027-purchasing.md) against a mocked API: a draft is saved from the form, ordered,
 * received with a LOT where needed, and the rest is closed with a reason.
 */
test.use({ timezoneId: 'UTC' })

test('a purchase order is drafted, ordered, received into stock and closed', async ({ page }) => {
  const projectId = 'prj-po'
  const item = (itemId: string, itemCode: string, lotManageYn: string) => ({ itemId, projectId, itemCode, itemName: itemCode.toLowerCase(),
    itemType: 'material', resourceCategory: 'material', unitId: 'unit_kg', itemStatus: 'active', lotManageYn, unitCost: null })
  const sent: { path: string; body: unknown }[] = []
  let orders: Record<string, unknown>[] = []
  const line = (lineId: string, itemId: string, itemCode: string, quantity: number, lotTracked: boolean) => ({ lineId,
    lineNo: lineId === 'l1' ? 1 : 2, itemId, itemCode, itemName: itemCode.toLowerCase(), unitCode: 'kg', lotTracked, quantity,
    receivedQuantity: 0, remainingQuantity: quantity, expectedDate: null, note: null })
  const base = { purchaseOrderId: 'po1', projectId, orderNumber: 'PO-0001', supplierName: 'Mill Co', expectedDate: null, note: null,
    createdBy: 'demo-owner', createdAt: '2041-09-01T09:00:00Z', orderedBy: null, orderedAt: null, endedBy: null, endedAt: null,
    endReason: null, receipts: [] as Record<string, unknown>[] }
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname.startsWith('/api/purchase-orders')) {
      const body = request.postDataJSON()
      sent.push({ path: pathname, body })
      if (pathname === '/api/purchase-orders') {
        orders = [{ ...base, status: 'draft', lines: [line('l1', 'flour', 'FLOUR', 10, false), line('l2', 'yeast', 'YEAST', 2, true)] }]
      } else if (pathname.endsWith('/order')) {
        orders = [{ ...orders[0], status: 'ordered', orderedBy: 'demo-owner', orderedAt: '2041-09-01T09:05:00Z' }]
      } else if (pathname.endsWith('/receipts')) {
        orders = [{ ...orders[0], lines: [{ ...line('l1', 'flour', 'FLOUR', 10, false), receivedQuantity: 4, remainingQuantity: 6 },
          { ...line('l2', 'yeast', 'YEAST', 2, true), receivedQuantity: 2, remainingQuantity: 0 }],
        receipts: [{ receiptId: 'r1', lineId: 'l1', itemCode: 'FLOUR', quantity: 4, inventoryId: 'i1', location: 'WH-A', lotNo: null,
          receivedBy: 'demo-owner', receivedAt: '2041-09-02T10:00:00Z' }, { receiptId: 'r2', lineId: 'l2', itemCode: 'YEAST', quantity: 2,
          inventoryId: 'i2', location: null, lotNo: 'L-1', receivedBy: 'demo-owner', receivedAt: '2041-09-02T10:00:00Z' }] }]
      } else if (pathname.endsWith('/close')) {
        orders = [{ ...orders[0], status: 'closed', endedBy: 'demo-owner', endReason: (body as { reason: string }).reason }]
      }
      return ok(route, orders[0])
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/purchase-orders') return ok(route, orders)
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Buying', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Buying', ownerId: 'demo-owner' })
    if (pathname === '/api/items') return ok(route, [item('flour', 'FLOUR', 'N'), item('yeast', 'YEAST', 'Y')])
    if (pathname === '/api/units') return ok(route, [{ unitId: 'unit_kg', unitCode: 'kg', unitName: 'kilogram', unitType: 'mass', activeYn: 'Y' }])
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=purchasing`)
  await expect(page.getByText('No purchase orders.')).toBeVisible({ timeout: 15_000 })

  const form = page.getByRole('form', { name: 'New purchase order' })
  await form.getByRole('button', { name: 'Save as draft' }).click()
  await expect(form.getByRole('alert')).toHaveText('Name the supplier.')
  await form.getByLabel('Supplier').fill('Mill Co')
  await form.getByLabel('Line 1 item').selectOption('flour')
  await form.getByLabel('Line 1 quantity').fill('10')
  await form.getByRole('button', { name: 'Add line' }).click()
  await form.getByLabel('Line 2 item').selectOption('yeast')
  await form.getByLabel('Line 2 quantity').fill('2')
  await form.getByRole('button', { name: 'Save as draft' }).click()

  const detail = page.getByRole('region', { name: 'Purchase order PO-0001' })
  await expect(detail.getByRole('list', { name: 'Order lines' }).getByRole('listitem')).toHaveText(['FLOUR · 10 kg', 'YEAST · 2 kg'])
  await detail.getByRole('button', { name: 'Order', exact: true }).click()
  await expect(detail.getByRole('heading')).toHaveText('PO-0001 · Mill Co · ordered')

  await detail.getByRole('button', { name: 'Receive' }).click()
  const receipt = detail.getByRole('form', { name: 'Receive' })
  await receipt.getByLabel('Receive FLOUR').fill('4')
  await receipt.getByLabel('Location for FLOUR').fill('WH-A')
  await receipt.getByRole('button', { name: 'Save receipt' }).click()
  await expect(receipt.getByRole('alert')).toHaveText('YEAST is LOT-tracked; give the LOT number.')
  await receipt.getByLabel('LOT for YEAST').fill('L-1')
  await receipt.getByLabel('Expiry for YEAST').fill('2041-12-31')
  await receipt.getByRole('button', { name: 'Save receipt' }).click()
  await expect(detail.getByRole('list', { name: 'Order lines' }).getByRole('listitem').first()).toHaveText('FLOUR · 4 of 10 kg received')
  await expect(detail.getByRole('list', { name: 'Receipts' }).getByRole('listitem'))
    .toHaveText(['2041-09-02 10:00 · FLOUR 4 at WH-A · by demo-owner', '2041-09-02 10:00 · YEAST 2 · LOT L-1 · by demo-owner'])

  page.once('dialog', (dialog) => void dialog.accept('Rest not coming'))
  await detail.getByRole('button', { name: 'Close', exact: true }).click()
  await expect(detail.getByRole('heading')).toHaveText('PO-0001 · Mill Co · closed')

  expect(sent.map((one) => one.path)).toEqual(['/api/purchase-orders', '/api/purchase-orders/po1/order', '/api/purchase-orders/po1/receipts',
    '/api/purchase-orders/po1/close'])
  expect(sent[0].body).toEqual({ projectId, supplierName: 'Mill Co', expectedDate: null, note: null, lines: [
    { itemId: 'flour', quantity: 10, expectedDate: null, note: null }, { itemId: 'yeast', quantity: 2, expectedDate: null, note: null }] })
  expect(sent[2].body).toEqual({ requestId: expect.any(String), lines: [
    { lineId: 'l1', quantity: 4, location: 'WH-A', lotNo: null, expiryDate: null },
    { lineId: 'l2', quantity: 2, location: null, lotNo: 'L-1', expiryDate: '2041-12-31' }] })
  expect(sent[3].body).toEqual({ reason: 'Rest not coming' })
})
