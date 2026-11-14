import { expect, test, type Route } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * Finishing a run says which by-products and waste are recorded short of its BOM (docs/domain/bom-by-products.md), against
 * a mocked API so no BOM is written to a real database: 20 loaves planned give off 2 kg bran and 0.5 kg dust; with 16
 * loaves and 0.5 kg bran recorded, 1.6 kg bran and 0.4 kg dust are expected. Nothing is finished: the confirm is dismissed.
 * The mocked run is then shown finished with 16 made, and the by-products show how far they are from the BOM.
 */
test('finishing a run names the by-products recorded short of the BOM', async ({ page }) => {
  const missing = async (route: Route) => {
    await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ success: false, data: null, message: 'Not mocked' }) })
  }
  const item = (itemId: string, itemCode: string, unitId: string) => ({
    itemId, projectId: 'prj-e2e', itemCode, itemName: itemCode.toLowerCase(), itemType: 'material', resourceCategory: null,
    resourceType: null, unitId, itemStatus: 'active', lotManageYn: 'N', unitCost: null,
  })
  const items = [item('bread', 'BREAD', 'unit_ea'), item('flour', 'FLOUR', 'unit_kg'), item('bran', 'BRAN', 'unit_kg'),
    item('dust', 'DUST', 'unit_kg')]
  const run = {
    productionRunId: 'run-1', projectId: 'prj-e2e', workflowId: 'wf-1', workflowRevisionId: null, runNumber: 'RUN-1', runType: 'actual',
    runStatus: 'running', targetItemId: 'bread', plannedOutputQty: 20, actualOutputQty: 0, workOrderId: null, bomId: 'bread-bom', bomVersion: 1,
  }
  const runItem = (productionRunItemId: string, itemId: string, direction: string, quantity: number, unit: string, quantitySource: string) => ({
    productionRunItemId, productionRunId: 'run-1', processId: null, processIoId: null, inventoryId: null, itemId, direction,
    plannedQty: quantity, actualQty: quantitySource === 'bom' ? null : quantity, unit, quantitySource, conversionRate: 1, lotId: null,
    cancelled: false, cancelledBy: null, cancelledAt: null, cancelReason: null,
  })
  const runItems = [
    runItem('ri-1', 'flour', 'input', 10, 'kg', 'bom'),
    runItem('ri-2', 'bread', 'output', 16, 'ea', 'manual'),
    runItem('ri-3', 'bran', 'output', 0.5, 'kg', 'manual'),
  ]
  const output = (bomLineId: string, itemId: string, lineType: string, quantity: number) => ({
    bomLineId, itemId, lineType, lineQuantity: quantity, lineUnit: 'kg', quantity, itemUnit: 'kg', itemQuantity: quantity,
  })

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    const method = request.method()
    if (await answerAuth(route, pathname)) return
    if (method !== 'GET') return missing(route)
    if (pathname === '/api/items') return ok(route, items)
    if (pathname === '/api/units') {
      return ok(route, [
        { unitId: 'unit_ea', unitCode: 'ea', unitName: 'each', unitType: 'count', activeYn: 'Y' },
        { unitId: 'unit_kg', unitCode: 'kg', unitName: 'kilogram', unitType: 'mass', activeYn: 'Y' },
      ])
    }
    if (pathname === '/api/production-runs/run-1') return ok(route, run)
    if (pathname === '/api/production-runs/run-1/items') return ok(route, runItems)
    if (pathname === '/api/production-runs/run-1/instruction') {
      return ok(route, { productionRunId: 'run-1', open: true, instruction: null, checks: [], requiredSteps: 0, requiredDone: 0,
        complete: true, undone: [] })
    }
    if (pathname === '/api/production-runs/run-1/quality-checklist') {
      return ok(route, { productionRunId: 'run-1', required: 0, requiredPassed: 0, requiredMissing: 0, failed: 0, lines: [] })
    }
    if (pathname === '/api/production-runs/run-1/cost' || pathname === '/api/production-runs/run-1/material-usage') return missing(route)
    if (pathname === '/api/boms/bread-bom/requirements') {
      return ok(route, { bomId: 'bread-bom', bomVersion: 1, targetItemId: 'bread', productionQuantity: 20, baseQuantity: 20,
        lines: [{ bomLineId: 'l1', childItemId: 'flour', lineQuantity: 10, lineUnit: 'kg', requiredQuantity: 10, itemUnit: 'kg',
          requiredItemQuantity: 10, conversionRate: 1, unitCost: null, lineCost: null }],
        materialCost: 0, costComplete: false,
        outputs: [output('o1', 'bran', 'by_product', 2), output('o2', 'dust', 'waste', 0.5)] })
    }
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/runs/run-1')

  await expect(page.getByRole('region', { name: 'Expected by-products' })).toContainText('expected 2 kg')
  const note = page.getByRole('note', { name: 'By-products before finishing' })
  // 16 loaves recorded of 20 planned.
  await expect(note).toHaveText('Not all that comes out is recorded: BRAN · bran 0.5 of 1.6 kg, DUST · dust 0 of 0.4 kg.')
  await page.getByLabel('Actual output qty').fill('20')
  await expect(note).toHaveText('Not all that comes out is recorded: BRAN · bran 0.5 of 2 kg, DUST · dust 0 of 0.5 kg.')

  let asked = ''
  page.once('dialog', async (dialog) => {
    asked = dialog.message()
    await dialog.dismiss()
  })
  await page.getByRole('button', { name: 'Finish', exact: true }).click()
  await expect.poll(() => asked).toContain('BRAN · bran 0.5 of 2 kg')
  expect(asked).toContain('Finish this run with 20 made?')

  // Left empty, the field finishes with the 16 loaves it shows, not with no quantity at all.
  await page.getByLabel('Actual output qty').fill('')
  await expect(page.getByLabel('Actual output qty')).toHaveAttribute('placeholder', '16')
  const finishing = page.waitForRequest((request) => request.method() === 'POST'
    && new URL(request.url()).pathname === '/api/production-runs/run-1/finish')
  page.once('dialog', async (dialog) => {
    asked = dialog.message()
    await dialog.accept()
  })
  await page.getByRole('button', { name: 'Finish', exact: true }).click()
  expect((await finishing).postDataJSON()).toEqual({ actualOutputQty: 16 })
  expect(asked).toContain('Finish this run with 16 made?')

  // Once finished with 16 made, what is expected follows that, and the difference shows instead of Record.
  Object.assign(run, { runStatus: 'finished', actualOutputQty: 16 })
  await page.reload()
  const expected = page.getByRole('region', { name: 'Expected by-products' })
  await expect(expected.getByRole('row', { name: /BRAN/ })).toContainText('expected 1.6 kg for 16 made')
  await expect(expected.getByRole('row', { name: /BRAN/ })).toContainText('recorded 0.5')
  await expect(expected.getByRole('row', { name: /BRAN/ })).toContainText('1.1 kg short')
  await expect(expected.getByRole('row', { name: /DUST/ })).toContainText('0.4 kg short')
  await expect(expected.getByRole('button', { name: 'Record' })).toHaveCount(0)
  await expect(page.getByRole('note', { name: 'By-products before finishing' })).toHaveCount(0)
})
