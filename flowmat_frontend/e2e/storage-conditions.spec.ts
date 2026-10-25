import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * Storage conditions of places (docs/domain/storage-location.md SL1-SL4) against a mocked API: a place's condition reaches the
 * places inside, stock kept differently from its item's condition is pointed out, and the form saves a place's own.
 */
const projectId = 'prj-cond'

async function mockApi(page: Page, sent: unknown[]) {
  const base = { projectId, locationName: null, active: true, note: null, stockRecords: 0, itemCount: 0 }
  const cold = { ...base, locationId: 'cold', parentLocationId: null, locationCode: 'COLD', locationType: 'warehouse', path: 'COLD',
    depth: 0, storageCondition: 'Chilled' }
  const bin = { ...base, locationId: 'bin', parentLocationId: 'cold', locationCode: 'B1', locationType: 'bin', path: 'COLD / B1',
    depth: 1, storageCondition: null as string | null, stockRecords: 1, itemCount: 1 }
  const fish = { itemId: 'fish', projectId, itemCode: 'FISH', itemName: 'Fish', itemType: 'material', resourceCategory: 'material',
    unitId: 'unit_kg', itemStatus: 'active', lotManageYn: 'N', unitCost: null,
    details: { itemGroup: null, spec: null, barcode: null, sku: null, storageCondition: 'Frozen', description: null } }
  const stock = { inventoryId: 'stock', projectId, itemId: 'fish', lotId: null, quantity: 5, reservedQuantity: 0, availableQuantity: 5,
    location: 'B1', inventoryStatus: 'available', minThreshold: null, version: 0 }
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'PUT' && pathname === '/api/storage-locations/bin') {
      const body = request.postDataJSON() as { storageCondition?: string }
      sent.push(body)
      bin.storageCondition = body.storageCondition || null
      return ok(route, bin)
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify({ success: false, data: null, message: 'Unexpected write' }) })
    }
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Storage', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Storage', ownerId: 'demo-owner' })
    if (pathname === '/api/items') return ok(route, [fish])
    if (pathname === '/api/units') return ok(route, [{ unitId: 'unit_kg', unitCode: 'kg', unitName: 'kilogram', unitType: 'mass', activeYn: 'Y' }])
    if (pathname === '/api/storage-locations') return ok(route, [cold, bin])
    if (pathname === '/api/inventories') return ok(route, [stock])
    if (pathname === '/api/material-requirements') return ok(route, { projectId, orders: 0, lines: [], problems: [] })
    return ok(route, [])
  })
}

test('a place\'s storage condition reaches the places inside and points out stock kept differently', async ({ page }) => {
  const sent: unknown[] = []
  await mockApi(page, sent)
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=stock`)
  await page.getByRole('tab', { name: 'Locations', exact: true }).click()
  const binRow = page.getByRole('row', { name: /B1/ })
  await expect(binRow).toContainText('Chilled (from COLD)')
  await expect(binRow.getByRole('note')).toHaveText('1 record needs another condition: FISH (Frozen)')
  await expect(page.getByRole('status', { name: 'Storage condition mismatches' }))
    .toHaveText("1 stock record is kept where the storage condition differs from the item's.")

  await binRow.getByRole('button', { name: 'Edit', exact: true }).click()
  const form = page.getByRole('form', { name: 'Edit location' })
  await form.getByLabel('Storage condition').fill('Frozen')
  await form.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(binRow).toContainText('Frozen')
  await expect(binRow.getByRole('note')).toHaveCount(0)
  await expect(page.getByRole('status', { name: 'Storage condition mismatches' })).toHaveCount(0)
  expect(sent).toEqual([expect.objectContaining({ storageCondition: 'Frozen' })])
})

test('adding stock points out a place kept differently from the item', async ({ page }) => {
  await mockApi(page, [])
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=stock`)
  await page.getByLabel('Item *').selectOption('fish')
  await page.getByLabel('Location', { exact: true }).fill('b1')
  await expect(page.getByRole('note', { name: 'Storage condition' })).toHaveText('FISH is kept "Frozen"; B1 is "Chilled".')
  await page.getByLabel('Location', { exact: true }).fill('nowhere')
  await expect(page.getByRole('note', { name: 'Storage condition' })).toHaveCount(0)
})
