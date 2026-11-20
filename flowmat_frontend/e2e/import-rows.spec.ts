import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * Leaving lines out of an item, a stock or a BOM material import (docs/domain/item-import.md I13 and B1, stock-import.md
 * S12), against a mocked API: a file with a wrong line can be saved without it, the file is checked again whenever the
 * choice changes, and what is saved is exactly what the last check showed.
 */
interface Input { dryRun: boolean; rows: { itemCode: string; itemName?: string }[]; skipRows?: number[] }

test('lines with problems are left out in one press and the rest is saved', async ({ page }) => {
  const projectId = 'prj-item-import-rows'
  const sent: Input[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/items/import') {
      const body = request.postDataJSON() as Input
      sent.push(body)
      const skip = new Set(body.skipRows ?? [])
      // Like the server: a left-out row is not checked; BAD rows are wrong; the rest are new items.
      const rows = body.rows.map((row, index) => skip.has(index + 1)
        ? { row: index + 1, itemCode: row.itemCode, action: 'skipped', message: null }
        : row.itemCode.startsWith('BAD')
          ? { row: index + 1, itemCode: row.itemCode, action: 'error', message: 'Unit parsec is not an active unit' }
          : { row: index + 1, itemCode: row.itemCode, action: 'create', message: null })
      const errors = rows.filter((row) => row.action === 'error').length
      return ok(route, { dryRun: body.dryRun, applied: !body.dryRun && errors === 0,
        created: rows.filter((row) => row.action === 'create').length, updated: 0, unchanged: 0, errors, skipped: skip.size, rows })
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 400, body: 'Unexpected write' })
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Import rows', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Import rows', ownerId: 'demo-owner' })
    if (pathname === '/api/material-requirements') return ok(route, { projectId, orders: 0, lines: [], problems: [] })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=items`)
  const panel = page.getByRole('region', { name: 'Items spreadsheet', exact: true })
  await panel.getByLabel('Import items file').setInputFiles({ name: 'items.csv', mimeType: 'text/csv',
    buffer: Buffer.from('item_code,item_name\nOATS,Oats\nBAD-1,Cane\nRYE,Rye\nBAD-2,Dust\n') })
  const check = panel.getByLabel('Import check', { exact: true })

  // The whole file: two lines are wrong, so nothing can be saved yet.
  await expect(check).toContainText('2 to add, 0 to update, 0 unchanged, 2 with problems')
  await expect(check.getByRole('button', { name: 'Save 2 changes', exact: true })).toBeDisabled()
  expect(sent).toHaveLength(1)
  expect(sent[0].skipRows).toBeUndefined()

  // One press leaves out both wrong lines; the file is checked again without them.
  await check.getByRole('button', { name: 'Leave out the 2 lines with problems', exact: true }).click()
  await expect(check).toContainText('2 to add, 0 to update, 0 unchanged, 2 left out')
  expect(sent[1]).toMatchObject({ dryRun: true, skipRows: [2, 4] })
  expect(sent[1].rows).toHaveLength(4)
  // Line numbers are the file's: the header is line 1.
  await expect(check.getByRole('row', { name: /line 3/ })).toContainText('left out')
  await expect(check.getByLabel('Import line 3', { exact: true })).not.toBeChecked()
  await expect(check.getByLabel('Import line 2', { exact: true })).toBeChecked()
  await expect(check.getByRole('button', { name: /Leave out the/ })).toHaveCount(0)

  // A good line can be left out as well, and a left-out line put back.
  await check.getByLabel('Import line 4', { exact: true }).uncheck()
  await expect(check).toContainText('1 to add, 0 to update, 0 unchanged, 3 left out')
  expect(sent[2]).toMatchObject({ dryRun: true, skipRows: [2, 3, 4] })
  // The last line that is still in cannot be taken out: the server refuses an import of nothing.
  await expect(check.getByLabel('Import line 2', { exact: true })).toBeDisabled()
  await check.getByLabel('Import line 4', { exact: true }).check()
  await expect(check).toContainText('2 to add, 0 to update, 0 unchanged, 2 left out')
  expect(sent[3]).toMatchObject({ dryRun: true, skipRows: [2, 4] })

  // Saving sends the same file with the same lines left out.
  await check.getByRole('button', { name: 'Save 2 changes', exact: true }).click()
  await expect(panel.getByRole('status')).toHaveText('Saved: 2 added, 0 updated, 0 unchanged, 2 left out.')
  expect(sent).toHaveLength(5)
  expect(sent[4]).toMatchObject({ dryRun: false, skipRows: [2, 4] })
  expect(sent[4].rows.map((row) => row.itemCode)).toEqual(['OATS', 'BAD-1', 'RYE', 'BAD-2'])
  await expect(check).toHaveCount(0)
})

test('stock lines with problems are left out in one press and the rest is received', async ({ page }) => {
  const projectId = 'prj-stock-import-rows'
  interface StockInput { dryRun: boolean; note?: string; rows: { itemCode: string; quantity?: string }[]; skipRows?: number[] }
  const sent: StockInput[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/inventories/import') {
      const body = request.postDataJSON() as StockInput
      sent.push(body)
      const skip = new Set(body.skipRows ?? [])
      // Like the server: a left-out row is not checked; NOPE rows are wrong; the rest make new records.
      const rows = body.rows.map((row, index) => skip.has(index + 1)
        ? { row: index + 1, itemCode: row.itemCode, action: 'skipped', message: null }
        : row.itemCode.startsWith('NOPE')
          ? { row: index + 1, itemCode: row.itemCode, action: 'error', message: `No item has code ${row.itemCode}` }
          : { row: index + 1, itemCode: row.itemCode, action: 'create', message: null })
      const errors = rows.filter((row) => row.action === 'error').length
      return ok(route, { dryRun: body.dryRun, applied: !body.dryRun && errors === 0,
        created: rows.filter((row) => row.action === 'create').length, received: 0, newLots: 0, errors, skipped: skip.size, rows })
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 400, body: 'Unexpected write' })
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Stock rows', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Stock rows', ownerId: 'demo-owner' })
    if (pathname === '/api/material-requirements') return ok(route, { projectId, orders: 0, lines: [], problems: [] })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=stock`)
  const panel = page.getByRole('group', { name: 'Receive stock from a spreadsheet', exact: true })
  await panel.locator('summary').click()
  await panel.getByLabel('Import stock file').setInputFiles({ name: 'stock.csv', mimeType: 'text/csv',
    buffer: Buffer.from('item_code,location,quantity\nOATS,A-1,5\nNOPE-1,A-1,1\nRYE,A-2,3\n') })
  const check = panel.getByLabel('Stock import check', { exact: true })

  await expect(check).toContainText('2 new records, 0 into existing ones, 1 with problems')
  await expect(check.getByRole('button', { name: 'Receive 2 lines', exact: true })).toBeDisabled()

  await check.getByRole('button', { name: 'Leave out the 1 line with problems', exact: true }).click()
  await expect(check).toContainText('2 new records, 0 into existing ones, 1 left out')
  expect(sent[1]).toMatchObject({ dryRun: true, skipRows: [2] })
  await expect(check.getByRole('row', { name: /line 3/ })).toContainText('left out')
  await expect(check.getByLabel('Import line 3', { exact: true })).not.toBeChecked()

  // A left-out line can be put back, and the file is checked again as a whole.
  await check.getByLabel('Import line 3', { exact: true }).check()
  await expect(check).toContainText('1 with problems')
  expect(sent[2].skipRows).toBeUndefined()
  await check.getByLabel('Import line 3', { exact: true }).uncheck()
  await expect(check).toContainText('1 left out')

  await panel.getByLabel('Import note').fill('opening balance')
  await check.getByRole('button', { name: 'Receive 2 lines', exact: true }).click()
  await expect(panel.getByRole('status')).toHaveText('Received: 2 new records, 0 into existing ones, 1 line left out.')
  expect(sent.at(-1)).toMatchObject({ dryRun: false, note: 'opening balance', skipRows: [2] })
  expect(sent.at(-1)?.rows.map((row) => row.itemCode)).toEqual(['OATS', 'NOPE-1', 'RYE'])
  await expect(check).toHaveCount(0)
})

