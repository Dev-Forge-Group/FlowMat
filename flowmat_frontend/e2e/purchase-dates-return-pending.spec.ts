import { expect, test, type Page } from '@playwright/test'
import type { PurchaseOrderDatesInput, PurchaseOrderDto, PurchaseReturnInput } from '../src/entities/purchasing/api/usePurchaseOrders'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

test.use({ timezoneId: 'UTC', locale: 'en-US' })

async function serve(page: Page, mode: 'dates' | 'return', options: { secondOrder?: boolean; loseFirstResponse?: boolean } = {}) {
  const projectId = 'prj-dates-return-pending'
  const project = { projectId, projectName: 'Pending purchase edits', ownerId: 'demo-owner' }
  const order = (suffix: string, orderNumber: string): PurchaseOrderDto => ({
    purchaseOrderId: `po-${suffix}`, projectId, orderNumber, supplierName: 'Mill Co', status: mode === 'dates' ? 'ordered' : 'received',
    expectedDate: '2041-09-10', note: null, createdBy: 'demo-owner', createdAt: '2041-09-01T09:00:00Z',
    orderedBy: 'demo-owner', orderedAt: '2041-09-01T09:05:00Z', endedBy: mode === 'return' ? 'demo-owner' : null,
    endedAt: mode === 'return' ? '2041-09-02T10:00:00Z' : null, endReason: null, orderValue: 25, valueComplete: true,
    dateChanges: [], returns: [], lines: [{ lineId: `line-${suffix}`, lineNo: 1, itemId: `flour-${suffix}`, itemCode: 'FLOUR',
      itemName: 'Flour', unitCode: 'kg', lotTracked: false, quantity: 10, receivedQuantity: mode === 'dates' ? 4 : 10,
      remainingQuantity: mode === 'dates' ? 6 : 0, returnedQuantity: 0, expectedDate: '2041-09-11', note: null,
      unitPrice: 2.5, lineValue: 25 }],
    receipts: [{ receiptId: `receipt-${suffix}`, lineId: `line-${suffix}`, itemCode: 'FLOUR', quantity: mode === 'dates' ? 4 : 10,
      inventoryId: `stock-${suffix}`, location: 'WH-A', lotNo: null, receivedBy: 'demo-owner', receivedAt: '2041-09-02T10:00:00Z' }],
  })
  let orders = [order('first', 'PO-0001')]
  if (options.secondOrder) orders.push(order('second', 'PO-0002'))
  let stock = orders.map((one) => ({ inventoryId: one.receipts[0].inventoryId, projectId, itemId: one.lines[0].itemId,
    quantity: one.lines[0].receivedQuantity, reservedQuantity: 0, availableQuantity: one.lines[0].receivedQuantity,
    inventoryStatus: 'available', location: 'WH-A', minThreshold: null, maxThreshold: null, stockLevel: 'ok', version: 1,
    lotId: null, lotNo: null }))
  const datesSent: { purchaseOrderId: string; input: PurchaseOrderDatesInput }[] = []
  const returnsSent: { purchaseOrderId: string; input: PurchaseReturnInput }[] = []
  const savedReturns = new Map<string, Map<string, PurchaseReturnInput>>()
  const unexpectedApi: string[] = []
  const pageErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))
  let releaseFirstResponse!: () => void
  const firstResponseGate = new Promise<void>((resolve) => { releaseFirstResponse = resolve })
  const emptyLists = new Set(['/api/items', '/api/units', '/api/suppliers', '/api/supplier-items', '/api/inspection-standards',
    '/api/nonconformities', '/api/storage-locations', '/api/organizations', '/api/notifications', '/api/workflows'])
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    const commandPath = /^\/api\/purchase-orders\/(po-first|po-second)\/(expected-dates|returns)$/.exec(pathname)
    if (request.method() === 'POST' && commandPath && commandPath[2] === (mode === 'dates' ? 'expected-dates' : 'returns')) {
      const purchaseOrderId = commandPath[1]
      const current = orders.find((one) => one.purchaseOrderId === purchaseOrderId)!
      if (mode === 'dates') {
        const input = request.postDataJSON() as PurchaseOrderDatesInput
        datesSent.push({ purchaseOrderId, input })
        const changes = [...(current.dateChanges ?? [])]
        const changedAt = `2041-09-12T10:0${datesSent.length}:00Z`
        if (input.expectedDate !== current.expectedDate) changes.push({ changeId: `date-${datesSent.length}-order`, lineId: null,
          itemCode: null, previousDate: current.expectedDate, expectedDate: input.expectedDate, reason: input.reason,
          changedBy: 'demo-owner', changedAt })
        const lines = current.lines.map((line) => {
          const wanted = input.lines.find((one) => one.lineId === line.lineId)
          if (!wanted || wanted.expectedDate === line.expectedDate) return line
          changes.push({ changeId: `date-${datesSent.length}-${line.lineId}`, lineId: line.lineId, itemCode: line.itemCode,
            previousDate: line.expectedDate, expectedDate: wanted.expectedDate, reason: input.reason, changedBy: 'demo-owner', changedAt })
          return { ...line, expectedDate: wanted.expectedDate }
        })
        orders = orders.map((one) => one.purchaseOrderId !== purchaseOrderId ? one
          : { ...one, expectedDate: input.expectedDate, lines, dateChanges: changes })
        const response = orders.find((one) => one.purchaseOrderId === purchaseOrderId)
        if (datesSent.length === 1) await firstResponseGate
        return ok(route, response)
      }
      const input = request.postDataJSON() as PurchaseReturnInput
      returnsSent.push({ purchaseOrderId, input })
      const previous = savedReturns.get(purchaseOrderId)?.get(input.requestId)
      if (previous && (previous.lineId !== input.lineId || previous.inventoryId.trim() !== input.inventoryId.trim()
        || previous.quantity !== input.quantity || previous.reason.trim() !== input.reason.trim()
        || (previous.nonconformityId?.trim() || null) !== (input.nonconformityId?.trim() || null)
        || Boolean(previous.replacement) !== Boolean(input.replacement))) {
        return route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ success: false, data: null,
          message: 'requestId already belongs to a different return.' }) })
      }
      if (!previous) {
        orders = orders.map((one) => one.purchaseOrderId !== purchaseOrderId ? one : { ...one,
          lines: one.lines.map((line) => line.lineId !== input.lineId ? line
            : { ...line, returnedQuantity: (line.returnedQuantity ?? 0) + input.quantity }),
          returns: [...(one.returns ?? []), { returnId: `return-${returnsSent.length}`, lineId: input.lineId, itemCode: 'FLOUR',
            quantity: input.quantity, inventoryId: input.inventoryId, location: 'WH-A', lotNo: null, reason: input.reason,
            nonconformityId: input.nonconformityId ?? null, ncrNo: null, replacement: input.replacement ?? false,
            returnedBy: 'demo-owner', returnedAt: `2041-09-05T10:0${returnsSent.length}:00Z`,
            creditAmount: null, creditAmountText: null, creditNote: null, creditedBy: null, creditedAt: null, creditChanges: [] }],
        })
        stock = stock.map((one) => one.inventoryId !== input.inventoryId ? one : { ...one,
          quantity: one.quantity - input.quantity, availableQuantity: one.availableQuantity - input.quantity, version: one.version + 1 })
        const commands = savedReturns.get(purchaseOrderId) ?? new Map<string, PurchaseReturnInput>()
        commands.set(input.requestId, input)
        savedReturns.set(purchaseOrderId, commands)
      }
      const response = orders.find((one) => one.purchaseOrderId === purchaseOrderId)
      if (returnsSent.length === 1) {
        await firstResponseGate
        if (options.loseFirstResponse) return route.abort('failed')
      }
      return ok(route, response)
    }
    if (request.method() === 'GET') {
      if (pathname === '/api/purchase-orders') return ok(route, orders)
      if (pathname === '/api/inventories') return ok(route, stock)
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
  const select = async (orderNumber: string) => {
    await page.getByRole('table', { name: 'Purchase orders' }).getByRole('button', { name: orderNumber, exact: true }).click({ timeout: 15_000 })
    const detail = page.getByRole('region', { name: `Purchase order ${orderNumber}`, exact: true })
    const formName = mode === 'dates' ? 'Change dates' : 'Return to supplier'
    await detail.getByRole('button', { name: formName, exact: true }).click()
    const form = detail.getByRole('form', { name: formName, exact: true })
    if (mode === 'return') await form.getByLabel('Replace on this order').uncheck()
    return { detail, form }
  }
  const first = await select('PO-0001')
  if (mode === 'dates') {
    await first.form.getByLabel('Order expected').fill('2041-09-15')
    await first.form.getByLabel('FLOUR expected').fill('2041-09-14')
    await first.form.getByLabel('Reason', { exact: true }).fill('Supplier delayed')
  } else {
    await first.form.getByLabel('Return quantity').fill('4')
    await first.form.getByLabel('Return reason').fill('Wet bags')
  }
  return { ...first, datesSent, returnsSent, select, releaseFirstResponse, orders: () => orders, stock: () => stock, assertClean: () => {
    expect(unexpectedApi).toEqual([])
    expect(pageErrors).toEqual([])
  } }
}

