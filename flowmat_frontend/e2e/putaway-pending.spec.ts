import { expect, test, type Locator, type Page } from '@playwright/test'
import type { WarehouseTaskDto } from '../src/entities/inventory/api/useWarehouseTasks'
import type { InventoryDto } from '../src/shared/types/api'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

async function pendingPutawayFixture(page: Page, fails = false) {
  const projectId = 'prj-putaway-pending'
  const item = { itemId: 'material', projectId, itemCode: 'MATERIAL', itemName: 'Material', itemType: 'material',
    resourceCategory: 'material', unitId: 'unit_ea', itemStatus: 'active', lotManageYn: 'N', unitCost: null }
  const first: InventoryDto = { inventoryId: 'stock-a', projectId, itemId: item.itemId, quantity: 10, reservedQuantity: 0,
    availableQuantity: 10, inventoryStatus: 'available', location: 'DOCK-A', minThreshold: null, maxThreshold: null,
    stockLevel: 'ok', version: 0, lotId: null, lotNo: null }
  const stocks = [first, { ...first, inventoryId: 'stock-b', quantity: 20, availableQuantity: 20, location: 'DOCK-B' }]
  const tasks: WarehouseTaskDto[] = []
  const writes: Record<string, unknown>[] = []
  let release!: () => void
  const firstResponse = new Promise<void>((resolve) => { release = resolve })
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname, searchParams } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/warehouse-tasks') {
      const body = request.postDataJSON() as {
        projectId: string; taskType: string; inventoryId: string; quantity: number; toLocation: string; note: string | null
      }
      writes.push(body)
      if (writes.length === 1) await firstResponse
      if (fails) return route.fulfill({ status: 500, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: 'Putaway planning unavailable.' }) })
      const stock = stocks.find((one) => one.inventoryId === body.inventoryId)
      if (!stock) return route.fulfill({ status: 400 })
      // Creating an open plan keeps stock in place; its quantity is deducted only from free-to-plan stock.
      const task: WarehouseTaskDto = { taskId: `task-${writes.length}`, projectId, taskNo: `WT-000${writes.length}`,
        taskType: 'putaway', status: 'open', inventoryId: stock.inventoryId, itemId: item.itemId,
        itemCode: item.itemCode, itemName: item.itemName, lotId: null, lotNo: null, quantity: body.quantity,
        fromLocation: stock.location, toLocation: body.toLocation, workOrderId: null, workOrderNumber: null,
        note: body.note, createdBy: 'demo-owner', createdAt: '2026-11-09T10:00:00Z', finishedBy: null, finishedAt: null,
        transferId: null, cancelReason: null, assignedTo: null, allocationId: null }
      tasks.unshift(task)
      return ok(route, task)
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Putaway pending', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Putaway pending', ownerId: 'demo-owner' })
    if (pathname === '/api/items') return ok(route, [item])
    if (pathname === '/api/inventories') return ok(route, stocks)
    if (pathname === '/api/warehouse-tasks') return ok(route,
      tasks.filter((task) => !searchParams.get('status') || task.status === searchParams.get('status')))
    if (pathname === '/api/project-members') return ok(route, [
      { projectMemberId: 'owner', projectId, userId: 'demo-owner', projectRole: 'owner', memberStatus: 'active' },
    ])
    // No location list: destination codes are free text, as allowed by the server.
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=tasks`)
  const form = page.getByRole('form', { name: 'New putaway' })
  await expect(form.getByLabel('Stock record').getByRole('option', { name: 'MATERIAL · DOCK-B · 20 free' })).toHaveCount(1)
  return { form, writes, release }
}

async function fillPutaway(form: Locator, inventoryId: string, quantity: string, to: string, note: string) {
  await form.getByLabel('Stock record').selectOption(inventoryId)
  await form.getByLabel('Quantity', { exact: true }).fill(quantity)
  await form.getByLabel('To', { exact: true }).fill(to)
  await form.getByLabel('Note', { exact: true }).fill(note)
}

test('a completed putaway plan preserves the next whole draft entered while waiting', async ({ page }) => {
  const f = await pendingPutawayFixture(page)
  try {
    await fillPutaway(f.form, 'stock-a', '2', '  SHELF-A  ', '  First plan  ')
    await f.form.getByRole('button', { name: 'Plan putaway', exact: true }).click()
    await expect.poll(() => f.writes.length).toBe(1)
    await expect(f.form.getByRole('button', { name: 'Saving...', exact: true })).toBeDisabled()
    await fillPutaway(f.form, 'stock-b', '3', '  SHELF-B  ', '  Next plan  ')
    f.release()
    const plan = f.form.getByRole('button', { name: 'Plan putaway', exact: true })
    await expect(plan).toBeEnabled()
    await expect(f.form.getByLabel('Stock record')).toHaveValue('stock-b')
    await expect(f.form.getByLabel('Quantity', { exact: true })).toHaveValue('3')
    await expect(f.form.getByLabel('To', { exact: true })).toHaveValue('  SHELF-B  ')
    await expect(f.form.getByLabel('Note', { exact: true })).toHaveValue('  Next plan  ')
    await expect(page.getByRole('table', { name: 'Task list' }).getByRole('row', { name: /WT-0001/ })).toContainText('SHELF-A')
    await plan.click()
    await expect(page.getByRole('table', { name: 'Task list' }).getByRole('row', { name: /WT-0002/ })).toContainText('SHELF-B')
    expect(f.writes).toEqual([
      { projectId: 'prj-putaway-pending', taskType: 'putaway', inventoryId: 'stock-a', quantity: 2, toLocation: 'SHELF-A', note: 'First plan' },
      { projectId: 'prj-putaway-pending', taskType: 'putaway', inventoryId: 'stock-b', quantity: 3, toLocation: 'SHELF-B', note: 'Next plan' },
    ])
  } finally {
    f.release()
  }
})

test('an unchanged putaway draft clears when its plan succeeds', async ({ page }) => {
  const f = await pendingPutawayFixture(page)
  try {
    await fillPutaway(f.form, 'stock-a', '2', 'SHELF-A', 'First plan')
    await f.form.getByRole('button', { name: 'Plan putaway', exact: true }).click()
    await expect.poll(() => f.writes.length).toBe(1)
    f.release()
    await expect(f.form.getByRole('button', { name: 'Plan putaway', exact: true })).toBeEnabled()
    await expect(f.form.getByLabel('Stock record')).toHaveValue('')
    await expect(f.form.getByLabel('Quantity', { exact: true })).toHaveValue('')
    await expect(f.form.getByLabel('To', { exact: true })).toHaveValue('')
    await expect(f.form.getByLabel('Note', { exact: true })).toHaveValue('')
    await expect(page.getByRole('table', { name: 'Task list' }).getByRole('row', { name: /WT-0001/ })).toContainText('SHELF-A')
    expect(f.writes).toHaveLength(1)
  } finally {
    f.release()
  }
})

test('a failed putaway plan keeps the complete current draft', async ({ page }) => {
  const f = await pendingPutawayFixture(page, true)
  try {
    await fillPutaway(f.form, 'stock-a', '2', 'SHELF-A', 'First plan')
    await f.form.getByRole('button', { name: 'Plan putaway', exact: true }).click()
    await expect.poll(() => f.writes.length).toBe(1)
    await fillPutaway(f.form, 'stock-b', '3', 'SHELF-B', 'Next plan')
    f.release()
    await expect(f.form.getByRole('alert')).toHaveText('Putaway planning unavailable.')
    await expect(f.form.getByRole('button', { name: 'Plan putaway', exact: true })).toBeEnabled()
    await expect(f.form.getByLabel('Stock record')).toHaveValue('stock-b')
    await expect(f.form.getByLabel('Quantity', { exact: true })).toHaveValue('3')
    await expect(f.form.getByLabel('To', { exact: true })).toHaveValue('SHELF-B')
    await expect(f.form.getByLabel('Note', { exact: true })).toHaveValue('Next plan')
    expect(f.writes).toHaveLength(1)
  } finally {
    f.release()
  }
})

for (const restore of [false, true]) {
  test(restore ? 'editing and restoring a putaway note preserves the newer draft'
    : 'editing only a putaway note preserves all other fields on an older success', async ({ page }) => {
    const f = await pendingPutawayFixture(page)
    try {
      await fillPutaway(f.form, 'stock-a', '2', 'SHELF-A', 'First plan')
      await f.form.getByRole('button', { name: 'Plan putaway', exact: true }).click()
      await expect.poll(() => f.writes.length).toBe(1)
      const note = f.form.getByLabel('Note', { exact: true })
      await note.fill('Edited note')
      if (restore) await note.fill('First plan')
      f.release()
      await expect(f.form.getByRole('button', { name: 'Plan putaway', exact: true })).toBeEnabled()
      await expect(f.form.getByLabel('Stock record')).toHaveValue('stock-a')
      await expect(f.form.getByLabel('Quantity', { exact: true })).toHaveValue('2')
      await expect(f.form.getByLabel('To', { exact: true })).toHaveValue('SHELF-A')
      await expect(note).toHaveValue(restore ? 'First plan' : 'Edited note')
      expect(f.writes).toHaveLength(1)
    } finally {
      f.release()
    }
  })
}
