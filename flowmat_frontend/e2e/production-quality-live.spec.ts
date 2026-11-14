import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { uniqueSuffix } from './support/uniqueSuffix'

test.skip(!process.env.REAL_API_E2E, 'Needs the real backend, PostgreSQL/Redis and demo-owner seed')
// Login and route.fetch use real credentials; preserve assertions, not a trace of those exchanges.
test.use({ trace: 'off' })

interface Envelope<T> { success: boolean; message?: string; data: T }
interface Stock { inventoryId: string; itemId: string; lotId: string; quantity: number; reservedQuantity: number }
interface RunItem {
  productionRunItemId: string
  itemId: string
  inventoryId: string | null
  lotId: string | null
  direction: string
  actualQty: number | null
  quantitySource: string
  cancelled: boolean
}
interface Recording { itemId: string; inventoryId?: string; direction: string; plannedQty: number; actualQty?: number; unit: string; requestId?: string }
interface Allocation { allocationId: string; lotId: string; status: string; remaining: number }

async function loginUi(page: Page) {
  await page.goto('/')
  await page.getByRole('textbox', { name: 'demo-owner' }).fill('demo-owner')
  await page.getByRole('textbox', { name: '••••••••' }).fill('demo1234')
  const reply = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/auth/login')
  await page.getByRole('button', { name: 'Log in', exact: true }).click()
  const response = await reply
  expect(response.ok()).toBe(true)
  const envelope = await response.json() as Envelope<{ accessToken: string }>
  await expect(page.getByRole('button', { name: 'Logout', exact: true })).toBeVisible({ timeout: 15_000 })
  return { Authorization: `Bearer ${envelope.data.accessToken}` }
}

function api(request: APIRequestContext, headers: { Authorization: string }) {
  return async function call<T>(method: string, path: string, data?: unknown, expected = 200): Promise<T> {
    const response = await request.fetch(`/api${path}`, { method, headers, data, timeout: 15_000 })
    const envelope = await response.json() as Envelope<T>
    expect(response.status(), `${method} ${path}: ${envelope.message ?? ''}`).toBe(expected)
    return envelope.data
  }
}

/**
 * The production journey of docs/status/MONTH-PLAN-2026-11-16.md week 2 through the run screen, against the real backend:
 * an approved BOM's order is allocated, its run takes two flour LOTs and makes a bread LOT on the screen, and the reply of
 * one input is lost after the server recorded it. Pressing Record again with the same values records nothing twice
 * (docs/domain/production-run-recording.md RR2). Quality, genealogy, cost and the ledger are then checked through the API.
 */
