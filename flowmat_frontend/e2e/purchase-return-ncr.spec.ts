import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/** A return sent back for an NCR names it, and the NCR shows it (docs/domain/purchasing.md PO42-PO43), mocked. */
test.use({ timezoneId: 'UTC' })

test('a return sent back for an NCR names it on both sides', async ({ page }) => {
  const projectId = 'prj-po-ncr'
  const yeast = { lineId: 'l1', lineNo: 1, itemId: 'yeast', itemCode: 'YEAST', itemName: 'yeast', unitCode: 'kg', lotTracked: true, quantity: 2,
    receivedQuantity: 2, remainingQuantity: 0, expectedDate: null, note: null, unitPrice: null, lineValue: null, returnedQuantity: 0 }
  const order = { purchaseOrderId: 'po1', projectId, orderNumber: 'PO-0001', supplierName: 'Mill Co', status: 'received', expectedDate: null,
    note: null, createdBy: 'demo-owner', createdAt: '2041-09-01T09:00:00Z', orderedBy: 'demo-owner', orderedAt: '2041-09-01T09:05:00Z',
    endedBy: 'demo-owner', endedAt: '2041-09-02T10:00:00Z', endReason: null, orderValue: 0, valueComplete: false, dateChanges: [], returns: [],
    lines: [yeast], receipts: [{ receiptId: 'r1', lineId: 'l1', itemCode: 'YEAST', quantity: 2, inventoryId: 'y1', location: null, lotNo: 'L-1',
      receivedBy: 'demo-owner', receivedAt: '2041-09-02T10:00:00Z' }] }
  const stock = { inventoryId: 'y1', projectId, itemId: 'yeast', quantity: 2, reservedQuantity: 0, availableQuantity: 2, inventoryStatus: 'quarantined',
    location: null, minThreshold: null, maxThreshold: null, stockLevel: 'ok', version: 1, lotId: 'lot-1', lotNo: 'L-1' }
  const ncr = { nonconformityId: 'n1', projectId, ncrNo: 'NCR-0001', title: 'Sour yeast', description: null, severity: 'major',
    status: 'open', itemId: 'yeast', itemCode: 'YEAST', lotId: 'lot-1', lotNo: 'L-1', productionRunId: null, runNumber: null,
    rootCause: 'Warm truck', disposition: 'return_to_supplier', raisedBy: 'demo-owner', raisedAt: '2041-09-03T09:00:00Z', closedBy: null,
    closedAt: null, closureNote: null, verificationResult: null, verificationNote: null, verifiedBy: null, verifiedAt: null,
    verificationDueDate: null, verificationOverdue: false, defects: [], actions: [], openActions: 0, overdueActions: 0 }
  let current: Record<string, unknown> = order
  const sent: unknown[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/purchase-orders/po1/returns') {
      sent.push(request.postDataJSON())
      // Replaced on the order (PO46), so nothing is owed back.
      current = { ...order, status: 'ordered', endedBy: null, endedAt: null,
        lines: [{ ...yeast, returnedQuantity: 2, replacementQuantity: 2, remainingQuantity: 2 }], returns: [{ returnId: 'b1', lineId: 'l1',
          itemCode: 'YEAST', quantity: 2, inventoryId: 'y1', location: null, lotNo: 'L-1', reason: 'Sour', returnedBy: 'demo-owner',
          returnedAt: '2041-09-05T10:00:00Z', nonconformityId: 'n1', ncrNo: 'NCR-0001', replacement: true }] }
      return ok(route, current)
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/purchase-orders') return ok(route, [current])
    if (pathname === '/api/nonconformities') return ok(route, [ncr])
    if (pathname === '/api/inventories') return ok(route, [sent.length ? { ...stock, quantity: 0, availableQuantity: 0 } : stock])
    if (pathname === '/api/quality/summary') {
      return ok(route, { inspections: 0, passed: 0, failed: 0, passRate: null, openDefects: 0, resolvedDefects: 0, defectsByType: [],
        failuresByCheck: [], byItem: [] })
    }
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Returns', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Returns', ownerId: 'demo-owner' })
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=quality`)
  await page.getByRole('table', { name: 'Nonconformity list' }).getByRole('button', { name: 'Open' }).click()

  // The NCR set to return to supplier sends it back from the order it came on; the link names the NCR (PO37, PO42).
  await page.getByRole('link', { name: 'PO-0001' }).click()
  await expect(page).toHaveURL(/tab=purchasing&order=po1&ncr=n1/)
  const detail = page.getByRole('region', { name: 'Purchase order PO-0001' })
  await detail.getByRole('button', { name: 'Return to supplier' }).click()
  const form = detail.getByRole('form', { name: 'Return to supplier' })
  await expect(form.getByLabel('For NCR')).toHaveValue('n1')
  await expect(form.getByLabel('For NCR').locator('option:checked')).toHaveText('NCR-0001 · Sour yeast · YEAST LOT L-1')
  await form.getByLabel('Return quantity').fill('2')
  await form.getByLabel('Return reason').fill('Sour')
  await form.getByRole('button', { name: 'Save return' }).click()
  await expect(form).toHaveCount(0)
  expect(sent).toEqual([{ requestId: expect.any(String), lineId: 'l1', inventoryId: 'y1', quantity: 2, reason: 'Sour', nonconformityId: 'n1',
    replacement: true }])
  await expect(detail.getByRole('list', { name: 'Returns' }).getByRole('listitem'))
    .toHaveText('2041-09-05 10:00 · YEAST 2 back · LOT L-1 · Sour · for NCR-0001 · replacement on this order · by demo-owner')

  // The NCR lists what went back for it (PO43).
  await page.goto(`/projects/${projectId}/inventory?tab=quality`)
  await page.getByRole('table', { name: 'Nonconformity list' }).getByRole('button', { name: 'Open' }).click()
  await expect(page.getByRole('list', { name: 'Sent back' }).getByRole('listitem'))
    .toHaveText('2041-09-05 10:00 · YEAST 2 back on PO-0001 · LOT L-1 · replacement on the order · by demo-owner')
})
