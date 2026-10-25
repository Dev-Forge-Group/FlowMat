import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * The reorder list shows what is on order and drafts a purchase order for the rest (docs/domain/purchasing.md PO8-PO9),
 * against a mocked API.
 */
test('the reorder list shows what is on order and drafts a purchase order for the rest', async ({ page }) => {
  const projectId = 'prj-reorder-po'
  const item = (itemId: string, itemCode: string) => ({ itemId, projectId, itemCode, itemName: itemCode.toLowerCase(), itemType: 'material',
    resourceCategory: 'material', unitId: 'unit_kg', itemStatus: 'active', lotManageYn: 'N', unitCost: null, safetyStockQty: 20 })
  const reorder = [
    { itemId: 'flour', itemCode: 'FLOUR', itemName: 'flour', unit: 'kg', safetyStockQty: 20, availableQuantity: 5, shortageQuantity: 15,
      leadTimeDays: null, location: null },
    { itemId: 'salt', itemCode: 'SALT', itemName: 'salt', unit: 'kg', safetyStockQty: 10, availableQuantity: 6, shortageQuantity: 4,
      leadTimeDays: null, location: null },
  ]
  const poLine = (lineId: string, itemId: string, quantity: number) => ({ lineId, lineNo: 1, itemId, itemCode: itemId.toUpperCase(),
    itemName: itemId, unitCode: 'kg', lotTracked: false, quantity, receivedQuantity: 0, remainingQuantity: quantity, expectedDate: null, note: null })
  const ordered = { purchaseOrderId: 'po1', projectId, orderNumber: 'PO-0001', supplierName: 'Mill Co', status: 'ordered', expectedDate: null,
    note: null, createdBy: 'demo-owner', createdAt: '2041-09-01T09:00:00Z', orderedBy: 'demo-owner', orderedAt: '2041-09-01T09:05:00Z',
    endedBy: null, endedAt: null, endReason: null, receipts: [], lines: [poLine('l1', 'flour', 10)] }
  const drafts: unknown[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/purchase-orders') {
      const body = request.postDataJSON() as { lines: { itemId: string; quantity: number }[] }
      drafts.push(body)
      return ok(route, { ...ordered, purchaseOrderId: 'po2', orderNumber: 'PO-0002', status: 'draft', orderedBy: null, orderedAt: null,
        lines: body.lines.map((one, index) => poLine(`d${index}`, one.itemId, one.quantity)) })
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/stock-alerts/reorder') return ok(route, reorder)
    if (pathname === '/api/purchase-orders') return ok(route, [ordered])
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Reorder', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Reorder', ownerId: 'demo-owner' })
    if (pathname === '/api/items') return ok(route, [item('flour', 'FLOUR'), item('salt', 'SALT')])
    if (pathname === '/api/units') return ok(route, [{ unitId: 'unit_kg', unitCode: 'kg', unitName: 'kilogram', unitType: 'mass', activeYn: 'Y' }])
    if (pathname === '/api/material-requirements') return ok(route, { projectId, orders: 0, lines: [], problems: [] })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=stock`)
  const list = page.getByRole('region', { name: 'Below safety stock' })
  const flour = list.getByRole('row', { name: /FLOUR/ })
  await expect(flour.getByRole('cell').nth(7)).toHaveText('10', { timeout: 15_000 })
  await expect(list.getByRole('row', { name: /SALT/ }).getByRole('cell').nth(7)).toHaveText('-')

  page.once('dialog', (dialog) => void dialog.accept('Mill Co'))
  await list.getByRole('button', { name: 'Draft purchase order' }).click()
  await expect(list.getByRole('status')).toHaveText('Drafted PO-0002 with 2 lines; check and order it on the Purchasing tab.')
  expect(drafts).toEqual([{ projectId, supplierName: 'Mill Co', expectedDate: null, note: 'From the reorder list', lines: [
    { itemId: 'flour', quantity: 5, expectedDate: null, note: null }, { itemId: 'salt', quantity: 4, expectedDate: null, note: null }] }])
})