test('a confirmed date change preserves newer dates and reason for the next change', async ({ page }) => {
  const fixture = await serve(page, 'dates')
  const { detail, form, datesSent } = fixture
  try {
    await form.getByRole('button', { name: 'Save dates', exact: true }).click()
    await expect.poll(() => datesSent.length).toBe(1)
    await expect(form.getByRole('button', { name: 'Saving...', exact: true })).toBeDisabled()
    await form.getByLabel('Order expected').fill('2041-09-16')
    await form.getByLabel('FLOUR expected').fill('2041-09-17')
    await form.getByLabel('Reason', { exact: true }).fill('Second delivery agreed')
    const firstInput = { expectedDate: '2041-09-15', lines: [{ lineId: 'line-first', expectedDate: '2041-09-14' }], reason: 'Supplier delayed' }
    expect(datesSent[0].input).toEqual(firstInput)
    fixture.releaseFirstResponse()
    const history = detail.getByRole('list', { name: 'Date changes' }).getByRole('listitem')
    await expect(history).toHaveCount(2)
    await expect(history.first()).toContainText('Supplier delayed')
    await expect(form.getByLabel('Order expected')).toHaveValue('2041-09-16')
    await expect(form.getByLabel('FLOUR expected')).toHaveValue('2041-09-17')
    await expect(form.getByLabel('Reason', { exact: true })).toHaveValue('Second delivery agreed')
    await form.getByRole('button', { name: 'Save dates', exact: true }).click()
    await expect(form).toHaveCount(0)
    expect(datesSent).toHaveLength(2)
    expect(datesSent[0].input).toEqual(firstInput)
    expect(datesSent[1]).toEqual({ purchaseOrderId: 'po-first', input: { expectedDate: '2041-09-16',
      lines: [{ lineId: 'line-first', expectedDate: '2041-09-17' }], reason: 'Second delivery agreed' } })
    await expect(history).toHaveCount(4)
    expect(fixture.orders()[0].expectedDate).toBe('2041-09-16')
    fixture.assertClean()
  } finally {
    fixture.releaseFirstResponse()
  }
})

