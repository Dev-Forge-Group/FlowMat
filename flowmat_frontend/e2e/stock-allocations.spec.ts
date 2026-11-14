import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { uniqueSuffix } from './support/uniqueSuffix'

test.skip(!process.env.REAL_API_E2E, 'Needs the real backend and demo seed')

/**
 * Stock allocated to a work order through the real UI (docs/domain/stock-allocation.md): lines allocated through the API
 * (no BOM is created), a run of the order uses part of it, and the rest is released on the Work Orders screen. The run is
 * finished and the order completed at the end.
 */
const PROJECT = 'prj_demo_main'
const WORKFLOW = 'wf_demo_main'
const suffix = uniqueSuffix()
const FLOUR = `SA-E2E-${suffix}`
const TITLE = `Allocate ${suffix}`

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

test('a run of the order uses its allocated stock and the rest is released', async ({ page, request }) => {
  const call = await api(request)
  const flour = await call('POST', '/items', { projectId: PROJECT, itemCode: FLOUR, itemName: `flour ${suffix}`, itemType: 'material', unitId: 'unit_kg' })
  const loaf = await call('POST', '/items', { projectId: PROJECT, itemCode: `SA-LOAF-${suffix}`, itemName: `loaf ${suffix}`, itemType: 'product', unitId: 'unit_ea' })
  const stock = await call('POST', '/inventories', { projectId: PROJECT, itemId: flour.itemId, quantity: 10, location: `SA-${suffix}` })
  const order = await call('POST', '/work-orders', {
    projectId: PROJECT, workOrderTitle: TITLE, workflowId: WORKFLOW, targetItemId: loaf.itemId, targetQuantity: 5,
  })
  await call('POST', `/work-orders/${order.workOrderId}/approve`, {})
  await call('POST', `/work-orders/${order.workOrderId}/allocations`, { lines: [{ itemId: flour.itemId, quantity: 6 }] })
  expect((await call('GET', `/inventories/${stock.inventoryId}`)).reservedQuantity).toBe(6)
  const run = await call('POST', '/production-runs/start', {
    projectId: PROJECT, workflowId: WORKFLOW, plannedOutputQty: 5, workOrderId: order.workOrderId,
  })
  await call('POST', `/production-runs/${run.productionRunId}/items`, {
    direction: 'input', itemId: flour.itemId, inventoryId: stock.inventoryId, plannedQty: 4, actualQty: 4, unit: 'kg',
  })
  try {
    await login(page)
    await page.goto(`/projects/${PROJECT}/runs?view=work-orders`)
    await page.getByRole('row', { name: new RegExp(TITLE) }).getByRole('button', { name: 'Readiness' }).click()
    const allocated = page.getByRole('region', { name: 'Allocated stock' })
    const row = allocated.getByRole('table', { name: 'Allocations' }).getByRole('row', { name: new RegExp(FLOUR) })
    // Allocated 6, used 4 by the run, 2 left.
    await expect(row).toContainText('6')
    await expect(row).toContainText('4')
    await expect(row).toContainText('open')
    await row.getByRole('button', { name: 'Release' }).click()
    await expect(row).toContainText('closed')
    const after = await call('GET', `/inventories/${stock.inventoryId}`)
    expect(after.quantity).toBe(6)
    expect(after.reservedQuantity).toBe(0)
  } finally {
    await call('POST', `/production-runs/${run.productionRunId}/finish`, {})
    await call('POST', `/work-orders/${order.workOrderId}/complete`, {})
  }
})
