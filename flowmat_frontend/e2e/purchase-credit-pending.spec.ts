import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

type Mode = 'record' | 'change' | 'withdraw'
interface CreditInput { amount: string | null; creditNote?: string; reason?: string }
interface CreditChange {
  amount: number | null
  amountText: string | null
  creditNote: string | null
  reason: string
  changedBy: string
  changedAt: string
}
interface Returned {
  returnId: string
  lineId: string
  itemCode: string
  quantity: number
  inventoryId: string
  location: string
  lotNo: null
  reason: string
  returnedBy: string
  returnedAt: string
  replacement: boolean
  creditAmount: number | null
  creditAmountText: string | null
  creditNote: string | null
  creditedBy: string | null
  creditedAt: string | null
  creditChanges: CreditChange[]
}

test.use({ timezoneId: 'UTC', locale: 'en-US', trace: 'off' })

/** Mock REST controls the response order; backend credit storage/idempotency is verified separately. */
async function serve(page: Page, mode: Mode, options: { loseFirstResponse?: boolean; secondReturn?: boolean } = {}) {
  const projectId = 'prj-credit-pending'
  const project = { projectId, projectName: 'Credit editing', ownerId: 'demo-owner' }
  const returned = (returnId: string, recorded: boolean): Returned => ({ returnId, lineId: 'line-first', itemCode: 'FLOUR',
    quantity: 4, inventoryId: 'stock-first', location: 'WH-A', lotNo: null, reason: `Wet bags ${returnId}`,
    returnedBy: 'demo-owner', returnedAt: '2041-09-05T10:00:00Z', replacement: false,
    creditAmount: recorded ? 9.5 : null, creditAmountText: recorded ? '9.5' : null, creditNote: recorded ? 'CN-12' : null,
    creditedBy: recorded ? 'demo-owner' : null, creditedAt: recorded ? '2041-09-09T10:00:00Z' : null, creditChanges: [] })
  let current = { purchaseOrderId: 'po-first', projectId, orderNumber: 'PO-0001', supplierName: 'Mill Co', status: 'received',
    expectedDate: null, note: null, createdBy: 'demo-owner', createdAt: '2041-09-01T09:00:00Z', orderedBy: 'demo-owner',
    orderedAt: '2041-09-01T09:05:00Z', endedBy: 'demo-owner', endedAt: '2041-09-02T10:00:00Z', endReason: null,
    orderValue: 25, valueComplete: true, dateChanges: [], returns: [returned('back-first', mode !== 'record')],
    lines: [{ lineId: 'line-first', lineNo: 1, itemId: 'flour', itemCode: 'FLOUR', itemName: 'Flour', unitCode: 'kg', lotTracked: false,
      quantity: 10, receivedQuantity: 10, remainingQuantity: 0, expectedDate: null, note: null, unitPrice: 2.5, lineValue: 25,
      returnedQuantity: options.secondReturn ? 8 : 4, replacementQuantity: 0 }],
    receipts: [{ receiptId: 'receipt-first', lineId: 'line-first', itemCode: 'FLOUR', quantity: 10, inventoryId: 'stock-first',
      location: 'WH-A', lotNo: null, receivedBy: 'demo-owner', receivedAt: '2041-09-02T10:00:00Z' }] }
  if (options.secondReturn) current.returns.push(returned('back-second', false))
  const initialRecords = new Map<string, { amount: string; creditNote: string | null }>()
  if (mode !== 'record') initialRecords.set('back-first', { amount: '9.5', creditNote: 'CN-12' })
  const sent: { returnId: string; method: string; input: CreditInput }[] = []
  const unexpectedApi: string[] = []
  const pageErrors: string[] = []
  page.on('pageerror', error => pageErrors.push(error.message))
  let releaseFirstResponse!: () => void
  const gate = new Promise<void>(resolve => { releaseFirstResponse = resolve })
  const emptyLists = new Set(['/api/items', '/api/units', '/api/inventories', '/api/suppliers', '/api/supplier-items',
    '/api/inspection-standards', '/api/nonconformities', '/api/storage-locations', '/api/organizations', '/api/notifications', '/api/workflows'])
  await page.route(/^https?:\/\/[^/]+\/api\//, async route => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    const creditPath = /^\/api\/purchase-orders\/po-first\/returns\/(back-first|back-second)\/credit$/.exec(pathname)
    if (creditPath && ['POST', 'PUT'].includes(request.method())) {
      const returnId = creditPath[1]
      const input = request.postDataJSON() as CreditInput
      const method = request.method()
      sent.push({ returnId, method, input })
      const original = initialRecords.get(returnId)
      const note = input.amount === null ? null : input.creditNote ?? null
      const refused = (status: number, message: string) => route.fulfill({ status, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message }) })
      if (method === 'POST' && original && (Number(original.amount) !== Number(input.amount) || original.creditNote !== note)) {
        return refused(409, 'That return on PO-0001 was already credited.')
      }
      if (method === 'PUT' && (!original || !input.reason?.trim())) {
        return refused(original ? 400 : 409, original ? 'Give a reason.' : 'Record the credit first.')
      }
      if (method === 'POST' && !original) initialRecords.set(returnId, { amount: input.amount!, creditNote: note })
      current = { ...current, returns: current.returns.map(one => {
        if (one.returnId !== returnId) return one
        // POST replay preserves its original record; PUT changes only when the standing amount/note differs.
        if (method === 'POST' && original) return one
        if (method === 'PUT' && one.creditAmountText === input.amount && one.creditNote === note) return one
        const recordedAt = `2041-09-10T10:0${sent.length}:00Z`
        const change: CreditChange = { amount: input.amount === null ? null : Number(input.amount), amountText: input.amount,
          creditNote: note, reason: input.reason ?? '', changedBy: 'demo-owner', changedAt: recordedAt }
        return { ...one, creditAmount: change.amount, creditAmountText: change.amountText, creditNote: note,
          creditedBy: input.amount === null ? null : 'demo-owner', creditedAt: input.amount === null ? null : recordedAt,
          creditChanges: method === 'PUT' ? [...one.creditChanges, change] : one.creditChanges }
      }) }
      if (sent.length === 1) {
        await gate
        if (options.loseFirstResponse) return route.abort('failed')
      }
      return ok(route, current)
    }
    if (request.method() === 'GET') {
      if (pathname === '/api/purchase-orders') return ok(route, [current])
      if (pathname === '/api/projects') return ok(route, [project])
      if (pathname === `/api/projects/${projectId}`) return ok(route, project)
      if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
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
  await page.getByRole('table', { name: 'Purchase orders' }).getByRole('button', { name: 'PO-0001', exact: true }).click({ timeout: 15_000 })
  const detail = page.getByRole('region', { name: 'Purchase order PO-0001', exact: true })
  const returns = detail.getByRole('list', { name: 'Returns', exact: true }).locator(':scope > li')
  await returns.first().getByRole('button', { name: mode === 'record' ? 'Record credit' : 'Change credit', exact: true }).click()
  const form = detail.getByRole('form', { name: /^(Record|Change) credit$/ })
  await form.getByLabel('Amount credited', { exact: true }).fill(mode === 'change' ? '8' : '9.5')
  await form.getByLabel('Credit note', { exact: true }).fill(mode === 'change' ? 'CN-13' : 'CN-12')
  if (mode !== 'record') await form.getByLabel('Change reason', { exact: true }).fill(mode === 'change' ? 'Supplier sent less' : 'Wrong return')
  const submitFirst = () => form.getByRole('button', { name: mode === 'withdraw' ? 'Withdraw credit' : 'Save credit', exact: true }).click()
  return { detail, form, returns, sent, submitFirst, releaseFirstResponse, state: () => current, initialRecordCount: () => initialRecords.size,
    assertClean: () => { expect(unexpectedApi).toEqual([]); expect(pageErrors).toEqual([]) } }
}