test('an unchanged date form closes after its confirmed response', async ({ page }) => {
  const fixture = await serve(page, 'dates')
  try {
    await fixture.form.getByRole('button', { name: 'Save dates', exact: true }).click()
    await expect.poll(() => fixture.datesSent.length).toBe(1)
    fixture.releaseFirstResponse()
    await expect(fixture.form).toHaveCount(0)
    await expect(fixture.detail.getByRole('list', { name: 'Date changes' }).getByRole('listitem')).toHaveCount(2)
    expect(fixture.datesSent).toHaveLength(1)
    fixture.assertClean()
  } finally {
    fixture.releaseFirstResponse()
  }
})

test('the previous date response cannot close a new dates form on another order', async ({ page }) => {
  const fixture = await serve(page, 'dates', { secondOrder: true })
  try {
    await fixture.form.getByRole('button', { name: 'Save dates', exact: true }).click()
    await expect.poll(() => fixture.datesSent.length).toBe(1)
    const second = await fixture.select('PO-0002')
    await second.form.getByLabel('Order expected').fill('2041-09-20')
    await second.form.getByLabel('Reason', { exact: true }).fill('Second order delivery')
    const refreshed = page.waitForResponse((response) => response.request().method() === 'GET'
      && new URL(response.url()).pathname === '/api/purchase-orders')
    fixture.releaseFirstResponse()
    await refreshed
    await expect(second.form.getByLabel('Order expected')).toHaveValue('2041-09-20')
    await expect(second.form.getByLabel('Reason', { exact: true })).toHaveValue('Second order delivery')
    await second.form.getByRole('button', { name: 'Save dates', exact: true }).click()
    await expect(second.form).toHaveCount(0)
    expect(fixture.datesSent).toHaveLength(2)
    expect(fixture.datesSent[1]).toEqual({ purchaseOrderId: 'po-second', input: { expectedDate: '2041-09-20', lines: [],
      reason: 'Second order delivery' } })
    expect(fixture.orders()[0].expectedDate).toBe('2041-09-15')
    expect(fixture.orders()[1].expectedDate).toBe('2041-09-20')
    fixture.assertClean()
  } finally {
    fixture.releaseFirstResponse()
  }
})

