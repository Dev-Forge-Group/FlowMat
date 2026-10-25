import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * A material covered once everything on order is in, but short before the order arrives (docs/domain/purchasing.md PO25),
 * against a mocked API.
 */
test.use({ timezoneId: 'UTC' })

test('open work order needs tell when a material runs short before its order arrives', async ({ page }) => {
  const projectId = 'prj-needs-timing'
  const needs = { projectId, orders: 2, problems: [], lines: [
    { itemId: 'flour', itemCode: 'FLOUR', itemName: 'flour', unit: 'kg', required: 8, usable: 5, shortage: 0, plannedSupply: 0, onOrder: 4,
      madeHere: false, orders: [{ workOrderId: 'w1', workOrderTitle: 'Bread', required: 4 }, { workOrderId: 'w2', workOrderTitle: 'Buns', required: 4 }] },
    { itemId: 'salt', itemCode: 'SALT', itemName: 'salt', unit: 'kg', required: 1, usable: 5, shortage: 0, plannedSupply: 0, onOrder: 0,
      madeHere: false, orders: [{ workOrderId: 'w1', workOrderTitle: 'Bread', required: 1 }] },
    // Covered by what the Dough order makes, but it is planned to end the day after Buns starts.
    { itemId: 'dough', itemCode: 'DOUGH', itemName: 'dough', unit: 'kg', required: 4, usable: 0, shortage: 0, plannedSupply: 4, onOrder: 0,
      madeHere: true, orders: [{ workOrderId: 'w2', workOrderTitle: 'Buns', required: 4 }],
      supplies: [{ workOrderId: 'w3', workOrderTitle: 'Dough', quantity: 4 }] },
  ] }
  const workOrder = (workOrderId: string, workOrderTitle: string, plannedStartAt: string, plannedEndAt: string | null = null) => ({ workOrderId,
    projectId, workflowId: null, workOrderNumber: workOrderId.toUpperCase(), workOrderTitle, workOrderStatus: 'approved', priority: 'normal',
    targetItemId: null, targetQuantity: null, plannedStartAt, plannedEndAt, actualStartAt: null, actualEndAt: null })
  const purchase = { purchaseOrderId: 'po2', projectId, orderNumber: 'PO-0002', supplierName: 'Mill Co', status: 'ordered', expectedDate: '2041-09-21',
    note: null, createdBy: 'demo-owner', createdAt: '2041-09-01T09:00:00Z', orderedBy: 'demo-owner', orderedAt: '2041-09-01T09:05:00Z',
    endedBy: null, endedAt: null, endReason: null, receipts: [], dateChanges: [],
    lines: [{ lineId: 'l1', lineNo: 1, itemId: 'flour', itemCode: 'FLOUR', itemName: 'flour', unitCode: 'kg', lotTracked: false, quantity: 4,
      receivedQuantity: 0, remainingQuantity: 4, expectedDate: null, note: null }] }
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/material-requirements') return ok(route, needs)
    if (pathname === '/api/work-orders') {
      return ok(route, [workOrder('w1', 'Bread', '2041-09-18T00:00:00Z'), workOrder('w2', 'Buns', '2041-09-20T00:00:00Z'),
        workOrder('w3', 'Dough', '2041-09-19T00:00:00Z', '2041-09-21T00:00:00Z')])
    }
    if (pathname === '/api/purchase-orders') return ok(route, [purchase])
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Timing', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Timing', ownerId: 'demo-owner' })
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=stock`)
  const panel = page.getByRole('region', { name: 'Open work order needs' })
  await expect(panel).toContainText('2 open work orders: all 3 materials covered · 2 short before their orders arrive', { timeout: 15_000 })
  // Bread takes 4 of the 5 on the 18th; Buns needs 4 on the 20th, a day before PO-0002 brings 4.
  await expect(panel.getByRole('row', { name: /^FLOUR ·/ }).getByRole('cell').nth(8)).toHaveText('2041-09-20 · Buns3 short')
  await expect(panel.getByRole('row', { name: /^SALT ·/ }).getByRole('cell').nth(8)).toHaveText('-')
  // What the Dough order makes comes on its planned end, the 21st (PO25).
  await expect(panel.getByRole('row', { name: /^DOUGH ·/ }).getByRole('cell').nth(8)).toHaveText('2041-09-20 · Buns4 short')
})
