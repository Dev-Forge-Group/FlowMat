import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { uniqueSuffix } from './support/uniqueSuffix'

/**
 * The read-only inventory reports through the real UI and API: quality overview, stock analysis, stock on a date and
 * the reorder suggestion (docs/domain/quality-inspection.md, stock-analysis.md, stock-ledger.md, stock-alert.md).
 * Needs a running backend with the demo seed; enabled with REAL_API_E2E. Codes get a fresh suffix so the test can run
 * against a database that already holds earlier runs.
 */
const PROJECT = 'prj_demo_main'
const suffix = uniqueSuffix()
const MALT = `RPT-${suffix}`
const CHECK = `Moisture ${suffix}`
const DEFECT = `Clumped ${suffix}`

async function api(request: APIRequestContext) {
  const login = await request.post('/api/auth/login', { data: { userIdOrEmail: 'demo-owner', password: 'demo1234' } })
  expect(login.ok(), await login.text()).toBeTruthy()
  const headers = { Authorization: `Bearer ${(await login.json()).data.accessToken}` }
  return async (method: string, path: string, data?: unknown) => {
    const response = await request.fetch('/api' + path, { method, headers, data })
    const body = await response.json()
    expect(response.ok(), `${method} ${path}: ${body.message}`).toBeTruthy()
    return body.data
  }
}

async function login(page: Page) {
  await page.goto('/')
  await page.getByRole('textbox', { name: 'demo-owner' }).fill('demo-owner')
  await page.getByRole('textbox', { name: '••••••••' }).fill('demo1234')
  await page.getByRole('button', { name: 'Log in' }).click()
  await expect(page.getByText('안녕하세요, Demo Owner님')).toBeVisible({ timeout: 15_000 })
  await page.waitForLoadState('networkidle')
}

test('quality overview, stock analysis, stock on a date and the reorder suggestion', async ({ page, request }) => {
  // Data first, through the API: 30 kg in, 15 kg issued, a failed and a passed check, and one defect.
  const call = await api(request)
  const malt = await call('POST', '/items', {
    projectId: PROJECT, itemCode: MALT, itemName: 'report malt', itemType: 'material', unitId: 'unit_kg',
    unitCost: 2, safetyStockQty: 50, leadTimeDays: 10,
  })
  const stock = await call('POST', '/inventories', { projectId: PROJECT, itemId: malt.itemId, quantity: 30, location: `RPT-${suffix}` })
  await call('POST', '/inventory-transactions', {
    inventoryId: stock.inventoryId, transactionType: 'issue', quantity: 15, requestId: crypto.randomUUID(),
  })
  await call('POST', '/quality-inspections', {
    projectId: PROJECT, itemId: malt.itemId, inspectionType: CHECK, measuredValue: 14, standardMax: 12, unit: '%',
  })
  await call('POST', '/quality-inspections', { projectId: PROJECT, itemId: malt.itemId, inspectionType: CHECK, result: 'pass' })
  await call('POST', '/defects', { projectId: PROJECT, itemId: malt.itemId, quantity: 1, defectType: DEFECT })

  await login(page)

  // Quality: the failing check and the defect type are counted, and the defect can be resolved from here.
  await page.goto(`/projects/${PROJECT}/inventory?tab=quality`)
  await expect(page.getByLabel('Quality summary')).toBeVisible({ timeout: 15_000 })
  await expect(page.getByLabel('Failures by check').getByRole('row', { name: new RegExp(CHECK) })).toContainText('1')
  const defect = page.getByRole('listitem', { name: `Defect ${DEFECT}` })
  await defect.getByRole('button', { name: 'Resolve' }).click()
  await defect.getByRole('textbox').first().fill('Dried and sieved')
  await defect.getByRole('button', { name: /^Resolve/ }).last().click()
  await expect(page.getByRole('listitem', { name: `Defect ${DEFECT}` })).toHaveCount(0)
  await expect(page.getByLabel('Defects by type').getByRole('row', { name: new RegExp(DEFECT) })).toContainText(/1\s*0$/)

  // Analysis: 15 kg left at 15 kg per 30 days lasts 30 days.
  await page.getByRole('tab', { name: 'Analysis' }).click()
  await expect(page.getByLabel('Stock analysis').getByRole('row', { name: new RegExp(MALT) })).toContainText('30 days')

  // Stock on a date: today's close shows the 15 kg left, worth 30.
  await page.getByRole('tab', { name: 'Movements' }).click()
  await page.getByRole('radio', { name: 'Stock on a date' }).click()
  await expect(page.getByLabel('Stock per item').getByRole('row', { name: new RegExp(MALT) })).toContainText('15 kg')

  // Reorder: 35 short plus 0.5 kg a day over the 10-day lead time.
  await page.getByRole('tab', { name: 'Stock' }).click()
  const reorder = page.getByLabel('Below safety stock').getByRole('row', { name: new RegExp(MALT) })
  await expect(reorder).toContainText('30 days')
  await expect(reorder).toContainText('40 kg')

  // Unit cost history (material-cost.md "단가 이력"): the item was created with a cost of 2.
  await page.getByRole('tab', { name: 'Items' }).click()
  await page.getByRole('row', { name: new RegExp(MALT) }).getByRole('button', { name: 'Details' }).click()
  await expect(page.getByLabel('Unit cost history')).toContainText('unknown → 2')
})