test('a confirmed return preserves newer input and gives the next return a fresh request ID', async ({ page }) => {
  const fixture = await serve(page, 'return')
  const { detail, form, returnsSent } = fixture
  try {
    await form.getByRole('button', { name: 'Save return', exact: true }).click()
    await expect.poll(() => returnsSent.length).toBe(1)
    await expect(form.getByRole('button', { name: 'Saving...', exact: true })).toBeDisabled()
    await form.getByLabel('Return quantity').fill('2')
    await form.getByLabel('Return reason').fill('Torn bags')
    const firstInput = { requestId: returnsSent[0].input.requestId, lineId: 'line-first', inventoryId: 'stock-first',
      quantity: 4, reason: 'Wet bags', replacement: false }
    expect(returnsSent[0].input).toEqual(firstInput)
    fixture.releaseFirstResponse()
    const history = detail.getByRole('list', { name: 'Returns' }).getByRole('listitem')
    await expect(history.first()).toContainText('FLOUR 4 back from WH-A')
    await expect(form.getByLabel('Return quantity')).toHaveValue('2')
    await expect(form.getByLabel('Return reason')).toHaveValue('Torn bags')
    await expect(form.getByLabel('Replace on this order')).not.toBeChecked()
    await form.getByRole('button', { name: 'Save return', exact: true }).click()
    await expect(form).toHaveCount(0)
    expect(returnsSent).toHaveLength(2)
    expect(returnsSent[0].input).toEqual(firstInput)
    expect(returnsSent[1].input.requestId).not.toBe(firstInput.requestId)
    expect(returnsSent[1]).toEqual({ purchaseOrderId: 'po-first', input: { requestId: expect.any(String), lineId: 'line-first',
      inventoryId: 'stock-first', quantity: 2, reason: 'Torn bags', replacement: false } })
    await expect(history).toHaveCount(2)
    await expect(history.last()).toContainText('Torn bags')
    expect(fixture.orders()[0].lines[0].returnedQuantity).toBe(6)
    expect(fixture.stock()[0].quantity).toBe(4)
    fixture.assertClean()
  } finally {
    fixture.releaseFirstResponse()
  }
})

