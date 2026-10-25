import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/** A LOT bought in shows the purchase orders it came on, back to the supplier (docs/domain/purchasing.md PO13), mocked. */
// Times are shown in the browser's zone.
test.use({ timezoneId: 'UTC' })

test('a LOT received on a purchase order shows where it came from', async ({ page }) => {
  const projectId = 'prj-e2e'
  const lot = { lotId: 'lot-1', projectId, itemId: 'yeast', lotNo: 'L-1', serialNo: null, lotStatus: 'available', receivedAt: null,
    producedAt: null, expiryDate: null, productionRunId: null, quantityOnHand: 2, quantityReserved: 0, expired: false }
  const order = { purchaseOrderId: 'po1', projectId, orderNumber: 'PO-0001', supplierName: 'Mill Co', status: 'received', expectedDate: null,
    note: null, createdBy: 'demo-owner', createdAt: '2041-09-01T09:00:00Z', orderedBy: 'demo-owner', orderedAt: '2041-09-01T09:05:00Z',
    endedBy: 'demo-owner', endedAt: '2041-09-02T10:00:00Z', endReason: null,
    lines: [{ lineId: 'l1', lineNo: 1, itemId: 'yeast', itemCode: 'YEAST', itemName: 'yeast', unitCode: 'kg', lotTracked: true, quantity: 2,
      receivedQuantity: 2, remainingQuantity: 0, expectedDate: null, note: null }],
    receipts: [{ receiptId: 'r1', lineId: 'l1', itemCode: 'YEAST', quantity: 2, inventoryId: 'i1', location: null, lotNo: 'L-1',
      receivedBy: 'demo-owner', receivedAt: '2041-09-02T10:00:00Z' }] }
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/lots') return ok(route, [lot])
    if (pathname === '/api/purchase-orders') return ok(route, [order])
    // The LOT's stock, held, so it can be sent back (PO26, PO37).
    if (pathname === '/api/inventories') {
      return ok(route, [{ inventoryId: 'i1', projectId, itemId: 'yeast', quantity: 2, reservedQuantity: 0, availableQuantity: 2,
        inventoryStatus: 'quarantined', location: null, minThreshold: null, maxThreshold: null, stockLevel: 'ok', version: 1, lotId: 'lot-1', lotNo: 'L-1' }])
    }
    if (pathname === '/api/items') {
      return ok(route, [{ itemId: 'yeast', projectId, itemCode: 'YEAST', itemName: 'yeast', itemType: 'material', resourceCategory: 'material',
        resourceType: null, unitId: 'unit_kg', itemStatus: 'active', lotManageYn: 'Y' }])
    }
    if (/^\/api\/lots\/[^/]+\/trace$/.test(pathname)) return ok(route, { lot, direction: 'backward', nodes: [] })
    if (pathname === '/api/lots/lot-1/release-history') return ok(route, { lotId: 'lot-1', events: [] })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=lots`)
  await page.getByRole('row', { name: /L-1/ }).click()
  await expect(page.getByRole('list', { name: 'LOT origin' }).getByRole('listitem'))
    .toHaveText(['Received on PO-0001 from Mill Co: 2 on 2041-09-02 10:00'])

  // A nonconformity of that LOT returned to the supplier names them.
  const ncr = { nonconformityId: 'n1', projectId, ncrNo: 'NCR-0001', title: 'Sour yeast', description: null, severity: 'major',
    status: 'open', itemId: 'yeast', itemCode: 'YEAST', lotId: 'lot-1', lotNo: 'L-1', productionRunId: null, runNumber: null,
    rootCause: null, disposition: 'pending', raisedBy: 'demo-owner', raisedAt: '2041-09-03T09:00:00Z', closedBy: null, closedAt: null,
    closureNote: null, verificationResult: null, verificationNote: null, verifiedBy: null, verifiedAt: null, verificationDueDate: null,
    verificationOverdue: false, defects: [], actions: [], openActions: 0, overdueActions: 0 }
  await page.route(/\/api\/nonconformities(\?.*)?$/, (route) => ok(route, [ncr]))
  await page.route(/\/api\/quality\/summary(\?.*)?$/, (route) => ok(route, { inspections: 0, passed: 0, failed: 0, passRate: null,
    openDefects: 0, resolvedDefects: 0, defectsByType: [], failuresByCheck: [], byItem: [] }))
  await page.goto(`/projects/${projectId}/inventory?tab=quality`)
  const list = page.getByRole('table', { name: 'Nonconformity list' })
  await list.getByRole('button', { name: 'Open' }).click()
  await page.getByLabel('Disposition').selectOption('return_to_supplier')
  await expect(page.getByRole('note', { name: 'Supplier' })).toHaveText('LOT L-1 came from Mill Co (PO-0001).')

  // It goes back from that order: the link opens it, ready to send the held LOT back (PO37).
  await page.getByRole('link', { name: 'PO-0001' }).click()
  await expect(page).toHaveURL(/tab=purchasing&order=po1/)
  const detail = page.getByRole('region', { name: 'Purchase order PO-0001' })
  await detail.getByRole('button', { name: 'Return to supplier' }).click()
  await expect(detail.getByRole('form', { name: 'Return to supplier' }).getByLabel('From stock')).toHaveText('no location · LOT L-1 · 2 free · quarantined')
  // The disposition was chosen but not saved, so the return cannot name that NCR (PO42).
  await expect(detail.getByRole('note', { name: 'NCR' }))
    .toHaveText('NCR-0001 is not an open NCR with Return to supplier saved, so this return will not name it.')
})
