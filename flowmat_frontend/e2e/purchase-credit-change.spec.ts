import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/** A kept supplier credit is changed or withdrawn with a reason (docs/domain/purchasing.md PO52), mocked. */
test.use({ timezoneId: 'UTC' })

test('a kept credit is changed, then withdrawn so the return is owed again', async ({ page }) => {
  const projectId = 'prj-po-credit-change'
  const flour = { lineId: 'l1', lineNo: 1, itemId: 'flour', itemCode: 'FLOUR', itemName: 'flour', unitCode: 'kg', lotTracked: false, quantity: 10,
    receivedQuantity: 10, remainingQuantity: 0, expectedDate: null, note: null, unitPrice: 2.5, lineValue: 25, returnedQuantity: 4,
    replacementQuantity: 0 }
  const back = { returnId: 'b1', lineId: 'l1', itemCode: 'FLOUR', quantity: 4, inventoryId: 'f1', location: 'WH-A', lotNo: null, reason: 'Wet bags',
    returnedBy: 'demo-owner', returnedAt: '2041-09-05T10:00:00Z', replacement: false, creditAmount: 9.5, creditNote: 'CN-12',
    creditedBy: 'demo-owner', creditedAt: '2041-09-09T10:00:00Z', creditChanges: [] as unknown[] }
  const order = { purchaseOrderId: 'po1', projectId, orderNumber: 'PO-0001', supplierName: 'Mill Co', status: 'received', expectedDate: null,
    note: null, createdBy: 'demo-owner', createdAt: '2041-09-01T09:00:00Z', orderedBy: 'demo-owner', orderedAt: '2041-09-01T09:05:00Z',
    endedBy: 'demo-owner', endedAt: '2041-09-02T10:00:00Z', endReason: null, orderValue: 25, valueComplete: true, dateChanges: [], returns: [back],
    lines: [flour], receipts: [{ receiptId: 'r1', lineId: 'l1', itemCode: 'FLOUR', quantity: 10, inventoryId: 'f1', location: 'WH-A', lotNo: null,
      receivedBy: 'demo-owner', receivedAt: '2041-09-02T10:00:00Z' }] }
  const lessened = { amount: 8, creditNote: 'CN-13', reason: 'Supplier sent less', changedBy: 'demo-owner', changedAt: '2041-09-10T10:00:00Z' }
  let current: Record<string, unknown> = order
  const sent: unknown[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'PUT' && pathname === '/api/purchase-orders/po1/returns/b1/credit') {
      const body = request.postDataJSON() as { amount: number | null }
      sent.push(body)
      current = body.amount === null
        ? { ...order, returns: [{ ...back, creditAmount: null, creditNote: null, creditedBy: null, creditedAt: null, creditChanges: [lessened,
          { amount: null, creditNote: null, reason: 'Wrong return', changedBy: 'demo-owner', changedAt: '2041-09-11T10:00:00Z' }] }] }
        : { ...order, returns: [{ ...back, creditAmount: 8, creditNote: 'CN-13', creditedAt: lessened.changedAt, creditChanges: [lessened] }] }
      return ok(route, current)
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/purchase-orders') return ok(route, [current])
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Credit changes', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Credit changes', ownerId: 'demo-owner' })
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=purchasing`)
  await page.getByRole('table', { name: 'Purchase orders' }).getByRole('button', { name: 'PO-0001' }).click({ timeout: 15_000 })
  const detail = page.getByRole('region', { name: 'Purchase order PO-0001' })
  const item = detail.getByRole('list', { name: 'Returns' }).getByRole('listitem').first()

  // The supplier sent less: 8 on another note, with the reason.
  await item.getByRole('button', { name: 'Change credit' }).click()
  const form = detail.getByRole('form', { name: 'Change credit' })
  await expect(form.getByLabel('Amount credited')).toHaveValue('9.5')
  await expect(form.getByLabel('Credit note')).toHaveValue('CN-12')
  await form.getByLabel('Amount credited').fill('8')
  await form.getByLabel('Credit note').fill('CN-13')
  await form.getByRole('button', { name: 'Save credit' }).click()
  await expect(form.getByRole('alert')).toHaveText('Give a reason for the change.')
  await form.getByLabel('Change reason').fill('Supplier sent less')
  await form.getByRole('button', { name: 'Save credit' }).click()
  await expect(form).toHaveCount(0)
  await expect(item).toContainText('· credited 8 · CN-13 · by demo-owner')
  await expect(item.getByRole('list', { name: 'Credit changes' }).getByRole('listitem'))
    .toHaveText(['2041-09-10 10:00 · credit 8 · CN-13 · Supplier sent less · by demo-owner'])

  // It was the wrong return after all: withdrawn, it is owed again.
  await item.getByRole('button', { name: 'Change credit' }).click()
  await form.getByRole('button', { name: 'Withdraw credit' }).click()
  await expect(form.getByRole('alert')).toHaveText('Give a reason for withdrawing the credit.')
  await form.getByLabel('Change reason').fill('Wrong return')
  await form.getByRole('button', { name: 'Withdraw credit' }).click()
  await expect(form).toHaveCount(0)
  expect(sent).toEqual([{ amount: 8, creditNote: 'CN-13', reason: 'Supplier sent less' }, { amount: null, reason: 'Wrong return' }])
  await expect(item).toContainText('· credit due 10')
  await expect(item.getByRole('button', { name: 'Record credit' })).toBeVisible()
  await expect(item.getByRole('button', { name: 'Change credit' })).toHaveCount(0)
  await expect(detail.getByLabel('Credit due')).toHaveText('Credit due 10')
  // Recorded again, it is a change with its reason.
  await item.getByRole('button', { name: 'Record credit' }).click()
  await expect(detail.getByRole('form', { name: 'Change credit' }).getByLabel('Change reason')).toBeVisible()
})
