import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

test.use({ timezoneId: 'UTC', locale: 'en-US' })

interface ReceiptLine {
  lineId: string
  quantity: number
  location: string | null
  lotNo: string | null
  expiryDate: string | null
}

interface ReceiptInput {
  requestId: string
  lines: ReceiptLine[]
}

interface ReceiptRecord {
  receiptId: string
  lineId: string
  itemCode: string
  quantity: number
  inventoryId: string
  location: string | null
  lotNo: string | null
  receivedBy: string
  receivedAt: string
}

async function serve(page: Page, options: { secondOrder?: boolean; loseFirstResponse?: boolean } = {}) {
  const projectId = 'prj-receipt-pending'
  const project = { projectId, projectName: 'Receipt editing', ownerId: 'demo-owner' }
  const order = (purchaseOrderId: string, orderNumber: string, lineId: string) => ({ purchaseOrderId, projectId, orderNumber,
    supplierName: 'Mill Co', status: 'ordered', expectedDate: null, note: null, createdBy: 'demo-owner', createdAt: '2041-09-01T09:00:00Z',
    orderedBy: 'demo-owner', orderedAt: '2041-09-01T09:05:00Z', endedBy: null, endedAt: null, endReason: null,
    orderValue: 25, valueComplete: true, dateChanges: [], returns: [], receipts: [] as ReceiptRecord[],
    lines: [{ lineId, lineNo: 1, itemId: 'flour', itemCode: 'FLOUR', itemName: 'Flour', unitCode: 'kg', lotTracked: false,
      quantity: 10, receivedQuantity: 0, remainingQuantity: 10, expectedDate: null, note: null, unitPrice: 2.5, lineValue: 25 }] })
  let orders = [order('po-first', 'PO-0001', 'line-first')]
  if (options.secondOrder) orders.push(order('po-second', 'PO-0002', 'line-second'))
  const sent: { purchaseOrderId: string; input: ReceiptInput }[] = []
  const saved = new Map<string, Map<string, ReceiptInput>>()
  const unexpectedApi: string[] = []
  const pageErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))
  let releaseFirstResponse!: () => void
  const firstResponseGate = new Promise<void>((resolve) => { releaseFirstResponse = resolve })
  const emptyLists = new Set(['/api/items', '/api/units', '/api/inventories', '/api/suppliers', '/api/supplier-items',
    '/api/inspection-standards', '/api/nonconformities', '/api/storage-locations', '/api/organizations', '/api/notifications', '/api/workflows'])
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    const receiptPath = /^\/api\/purchase-orders\/(po-first|po-second)\/receipts$/.exec(pathname)
    if (request.method() === 'POST' && receiptPath) {
      const purchaseOrderId = receiptPath[1]
      const input = request.postDataJSON() as ReceiptInput
      sent.push({ purchaseOrderId, input })
      const previous = saved.get(purchaseOrderId)?.get(input.requestId)
      if (previous && (previous.lines.length !== input.lines.length || previous.lines.some((before, index) => {
        const after = input.lines[index]
        return before.lineId !== after.lineId || before.quantity !== after.quantity
          || before.location !== after.location || before.lotNo !== after.lotNo
      }))) {
        return route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ success: false, data: null,
          message: 'requestId already belongs to a different receipt.' }) })
      }
      if (!previous) {
        const current = orders.find((one) => one.purchaseOrderId === purchaseOrderId)!
        const entry = input.lines[0]
        const receivedQuantity = current.lines[0].receivedQuantity + entry.quantity
        const receipt: ReceiptRecord = { receiptId: `receipt-${sent.length}`, lineId: entry.lineId, itemCode: 'FLOUR',
          quantity: entry.quantity, inventoryId: `stock-${sent.length}`, location: entry.location, lotNo: entry.lotNo,
          receivedBy: 'demo-owner', receivedAt: `2041-09-02T10:0${sent.length}:00Z` }
        orders = orders.map((one) => one.purchaseOrderId !== purchaseOrderId ? one : { ...one,
          lines: [{ ...one.lines[0], receivedQuantity, remainingQuantity: 10 - receivedQuantity }], receipts: [...one.receipts, receipt] })
        const commands = saved.get(purchaseOrderId) ?? new Map<string, ReceiptInput>()
        commands.set(input.requestId, input)
        saved.set(purchaseOrderId, commands)
      }
      const response = orders.find((one) => one.purchaseOrderId === purchaseOrderId)
      if (sent.length === 1) {
        await firstResponseGate
        if (options.loseFirstResponse) return route.abort('failed')
      }
      return ok(route, response)
    }
    if (request.method() === 'GET') {
      if (pathname === '/api/purchase-orders') return ok(route, orders)
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
    await detail.getByRole('button', { name: 'Receive', exact: true }).click()
    return { detail, form: detail.getByRole('form', { name: 'Receive', exact: true }) }
  }
  const first = await select('PO-0001')
  await first.form.getByLabel('Receive FLOUR').fill('4')
  await first.form.getByLabel('Location for FLOUR').fill('WH-A')
  return { ...first, sent, select, releaseFirstResponse, orders: () => orders, assertClean: () => {
    expect(unexpectedApi).toEqual([])
    expect(pageErrors).toEqual([])
  } }
}

