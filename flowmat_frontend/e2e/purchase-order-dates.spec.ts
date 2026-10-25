import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/** An order being received moves its expected dates with a reason (docs/domain/purchasing.md PO22-PO23), mocked. */
test.use({ timezoneId: 'UTC' })

test('an order being received moves its dates with a reason and shows each change', async ({ page }) => {
  const projectId = 'prj-po-dates'
  const flour = { lineId: 'l1', lineNo: 1, itemId: 'flour', itemCode: 'FLOUR', itemName: 'flour', unitCode: 'kg', lotTracked: false, quantity: 10,
    receivedQuantity: 0, remainingQuantity: 10, expectedDate: null, note: null, unitPrice: null, lineValue: null }
  const salt = { ...flour, lineId: 'l2', lineNo: 2, itemId: 'salt', itemCode: 'SALT', itemName: 'salt', quantity: 5, receivedQuantity: 5,
    remainingQuantity: 0 }
  const order = { purchaseOrderId: 'po1', projectId, orderNumber: 'PO-0001', supplierName: 'Mill Co', status: 'ordered', expectedDate: '2041-09-10',
    note: null, createdBy: 'demo-owner', createdAt: '2041-09-01T09:00:00Z', orderedBy: 'demo-owner', orderedAt: '2041-09-01T09:05:00Z',
    endedBy: null, endedAt: null, endReason: null, orderValue: 0, valueComplete: false, receipts: [], dateChanges: [], lines: [flour, salt] }
  const change = { changeId: 'c1', lineId: null, itemCode: null, previousDate: '2041-09-10', expectedDate: '2041-09-15', reason: 'Supplier delayed',
    changedBy: 'demo-owner', changedAt: '2041-09-12T10:00:00Z' }
  let current: Record<string, unknown> = order
  const sent: unknown[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/purchase-orders/po1/expected-dates') {
      sent.push(request.postDataJSON())
      current = { ...order, expectedDate: '2041-09-15', lines: [{ ...flour, expectedDate: '2041-09-14' }, salt],
        dateChanges: [change, { ...change, changeId: 'c2', lineId: 'l1', itemCode: 'FLOUR', previousDate: null, expectedDate: '2041-09-14' }] }
      return ok(route, current)
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/purchase-orders') return ok(route, [current])
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Dates', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Dates', ownerId: 'demo-owner' })
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=purchasing`)
  await page.getByRole('table', { name: 'Purchase orders' }).getByRole('button', { name: 'PO-0001' }).click({ timeout: 15_000 })
  const detail = page.getByRole('region', { name: 'Purchase order PO-0001' })
  await detail.getByRole('button', { name: 'Change dates' }).click()
  const form = detail.getByRole('form', { name: 'Change dates' })
  // A fully received line keeps its date.
  await expect(form.getByLabel('SALT expected')).toHaveCount(0)
  await form.getByRole('button', { name: 'Save dates' }).click()
  await expect(form.getByRole('alert')).toHaveText('Change a date first.')
  await form.getByLabel('Order expected').fill('2041-09-15')
  await form.getByLabel('FLOUR expected').fill('2041-09-14')
  await form.getByRole('button', { name: 'Save dates' }).click()
  await expect(form.getByRole('alert')).toHaveText('Give a reason for the new dates.')
  await form.getByLabel('Reason').fill('Supplier delayed')
  await form.getByRole('button', { name: 'Save dates' }).click()
  await expect(form).toHaveCount(0)
  expect(sent).toEqual([{ expectedDate: '2041-09-15', lines: [{ lineId: 'l1', expectedDate: '2041-09-14' }], reason: 'Supplier delayed' }])
  await expect(detail.getByRole('list', { name: 'Date changes' }).getByRole('listitem')).toHaveText([
    '2041-09-12 10:00 · order 2041-09-10 → 2041-09-15 · Supplier delayed · by demo-owner',
    '2041-09-12 10:00 · FLOUR none → 2041-09-14 · Supplier delayed · by demo-owner',
  ])
  await expect(detail.getByRole('list', { name: 'Order lines' })).toContainText('FLOUR · 10 kg · expected 2041-09-14')
})
