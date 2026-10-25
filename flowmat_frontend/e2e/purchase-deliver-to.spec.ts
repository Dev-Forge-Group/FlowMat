import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/** Where a purchase order is to arrive, and receipts starting there (docs/domain/purchasing.md PO39-PO40), mocked. */
test.use({ timezoneId: 'UTC' })

test('a purchase order says where it is to arrive and its receipt starts there', async ({ page }) => {
  const projectId = 'prj-po-deliver'
  const flour = { itemId: 'flour', projectId, itemCode: 'FLOUR', itemName: 'flour', itemType: 'material', resourceCategory: 'material',
    unitId: 'unit_kg', itemStatus: 'active', lotManageYn: 'N', unitCost: null }
  const saved = (body: Record<string, unknown>, status: string) => ({ purchaseOrderId: 'po1', projectId, orderNumber: 'PO-0001',
    supplierName: body.supplierName, status, expectedDate: null, note: null, createdBy: 'demo-owner', createdAt: '2041-09-01T09:00:00Z',
    orderedBy: status === 'ordered' ? 'demo-owner' : null, orderedAt: status === 'ordered' ? '2041-09-01T09:05:00Z' : null, endedBy: null,
    endedAt: null, endReason: null, receipts: [], dateChanges: [], returns: [], deliverTo: body.deliverTo ?? null,
    lines: [{ lineId: 'l1', lineNo: 1, itemId: 'flour', itemCode: 'FLOUR', itemName: 'flour', unitCode: 'kg', lotTracked: false, quantity: 10,
      receivedQuantity: 0, remainingQuantity: 10, expectedDate: null, note: null }] })
  const orders: Record<string, unknown>[] = []
  const sent: Record<string, unknown>[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/purchase-orders') {
      const body = request.postDataJSON() as Record<string, unknown>
      sent.push(body)
      orders.splice(0, orders.length, saved(body, 'draft'))
      return ok(route, orders[0])
    }
    if (request.method() === 'POST' && pathname === '/api/purchase-orders/po1/order') {
      orders.splice(0, orders.length, saved(sent[0], 'ordered'))
      return ok(route, orders[0])
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/purchase-orders') return ok(route, orders)
    if (pathname === '/api/items') return ok(route, [flour])
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Deliver', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Deliver', ownerId: 'demo-owner' })
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=purchasing`)
  const form = page.getByRole('form', { name: 'New purchase order' })
  await form.getByLabel('Supplier').fill('Mill Co', { timeout: 15_000 })
  await form.getByLabel('Deliver to').fill('WH-A')
  await form.getByLabel('Line 1 item').selectOption('flour')
  await form.getByLabel('Line 1 quantity').fill('10')
  await form.getByRole('button', { name: 'Save as draft' }).click()
  expect(sent).toEqual([{ projectId, supplierName: 'Mill Co', expectedDate: null, note: null, deliverTo: 'WH-A',
    lines: [{ itemId: 'flour', quantity: 10, expectedDate: null, note: null }] }])
  const detail = page.getByRole('region', { name: 'Purchase order PO-0001' })
  await expect(detail.getByText('Deliver to WH-A')).toBeVisible()
  await detail.getByRole('button', { name: 'Order', exact: true }).click()
  await detail.getByRole('button', { name: 'Receive' }).click()
  await expect(detail.getByRole('form', { name: 'Receive' }).getByLabel('Location for FLOUR')).toHaveValue('WH-A')
})