test('a confirmed receipt preserves newer input and gives that next receipt a fresh request ID', async ({ page }) => {
  const fixture = await serve(page)
  const { detail, form, sent } = fixture
  try {
    await form.getByRole('button', { name: 'Save receipt', exact: true }).click()
    await expect.poll(() => sent.length).toBe(1)
    await expect(form.getByRole('button', { name: 'Saving...', exact: true })).toBeDisabled()
    await form.getByLabel('Receive FLOUR').fill('2')
    await form.getByLabel('Location for FLOUR').fill('WH-B')
    const firstInput = { requestId: sent[0].input.requestId,
      lines: [{ lineId: 'line-first', quantity: 4, location: 'WH-A', lotNo: null, expiryDate: null }] }
    expect(sent[0].input).toEqual(firstInput)
    fixture.releaseFirstResponse()
    const receipts = detail.getByRole('list', { name: 'Receipts' }).getByRole('listitem')
    await expect(receipts.first()).toContainText('FLOUR 4 at WH-A')
    await expect(form.getByLabel('Receive FLOUR')).toHaveValue('2')
    await expect(form.getByLabel('Location for FLOUR')).toHaveValue('WH-B')
    await form.getByRole('button', { name: 'Save receipt', exact: true }).click()
    await expect(form).toHaveCount(0)
    expect(sent).toHaveLength(2)
    expect(sent[0].input).toEqual(firstInput)
    expect(sent[1].input.requestId).not.toBe(firstInput.requestId)
    expect(sent[1].input).toEqual({ requestId: expect.any(String),
      lines: [{ lineId: 'line-first', quantity: 2, location: 'WH-B', lotNo: null, expiryDate: null }] })
    await expect(receipts).toHaveCount(2)
    await expect(receipts.last()).toContainText('FLOUR 2 at WH-B')
    expect(fixture.orders()[0].lines[0].receivedQuantity).toBe(6)
    fixture.assertClean()
  } finally {
    fixture.releaseFirstResponse()
  }
})

test('an unchanged receipt form closes after its confirmed response', async ({ page }) => {
  const fixture = await serve(page)
  try {
    await fixture.form.getByRole('button', { name: 'Save receipt', exact: true }).click()
    await expect.poll(() => fixture.sent.length).toBe(1)
    fixture.releaseFirstResponse()
    await expect(fixture.form).toHaveCount(0)
    await expect(fixture.detail.getByRole('list', { name: 'Receipts' }).getByRole('listitem')).toContainText('FLOUR 4 at WH-A')
    expect(fixture.sent).toHaveLength(1)
    fixture.assertClean()
  } finally {
    fixture.releaseFirstResponse()
  }
})

test('the previous receipt response cannot close a new receipt form on another order', async ({ page }) => {
  const fixture = await serve(page, { secondOrder: true })
  try {
    await fixture.form.getByRole('button', { name: 'Save receipt', exact: true }).click()
    await expect.poll(() => fixture.sent.length).toBe(1)
    const second = await fixture.select('PO-0002')
    await second.form.getByLabel('Receive FLOUR').fill('2')
    await second.form.getByLabel('Location for FLOUR').fill('WH-B')
    const refreshed = page.waitForResponse((response) => response.request().method() === 'GET'
      && new URL(response.url()).pathname === '/api/purchase-orders')
    fixture.releaseFirstResponse()
    await refreshed
    await expect(second.form.getByLabel('Receive FLOUR')).toHaveValue('2')
    await expect(second.form.getByLabel('Location for FLOUR')).toHaveValue('WH-B')
    await second.form.getByRole('button', { name: 'Save receipt', exact: true }).click()
    await expect(second.form).toHaveCount(0)
    expect(fixture.sent).toHaveLength(2)
    expect(fixture.sent[1].purchaseOrderId).toBe('po-second')
    expect(fixture.sent[1].input.requestId).not.toBe(fixture.sent[0].input.requestId)
    expect(fixture.sent[1].input.lines).toEqual([{ lineId: 'line-second', quantity: 2, location: 'WH-B', lotNo: null, expiryDate: null }])
    expect(fixture.orders()[0].lines[0].receivedQuantity).toBe(4)
    expect(fixture.orders()[1].lines[0].receivedQuantity).toBe(2)
    fixture.assertClean()
  } finally {
    fixture.releaseFirstResponse()
  }
})

test('an unconfirmed receipt retries the original request ID and records stock only once', async ({ page }) => {
  const fixture = await serve(page, { loseFirstResponse: true })
  try {
    await fixture.form.getByRole('button', { name: 'Save receipt', exact: true }).click()
    await expect.poll(() => fixture.sent.length).toBe(1)
    fixture.releaseFirstResponse()
    await expect(fixture.form.getByRole('alert')).toBeVisible()
    await expect(fixture.form.getByLabel('Receive FLOUR')).toHaveValue('4')
    await expect(fixture.form.getByLabel('Location for FLOUR')).toHaveValue('WH-A')
    await fixture.form.getByRole('button', { name: 'Save receipt', exact: true }).click()
    await expect(fixture.form).toHaveCount(0)
    expect(fixture.sent).toHaveLength(2)
    expect(fixture.sent[1]).toEqual(fixture.sent[0])
    expect(fixture.orders()[0].receipts).toHaveLength(1)
    expect(fixture.orders()[0].lines[0].receivedQuantity).toBe(4)
    fixture.assertClean()
  } finally {
    fixture.releaseFirstResponse()
  }
})