for (const mode of ['record', 'change', 'withdraw'] as const) {
  test(`a confirmed ${mode} preserves newer credit input for the next intentional change`, async ({ page }) => {
    const f = await serve(page, mode)
    try {
      await f.submitFirst()
      await expect.poll(() => f.sent.length).toBe(1)
      const first = structuredClone(f.sent[0])
      await f.form.getByLabel('Amount credited', { exact: true }).fill('7')
      await f.form.getByLabel('Credit note', { exact: true }).fill('CN-NEXT')
      if (mode !== 'record') await f.form.getByLabel('Change reason', { exact: true }).fill('Next supplier advice')
      f.releaseFirstResponse()
      await expect(f.returns.first()).toContainText(mode === 'withdraw' ? 'credit due' : `credited ${mode === 'change' ? '8' : '9.5'}`)
      await expect(f.form.getByLabel('Amount credited', { exact: true })).toHaveValue('7')
      await expect(f.form.getByLabel('Credit note', { exact: true })).toHaveValue('CN-NEXT')
      expect(f.sent[0]).toEqual(first)
      if (mode === 'record') {
        await expect(f.form.getByLabel('Change reason', { exact: true })).toHaveValue('')
        await f.form.getByRole('button', { name: 'Save credit', exact: true }).click()
        await expect(f.form.getByRole('alert')).toHaveText('Give a reason for the change.')
        expect(f.sent).toHaveLength(1)
        await f.form.getByLabel('Change reason', { exact: true }).fill('Next supplier advice')
      } else await expect(f.form.getByLabel('Change reason', { exact: true })).toHaveValue('Next supplier advice')
      await f.form.getByRole('button', { name: 'Save credit', exact: true }).click()
      await expect(f.form).toHaveCount(0)
      expect(f.sent).toHaveLength(2)
      expect(f.sent[1]).toEqual({ returnId: 'back-first', method: 'PUT',
        input: { amount: '7', creditNote: 'CN-NEXT', reason: 'Next supplier advice' } })
      expect(f.state().returns[0].creditChanges).toHaveLength(mode === 'record' ? 1 : 2)
      await expect(f.returns.first()).toContainText('credited 7 · CN-NEXT')
      f.assertClean()
    } finally { f.releaseFirstResponse() }
  })
}