test('BOM material lines can be left out, and replacing says which current materials go for good', async ({ page }) => {
  const projectId = 'prj-bom-import-rows'
  interface BomInput { dryRun: boolean; replace: boolean; rows: { itemCode: string; quantity?: string; unit?: string }[]; skipRows?: number[] }
  const material = { itemId: 'flour', projectId, itemCode: 'FLOUR', itemName: 'Flour', itemType: 'material',
    resourceCategory: 'material', unitId: 'unit_kg', itemStatus: 'active', lotManageYn: 'N', unitCost: 1 }
  const product = { ...material, itemId: 'cake', itemCode: 'CAKE', itemName: 'Cake' }
  const bom = { bomId: 'cake-draft', projectId, targetItemId: product.itemId, bomName: 'Cake draft', bomVersion: 1,
    baseQuantity: 1, baseUnit: 'kg', bomStatus: 'draft', approvedBy: null, approvedAt: null, note: null, lines: [] }
  const sent: BomInput[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === `/api/boms/${bom.bomId}/lines/import`) {
      const body = request.postDataJSON() as BomInput
      sent.push(body)
      const skip = new Set(body.skipRows ?? [])
      // Like the server: the draft has SUGAR now. A left-out row is not checked, but when replacing, a left-out SUGAR row
      // is said to be removed for good; NOPE rows are wrong; SUGAR is wrong only when added to the SUGAR already there.
      const rows = body.rows.map((row, index) => {
        if (skip.has(index + 1)) {
          const current = body.replace && row.itemCode === 'SUGAR'
          return { row: index + 1, itemCode: row.itemCode, action: 'skipped',
            message: current ? 'In this BOM now; replacing removes it and this line does not add it back' : null }
        }
        if (row.itemCode.startsWith('NOPE')) return { row: index + 1, itemCode: row.itemCode, action: 'error', message: `No item has code ${row.itemCode}` }
        if (row.itemCode === 'SUGAR' && !body.replace) {
          return { row: index + 1, itemCode: row.itemCode, action: 'error', message: 'SUGAR is already a material of this BOM; combine the lines' }
        }
        return { row: index + 1, itemCode: row.itemCode, action: 'add', message: null }
      })
      const errors = rows.filter((row) => row.action === 'error').length
      return ok(route, { dryRun: body.dryRun, applied: !body.dryRun && errors === 0, added: rows.filter((row) => row.action === 'add').length,
        removed: body.replace ? 1 : 0, errors, skipped: skip.size,
        skippedCurrent: rows.filter((row) => row.action === 'skipped' && row.message).length, rows })
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 400, body: 'Unexpected write' })
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'BOM rows', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'BOM rows', ownerId: 'demo-owner' })
    if (pathname === '/api/items') return ok(route, [material, product])
    if (pathname === '/api/units') return ok(route, [{ unitId: 'unit_kg', unitCode: 'kg', unitName: 'kilogram', unitType: 'mass', activeYn: 'Y' }])
    if (pathname === '/api/boms') return ok(route, [bom])
    if (pathname === `/api/boms/${bom.bomId}`) return ok(route, bom)
    if (pathname === '/api/material-requirements') return ok(route, { projectId, orders: 0, lines: [], problems: [] })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=boms`)
  await page.getByRole('row', { name: /Cake draft/ }).click()
  const panel = page.getByLabel('Materials from CSV', { exact: true })
  await panel.getByLabel('Import materials file').setInputFiles({ name: 'cake.csv', mimeType: 'text/csv',
    buffer: Buffer.from('item_code,quantity,unit\nFLOUR,1,kg\nNOPE-1,1,kg\nSUGAR,2,kg\nEGG,4,ea\n') })
  const check = panel.getByLabel('Materials import check', { exact: true })

  // Added to what the draft has, SUGAR is there twice; NOPE-1 is no item.
  await expect(check).toContainText('2 to add, 2 with problems')
  expect(sent[0]).not.toHaveProperty('skipRows')
  await panel.getByRole('checkbox', { name: 'Replace current materials', exact: true }).check()
  await expect(check).toContainText('3 to add, 1 to remove, 1 with problems')
  expect(sent[1]).toMatchObject({ dryRun: true, replace: true })

  await check.getByRole('button', { name: 'Leave out the 1 line with problems', exact: true }).click()
  await expect(check).toContainText('3 to add, 1 to remove, 1 left out')
  expect(sent[2]).toMatchObject({ dryRun: true, replace: true, skipRows: [2] })
  await expect(check).not.toContainText('not added back')

  // Leaving SUGAR out while replacing removes the SUGAR the draft has and does not put it back: said above and on the line.
  await check.getByLabel('Import line 4', { exact: true }).uncheck()
  await expect(check).toContainText('2 to add, 1 to remove, 2 left out')
  await expect(check).toContainText('1 left-out line names a material this BOM has now. Replacing removes it, and it is not added back.')
  await expect(check.getByRole('row', { name: /line 4/ })).toContainText('replacing removes it')
  expect(sent[3]).toMatchObject({ dryRun: true, replace: true, skipRows: [2, 3] })

  await check.getByRole('button', { name: 'Replace materials', exact: true }).click()
  await expect(panel.getByRole('status')).toHaveText('Added 2 materials, removed 1, 2 lines left out.')
  expect(sent.at(-1)).toMatchObject({ dryRun: false, replace: true, skipRows: [2, 3] })
  expect(sent.at(-1)?.rows.map((row) => row.itemCode)).toEqual(['FLOUR', 'NOPE-1', 'SUGAR', 'EGG'])
  await expect(check).toHaveCount(0)
})
