import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/** Spend by supplier over a period (docs/domain/purchasing.md PO41), mocked. */
test.use({ timezoneId: 'UTC' })

test('suppliers show what was ordered, received and returned in a period', async ({ page }) => {
  const projectId = 'prj-supplier-spend'
  await page.clock.setFixedTime(new Date('2041-09-28T03:00:00Z'))
  const order = (purchaseOrderId: string, supplierName: string, orderedAt: string, patch: Record<string, unknown>) => ({ purchaseOrderId, projectId,
    orderNumber: purchaseOrderId.toUpperCase(), supplierName, status: 'received', expectedDate: null, note: null, createdBy: 'demo-owner',
    createdAt: orderedAt, orderedBy: 'demo-owner', orderedAt, endedBy: 'demo-owner', endedAt: orderedAt, endReason: null, dateChanges: [],
    returns: [], receipts: [], lines: [], ...patch })
  const line = (lineId: string, quantity: number, unitPrice: number | null) => ({ lineId, lineNo: 1, itemId: 'flour', itemCode: 'FLOUR',
    itemName: 'flour', unitCode: 'kg', lotTracked: false, quantity, receivedQuantity: quantity, remainingQuantity: 0, expectedDate: null,
    note: null, unitPrice, lineValue: unitPrice == null ? null : quantity * unitPrice })
  const receipt = (lineId: string, quantity: number) => ({ receiptId: `r-${lineId}`, lineId, itemCode: 'FLOUR', quantity, inventoryId: 'i1',
    location: null, lotNo: null, receivedBy: 'demo-owner', receivedAt: '2041-09-12T10:00:00Z' })
  const orders = [
    order('po-1', 'Mill Co', '2041-09-10T09:00:00Z', { lines: [line('a1', 10, 1.25), line('a2', 3, null)],
      receipts: [receipt('a1', 10), receipt('a2', 3)],
      returns: [{ returnId: 'b1', lineId: 'a1', itemCode: 'FLOUR', quantity: 2, inventoryId: 'i1', location: null, lotNo: null, reason: 'Wet bags',
        returnedBy: 'demo-owner', returnedAt: '2041-09-13T10:00:00Z' }] }),
    order('po-2', 'Salt Works', '2041-05-01T09:00:00Z', { lines: [line('c1', 1, 3)], receipts: [receipt('c1', 1)] }),
  ]
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/purchase-orders') return ok(route, orders)
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Spend', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Spend', ownerId: 'demo-owner' })
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=purchasing`)
  const spend = page.getByRole('table', { name: 'Supplier spend' })
  // The last 90 days: Salt Works ordered in May is left out.
  await expect(spend.getByRole('row')).toHaveCount(2, { timeout: 15_000 })
  await expect(spend.getByRole('row').nth(1)).toHaveText('Mill Co112.512.52.51')
  await page.getByLabel('Spend period').selectOption({ label: 'all time' })
  await expect(spend.getByRole('row').nth(2)).toHaveText('Salt Works13300')
  await page.getByLabel('Spend period').selectOption({ label: 'last 30 days' })
  await expect(spend.getByRole('row')).toHaveCount(2)
})