test('an unchanged record form closes only after its confirmed response', async ({ page }) => {
  const f = await serve(page, 'record')
  try {
    await f.submitFirst()
    await expect.poll(() => f.sent.length).toBe(1)
    await expect(f.form).toBeVisible()
    f.releaseFirstResponse()
    await expect(f.form).toHaveCount(0)
    expect(f.initialRecordCount()).toBe(1)
    expect(f.state().returns[0].creditChanges).toHaveLength(0)
    f.assertClean()
  } finally { f.releaseFirstResponse() }
})

test('an unconfirmed record keeps its input and retries the original credit only once', async ({ page }) => {
  const f = await serve(page, 'record', { loseFirstResponse: true })
  try {
    await f.submitFirst()
    await expect.poll(() => f.sent.length).toBe(1)
    f.releaseFirstResponse()
    await expect(f.form.getByRole('alert')).toBeVisible()
    await expect(f.form.getByLabel('Amount credited', { exact: true })).toHaveValue('9.5')
    await expect(f.form.getByLabel('Credit note', { exact: true })).toHaveValue('CN-12')
    await f.form.getByRole('button', { name: 'Save credit', exact: true }).click()
    await expect(f.form).toHaveCount(0)
    expect(f.sent).toHaveLength(2)
    expect(f.sent[1]).toEqual(f.sent[0])
    expect(f.initialRecordCount()).toBe(1)
    expect(f.state().returns[0].creditChanges).toHaveLength(0)
    f.assertClean()
  } finally { f.releaseFirstResponse() }
})

test('opening another return detaches the old local callback and preserves that return draft', async ({ page }) => {
  const f = await serve(page, 'record', { secondReturn: true })
  try {
    await f.submitFirst()
    await expect.poll(() => f.sent.length).toBe(1)
    await f.form.getByRole('button', { name: 'Close credit', exact: true }).click()
    await f.returns.nth(1).getByRole('button', { name: 'Record credit', exact: true }).click()
    await f.form.getByLabel('Amount credited', { exact: true }).fill('6')
    await f.form.getByLabel('Credit note', { exact: true }).fill('CN-SECOND')
    f.releaseFirstResponse()
    await expect(f.returns.first()).toContainText('credited 9.5 · CN-12')
    await expect(f.form.getByLabel('Amount credited', { exact: true })).toHaveValue('6')
    await expect(f.form.getByLabel('Credit note', { exact: true })).toHaveValue('CN-SECOND')
    expect(f.sent).toHaveLength(1)
    expect(f.state().returns[1].creditedAt).toBeNull()
    f.assertClean()
  } finally { f.releaseFirstResponse() }
})
