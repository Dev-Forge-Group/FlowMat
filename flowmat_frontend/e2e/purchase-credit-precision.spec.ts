import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

test.use({ timezoneId: 'UTC', locale: 'en-US' })

const exactAmount = '12345678901234567890.1234'
const displayedAmount = '12,345,678,901,234,567,890.1234'

interface CreditChange {
  amount: number | null
  amountText?: string | null
  creditNote: string | null
  reason: string
  changedBy: string
  changedAt: string
}

async function openCredit(page: Page, credited: boolean, exactFields = true) {
  const projectId = 'prj-credit-precision'
  const project = { projectId, projectName: 'Credit precision', ownerId: 'demo-owner' }
  const sent: unknown[] = []
  const unexpectedApi: string[] = []
  const consoleErrors: string[] = []
  const pageErrors: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text())
  })
  page.on('pageerror', (error) => pageErrors.push(error.message))

  const savedChange: CreditChange = { amount: Number(exactAmount), amountText: exactFields ? exactAmount : undefined, creditNote: 'CN-12',
    reason: 'Supplier confirmed amount', changedBy: 'demo-owner', changedAt: '2041-09-09T10:00:00Z' }
  let credit = {
    creditAmount: credited ? Number(exactAmount) : null,
    creditAmountText: credited ? (exactFields ? exactAmount : undefined) : null,
    creditNote: credited ? 'CN-12' : null,
    creditedBy: credited ? 'demo-owner' : null,
    creditedAt: credited ? savedChange.changedAt : null,
    creditChanges: credited ? [savedChange] : [],
  }
  const currentOrder = () => ({ purchaseOrderId: 'po1', projectId, orderNumber: 'PO-0001', supplierName: 'Mill Co', status: 'received',
    expectedDate: null, note: null, createdBy: 'demo-owner', createdAt: '2041-09-01T09:00:00Z', orderedBy: 'demo-owner',
    orderedAt: '2041-09-01T09:05:00Z', endedBy: 'demo-owner', endedAt: '2041-09-02T10:00:00Z', endReason: null,
    orderValue: 25, valueComplete: true, dateChanges: [],
    lines: [{ lineId: 'l1', lineNo: 1, itemId: 'flour', itemCode: 'FLOUR', itemName: 'Flour', unitCode: 'kg', lotTracked: false,
      quantity: 10, receivedQuantity: 10, remainingQuantity: 0, expectedDate: null, note: null, unitPrice: 2.5, lineValue: 25,
      returnedQuantity: 4, replacementQuantity: 0 }],
    receipts: [{ receiptId: 'r1', lineId: 'l1', itemCode: 'FLOUR', quantity: 10, inventoryId: 'f1', location: 'WH-A', lotNo: null,
      receivedBy: 'demo-owner', receivedAt: '2041-09-02T10:00:00Z' }],
    returns: [{ returnId: 'b1', lineId: 'l1', itemCode: 'FLOUR', quantity: 4, inventoryId: 'f1', location: 'WH-A', lotNo: null,
      reason: 'Wet bags', returnedBy: 'demo-owner', returnedAt: '2041-09-05T10:00:00Z', replacement: false, ...credit }],
  })
  const emptyLists = new Set(['/api/items', '/api/units', '/api/inventories', '/api/suppliers', '/api/supplier-items',
    '/api/inspection-standards', '/api/nonconformities', '/api/storage-locations', '/api/notifications', '/api/organizations', '/api/workflows'])
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (['POST', 'PUT'].includes(request.method()) && pathname === '/api/purchase-orders/po1/returns/b1/credit') {
      const body = request.postDataJSON() as { amount: string | number | null; creditNote?: string; reason?: string }
      sent.push(body)
      const withdrawn = body.amount === null
      const changedAt = '2041-09-10T10:00:00Z'
      credit = { creditAmount: withdrawn ? null : Number(body.amount), creditAmountText: withdrawn ? null : String(body.amount),
        creditNote: withdrawn ? null : body.creditNote ?? null, creditedBy: withdrawn ? null : 'demo-owner',
        creditedAt: withdrawn ? null : changedAt, creditChanges: request.method() === 'PUT'
          ? [...credit.creditChanges, { amount: withdrawn ? null : Number(body.amount), amountText: withdrawn ? null : String(body.amount),
            creditNote: withdrawn ? null : body.creditNote ?? null, reason: body.reason ?? '', changedBy: 'demo-owner', changedAt }]
          : credit.creditChanges }
      return ok(route, currentOrder())
    }
    if (request.method() === 'GET') {
      if (pathname === '/api/purchase-orders') return ok(route, [currentOrder()])
      if (pathname === '/api/projects') return ok(route, [project])
      if (pathname === `/api/projects/${projectId}`) return ok(route, project)
      if (pathname === `/api/projects/${projectId}/time-zone`) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
      if (pathname === '/api/project-members') return ok(route, [{ projectMemberId: 'm1', projectId, userId: 'demo-owner',
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
  return { detail, returned, sent, assertClean: () => {
    expect(unexpectedApi).toEqual([])
    expect(consoleErrors).toEqual([])
    expect(pageErrors).toEqual([])
  } }
}

test('recording a credit sends all twenty integer digits and four decimals unchanged', async ({ page }) => {
  const { detail, returned, sent, assertClean } = await openCredit(page, false)
  await returned.getByRole('button', { name: 'Record credit' }).click()
  const form = detail.getByRole('form', { name: 'Record credit' })
  await form.getByLabel('Amount credited').fill(exactAmount)
  await form.getByLabel('Amount credited').press('ArrowUp')
  await expect(form.getByLabel('Amount credited')).toHaveValue(exactAmount)
  await form.getByLabel('Credit note').fill('CN-12')
  await form.getByRole('button', { name: 'Save credit' }).click()
  await expect(form).toHaveCount(0)
  expect(sent).toEqual([{ amount: exactAmount, creditNote: 'CN-12' }])
  await expect(returned).toContainText(`credited ${displayedAmount}`)
  assertClean()
})

test('a note-only credit change keeps the exact server amount in the form, request and history', async ({ page }) => {
  const { detail, returned, sent, assertClean } = await openCredit(page, true)
  await returned.getByRole('button', { name: 'Change credit' }).click()
  const form = detail.getByRole('form', { name: 'Change credit' })
  await expect(form.getByLabel('Amount credited')).toHaveValue(exactAmount)
  await expect(returned).toContainText(`credited ${displayedAmount}`)
  const history = returned.getByRole('list', { name: 'Credit changes' }).getByRole('listitem')
  await expect(history).toHaveCount(1)
  await expect(history.first()).toContainText(`credit ${displayedAmount}`)
  await form.getByLabel('Credit note').fill('CN-13')
  await form.getByLabel('Change reason').fill('Correct note number')
  await form.getByRole('button', { name: 'Save credit' }).click()
  await expect(form).toHaveCount(0)
  expect(sent).toEqual([{ amount: exactAmount, creditNote: 'CN-13', reason: 'Correct note number' }])
  await expect(history).toHaveCount(2)
  await expect(history.last()).toContainText(`credit ${displayedAmount}`)
  await expect(history.last()).toContainText('Correct note number')
  assertClean()
})

test('withdrawing a precise credit still sends null and leaves its exact recorded history visible', async ({ page }) => {
  const { detail, returned, sent, assertClean } = await openCredit(page, true)
  await returned.getByRole('button', { name: 'Change credit' }).click()
  const form = detail.getByRole('form', { name: 'Change credit' })
  await form.getByLabel('Change reason').fill('Wrong return')
  await form.getByRole('button', { name: 'Withdraw credit' }).click()
  await expect(form).toHaveCount(0)
  expect(sent).toEqual([{ amount: null, reason: 'Wrong return' }])
  await expect(returned.getByRole('button', { name: 'Record credit' })).toBeVisible()
  const history = returned.getByRole('list', { name: 'Credit changes' }).getByRole('listitem')
  await expect(history).toHaveCount(2)
  await expect(history.first()).toContainText(`credit ${displayedAmount}`)
  await expect(history.last()).toContainText('credit withdrawn')
  assertClean()
})


test('a legacy response with an unsafe amount requires explicit entry before a note change can save', async ({ page }) => {
  const { detail, returned, sent, assertClean } = await openCredit(page, true, false)
  await returned.getByRole('button', { name: 'Change credit' }).click()
  const form = detail.getByRole('form', { name: 'Change credit' })
  const warning = form.getByText('The saved credit amount could not be read exactly. Enter the correct amount before saving.', { exact: true })
  await expect(form.getByLabel('Amount credited')).toHaveValue('')
  await expect(warning).toBeVisible()
  await form.getByLabel('Credit note').fill('CN-13')
  await form.getByLabel('Change reason').fill('Correct note number')
  await form.getByRole('button', { name: 'Save credit' }).click()
  await expect(form.getByText('Enter the amount credited: 0 or more with at most four decimals.', { exact: true })).toBeVisible()
  expect(sent).toEqual([])
  await form.getByLabel('Amount credited').fill(exactAmount)
  await expect(warning).toHaveCount(0)
  await form.getByRole('button', { name: 'Save credit' }).click()
  await expect(form).toHaveCount(0)
  expect(sent).toEqual([{ amount: exactAmount, creditNote: 'CN-13', reason: 'Correct note number' }])
  await expect(returned).toContainText(`credited ${displayedAmount}`)
  assertClean()
})
