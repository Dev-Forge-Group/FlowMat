import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/** How each supplier's orders arrived against their dates (docs/domain/purchasing.md PO24), mocked. */
test.use({ timezoneId: 'UTC' })

test('suppliers show how their orders arrived against the first and the last date', async ({ page }) => {
  const projectId = 'prj-supplier-delivery'
  await page.clock.setFixedTime(new Date('2041-09-25T03:00:00Z'))
  const order = (id: string, supplierName: string, patch: Record<string, unknown>) => ({ purchaseOrderId: id, projectId, orderNumber: id,
    supplierName, status: 'received', expectedDate: '2041-09-15', note: null, createdBy: 'demo-owner', createdAt: '2041-09-01T09:00:00Z',
    orderedBy: 'demo-owner', orderedAt: '2041-09-01T09:05:00Z', endedBy: 'demo-owner', endedAt: '2041-09-12T23:30:00Z', endReason: null,
    orderValue: 0, valueComplete: false, lines: [], dateChanges: [],
    receipts: [{ receiptId: `r-${id}`, lineId: 'l1', itemCode: 'FLOUR', quantity: 1, inventoryId: 'i1', location: null, lotNo: null,
      receivedBy: 'demo-owner', receivedAt: '2041-09-12T23:30:00Z' }], ...patch })
  const orders = [
    order('PO-0001', 'Mill Co', {}),
    // Moved from the 10th; in on the 13th in Seoul.
    order('PO-0002', 'Mill Co', { dateChanges: [{ changeId: 'c1', lineId: null, itemCode: null, previousDate: '2041-09-10', expectedDate: '2041-09-15',
      reason: 'Supplier delayed', changedBy: 'demo-owner', changedAt: '2041-09-08T10:00:00Z' }] }),
    order('PO-0003', 'Salt Works', { expectedDate: '2041-09-11' }),
    order('PO-0004', 'Salt Works', { status: 'ordered', expectedDate: '2041-09-20', endedBy: null, endedAt: null, receipts: [] }),
    // Back in May: due on the 1st, in on the 4th (3 days late); its line had its own 5th (on time) (PO44).
    order('PO-0005', 'Mill Co', { expectedDate: '2041-05-01', endedAt: '2041-05-04T03:00:00Z',
      lines: [{ lineId: 'l5', lineNo: 1, itemId: 'flour', itemCode: 'FLOUR', itemName: 'flour', unitCode: 'kg', lotTracked: false, quantity: 1,
        receivedQuantity: 1, remainingQuantity: 0, expectedDate: '2041-05-05', note: null }],
      receipts: [{ receiptId: 'r-PO-0005', lineId: 'l5', itemCode: 'FLOUR', quantity: 1, inventoryId: 'i1', location: null, lotNo: null,
        receivedBy: 'demo-owner', receivedAt: '2041-05-04T03:00:00Z' }] }),
  ]
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/purchase-orders') return ok(route, orders)
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Delivery', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Delivery', ownerId: 'demo-owner' })
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'Asia/Seoul', version: 0 })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=purchasing`)
  const delivery = page.getByRole('table', { name: 'Supplier delivery' })
  // Received, on time (first), on time (last), lines on time, avg days late, late now.
  await expect(delivery.getByRole('row').nth(1)).toHaveText('Mill Co31 of 32 of 31 of 130', { timeout: 15_000 })
  await expect(delivery.getByRole('row').nth(2)).toHaveText('Salt Works10 of 10 of 1—21')
  // The last 90 days leave May out.
  await page.getByLabel('Delivery period').selectOption('90')
  await expect(delivery.getByRole('row').nth(1)).toHaveText('Mill Co21 of 22 of 2——0')
  await expect(delivery.getByRole('row').nth(2)).toHaveText('Salt Works10 of 10 of 1—21')
})
