import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * Planned scrap on BOM material lines against a mocked API (ADR-031), so no BOM is written to a real database: a line
 * says its scrap, requirements say they include it, the line form sends the percent as a 0-1 rate for materials only, and
 * a materials file sends its scrap_percent column as written. The server side is covered by BomScrapRateIntegrationTest
 * and BomLineImportIntegrationTest.
 */
test('a line plans its scrap, requirements include it and the form sends it for materials only', async ({ page }) => {
  const item = (itemId: string, itemCode: string, unitId: string) => ({
    itemId, projectId: 'prj-e2e', itemCode, itemName: itemCode.toLowerCase(), itemType: 'material', resourceCategory: null,
    resourceType: null, unitId, itemStatus: 'active', lotManageYn: 'N', unitCost: null,
  })
  const items = [item('bread', 'BREAD', 'unit_ea'), item('roll', 'ROLL', 'unit_ea'), item('flour', 'FLOUR', 'unit_kg'),
    item('crumbs', 'CRUMBS', 'unit_kg')]
  const line = (bomLineId: string, childItemId: string, quantity: number, scrapRate: number | null, lineType = 'material') => ({
    bomLineId, childItemId, quantity, unit: 'kg', scrapRate, optionalYn: 'N', substituteGroup: null, sortOrder: 1, note: null,
    lineType, phantom: false,
  })
  const bom = (bomId: string, targetItemId: string, bomName: string, bomStatus: string, lines: unknown[]) => ({
    bomId, projectId: 'prj-e2e', targetItemId, bomName, bomVersion: 1, baseQuantity: 1, baseUnit: 'ea', bomStatus,
    approvedBy: null, approvedAt: null, note: null, lines,
  })
  const boms = [
    bom('bread-bom', 'bread', 'Bread', 'approved', [line('b1', 'flour', 2, 0.05)]),
    bom('roll-draft', 'roll', 'Roll draft', 'draft', []),
  ]
  const added: Record<string, unknown>[] = []
  const imported: Record<string, unknown>[] = []

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const { pathname } = url
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/boms/roll-draft/lines') {
      const body = request.postDataJSON() as Record<string, unknown>
      added.push(body)
      return ok(route, { ...boms[1], lines: [line('r1', String(body.childItemId), Number(body.quantity),
        typeof body.scrapRate === 'number' ? body.scrapRate : null, String(body.lineType))] })
    }
    if (request.method() === 'POST' && pathname === '/api/boms/roll-draft/lines/import') {
      imported.push(request.postDataJSON() as Record<string, unknown>)
      return ok(route, { dryRun: true, applied: false, added: 1, removed: 0, errors: 0,
        rows: [{ row: 1, itemCode: 'FLOUR', action: 'add', message: null }] })
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/items') return ok(route, items)
    if (pathname === '/api/units') {
      return ok(route, [
        { unitId: 'unit_ea', unitCode: 'ea', unitName: 'each', unitType: 'count', activeYn: 'Y' },
        { unitId: 'unit_kg', unitCode: 'kg', unitName: 'kilogram', unitType: 'mass', activeYn: 'Y' },
      ])
    }
    if (pathname === '/api/boms') return ok(route, boms)
    if (pathname === '/api/boms/bread-bom/buildable') {
      return ok(route, { bomId: 'bread-bom', targetItemId: 'bread', targetUnit: 'ea', baseQuantity: 1, buildable: 0,
        limitingItemId: 'flour', lines: [{ childItemId: 'flour', itemUnit: 'kg', perBatch: 2.1, usable: 0, buildable: 0 }] })
    }
    if (pathname === '/api/boms/bread-bom/scrap-performance') {
      // Three finished runs used 8.33% over the net against 5% planned (ADR-034).
      return ok(route, { bomId: 'bread-bom', bomVersion: 1, runs: 3, lines: [{ itemId: 'flour', itemCode: 'FLOUR', unit: 'kg',
        plannedScrapRate: 0.05, runs: 3, net: 6, planned: 6.3, actual: 6.5, actualLossRate: 0.0833, complete: true }] })
    }
    if (pathname === '/api/boms/bread-bom/requirements') {
      // 2 kg a loaf with 5% scrap on top.
      const quantity = Number(url.searchParams.get('quantity'))
      return ok(route, { bomId: 'bread-bom', bomVersion: 1, targetItemId: 'bread', productionQuantity: quantity, baseQuantity: 1,
        lines: [{ bomLineId: 'b1', childItemId: 'flour', lineQuantity: 2, lineUnit: 'kg', requiredQuantity: 2.1 * quantity,
          itemUnit: 'kg', requiredItemQuantity: 2.1 * quantity, conversionRate: 1, unitCost: null, lineCost: null, viaItemId: null,
          scrapRate: 0.05 }],
        materialCost: 0, costComplete: false, outputs: [] })
    }
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/inventory?tab=boms')

  await page.getByRole('row', { name: /Bread/ }).first().click()
  await expect(page.getByRole('cell', { name: 'FLOUR · flour · 5% scrap', exact: true })).toBeVisible()
  await page.getByRole('spinbutton', { name: 'Materials needed to make' }).fill('10')
  await expect(page.getByRole('row', { name: /FLOUR.*incl\. 5% scrap/ })).toContainText('21 kg')
  // What finished runs actually lost, beside the plan (ADR-034).
  const actualScrap = page.getByRole('table', { name: 'Scrap: planned vs actual' })
  await expect(actualScrap.getByRole('row', { name: /FLOUR/ })).toContainText('5%')
  await expect(actualScrap.getByRole('row', { name: /FLOUR/ })).toContainText('8.33% (+3.33 pts)')
  await expect(actualScrap.getByRole('row', { name: /FLOUR/ })).toContainText('6.5 of 6 kg · 3 runs')

  await page.getByRole('row', { name: /Roll draft/ }).click()
  const scrap = page.getByRole('spinbutton', { name: 'Scrap percent' })
  const scrapAlert = page.getByRole('alert').filter({ hasText: /^Scrap/ })
  await page.getByLabel('Material', { exact: true }).selectOption('flour')
  await page.getByLabel('Material quantity').fill('1')
  // More than 2 decimals is refused before anything is sent.
  await scrap.fill('0.125')
  await page.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(scrapAlert).toHaveText('Scrap takes at most 2 decimals (0.01%).')
  expect(added).toHaveLength(0)

  await scrap.fill('2.5')
  await page.getByRole('button', { name: 'Add', exact: true }).click()
  await expect.poll(() => added.length).toBe(1)
  expect(added[0]).toMatchObject({ childItemId: 'flour', quantity: 1, unit: 'kg', lineType: 'material', scrapRate: 0.025 })
  await expect(scrapAlert).toHaveCount(0)

  // What a batch gives off plans no scrap: the field is off and nothing is sent.
  await page.getByLabel('Line type').selectOption('waste')
  await expect(scrap).toBeDisabled()
  await page.getByLabel('Material', { exact: true }).selectOption('crumbs')
  await page.getByLabel('Material quantity').fill('0.1')
  await page.getByRole('button', { name: 'Add', exact: true }).click()
  await expect.poll(() => added.length).toBe(2)
  expect(added[1]).not.toHaveProperty('scrapRate')

  // A materials file gives scrap as a percent too. A column that could hold a 0-1 rate is refused before anything is sent.
  const file = (text: string) => ({ name: 'roll.csv', mimeType: 'text/csv', buffer: Buffer.from(text) })
  const materialsFile = page.getByLabel('Materials from CSV')
  await page.getByLabel('Import materials file').setInputFiles(file('item_code,quantity,unit,scrap_rate\nFLOUR,1,kg,0.05\n'))
  await expect(materialsFile.getByRole('alert'))
    .toHaveText('Give scrap as a percent in a scrap_percent column (5 means 5%), not scrap_rate.')
  expect(imported).toHaveLength(0)
  await page.getByLabel('Import materials file').setInputFiles(file('item_code,quantity,unit,scrap_percent\nFLOUR,1,kg,5%\n'))
  await expect(materialsFile).toContainText('1 to add')
  expect(imported[0]).toMatchObject({ dryRun: true, rows: [{ itemCode: 'FLOUR', quantity: '1', unit: 'kg', scrapPercent: '5%' }] })
})