test('a run recorded on the screen takes each LOT once even when an input reply is lost', async ({ page, request }) => {
  test.setTimeout(150_000)
  const call = api(request, await loginUi(page))
  const suffix = uniqueSuffix()
  // Every write belongs to this fresh project; the existing prj_demo_main business data is never changed.
  const { projectId } = await call<{ projectId: string }>('POST', '/projects', {
    projectName: `Production quality live ${suffix}`, ownerId: 'demo-owner',
  })
  for (const locationCode of ['RAW', 'FG']) {
    await call('POST', '/storage-locations', { projectId, locationCode, locationType: 'location' })
  }
  const flour = await call<{ itemId: string }>('POST', '/items', {
    projectId, itemCode: `PQ-FLOUR-${suffix}`, itemName: `Live flour ${suffix}`, itemType: 'material', unitId: 'unit_kg',
    lotManageYn: 'Y', unitCost: 2,
  })
  const bread = await call<{ itemId: string }>('POST', '/items', {
    projectId, itemCode: `PQ-BREAD-${suffix}`, itemName: `Live bread ${suffix}`, itemType: 'product', unitId: 'unit_ea',
    lotManageYn: 'Y',
  })
  const stockOf = async (itemId: string, lotNo: string, quantity: number, location: string, expiryDate?: string) => {
    const lot = await call<{ lotId: string }>('POST', '/lots', { projectId, itemId, lotNo, ...(expiryDate ? { expiryDate } : {}) })
    const stock = await call<Stock>('POST', '/inventories', { projectId, itemId, lotId: lot.lotId, location, quantity })
    return { lotId: lot.lotId, inventoryId: stock.inventoryId }
  }
  const soon = await stockOf(flour.itemId, `PQ-SOON-${suffix}`, 6, 'RAW', '2099-01-01')
  const late = await stockOf(flour.itemId, `PQ-LATE-${suffix}`, 10, 'RAW', '2099-06-01')
  const loaves = await stockOf(bread.itemId, `PQ-LOAVES-${suffix}`, 0, 'FG')
  const { workflowId } = await call<{ workflowId: string }>('POST', '/workflows', { projectId, workflowName: `Bake ${suffix}` })
  const { bomId } = await call<{ bomId: string }>('POST', '/boms', {
    projectId, targetItemId: bread.itemId, bomName: `Bread ${suffix}`, baseQuantity: 10, baseUnit: 'ea',
  })
  await call('POST', `/boms/${bomId}/lines`, { childItemId: flour.itemId, quantity: 5, unit: 'kg' })
  await call('POST', `/boms/${bomId}/submit`)
  await call('POST', `/boms/${bomId}/approve`)
  const order = await call<{ workOrderId: string; bomId: string }>('POST', '/work-orders', {
    projectId, workflowId, workOrderTitle: `Bake ${suffix}`, targetItemId: bread.itemId, targetQuantity: 20, bomId,
  })
  expect(order.bomId).toBe(bomId)
  const orderPath = `/work-orders/${order.workOrderId}`
  await call('POST', `${orderPath}/approve`)
  // 20 loaves need 10 kg: the LOT that expires first gives its 6, the next one 4.
  const allocated = await call<{ allocations: Allocation[] }>('POST', `${orderPath}/allocations`, {})
  expect(allocated.allocations.map(row => row.lotId)).toEqual([soon.lotId, late.lotId])
  const run = await call<{ productionRunId: string }>('POST', '/production-runs/start', {
    projectId, workflowId, workOrderId: order.workOrderId, plannedOutputQty: 20,
  })
  const runId = run.productionRunId
  const stock = (inventoryId: string) => call<Stock>('GET', `/inventories/${inventoryId}`)
  // Recordings only: the BOM's plan lines are listed with them.
  const items = async () => (await call<RunItem[]>('GET', `/production-runs/${runId}/items`)).filter(row => row.quantitySource !== 'bom')
  const ledgerTotal = async (itemId: string) => (await call<{ itemId: string; quantityDelta: number }[]>('GET',
    `/inventory-transactions?projectId=${projectId}`)).filter(row => row.itemId === itemId)
    .reduce((total, row) => total + row.quantityDelta, 0)

  await page.goto(`/projects/${projectId}/runs/${runId}`)
  const form = page.locator('#record-run-item')
  await expect(form).toBeVisible({ timeout: 15_000 })
  const record = async (direction: 'input' | 'output', itemId: string, inventoryId: string, quantity: number, unit: string) => {
    await form.getByRole('radio', { name: direction, exact: true }).check()
    await form.locator('label', { hasText: /^Item \*/ }).locator('select').selectOption(itemId)
    await form.locator('label', { hasText: /^Planned \*$/ }).locator('input').fill(String(quantity))
    await form.locator('label', { hasText: /^Actual$/ }).locator('input').fill(String(quantity))
    await form.locator('label', { hasText: /^Unit \*/ }).locator('select').selectOption(unit)
    await form.locator('label', { hasText: /^LOT \*/ }).locator('select').selectOption(inventoryId)
    await form.getByRole('button', { name: 'Record', exact: true }).click()
  }

  const itemsPath = `/api/production-runs/${runId}/items`
  const routePattern = `**${itemsPath}`
  const submitted: Recording[] = []
  let lost: RunItem | undefined
  await page.route(routePattern, async route => {
    if (route.request().method() !== 'POST') {
      await route.continue()
      return
    }
    submitted.push(route.request().postDataJSON() as Recording)
    if (submitted.length === 1) {
      // Commit the real backend transaction, then lose only the reply delivered to the browser.
      const response = await route.fetch({ timeout: 15_000, maxRetries: 0 })
      expect(response.status()).toBe(200)
      lost = (await response.json() as Envelope<RunItem>).data
      await route.abort('failed')
      return
    }
    await route.continue()
  })
  try {
    // 4 kg from the later LOT: its reply is lost after the server took the stock.
    await record('input', flour.itemId, late.inventoryId, 4, 'kg')
    await expect.poll(() => lost?.productionRunItemId).toBeTruthy()
    await expect(form.getByText('The result is unconfirmed.')).toBeVisible()
    await expect(form.locator('label', { hasText: /^Actual$/ }).locator('input')).toHaveValue('4')
    expect((await stock(late.inventoryId)).quantity).toBe(6)

    // The same values sent again: the server answers with the recording it already made.
    const retried = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === itemsPath)
    await form.getByRole('button', { name: 'Record', exact: true }).click()
    const retryResponse = await retried
    expect(retryResponse.status()).toBe(200)
    expect((await retryResponse.json() as Envelope<RunItem>).data.productionRunItemId).toBe(lost?.productionRunItemId)
    expect(submitted).toHaveLength(2)
    expect(submitted[0].requestId).toBeTruthy()
    expect(submitted[1]).toEqual(submitted[0])
    await expect(form.getByText('The result is unconfirmed.')).toHaveCount(0)
    expect((await stock(late.inventoryId)).quantity).toBe(6)
    expect((await items()).filter(row => row.direction === 'input' && !row.cancelled)).toHaveLength(1)
  } finally {
    await page.unroute(routePattern)
  }

  // The rest of the run on the screen: the sooner LOT's 6 kg and twenty loaves; each new recording has its own key.
  const reply = () => page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === itemsPath)
  let next = reply()
  await record('input', flour.itemId, soon.inventoryId, 6, 'kg')
  expect((await next).status()).toBe(200)
  next = reply()
  await record('output', bread.itemId, loaves.inventoryId, 20, 'ea')
  const outputResponse = await next
  expect(outputResponse.status()).toBe(200)
  expect((outputResponse.request().postDataJSON() as Recording).requestId).not.toBe(submitted[0].requestId)
  const recorded = (await items()).filter(row => !row.cancelled)
  expect(recorded.map(row => [row.direction, row.inventoryId, row.actualQty]).sort()).toEqual([
    ['input', late.inventoryId, 4], ['input', soon.inventoryId, 6], ['output', loaves.inventoryId, 20],
  ].sort())

  page.once('dialog', dialog => void dialog.accept())
  await page.getByRole('button', { name: 'Finish', exact: true }).click()
  await expect(page.getByText('This run is finished; no further items can be recorded.')).toBeVisible({ timeout: 15_000 })
  // Finished with the output field left empty: the run keeps the 20 loaves the field showed (RunDetailRoute handleFinish).
  expect((await call<{ actualOutputQty: number }>('GET', `/production-runs/${runId}`)).actualOutputQty).toBe(20)
  await call('POST', `${orderPath}/complete`)

  // Stock, reservations and the ledger agree; the allocation was used once.
  expect(await stock(soon.inventoryId)).toMatchObject({ quantity: 0, reservedQuantity: 0 })
  expect(await stock(late.inventoryId)).toMatchObject({ quantity: 6, reservedQuantity: 0 })
  expect((await stock(loaves.inventoryId)).quantity).toBe(20)
  expect(await ledgerTotal(flour.itemId)).toBe(6)
  expect(await ledgerTotal(bread.itemId)).toBe(20)
  const used = await call<{ allocations: Allocation[] }>('GET', `${orderPath}/allocations`)
  expect(used.allocations.every(row => row.status === 'closed' && row.remaining === 0)).toBe(true)

  // A failed check quarantines the loaves; genealogy and cost reach back to both flour LOTs.
  await call('POST', '/quality-inspections', {
    projectId, productionRunId: runId, lotId: loaves.lotId, inspectionType: 'Visual', result: 'fail', quarantineLot: true,
  })
  expect((await call<{ lotStatus: string }>('GET', `/lots/${loaves.lotId}`)).lotStatus).toBe('quarantined')
  const back = await call<{ nodes: { lot: { lotId: string } }[] }>('GET', `/lots/${loaves.lotId}/trace?direction=backward`)
  expect(back.nodes.map(node => node.lot.lotId)).toEqual(expect.arrayContaining([soon.lotId, late.lotId]))
  const cost = await call<{ materialCost: number; costPerUnit: number }>('GET', `/production-runs/${runId}/cost`)
  expect(cost).toMatchObject({ materialCost: 20, costPerUnit: 1 })
})
