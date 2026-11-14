import { expect, test, type Page } from '@playwright/test'
import type { WarehouseTaskCompletionInput, WarehouseTaskDto } from '../src/entities/inventory/api/useWarehouseTasks'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

type CompletionBody = Omit<WarehouseTaskCompletionInput, 'taskId'>

async function fixture(page: Page, scenario: 'lost' | 'pending' | 'whole' | 'scanner') {
  const projectId = 'prj-quantity-confirmation'
  const item = { itemId: 'material', projectId, itemCode: 'MATERIAL', itemName: 'Material', itemType: 'material',
    resourceCategory: 'material', unitId: 'unit_kg', itemStatus: 'active', lotManageYn: 'N', unitCost: null }
  const task: WarehouseTaskDto = { taskId: 'task', projectId, taskNo: 'WT-0001', taskType: 'putaway', status: 'open',
    inventoryId: 'stock', itemId: item.itemId, itemCode: item.itemCode, itemName: item.itemName, lotId: null, lotNo: null,
    quantity: 6, fromLocation: 'DOCK', toLocation: 'SHELF', workOrderId: null, workOrderNumber: null, note: null,
    createdBy: 'demo-owner', createdAt: '2026-11-10T00:00:00Z', finishedBy: null, finishedAt: null,
    transferId: null, cancelReason: null, assignedTo: null }
  const requests: CompletionBody[] = []
  let moved = 0
  let release!: () => void
  const response = new Promise<void>((resolve) => { release = resolve })
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'PUT' && pathname === '/api/warehouse-tasks/task/assignee') {
      task.assignedTo = 'demo-owner'
      return ok(route, task)
    }
    if (request.method() === 'POST' && pathname === '/api/warehouse-tasks/task/complete') {
      const body = request.postDataJSON() as CompletionBody
      requests.push(body)
      if (scenario === 'scanner' && requests.length === 1) task.quantity = 4
      if (body.expectedQuantity !== task.quantity) return route.fulfill({ status: 409, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null,
          message: 'expectedQuantity changed. Check completed moves before reopening the form.' }) })
      const quantity = body.quantity ?? task.quantity
      moved += quantity
      const done = { ...task, taskId: body.quantity ? 'child' : task.taskId, taskNo: body.quantity ? 'WT-0002' : task.taskNo,
        status: 'done', quantity, transferId: 'transfer' }
      if (body.quantity) task.quantity -= quantity
      else task.status = 'done'
      if (requests.length === 1 && scenario === 'lost') return route.abort('connectionreset')
      if (requests.length === 1 && scenario === 'pending') await response
      return ok(route, done)
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 400 })
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Quantity confirmation', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Quantity confirmation', ownerId: 'demo-owner' })
    if (pathname === '/api/items') return ok(route, [item])
    if (pathname === '/api/inventories') return ok(route, [{ inventoryId: 'stock', projectId, itemId: item.itemId,
      quantity: 10 - moved, reservedQuantity: 0, availableQuantity: 10 - moved, inventoryStatus: 'available', location: 'DOCK',
      minThreshold: null, maxThreshold: null, stockLevel: 'ok', version: 0, lotId: null, lotNo: null }])
    if (pathname === '/api/warehouse-tasks') return ok(route, task.status === 'open' ? [task] : [])
    if (pathname === '/api/material-requirements') return ok(route, { projectId, orders: 0, lines: [], problems: [] })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=tasks`)
  const row = page.getByRole('table', { name: 'Task list' }).getByRole('row', { name: /WT-0001/ })
  await expect(row).toContainText('open')
  return { row, requests, moved: () => moved, release }
}

test('an uncertain partial result keeps its original quantity confirmation until the form is reopened', async ({ page }) => {
  const f = await fixture(page, 'lost')
  await f.row.getByRole('button', { name: 'Part…', exact: true }).click()
  const form = f.row.getByRole('form', { name: 'Move part of WT-0001' })
  await form.getByLabel('Quantity moved').fill('2')
  await form.getByRole('button', { name: 'Move', exact: true }).click()
  await expect(page.getByRole('region', { name: 'Warehouse tasks', exact: true }).getByRole('alert')).toBeVisible()
  await expect(f.row.getByRole('cell', { name: '4', exact: true })).toBeVisible()
  // Both the remaining task (4) and the source stock (8) must refresh after the lost response.
  await expect(page.getByRole('form', { name: 'New putaway' }).getByLabel('Stock record')
    .getByRole('option', { name: 'MATERIAL · DOCK · 4 free', exact: true })).toHaveCount(1)
  await expect(form.getByLabel('Quantity moved')).toHaveValue('2')
  await form.getByRole('button', { name: 'Move', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('expectedQuantity changed')
  expect(f.requests).toEqual([{ quantity: 2, expectedQuantity: 6 }, { quantity: 2, expectedQuantity: 6 }])
  expect(f.moved()).toBe(2)

  await form.getByRole('button', { name: 'Back', exact: true }).click()
  await f.row.getByRole('button', { name: 'Part…', exact: true }).click()
  await form.getByLabel('Quantity moved').fill('1')
  await form.getByRole('button', { name: 'Move', exact: true }).click()
  await expect(form).toHaveCount(0)
  expect(f.requests[2]).toEqual({ quantity: 1, expectedQuantity: 4 })
  expect(f.moved()).toBe(3)
})

for (const reopen of [false, true]) {
  test(reopen ? 'a late partial response preserves a newly reopened completion form'
    : 'a late partial response preserves newer input in the same completion form', async ({ page }) => {
    const f = await fixture(page, 'pending')
    try {
      await f.row.getByRole('button', { name: 'Part…', exact: true }).click()
      const form = f.row.getByRole('form', { name: 'Move part of WT-0001' })
      await form.getByLabel('Quantity moved').fill('2')
      await form.getByRole('button', { name: 'Move', exact: true }).click()
      await expect.poll(() => f.requests.length).toBe(1)
      await page.getByLabel('Assignee of WT-0001').selectOption('demo-owner')
      await expect(f.row.getByRole('cell', { name: '4', exact: true })).toBeVisible()
      if (reopen) {
        await form.getByRole('button', { name: 'Back', exact: true }).click()
        await f.row.getByRole('button', { name: 'Part…', exact: true }).click()
      }
      await form.getByLabel('Quantity moved').fill('1')
      f.release()
      await expect(form.getByRole('button', { name: 'Move', exact: true })).toBeEnabled()
      await expect(form.getByLabel('Quantity moved')).toHaveValue('1')
      await form.getByRole('button', { name: 'Move', exact: true }).click()
      if (!reopen) {
        await expect(page.getByRole('alert')).toContainText('expectedQuantity changed')
        expect(f.requests[1]).toEqual({ quantity: 1, expectedQuantity: 6 })
        expect(f.moved()).toBe(2)
        await form.getByRole('button', { name: 'Back', exact: true }).click()
        await f.row.getByRole('button', { name: 'Part…', exact: true }).click()
        await form.getByLabel('Quantity moved').fill('1')
        await form.getByRole('button', { name: 'Move', exact: true }).click()
      }
      await expect(form).toHaveCount(0)
      expect(f.requests).toEqual([{ quantity: 2, expectedQuantity: 6 },
        ...(!reopen ? [{ quantity: 1, expectedQuantity: 6 }] : []), { quantity: 1, expectedQuantity: 4 }])
    } finally { f.release() }
  })
}

test('whole completion confirms the displayed remaining quantity', async ({ page }) => {
  const f = await fixture(page, 'whole')
  await f.row.getByRole('button', { name: 'Done', exact: true }).click()
  await expect(f.row).toHaveCount(0)
  expect(f.requests).toEqual([{ expectedQuantity: 6 }])
  expect(f.moved()).toBe(6)
})

test('a scanner refreshes a rejected quantity and confirms it after scanning again', async ({ page }) => {
  const f = await fixture(page, 'scanner')
  const scanner = page.getByRole('region', { name: 'Scan to do a task' })
  await scanner.getByLabel('Scan item', { exact: true }).fill('MATERIAL')
  await scanner.getByRole('button', { name: 'Find task' }).click()
  await scanner.getByLabel('Scan place', { exact: true }).fill('SHELF')
  await scanner.getByRole('button', { name: 'Done here' }).click()
  await expect(scanner.getByRole('alert')).toContainText('expectedQuantity changed')
  await expect(scanner).toContainText('move 4 MATERIAL')
  await scanner.getByLabel('Scan place', { exact: true }).fill('shelf')
  await scanner.getByRole('button', { name: 'Done here' }).click()
  await expect(scanner.getByRole('status')).toContainText('4 moved to SHELF')
  expect(f.requests).toEqual([{ expectedToLocation: 'SHELF', expectedQuantity: 6 }, { expectedToLocation: 'shelf', expectedQuantity: 4 }])
  expect(f.moved()).toBe(4)
})
