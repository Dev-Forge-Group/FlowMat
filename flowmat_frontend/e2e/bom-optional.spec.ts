import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * Optional BOM materials against a mocked API (ADR-033), so nothing is written to a real database: a BOM marks a line
 * optional and shows it apart from the plan, a materials file with an optional column is refused, and a work order picks
 * the optional materials of its BOM. The server side is covered by BomOptionalIntegrationTest.
 */
const projectId = 'prj-e2e'
const item = (itemId: string, itemCode: string, unitId: string) => ({
  itemId, projectId, itemCode, itemName: itemCode.toLowerCase(), itemType: 'material', resourceCategory: null,
  resourceType: null, unitId, itemStatus: 'active', lotManageYn: 'N', unitCost: null,
})
const items = [item('cake', 'CAKE', 'unit_ea'), item('roll', 'ROLL', 'unit_ea'), item('flour', 'FLOUR', 'unit_kg'),
  item('box', 'BOX', 'unit_ea')]
const line = (bomLineId: string, childItemId: string, quantity: number, unit: string, optionalYn = 'N') => ({
  bomLineId, childItemId, quantity, unit, scrapRate: null, optionalYn, substituteGroup: null, sortOrder: 1, note: null,
  lineType: 'material', phantom: false, priority: 0, substituteFor: null,
})
const bom = (bomId: string, targetItemId: string, bomName: string, bomStatus: string, lines: unknown[]) => ({
  bomId, projectId, targetItemId, bomName, bomVersion: 1, baseQuantity: 10, baseUnit: 'ea', bomStatus,
  approvedBy: null, approvedAt: null, note: null, lines,
})
const boms = [
  bom('cake-bom', 'cake', 'Cake', 'approved', [line('c1', 'flour', 2, 'kg'), line('c2', 'box', 10, 'ea', 'Y')]),
  bom('roll-draft', 'roll', 'Roll draft', 'draft', []),
]

async function mockApi(page: Page, writes: { lines: Record<string, unknown>[]; imports: unknown[]; orders: Record<string, unknown>[] }) {
  const orders: Record<string, unknown>[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const { pathname } = url
    if (await answerAuth(route, pathname)) return
    if (pathname === '/api/project-members') {
      return ok(route, [{ projectMemberId: 'pm', projectId, userId: 'demo-owner', projectRole: 'owner', memberStatus: 'active' }])
    }
    if (pathname === `/api/projects/${projectId}/time-zone`) return ok(route, { projectId, timeZone: 'Asia/Seoul', version: 0 })
    if (request.method() === 'POST' && pathname === '/api/boms/roll-draft/lines') {
      writes.lines.push(request.postDataJSON() as Record<string, unknown>)
      return ok(route, boms[1])
    }
    if (request.method() === 'POST' && pathname === '/api/boms/roll-draft/lines/import') {
      writes.imports.push(request.postDataJSON())
      return ok(route, { dryRun: true, applied: false, added: 0, removed: 0, errors: 0, rows: [] })
    }
    if (pathname === '/api/work-orders') {
      if (request.method() === 'GET') return ok(route, orders)
      const body = request.postDataJSON() as Record<string, unknown>
      writes.orders.push(body)
      const order = { ...body, workOrderId: 'wo-gift', workOrderNumber: 'WO-GIFT', workOrderStatus: 'draft', bomId: 'cake-bom',
        priority: 'normal', targetQuantity: 20, producedQuantity: 0, runCount: 0, plannedStartAt: null, plannedEndAt: null,
        actualStartAt: null, actualEndAt: null, instruction: null, assignedTo: null, approvedBy: null, approvedAt: null }
      orders.push(order)
      return ok(route, order)
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
    if (pathname === '/api/boms/cake-bom/requirements') {
      // 10 cakes take 2 kg of flour; a box each only for the orders that pick it.
      const quantity = Number(url.searchParams.get('quantity'))
      const requirement = (bomLineId: string, childItemId: string, perCake: number, unit: string, unitCost: number) => ({
        bomLineId, childItemId, lineQuantity: perCake * 10, lineUnit: unit, requiredQuantity: perCake * quantity, itemUnit: unit,
        requiredItemQuantity: perCake * quantity, conversionRate: 1, unitCost, lineCost: perCake * quantity * unitCost,
        viaItemId: null, scrapRate: 0, substitutes: [] })
      return ok(route, { bomId: 'cake-bom', bomVersion: 1, targetItemId: 'cake', productionQuantity: quantity, baseQuantity: 10,
        lines: [requirement('c1', 'flour', 0.2, 'kg', 10)], optionals: [requirement('c2', 'box', 1, 'ea', 3)],
        materialCost: 2 * quantity, costComplete: true, outputs: [] })
    }
    return ok(route, [])
  })
}

test('a BOM shows an optional material apart from the plan and the line form marks one', async ({ page }) => {
  const writes = { lines: [] as Record<string, unknown>[], imports: [] as unknown[], orders: [] as Record<string, unknown>[] }
  await mockApi(page, writes)
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=boms`)

  await page.getByRole('row', { name: /Cake/ }).first().click()
  await expect(page.getByRole('cell', { name: 'BOX · box · optional, per work order', exact: true })).toBeVisible()
  await page.getByRole('spinbutton', { name: 'Materials needed to make' }).fill('20')
  const optionals = page.getByRole('table', { name: 'Optional, per work order' })
  await expect(optionals).toContainText('BOX · box')
  await expect(optionals).toContainText('+ 20 ea (cost 60)')

  await page.getByRole('row', { name: /Roll draft/ }).click()
  await page.getByLabel('Material', { exact: true }).selectOption('box')
  await page.getByLabel('Material quantity').fill('1')
  await page.getByLabel('Optional: planned only for work orders that pick it').check()
  await page.getByRole('button', { name: 'Add', exact: true }).click()
  await expect.poll(() => writes.lines.length).toBe(1)
  expect(writes.lines[0]).toMatchObject({ childItemId: 'box', quantity: 1, lineType: 'material', optionalYn: 'Y' })

  // A materials file cannot mark optional materials yet: its column is refused before anything is sent.
  await page.getByLabel('Import materials file').setInputFiles({ name: 'roll.csv', mimeType: 'text/csv',
    buffer: Buffer.from('item_code,quantity,unit,optional\nBOX,1,ea,Y\n') })
  await expect(page.getByLabel('Materials from CSV').getByRole('alert'))
    .toHaveText('Optional materials are marked on screen, not by CSV yet; remove the optional column.')
  expect(writes.imports).toHaveLength(0)
})

test('a work order picks the optional materials of its BOM', async ({ page }) => {
  const writes = { lines: [] as Record<string, unknown>[], imports: [] as unknown[], orders: [] as Record<string, unknown>[] }
  await mockApi(page, writes)
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/runs?view=work-orders`)

  await page.getByLabel('Title *', { exact: true }).fill('Gift cakes')
  await page.getByLabel('Target item', { exact: true }).selectOption('cake')
  await expect(page.getByLabel('BOM', { exact: true })).toHaveValue('cake-bom')
  const optional = page.getByRole('group', { name: 'Optional materials' })
  await expect(optional.getByRole('checkbox')).toHaveCount(1)
  await optional.getByLabel('Take BOX · box (10 ea a batch)').check()
  await page.getByRole('button', { name: 'Create draft', exact: true }).click()
  await expect.poll(() => writes.orders.length).toBe(1)
  expect(writes.orders[0]).toMatchObject({ targetItemId: 'cake', bomId: 'cake-bom', optionalItemIds: ['box'] })
  await expect(page.getByRole('row', { name: /Gift cakes/ })).toContainText('with BOX')
})
