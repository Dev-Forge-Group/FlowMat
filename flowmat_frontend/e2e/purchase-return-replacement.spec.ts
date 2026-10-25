import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/** Stock sent back to be replaced comes again on the same order (docs/domain/purchasing.md PO46), mocked. */
test.use({ timezoneId: 'UTC' })

const projectId = 'prj-po-replace'
const flour = { lineId: 'l1', lineNo: 1, itemId: 'flour', itemCode: 'FLOUR', itemName: 'flour', unitCode: 'kg', lotTracked: false, quantity: 10,
  receivedQuantity: 10, remainingQuantity: 0, expectedDate: null, note: null, unitPrice: null, lineValue: null, returnedQuantity: 0,
  replacementQuantity: 0 }
const receipt = { receiptId: 'r1', lineId: 'l1', itemCode: 'FLOUR', quantity: 10, inventoryId: 'f1', location: 'WH-A', lotNo: null,
  receivedBy: 'demo-owner', receivedAt: '2041-09-02T10:00:00Z' }
const order = { purchaseOrderId: 'po1', projectId, orderNumber: 'PO-0001', supplierName: 'Mill Co', status: 'received', expectedDate: null,
  note: null, createdBy: 'demo-owner', createdAt: '2041-09-01T09:00:00Z', orderedBy: 'demo-owner', orderedAt: '2041-09-01T09:05:00Z',
  endedBy: 'demo-owner', endedAt: '2041-09-02T10:00:00Z', endReason: null, orderValue: 0, valueComplete: false, dateChanges: [], returns: [],
  lines: [flour], receipts: [receipt] }
const back = { returnId: 'b1', lineId: 'l1', itemCode: 'FLOUR', quantity: 4, inventoryId: 'f1', location: 'WH-A', lotNo: null, reason: 'Wet bags',
  returnedBy: 'demo-owner', returnedAt: '2041-09-05T10:00:00Z', replacement: true }
const stock = { inventoryId: 'f1', projectId, itemId: 'flour', quantity: 10, reservedQuantity: 0, availableQuantity: 10, inventoryStatus: 'available',
  location: 'WH-A', minThreshold: null, maxThreshold: null, stockLevel: 'ok', version: 1, lotId: null, lotNo: null }

/** Serves the order as given, then as a replacement return and a receipt leave it; opens it. */
async function serve(page: Page, first: Record<string, unknown>, sent: { path: string; body: unknown }[]) {
  let current = first
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/purchase-orders/po1/returns') {
      sent.push({ path: pathname, body: request.postDataJSON() })
      // Replaced on the order: what went back is to come again, so the order is being received again.
      current = { ...current, status: 'ordered', endedBy: null, endedAt: null, returns: [back],
        lines: [{ ...flour, returnedQuantity: 4, replacementQuantity: 4, remainingQuantity: 4 }] }
      return ok(route, current)
    }
    if (request.method() === 'POST' && pathname === '/api/purchase-orders/po1/receipts') {
      sent.push({ path: pathname, body: request.postDataJSON() })
      current = { ...current, status: 'received', endedBy: 'demo-owner', endedAt: '2041-09-08T10:00:00Z',
        lines: [{ ...flour, receivedQuantity: 14, returnedQuantity: 4, replacementQuantity: 4, remainingQuantity: 0 }],
        receipts: [receipt, { ...receipt, receiptId: 'r2', quantity: 4, receivedAt: '2041-09-08T10:00:00Z' }] }
      return ok(route, current)
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/purchase-orders') return ok(route, [current])
    if (pathname === '/api/inventories') return ok(route, [stock])
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Replacements', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Replacements', ownerId: 'demo-owner' })
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=purchasing`)
  await page.getByRole('table', { name: 'Purchase orders' }).getByRole('button', { name: 'PO-0001' }).click({ timeout: 15_000 })
  return page.getByRole('region', { name: 'Purchase order PO-0001' })
}

test('stock sent back to be replaced is received again on the same order', async ({ page }) => {
  const sent: { path: string; body: unknown }[] = []
  const detail = await serve(page, order, sent)
  const heading = detail.getByRole('heading', { name: /^PO-0001 · Mill Co/ })
  await detail.getByRole('button', { name: 'Return to supplier' }).click()
  const form = detail.getByRole('form', { name: 'Return to supplier' })
  await expect(form.getByLabel('Replace on this order')).toBeChecked()
  await expect(form).toContainText('It is to come again on PO-0001 and counts as on order until it has.')
  await form.getByLabel('Return quantity').fill('4')
  await form.getByLabel('Return reason').fill('Wet bags')
  await form.getByRole('button', { name: 'Save return' }).click()
  await expect(form).toHaveCount(0)
  expect(sent.map((one) => one.body)).toEqual([{ requestId: expect.any(String), lineId: 'l1', inventoryId: 'f1', quantity: 4, reason: 'Wet bags',
    replacement: true }])
  await expect(heading).toHaveText('PO-0001 · Mill Co · ordered')
  await expect(detail.getByRole('list', { name: 'Order lines' })).toContainText('FLOUR · 6 of 10 kg received · 4 returned, 4 for replacement')
  await expect(detail.getByRole('list', { name: 'Returns' }).getByRole('listitem'))
    .toHaveText('2041-09-05 10:00 · FLOUR 4 back from WH-A · Wet bags · replacement on this order · by demo-owner')

  // The replacement is all that is left to receive.
  await detail.getByRole('button', { name: 'Receive', exact: true }).click()
  const receiving = detail.getByRole('form', { name: 'Receive' })
  await expect(receiving.getByRole('cell', { name: 'FLOUR (4 left)' })).toBeVisible()
  await expect(receiving.getByLabel('Receive FLOUR')).toHaveValue('4')
  await receiving.getByLabel('Location for FLOUR').fill('WH-A')
  await receiving.getByRole('button', { name: 'Save receipt' }).click()
  await expect(receiving).toHaveCount(0)
  expect(sent[1]).toEqual({ path: '/api/purchase-orders/po1/receipts', body: { requestId: expect.any(String),
    lines: [{ lineId: 'l1', quantity: 4, location: 'WH-A', lotNo: null, expiryDate: null }] } })
  await expect(heading).toHaveText('PO-0001 · Mill Co · received')
  await expect(detail.getByRole('list', { name: 'Order lines' })).toContainText('FLOUR · 10 of 10 kg received · 4 returned, 4 for replacement')
  await expect(detail.getByRole('button', { name: 'Receive', exact: true })).toHaveCount(0)
})

test('a closed order is not replaced on', async ({ page }) => {
  const sent: { path: string; body: unknown }[] = []
  const detail = await serve(page, { ...order, status: 'closed', endReason: 'Supplier stopped', lines: [{ ...flour, receivedQuantity: 6,
    remainingQuantity: 4 }], receipts: [{ ...receipt, quantity: 6 }] }, sent)
  await detail.getByRole('button', { name: 'Return to supplier' }).click()
  const form = detail.getByRole('form', { name: 'Return to supplier' })
  await expect(form.getByLabel('Replace on this order')).not.toBeChecked()
  await expect(form.getByLabel('Replace on this order')).toBeDisabled()
  await expect(form.getByRole('note', { name: 'Replacement' })).toHaveText('PO-0001 is closed, so a replacement is ordered anew.')
})
