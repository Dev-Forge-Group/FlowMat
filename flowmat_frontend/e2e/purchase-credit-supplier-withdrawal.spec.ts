import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

test.use({ timezoneId: 'UTC', locale: 'en-US' })

test('withdrawing a kept credit restores the supplier amount and return count after the orders refetch', async ({ page }) => {
  const projectId = 'prj-credit-withdraw-supplier'
  const project = { projectId, projectName: 'Supplier credit withdrawal', ownerId: 'demo-owner' }
  const supplier = { supplierId: 'supplier-1', projectId, supplierName: 'Mill Co', contact: null, leadTimeDays: null, note: null,
    active: true, version: 0, createdBy: 'demo-owner', createdAt: '2041-09-01T09:00:00Z', updatedBy: null, updatedAt: null }
  const line = { lineId: 'line-1', lineNo: 1, itemId: 'flour', itemCode: 'FLOUR', itemName: 'Flour', unitCode: 'kg', lotTracked: false,
    quantity: 10, receivedQuantity: 10, remainingQuantity: 0, expectedDate: null, note: null, unitPrice: 2.5, lineValue: 25,
    returnedQuantity: 4, replacementQuantity: 0 }
  const previousChange = { amount: 8, amountText: '8.0000', creditNote: 'CN-13', reason: 'Supplier sent less',
    changedBy: 'demo-owner', changedAt: '2041-09-10T10:00:00Z' }
  const back = { returnId: 'return-1', lineId: 'line-1', itemCode: 'FLOUR', quantity: 4, inventoryId: 'stock-1', location: 'WH-A',
    lotNo: null, reason: 'Wet bags', returnedBy: 'demo-owner', returnedAt: '2041-09-05T10:00:00Z', replacement: false,
    creditAmount: 8, creditAmountText: '8.0000', creditNote: 'CN-13', creditedBy: 'demo-owner', creditedAt: previousChange.changedAt,
    creditChanges: [previousChange] }
  const order = { purchaseOrderId: 'po-1', projectId, orderNumber: 'PO-0001', supplierName: supplier.supplierName, status: 'received',
    expectedDate: null, note: null, createdBy: 'demo-owner', createdAt: '2041-09-01T09:00:00Z', orderedBy: 'demo-owner',
    orderedAt: '2041-09-01T09:05:00Z', endedBy: 'demo-owner', endedAt: '2041-09-02T10:00:00Z', endReason: null,
    orderValue: 25, valueComplete: true, dateChanges: [], returns: [back], lines: [line], receipts: [{ receiptId: 'receipt-1',
      lineId: 'line-1', itemCode: 'FLOUR', quantity: 10, inventoryId: 'stock-1', location: 'WH-A', lotNo: null,
      receivedBy: 'demo-owner', receivedAt: '2041-09-02T10:00:00Z' }] }
  const withdrawnOrder = { ...order, returns: [{ ...back, creditAmount: null, creditAmountText: null, creditNote: null,
    creditedBy: null, creditedAt: null, creditChanges: [previousChange, { amount: null, amountText: null, creditNote: null,
      reason: 'Wrong return', changedBy: 'demo-owner', changedAt: '2041-09-11T10:00:00Z' }] }] }
  let withdrawn = false
  let orderReadsAfterWithdrawal = 0
  const sent: unknown[] = []
  const unexpectedApi: string[] = []
  const consoleErrors: string[] = []
  const pageErrors: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text())
  })
  page.on('pageerror', (error) => pageErrors.push(error.message))
  const emptyLists = new Set(['/api/items', '/api/units', '/api/inventories', '/api/supplier-items', '/api/inspection-standards',
    '/api/nonconformities', '/api/storage-locations', '/api/organizations', '/api/notifications', '/api/workflows'])
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'PUT' && pathname === '/api/purchase-orders/po-1/returns/return-1/credit') {
      const body = request.postDataJSON() as { amount: string | null; reason: string }
      sent.push(body)
      if (body.amount === null) {
        withdrawn = true
        return ok(route, withdrawnOrder)
      }
    }
    if (request.method() === 'GET') {
      if (pathname === '/api/purchase-orders') {
        if (withdrawn) orderReadsAfterWithdrawal += 1
        return ok(route, [withdrawn ? withdrawnOrder : order])
      }
      if (pathname === '/api/suppliers') return ok(route, [supplier])
      if (pathname === '/api/projects') return ok(route, [project])
      if (pathname === `/api/projects/${projectId}`) return ok(route, project)
      if (pathname === `/api/projects/${projectId}/time-zone`) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
      if (pathname === '/api/project-members') return ok(route, [{ projectMemberId: 'owner-member', projectId, userId: 'demo-owner',
        projectRole: 'owner', memberStatus: 'active', joinedAt: null }])
      if (pathname === '/api/purchase-settings') return ok(route, { projectId, approvalLimit: null, version: null,
        updatedBy: null, updatedAt: null, approvalSteps: [] })
      if (pathname === '/api/notifications/unread-count') return ok(route, { unread: 0 })
      if (emptyLists.has(pathname)) return ok(route, [])
    }
    unexpectedApi.push(`${request.method()} ${pathname}`)
    return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ success: false, data: null,
      message: 'Unexpected mocked API request' }) })
  })

  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=purchasing`)
  await page.getByRole('table', { name: 'Purchase orders' }).getByRole('button', { name: 'PO-0001' }).click({ timeout: 15_000 })
  const detail = page.getByRole('region', { name: 'Purchase order PO-0001' })
  const returned = detail.getByRole('list', { name: 'Returns' }).getByRole('listitem').first()
  await expect(returned).toContainText('credited 8')
  const suppliers = page.getByRole('region', { name: 'Suppliers', exact: true })
  await expect(suppliers.getByRole('table', { name: 'Supplier list' }).getByRole('row', { name: /Mill Co/ })).toBeVisible()
  const due = suppliers.getByRole('table', { name: 'Supplier credit due' })
  await expect(due).toHaveCount(0)

  await returned.getByRole('button', { name: 'Change credit' }).click()
  const form = detail.getByRole('form', { name: 'Change credit' })
  await form.getByLabel('Change reason').fill('Wrong return')
  await form.getByRole('button', { name: 'Withdraw credit' }).click()
  await expect(form).toHaveCount(0)
  expect(sent).toEqual([{ amount: null, reason: 'Wrong return' }])
  await expect.poll(() => orderReadsAfterWithdrawal).toBeGreaterThan(0)
  await expect(due.getByRole('row').nth(1).getByRole('cell')).toHaveText(['Mill Co', '1', '10', '0'])
  await expect(detail.getByLabel('Credit due')).toHaveText('Credit due 10')
  const history = returned.getByRole('list', { name: 'Credit changes' }).getByRole('listitem')
  await expect(history).toHaveCount(2)
  await expect(history.first()).toContainText('Supplier sent less')
  await expect(history.last()).toContainText('credit withdrawn')
  await expect(history.last()).toContainText('Wrong return')
  expect(unexpectedApi).toEqual([])
  expect(consoleErrors).toEqual([])
  expect(pageErrors).toEqual([])
})
