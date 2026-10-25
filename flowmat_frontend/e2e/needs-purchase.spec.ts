import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * The bought materials open work orders leave short become a draft purchase order, in whole purchase units
 * (docs/domain/purchasing.md PO12), against a mocked API.
 */
test('short bought materials of open work orders become a draft purchase order', async ({ page }) => {
  const projectId = 'prj-needs-po'
  const item = (itemId: string, extra: Record<string, unknown> = {}) => ({ itemId, projectId, itemCode: itemId.toUpperCase(),
    itemName: itemId, itemType: 'material', resourceCategory: 'material', unitId: 'unit_kg', itemStatus: 'active', lotManageYn: 'N',
    unitCost: null, ...extra })
  const needs = { projectId, orders: 1, problems: [], lines: [
    { itemId: 'flour', itemCode: 'FLOUR', itemName: 'flour', unit: 'kg', required: 12, usable: 5, shortage: 7, plannedSupply: 0, onOrder: 0,
      madeHere: false, orders: [{ workOrderId: 'w1', workOrderTitle: 'Bread', required: 12 }] },
    { itemId: 'dough', itemCode: 'DOUGH', itemName: 'dough', unit: 'kg', required: 4, usable: 2, shortage: 2, plannedSupply: 0, onOrder: 0,
      madeHere: true, orders: [{ workOrderId: 'w1', workOrderTitle: 'Bread', required: 4 }] },
    { itemId: 'salt', itemCode: 'SALT', itemName: 'salt', unit: 'kg', required: 1, usable: 5, shortage: 0, plannedSupply: 0, onOrder: 0,
      madeHere: false, orders: [{ workOrderId: 'w1', workOrderTitle: 'Bread', required: 1 }] },
  ] }
  const drafts: unknown[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/purchase-orders') {
      const body = request.postDataJSON() as { lines: { itemId: string; quantity: number }[] }
      drafts.push(body)
      return ok(route, { purchaseOrderId: 'po1', projectId, orderNumber: 'PO-0001', supplierName: 'Mill Co', status: 'draft', expectedDate: null,
        note: 'For open work orders', createdBy: 'demo-owner', createdAt: '2041-09-01T09:00:00Z', orderedBy: null, orderedAt: null,
        endedBy: null, endedAt: null, endReason: null, receipts: [], lines: body.lines.map((one, index) => ({ lineId: `l${index}`,
          lineNo: index + 1, itemId: one.itemId, itemCode: one.itemId.toUpperCase(), itemName: one.itemId, unitCode: 'kg', lotTracked: false,
          quantity: one.quantity, receivedQuantity: 0, remainingQuantity: one.quantity, expectedDate: null, note: null })) })
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/material-requirements') return ok(route, needs)
    if (pathname === '/api/items') return ok(route, [item('flour', { purchaseUnit: 'bag', purchaseUnitQty: 5 }), item('dough'), item('salt')])
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Needs', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Needs', ownerId: 'demo-owner' })
    if (pathname === '/api/units') return ok(route, [{ unitId: 'unit_kg', unitCode: 'kg', unitName: 'kilogram', unitType: 'mass', activeYn: 'Y' }])
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=stock`)
  const needsPanel = page.getByRole('region', { name: 'Open work order needs' })
  page.once('dialog', (dialog) => void dialog.accept('Mill Co'))
  await needsPanel.getByRole('button', { name: 'Draft a purchase order for 1 short material' }).click({ timeout: 15_000 })
  await expect(needsPanel.getByRole('status')).toContainText('Drafted PO-0001 with 1 line; check and order it on the Purchasing tab.')
  await expect(needsPanel.getByRole('link', { name: 'Open Purchasing' })).toHaveAttribute('href', `/projects/${projectId}/inventory?tab=purchasing`)
  expect(drafts).toEqual([{ projectId, supplierName: 'Mill Co', expectedDate: null, note: 'For open work orders',
    lines: [{ itemId: 'flour', quantity: 10, expectedDate: null, note: null }] }])
})
