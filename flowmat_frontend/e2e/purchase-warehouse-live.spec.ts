import { randomUUID } from 'node:crypto'
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { uniqueSuffix } from './support/uniqueSuffix'

test.skip(!process.env.REAL_API_E2E, 'Needs the real backend, PostgreSQL/Redis and demo-owner seed')
// Login and route.fetch use real credentials; preserve assertions, not a trace of those exchanges.
test.use({ trace: 'off' })

interface Envelope<T> { success: boolean; message?: string; data: T }
interface PurchaseOrder {
  purchaseOrderId: string
  status: string
  needsApproval: boolean
  approvedBy: string | null
  lines: { lineId: string; receivedQuantity: number; remainingQuantity: number }[]
  receipts: { receiptId: string; inventoryId: string }[]
}
interface Stock { inventoryId: string; itemId: string; lotId: string; location: string; quantity: number }
interface Task {
  taskId: string
  taskNo: string
  status: string
  quantity: number
  lotId: string
  transferId: string | null
}
interface Movement {
  inventoryTransactionId: string
  inventoryId: string
  lotId: string
  quantityDelta: number
  referenceType: string
  referenceId: string
}
interface Completion { quantity?: number; expectedQuantity?: number }

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
  expect(envelope.success).toBe(true)
  expect(typeof envelope.data.accessToken).toBe('string')
  await expect(page.getByRole('button', { name: 'Logout', exact: true })).toBeVisible({ timeout: 15_000 })
  return { Authorization: `Bearer ${envelope.data.accessToken}` }
}

function api(request: APIRequestContext, headers: { Authorization: string }) {
  return async function call<T>(method: string, path: string, data?: unknown, expected = 200): Promise<T> {
    const response = await request.fetch(`/api${path}`, { method, headers, data, timeout: 15_000 })
    const envelope = await response.json() as Envelope<T>
    expect(response.status(), `${method} ${path}: ${envelope.message ?? ''}`).toBe(expected)
    expect(envelope.success).toBe(expected === 200)
    return envelope.data
  }
}

