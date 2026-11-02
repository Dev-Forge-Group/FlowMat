import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * Substitute materials on BOM lines against a mocked API (ADR-032), so no BOM is written to a real database: a substitute
 * line names its main material, requirements show what the substitute would take instead, the line form sends the main
 * line it stands in for, and a materials file with a substitute column is refused. The server side is covered by
 * BomSubstituteIntegrationTest.
 */
test('a substitute names its main material, requirements offer it and the form sends the line it stands in for', async ({ page }) => {
  const item = (itemId: string, itemCode: string, unitId: string) => ({
    itemId, projectId: 'prj-e2e', itemCode, itemName: itemCode.toLowerCase(), itemType: 'material', resourceCategory: null,
    resourceType: null, unitId, itemStatus: 'active', lotManageYn: 'N', unitCost: null,
  })
  const items = [item('cake', 'CAKE', 'unit_ea'), item('roll', 'ROLL', 'unit_ea'), item('flour', 'FLOUR', 'unit_kg'),
    item('rye', 'RYE', 'unit_kg'), item('sugar', 'SUGAR', 'unit_kg'), item('crumbs', 'CRUMBS', 'unit_kg')]
  const line = (bomLineId: string, childItemId: string, quantity: number, extra: Record<string, unknown> = {}) => ({
    bomLineId, childItemId, quantity, unit: 'kg', scrapRate: null, optionalYn: 'N', substituteGroup: null, sortOrder: 1,
    note: null, lineType: 'material', phantom: false, priority: 0, substituteFor: null, ...extra,
  })
  const bom = (bomId: string, targetItemId: string, bomName: string, bomStatus: string, lines: unknown[]) => ({
    bomId, projectId: 'prj-e2e', targetItemId, bomName, bomVersion: 1, baseQuantity: 1, baseUnit: 'ea', bomStatus,
    approvedBy: null, approvedAt: null, note: null, lines,
  })
  const boms = [
    bom('cake-bom', 'cake', 'Cake', 'approved', [line('c1', 'flour', 2, { substituteGroup: 'c1' }),
      line('c2', 'rye', 2.5, { substituteGroup: 'c1', priority: 1, substituteFor: 'c1' })]),
    bom('roll-draft', 'roll', 'Roll draft', 'draft', [line('r1', 'sugar', 1), line('r2', 'crumbs', 0.1, { lineType: 'waste' })]),
  ]
  const added: Record<string, unknown>[] = []
  const imported: Record<string, unknown>[] = []

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const { pathname } = url
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/boms/roll-draft/lines') {
      added.push(request.postDataJSON() as Record<string, unknown>)
      return ok(route, boms[1])
    }
    if (request.method() === 'POST' && pathname === '/api/boms/roll-draft/lines/import') {
      imported.push(request.postDataJSON() as Record<string, unknown>)
      return ok(route, { dryRun: true, applied: false, added: 0, removed: 0, errors: 0, rows: [] })
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
    if (pathname === '/api/boms/cake-bom/buildable') {
      return ok(route, { bomId: 'cake-bom', targetItemId: 'cake', targetUnit: 'ea', baseQuantity: 1, buildable: 0,
        limitingItemId: 'flour', lines: [{ childItemId: 'flour', itemUnit: 'kg', perBatch: 2, usable: 0, buildable: 0 }] })
    }
    if (pathname === '/api/boms/cake-bom/requirements') {
      // 2 kg of flour a cake, or 2.5 kg of rye instead.
      const quantity = Number(url.searchParams.get('quantity'))
      return ok(route, { bomId: 'cake-bom', bomVersion: 1, targetItemId: 'cake', productionQuantity: quantity, baseQuantity: 1,
        lines: [{ bomLineId: 'c1', childItemId: 'flour', lineQuantity: 2, lineUnit: 'kg', requiredQuantity: 2 * quantity,
          itemUnit: 'kg', requiredItemQuantity: 2 * quantity, conversionRate: 1, unitCost: 10, lineCost: 20 * quantity,
          viaItemId: null, scrapRate: 0,
          substitutes: [{ bomLineId: 'c2', childItemId: 'rye', requiredItemQuantity: 2.5 * quantity, itemUnit: 'kg', unitCost: 12,
            lineCost: 30 * quantity, priority: 1, mainPerUnit: 0.8 }] }],
        materialCost: 20 * quantity, costComplete: true, outputs: [] })
    }
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/inventory?tab=boms')

  await page.getByRole('row', { name: /Cake/ }).first().click()
  await expect(page.getByRole('cell', { name: 'RYE · rye · substitute for FLOUR · flour', exact: true })).toBeVisible()
  await page.getByRole('spinbutton', { name: 'Materials needed to make' }).fill('10')
  // The plan is the flour; the rye is offered beside it, with what it would cost.
  await expect(page.getByRole('row', { name: /FLOUR/ }).filter({ hasText: 'or RYE · rye 25 kg' })).toContainText('20 kg')
  await expect(page.getByRole('row', { name: /FLOUR/ }).filter({ hasText: 'or RYE' })).toContainText('(cost 300)')

  await page.getByRole('row', { name: /Roll draft/ }).click()
  const substituteFor = page.getByLabel('Substitute for')
  // Only a plain material can be stood in for: the sugar, not the waste.
  await expect(substituteFor.locator('option')).toHaveText(['Main material (not a substitute)', 'Substitute for SUGAR · sugar'])
  await page.getByLabel('Material', { exact: true }).selectOption('flour')
  await page.getByLabel('Material quantity').fill('1.2')
  await substituteFor.selectOption('r1')
  await page.getByRole('button', { name: 'Add', exact: true }).click()
  await expect.poll(() => added.length).toBe(1)
  expect(added[0]).toMatchObject({ childItemId: 'flour', quantity: 1.2, lineType: 'material', substituteFor: 'r1' })

  // What a batch gives off is never a substitute.
  await page.getByLabel('Line type').selectOption('waste')
  await expect(substituteFor).toHaveCount(0)

  // A materials file cannot set substitutes yet: its column is refused before anything is sent.
  const file = (text: string) => ({ name: 'roll.csv', mimeType: 'text/csv', buffer: Buffer.from(text) })
  await page.getByLabel('Import materials file').setInputFiles(file('item_code,quantity,unit,substitute_for\nRYE,1,kg,SUGAR\n'))
  await expect(page.getByLabel('Materials from CSV').getByRole('alert'))
    .toHaveText('Substitute materials are added on screen, not by CSV yet; remove the substitute_for column.')
  expect(imported).toHaveLength(0)
})