test('an unchanged return form closes after its confirmed response', async ({ page }) => {
  const fixture = await serve(page, 'return')
  try {
    await fixture.form.getByRole('button', { name: 'Save return', exact: true }).click()
    await expect.poll(() => fixture.returnsSent.length).toBe(1)
    fixture.releaseFirstResponse()
    await expect(fixture.form).toHaveCount(0)
    await expect(fixture.detail.getByRole('list', { name: 'Returns' }).getByRole('listitem')).toContainText('FLOUR 4 back from WH-A')
    expect(fixture.returnsSent).toHaveLength(1)
    expect(fixture.stock()[0].quantity).toBe(6)
    fixture.assertClean()
  } finally {
    fixture.releaseFirstResponse()
  }
})

test('the previous return response cannot close a new return form on another order', async ({ page }) => {
  const fixture = await serve(page, 'return', { secondOrder: true })
  try {
    await fixture.form.getByRole('button', { name: 'Save return', exact: true }).click()
    await expect.poll(() => fixture.returnsSent.length).toBe(1)
    const second = await fixture.select('PO-0002')
    await second.form.getByLabel('Return quantity').fill('2')
    await second.form.getByLabel('Return reason').fill('Second order damage')
    const refreshed = page.waitForResponse((response) => response.request().method() === 'GET'
      && new URL(response.url()).pathname === '/api/purchase-orders')
    fixture.releaseFirstResponse()
    await refreshed
    await expect(second.form.getByLabel('Return quantity')).toHaveValue('2')
    await expect(second.form.getByLabel('Return reason')).toHaveValue('Second order damage')
    await second.form.getByRole('button', { name: 'Save return', exact: true }).click()
    await expect(second.form).toHaveCount(0)
    expect(fixture.returnsSent).toHaveLength(2)
    expect(fixture.returnsSent[1].input.requestId).not.toBe(fixture.returnsSent[0].input.requestId)
    expect(fixture.returnsSent[1]).toEqual({ purchaseOrderId: 'po-second', input: { requestId: expect.any(String), lineId: 'line-second',
      inventoryId: 'stock-second', quantity: 2, reason: 'Second order damage', replacement: false } })
    expect(fixture.orders()[0].lines[0].returnedQuantity).toBe(4)
    expect(fixture.orders()[1].lines[0].returnedQuantity).toBe(2)
    expect(fixture.stock().map((one) => one.quantity)).toEqual([6, 8])
    fixture.assertClean()
  } finally {
    fixture.releaseFirstResponse()
  }
})

test('an unconfirmed return retries the original request ID and decreases stock only once', async ({ page }) => {
  const fixture = await serve(page, 'return', { loseFirstResponse: true })
  try {
    await fixture.form.getByRole('button', { name: 'Save return', exact: true }).click()
    await expect.poll(() => fixture.returnsSent.length).toBe(1)
    fixture.releaseFirstResponse()
    await expect(fixture.form.getByRole('alert')).toBeVisible()
    await expect(fixture.form.getByLabel('Return quantity')).toHaveValue('4')
    await expect(fixture.form.getByLabel('Return reason')).toHaveValue('Wet bags')
    await fixture.form.getByRole('button', { name: 'Save return', exact: true }).click()
    await expect(fixture.form).toHaveCount(0)
    expect(fixture.returnsSent).toHaveLength(2)
    expect(fixture.returnsSent[1]).toEqual(fixture.returnsSent[0])
    expect(fixture.orders()[0].returns).toHaveLength(1)
    expect(fixture.orders()[0].lines[0].returnedQuantity).toBe(4)
    expect(fixture.stock()[0].quantity).toBe(6)
    await expect(fixture.detail.getByRole('list', { name: 'Returns' }).getByRole('listitem')).toHaveCount(1)
    fixture.assertClean()
  } finally {
    fixture.releaseFirstResponse()
  }
})
