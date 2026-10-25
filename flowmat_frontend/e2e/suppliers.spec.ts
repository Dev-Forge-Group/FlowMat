import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/** The supplier list and what it asks of a new purchase order (docs/domain/purchasing.md PO18-PO21), mocked. */
test.use({ timezoneId: 'UTC' })

test('listed suppliers are offered on orders and their lead time suggests the expected date', async ({ page }) => {
  const projectId = 'prj-suppliers'
  await page.clock.setFixedTime(new Date('2041-09-28T10:00:00Z'))
  const suppliers: Record<string, unknown>[] = []
  const sent: { method: string; body: unknown }[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (pathname === '/api/suppliers' && request.method() === 'POST') {
      const body = request.postDataJSON() as Record<string, unknown>
      sent.push({ method: 'POST', body })
      const saved = { supplierId: `s${suppliers.length + 1}`, ...body, active: true, version: 0, createdBy: 'demo-owner',
        createdAt: '2041-09-28T10:00:00Z', updatedBy: null, updatedAt: null }
      suppliers.push(saved)
      return ok(route, saved)
    }
    if (pathname.startsWith('/api/suppliers/') && request.method() === 'PUT') {
      const body = request.postDataJSON() as Record<string, unknown>
      sent.push({ method: 'PUT', body })
      const at = suppliers.findIndex((one) => pathname === `/api/suppliers/${String(one.supplierId)}`)
      const { expectedVersion, ...fields } = body
      suppliers[at] = { ...suppliers[at], ...fields, version: Number(expectedVersion) + 1 }
      return ok(route, suppliers[at])
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/suppliers') return ok(route, suppliers)
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Suppliers', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Suppliers', ownerId: 'demo-owner' })
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=purchasing`)
  const list = page.getByRole('region', { name: 'Suppliers' })
  await expect(list.getByText('No suppliers listed; purchase orders name any supplier.')).toBeVisible({ timeout: 15_000 })

  const add = page.getByRole('form', { name: 'New supplier' })
  await add.getByLabel('Name').fill('Mill Co')
  await add.getByLabel('Contact').fill('Kim 010-1234')
  await add.getByLabel('Lead time (days)').fill('5')
  await add.getByRole('button', { name: 'Add supplier' }).click()
  const row = list.getByRole('table', { name: 'Supplier list' }).getByRole('row').nth(1)
  await expect(row).toHaveText('Mill CoKim 010-12345 daysactiveEdit')
  expect(sent[0]).toEqual({ method: 'POST', body: { projectId, supplierName: 'Mill Co', contact: 'Kim 010-1234', leadTimeDays: 5, note: null } })

  // A name the list lacks is pointed out before saving; a listed one offers its lead time.
  const draft = page.getByRole('form', { name: 'New purchase order' })
  await draft.getByLabel('Supplier').fill('Other Ltd')
  await expect(draft.getByRole('note')).toHaveText('Other Ltd is not in the supplier list.')
  await draft.getByLabel('Supplier').fill('mill co')
  await expect(draft.getByRole('note')).toHaveCount(0)
  await draft.getByRole('button', { name: 'Expect by 2041-10-03 (lead time 5 days)' }).click()
  await expect(draft.getByLabel('Expected')).toHaveValue('2041-10-03')
  await expect(draft.getByRole('button', { name: /^Expect by/ })).toHaveCount(0)

  // Switched off from the version the form was opened at.
  await list.getByRole('button', { name: 'Edit Mill Co' }).click()
  const edit = page.getByRole('form', { name: 'Edit supplier Mill Co' })
  await edit.getByLabel('Active').uncheck()
  await edit.getByRole('button', { name: 'Save supplier' }).click()
  await expect(row).toContainText('inactive')
  expect(sent[1]).toEqual({ method: 'PUT', body: { supplierName: 'Mill Co', contact: 'Kim 010-1234', leadTimeDays: 5, note: null, active: false,
    expectedVersion: 0 } })
  await expect(draft.getByRole('note')).toHaveText('Mill Co is inactive.')
})
