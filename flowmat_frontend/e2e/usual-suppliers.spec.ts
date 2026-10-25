import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/** Items' usual suppliers, and drafts that go one per usual supplier (docs/domain/purchasing.md PO29-PO31), mocked. */
test.use({ timezoneId: 'UTC' })

const projectId = 'prj-usual-suppliers'
const item = (itemId: string, extra: Record<string, unknown> = {}) => ({ itemId, projectId, itemCode: itemId.toUpperCase(), itemName: itemId,
  itemType: 'material', resourceCategory: 'material', unitId: 'unit_kg', itemStatus: 'active', lotManageYn: 'N', unitCost: null, ...extra })
const mill = { supplierId: 's1', projectId, supplierName: 'Mill Co', contact: null, leadTimeDays: 5, note: null, active: true, version: 0,
  createdBy: 'demo-owner', createdAt: '2041-09-01T09:00:00Z', updatedBy: null, updatedAt: null }

test('an item gets a usual supplier with that supplier\'s code', async ({ page }) => {
  const sent: unknown[] = []
  let usual: Record<string, unknown>[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'PUT' && pathname === '/api/supplier-items') {
      const body = request.postDataJSON() as Record<string, unknown>
      sent.push(body)
      usual = [{ supplierItemId: 'si1', projectId, itemId: 'flour', itemCode: 'FLOUR', supplierId: 's1', supplierName: 'Mill Co', supplierActive: true,
        supplierItemCode: body.supplierItemCode, unitPrice: body.unitPrice, version: 0, updatedBy: 'demo-owner', updatedAt: '2041-09-28T10:00:00Z' }]
      return ok(route, usual[0])
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/suppliers') return ok(route, [mill])
    if (pathname === '/api/supplier-items') return ok(route, usual)
    if (pathname === '/api/items') return ok(route, [item('flour'), item('salt')])
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Usual', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Usual', ownerId: 'demo-owner' })
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=purchasing`)
  const form = page.getByRole('form', { name: 'Usual supplier' })
  await form.getByLabel('Item').selectOption({ label: 'FLOUR · flour' }, { timeout: 15_000 })
  await form.getByLabel('Usual supplier').selectOption({ label: 'Mill Co' })
  await form.getByLabel('Their code').fill('MC-77')
  await form.getByLabel('Their price').fill('1.25')
  await form.getByRole('button', { name: 'Save usual supplier' }).click()
  await expect(page.getByRole('table', { name: 'Usual suppliers' }).getByRole('row').nth(1)).toHaveText('FLOURMill CoMC-771.25')
  expect(sent).toEqual([{ projectId, itemId: 'flour', supplierId: 's1', supplierItemCode: 'MC-77', unitPrice: 1.25, expectedVersion: null }])
})

test('short materials are drafted one purchase order per usual supplier', async ({ page }) => {
  await page.clock.setFixedTime(new Date('2041-09-28T10:00:00Z'))
  const needs = { projectId, orders: 1, problems: [], lines: [
    { itemId: 'flour', itemCode: 'FLOUR', itemName: 'flour', unit: 'kg', required: 12, usable: 5, shortage: 7, plannedSupply: 0, onOrder: 0,
      madeHere: false, orders: [{ workOrderId: 'w1', workOrderTitle: 'Bread', required: 12 }] },
    { itemId: 'salt', itemCode: 'SALT', itemName: 'salt', unit: 'kg', required: 3, usable: 1, shortage: 2, plannedSupply: 0, onOrder: 0,
      madeHere: false, orders: [{ workOrderId: 'w1', workOrderTitle: 'Bread', required: 3 }] },
  ] }
  const drafts: { supplierName: string; expectedDate: string | null; lines: { itemId: string; unitPrice?: number }[] }[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/purchase-orders') {
      const body = request.postDataJSON() as { supplierName: string; expectedDate: string | null; lines: { itemId: string; quantity: number }[] }
      drafts.push(body)
      const number = `PO-000${drafts.length}`
      return ok(route, { purchaseOrderId: number, projectId, orderNumber: number, supplierName: body.supplierName, status: 'draft',
        expectedDate: body.expectedDate, note: null, createdBy: 'demo-owner', createdAt: '2041-09-28T10:00:00Z', orderedBy: null, orderedAt: null,
        endedBy: null, endedAt: null, endReason: null, receipts: [], lines: body.lines.map((one, index) => ({ lineId: `l${index}`, lineNo: index + 1,
          itemId: one.itemId, itemCode: one.itemId.toUpperCase(), itemName: one.itemId, unitCode: 'kg', lotTracked: false, quantity: one.quantity,
          receivedQuantity: 0, remainingQuantity: one.quantity, expectedDate: null, note: null })) })
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/material-requirements') return ok(route, needs)
    if (pathname === '/api/suppliers') return ok(route, [mill])
    if (pathname === '/api/supplier-items') {
      return ok(route, [{ supplierItemId: 'si1', projectId, itemId: 'flour', itemCode: 'FLOUR', supplierId: 's1', supplierName: 'Mill Co',
        supplierActive: true, supplierItemCode: 'MC-77', unitPrice: 1.1, version: 0, updatedBy: 'demo-owner', updatedAt: '2041-09-01T10:00:00Z' }])
    }
    if (pathname === '/api/items') return ok(route, [item('flour'), item('salt')])
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Usual', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Usual', ownerId: 'demo-owner' })
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=stock`)
  const panel = page.getByRole('region', { name: 'Open work order needs' })
  const asked: string[] = []
  page.on('dialog', (dialog) => {
    asked.push(dialog.message())
    void (dialog.type() === 'prompt' ? dialog.accept('Salt Works') : dialog.accept())
  })
  await panel.getByRole('button', { name: 'Draft a purchase order for 2 short materials' }).click({ timeout: 15_000 })
  await expect(panel.getByRole('status')).toHaveText(
    'Drafted PO-0001 for Mill Co with 1 line, PO-0002 for Salt Works with 1 line; check and order them on the Purchasing tab. Open Purchasing')
  expect(asked).toEqual(['Draft purchase orders for Mill Co (1 line) and 1 line without a usual supplier?',
    'Which supplier is the draft for the 1 line without a usual supplier?'])
  expect(drafts.map((one) => [one.supplierName, one.expectedDate, one.lines.map((line) => line.itemId)]))
    .toEqual([['Mill Co', '2041-10-03', ['flour']], ['Salt Works', null, ['salt']]])
  // Mill Co's own price for flour (PO38); salt has no usual supplier and no unit cost, so no price.
  expect(drafts.map((one) => one.lines.map((line) => line.unitPrice ?? null))).toEqual([[1.1], [null]])
})