test('real purchase receipts and putaway reconcile after a lost partial-completion response', async ({ page, request }) => {
  test.setTimeout(120_000)
  // One login supplies the UI and fixture API: another login of the same account can invalidate its previous session.
  const call = api(request, await loginUi(page))
  const suffix = uniqueSuffix()
  const project = await call<{ projectId: string }>('POST', '/projects', {
    projectName: `Purchase warehouse live ${suffix}`, ownerId: 'demo-owner',
  })
  const projectId = project.projectId
  // Every write belongs to this fresh project; the existing prj_demo_main business data is never changed.
  const item = await call<{ itemId: string }>('POST', '/items', {
    projectId, itemCode: `PW-LIVE-${suffix}`, itemName: `Live purchase material ${suffix}`,
    itemType: 'material', unitId: 'unit_kg', lotManageYn: 'Y',
  })
  for (const locationCode of ['DOCK', 'SHELF']) {
    await call('POST', '/storage-locations', { projectId, locationCode, locationType: 'location' })
  }
  await call('PUT', '/purchase-settings', { projectId, approvalLimit: 100, expectedVersion: null })
  const draft = await call<PurchaseOrder>('POST', '/purchase-orders', {
    projectId, supplierName: 'Live journey supplier', lines: [{ itemId: item.itemId, quantity: 10, unitPrice: 20 }],
  })
  expect(draft.needsApproval).toBe(true)
  const orderPath = `/purchase-orders/${draft.purchaseOrderId}`
  await call('POST', `${orderPath}/order`, undefined, 409)
  expect((await call<PurchaseOrder>('POST', `${orderPath}/approve`)).approvedBy).toBe('demo-owner')
  expect((await call<PurchaseOrder>('POST', `${orderPath}/order`)).status).toBe('ordered')
  const lineId = draft.lines[0].lineId
  const lotNo = `LOT-${suffix}`
  const receipt = (quantity: number) => ({ requestId: randomUUID(), lines: [
    { lineId, quantity, location: 'DOCK', lotNo, expiryDate: '2100-12-31' },
  ] })
  const first = receipt(4)
  let received = await call<PurchaseOrder>('POST', `${orderPath}/receipts`, first)
  const dockId = received.receipts[0].inventoryId
  const ledger = () => call<Movement[]>('GET', `/inventory-transactions?projectId=${projectId}`)
  const afterFirst = await ledger()
  expect(afterFirst).toHaveLength(2)
  expect(afterFirst.filter(movement => movement.referenceType === 'inventory' && movement.quantityDelta === 0)).toHaveLength(1)
  received = await call<PurchaseOrder>('POST', `${orderPath}/receipts`, first)
  expect(received.receipts).toHaveLength(1)
  expect(await ledger()).toEqual(afterFirst)
  received = await call<PurchaseOrder>('POST', `${orderPath}/receipts`, receipt(2))
  expect(received.status).toBe('ordered')
  expect(received.lines[0]).toMatchObject({ receivedQuantity: 6, remainingQuantity: 4 })
  const dock = await call<Stock>('GET', `/inventories/${dockId}`)
  expect(dock.quantity).toBe(6)
  expect(dock.lotId).toBeTruthy()
  const beforePlan = await ledger()
  expect(beforePlan).toHaveLength(3)

  await page.goto(`/projects/${projectId}/inventory?tab=tasks`)
  const putaway = page.getByRole('form', { name: 'New putaway' })
  await putaway.getByLabel('Stock record').selectOption(dockId, { timeout: 15_000 })
  await putaway.getByLabel('Quantity', { exact: true }).fill('6')
  await putaway.getByLabel('To', { exact: true }).fill('SHELF')
  const planReply = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/warehouse-tasks')
  await putaway.getByRole('button', { name: 'Plan putaway' }).click()
  const planResponse = await planReply
  expect(planResponse.status()).toBe(200)
  const planned = (await planResponse.json() as Envelope<Task>).data
  expect(planned).toMatchObject({ status: 'open', quantity: 6, lotId: dock.lotId })
  expect((await call<Stock>('GET', `/inventories/${dockId}`)).quantity).toBe(6)
  expect(await ledger()).toEqual(beforePlan)

  const table = page.getByRole('table', { name: 'Task list' })
  const row = table.getByRole('row').filter({ has: page.getByRole('cell', { name: planned.taskNo, exact: true }) })
  await expect(row.getByRole('cell').nth(3)).toHaveText('6')
  await row.getByRole('button', { name: 'Part…', exact: true }).click()
  const part = page.getByRole('form', { name: `Move part of ${planned.taskNo}` })
  await part.getByLabel('Quantity moved').fill('2')

  const completePath = `/api/warehouse-tasks/${planned.taskId}/complete`
  const routePattern = `**${completePath}`
  const submitted: Completion[] = []
  let droppedStatus: number | undefined
  let droppedMove: Task | undefined
  await page.route(routePattern, async route => {
    if (route.request().method() !== 'POST') {
      await route.continue()
      return
    }
    submitted.push(route.request().postDataJSON() as Completion)
    if (submitted.length === 1) {
      // Commit the actual backend transaction, then lose only the response delivered to the browser.
      const response = await route.fetch({ timeout: 15_000, maxRetries: 0 })
      droppedStatus = response.status()
      droppedMove = (await response.json() as Envelope<Task>).data
      await route.abort('failed')
      return
    }
    await route.continue()
  })
  try {
    await part.getByRole('button', { name: 'Move', exact: true }).click()
    await expect.poll(() => droppedStatus).toBe(200)
    await expect(row.getByRole('cell').nth(3)).toHaveText('4')
    await expect(part).toBeVisible()
    await expect(part.getByLabel('Quantity moved')).toHaveValue('2')
    expect(submitted).toEqual([{ quantity: 2, expectedQuantity: 6 }])
    expect(droppedMove).toMatchObject({ status: 'done', quantity: 2, lotId: dock.lotId })
    expect(droppedMove?.taskId).not.toBe(planned.taskId)

    const stockAt = async (source: number, destination: number) => {
      const rows = await call<Stock[]>('GET', `/inventories?projectId=${projectId}`)
      const material = rows.filter(stock => stock.itemId === item.itemId)
      expect(material).toHaveLength(2)
      expect(material.find(stock => stock.inventoryId === dockId)).toMatchObject({ location: 'DOCK', quantity: source, lotId: dock.lotId })
      expect(material.find(stock => stock.location === 'SHELF')).toMatchObject({ quantity: destination, lotId: dock.lotId })
      expect(material.reduce((total, stock) => total + stock.quantity, 0)).toBe(source + destination)
      return material.find(stock => stock.location === 'SHELF')!
    }
    const shelf = await stockAt(4, 2)
    const afterLost = await ledger()
    expect(afterLost).toHaveLength(5)
    const legs = afterLost.filter(movement => movement.referenceId === droppedMove?.transferId)
    expect(legs).toHaveLength(2)
    expect(legs.map(movement => movement.quantityDelta).sort((left, right) => left - right)).toEqual([-2, 2])
    expect(legs.every(movement => movement.referenceType === 'inventory_transfer' && movement.lotId === dock.lotId)).toBe(true)

    const retryReply = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === completePath)
    await part.getByRole('button', { name: 'Move', exact: true }).click()
    expect((await retryReply).status()).toBe(409)
    await expect(page.getByRole('alert').filter({ hasText: 'expectedQuantity' })).toBeVisible()
    await expect(row.getByRole('cell').nth(3)).toHaveText('4')
    await expect(part.getByLabel('Quantity moved')).toHaveValue('2')
    expect(submitted).toEqual([{ quantity: 2, expectedQuantity: 6 }, { quantity: 2, expectedQuantity: 6 }])
    await stockAt(4, 2)
    expect(await ledger()).toEqual(afterLost)
    let tasks = await call<Task[]>('GET', `/warehouse-tasks?projectId=${projectId}`)
    expect(tasks).toHaveLength(2)
    expect(tasks.find(task => task.taskId === planned.taskId)).toMatchObject({ status: 'open', quantity: 4 })

    // A deliberate reopening starts the next move from the newly observed quantity, instead of rebasing a retry.
    await part.getByRole('button', { name: 'Back', exact: true }).click()
    await row.getByRole('button', { name: 'Part…', exact: true }).click()
    await part.getByLabel('Quantity moved').fill('2')
    const nextReply = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === completePath)
    await part.getByRole('button', { name: 'Move', exact: true }).click()
    expect((await nextReply).status()).toBe(200)
    await expect(part).toHaveCount(0)
    await expect(row.getByRole('cell').nth(3)).toHaveText('2')
    expect(submitted[2]).toEqual({ quantity: 2, expectedQuantity: 4 })
    await stockAt(2, 4)

    received = await call<PurchaseOrder>('POST', `${orderPath}/receipts`, receipt(4))
    expect(received.status).toBe('received')
    expect(received.lines[0]).toMatchObject({ receivedQuantity: 10, remainingQuantity: 0 })
    expect(received.receipts).toHaveLength(3)
    expect(received.receipts.every(one => one.inventoryId === dockId)).toBe(true)
    expect((await stockAt(6, 4)).inventoryId).toBe(shelf.inventoryId)
    expect((await call<{ quantityOnHand: number }>('GET', `/lots/${dock.lotId}`)).quantityOnHand).toBe(10)
    const finalLedger = await ledger()
    expect(finalLedger).toHaveLength(8)
    expect(finalLedger.filter(movement => movement.referenceType === 'purchase_receipt')).toHaveLength(3)
    expect(finalLedger.filter(movement => movement.referenceType === 'inventory_transfer')).toHaveLength(4)
    expect(finalLedger.every(movement => movement.lotId === dock.lotId)).toBe(true)
    expect(finalLedger.reduce((total, movement) => total + movement.quantityDelta, 0)).toBe(10)
    tasks = await call<Task[]>('GET', `/warehouse-tasks?projectId=${projectId}`)
    expect(tasks).toHaveLength(3)
    expect(tasks.filter(task => task.status === 'done')).toHaveLength(2)
    // Cancel only this test's unfinished plan. The isolated project and ledger remain available for diagnosis.
    await call('POST', `/warehouse-tasks/${planned.taskId}/cancel`, { reason: 'Live journey complete; leave remaining stock at dock' })
    expect(await ledger()).toEqual(finalLedger)
  } finally {
    await page.unroute(routePattern)
  }
})
