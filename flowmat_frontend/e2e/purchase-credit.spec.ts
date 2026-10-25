import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/** What a supplier owes back for a return, until its credit is kept (docs/domain/purchasing.md PO47-PO48), mocked. */
test.use({ timezoneId: 'UTC' })

test('a return not replaced is owed back until the supplier credit is recorded', async ({ page }) => {
  const projectId = 'prj-po-credit'
  const flour = { lineId: 'l1', lineNo: 1, itemId: 'flour', itemCode: 'FLOUR', itemName: 'flour', unitCode: 'kg', lotTracked: false, quantity: 10,
    receivedQuantity: 10, remainingQuantity: 0, expectedDate: null, note: null, unitPrice: 2.5, lineValue: 25, returnedQuantity: 4,
    replacementQuantity: 0 }
  const back = { returnId: 'b1', lineId: 'l1', itemCode: 'FLOUR', quantity: 4, inventoryId: 'f1', location: 'WH-A', lotNo: null, reason: 'Wet bags',
    returnedBy: 'demo-owner', returnedAt: '2041-09-05T10:00:00Z', replacement: false, creditAmount: null, creditNote: null, creditedBy: null,
    creditedAt: null }
  const order = { purchaseOrderId: 'po1', projectId, orderNumber: 'PO-0001', supplierName: 'Mill Co', status: 'received', expectedDate: null,
    note: null, createdBy: 'demo-owner', createdAt: '2041-09-01T09:00:00Z', orderedBy: 'demo-owner', orderedAt: '2041-09-01T09:05:00Z',
    endedBy: 'demo-owner', endedAt: '2041-09-02T10:00:00Z', endReason: null, orderValue: 25, valueComplete: true, dateChanges: [], returns: [back],
    lines: [flour], receipts: [{ receiptId: 'r1', lineId: 'l1', itemCode: 'FLOUR', quantity: 10, inventoryId: 'f1', location: 'WH-A', lotNo: null,
      receivedBy: 'demo-owner', receivedAt: '2041-09-02T10:00:00Z' }] }
  let current: Record<string, unknown> = order
  const sent: unknown[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/purchase-orders/po1/returns/b1/credit') {
      sent.push(request.postDataJSON())
      current = { ...order, returns: [{ ...back, creditAmount: 9.5, creditNote: 'CN-12', creditedBy: 'demo-owner',
        creditedAt: '2041-09-09T10:00:00Z' }] }
      return ok(route, current)
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/purchase-orders') return ok(route, [current])
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Credits', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Credits', ownerId: 'demo-owner' })
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=purchasing`)

  // The supplier owes 4 × 2.5 back.
  const due = page.getByRole('table', { name: 'Supplier credit due' })
  await expect(due.getByRole('row').nth(1).getByRole('cell')).toHaveText(['Mill Co', '1', '10', '0'], { timeout: 15_000 })
  await page.getByRole('table', { name: 'Purchase orders' }).getByRole('button', { name: 'PO-0001' }).click()
  const detail = page.getByRole('region', { name: 'Purchase order PO-0001' })
  const item = detail.getByRole('list', { name: 'Returns' }).getByRole('listitem')
  await expect(item).toHaveText('2041-09-05 10:00 · FLOUR 4 back from WH-A · Wet bags · by demo-owner · credit due 10 Record credit')
  await expect(detail.getByLabel('Credit due')).toHaveText('Credit due 10')

  await item.getByRole('button', { name: 'Record credit' }).click()
  const form = detail.getByRole('form', { name: 'Record credit' })
  await expect(form.getByLabel('Amount credited')).toHaveValue('10')
  await form.getByLabel('Amount credited').fill('')
  await form.getByRole('button', { name: 'Save credit' }).click()
  await expect(form.getByRole('alert')).toHaveText('Enter the amount credited: 0 or more with at most four decimals.')
  await form.getByLabel('Amount credited').fill('9.5')
  await form.getByLabel('Credit note').fill('CN-12')
  await form.getByRole('button', { name: 'Save credit' }).click()
  await expect(form).toHaveCount(0)
  expect(sent).toEqual([{ amount: 9.5, creditNote: 'CN-12' }])

  // Credited, nothing is owed any more.
  await expect(item).toHaveText('2041-09-05 10:00 · FLOUR 4 back from WH-A · Wet bags · by demo-owner · credited 9.5 · CN-12 · by demo-owner'
    + ' Change credit')
  await expect(detail.getByLabel('Credit due')).toHaveCount(0)
  await expect(due).toHaveCount(0)
})
