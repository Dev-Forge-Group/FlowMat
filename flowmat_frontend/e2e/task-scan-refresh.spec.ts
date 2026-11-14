import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

for (const change of ['renamed', 'partial', 'cancelled', 'keyboard', 'conflict', 'result'] as const) {
  test(`scanner stays current and safe during ${change}`, async ({ page }) => {
    const projectId = 'prj-scan-refresh'
    const item = { itemId: 'material', projectId, itemCode: 'MATERIAL', itemName: 'Material', itemType: 'material',
      resourceCategory: 'material', unitId: 'unit_kg', itemStatus: 'active', lotManageYn: 'N', unitCost: null }
    const task = { taskId: 'task', projectId, taskNo: 'WT-0001', taskType: 'putaway', status: 'open', inventoryId: 'stock',
      itemId: item.itemId, itemCode: item.itemCode, itemName: item.itemName, lotId: null, lotNo: null, quantity: 2,
      fromLocation: 'SRC', toLocation: 'DST', workOrderId: null, workOrderNumber: null, note: null,
      createdBy: 'demo-owner', createdAt: '2026-10-05T00:00:00Z', finishedBy: null, finishedAt: null,
      transferId: null, cancelReason: null, assignedTo: null as string | null }
    let scannerCompletions = 0
    const confirmations: string[] = []
    let releaseCompletion: () => void = () => {}
    const completion = new Promise<void>((resolve) => { releaseCompletion = resolve })
    await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
      const request = route.request()
      const { pathname } = new URL(request.url())
      if (await answerAuth(route, pathname)) return
      if (request.method() === 'PUT' && pathname === '/api/warehouse-tasks/task/assignee') {
        task.assignedTo = 'demo-owner'
        // The next query sees a concurrent location rename or partial completion by another worker.
        if (change === 'renamed') task.toLocation = 'DST-R'
        if (change === 'partial') task.quantity = 1
        return ok(route, task)
      }
      if (request.method() === 'POST' && pathname === '/api/warehouse-tasks/task/cancel') {
        task.status = 'cancelled'
        return ok(route, task)
      }
      if (request.method() === 'POST' && pathname === '/api/warehouse-tasks/task/complete') {
        const body = request.postDataJSON() as { expectedToLocation?: string; expectedQuantity?: number }
        expect(body.expectedQuantity).toBe(task.quantity)
        confirmations.push(body.expectedToLocation ?? '')
        if (change === 'conflict' && confirmations.length === 1) {
          task.toLocation = 'DST-R'
          return route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({
            success: false, data: null, message: 'expectedToLocation changed to DST-R. Scan its destination again.',
          }) })
        }
        if (body.expectedToLocation?.trim().toLowerCase() !== task.toLocation.toLowerCase()) {
          return route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({
            success: false, data: null, message: 'expectedToLocation must match the current destination.',
          }) })
        }
        scannerCompletions += 1
        if (change === 'keyboard') await completion
        if (change === 'result') task.quantity = 1
        task.status = 'done'
        return ok(route, task)
      }
      if (request.method() !== 'GET') {
        return route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify({ success: false, data: null, message: 'Unexpected write' }) })
      }
      if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Scanner refresh', ownerId: 'demo-owner' }])
      if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Scanner refresh', ownerId: 'demo-owner' })
      if (pathname === '/api/items') return ok(route, [item])
      if (pathname === '/api/warehouse-tasks') return ok(route, task.status === 'open' ? [task] : [])
      if (pathname === '/api/material-requirements') return ok(route, { projectId, orders: 0, lines: [], problems: [] })
      return ok(route, [])
    })
    await mockedLogin(page)
    await page.goto(`/projects/${projectId}/inventory?tab=tasks${change === 'keyboard' ? '&view=scanner' : ''}`)
    const scanner = page.getByRole('region', { name: 'Scan to do a task' })
    await scanner.getByRole('textbox', { name: 'Scan item', exact: true }).fill('MATERIAL')
    if (change === 'keyboard') await scanner.getByRole('textbox', { name: 'Scan item', exact: true }).press('Enter')
    else await scanner.getByRole('button', { name: 'Find task' }).click()
    await expect(scanner).toContainText('move 2 MATERIAL from SRC to DST.')
    if (change === 'keyboard') {
      const itemInput = scanner.getByRole('textbox', { name: 'Scan item', exact: true })
      const placeInput = scanner.getByRole('textbox', { name: 'Scan place', exact: true })
      await expect(placeInput).toBeFocused()
      await placeInput.fill('dst')
      await placeInput.press('Enter')
      await expect(itemInput).toBeDisabled()
      await expect(placeInput).toBeDisabled()
      await expect(scanner.getByRole('button', { name: 'Find task' })).toBeDisabled()
      releaseCompletion()
      await expect(scanner.getByRole('status')).toContainText('2 moved to DST')
      await expect(itemInput).toBeFocused()
      await expect(itemInput).toBeEnabled()
      expect(scannerCompletions).toBe(1)
    } else if (change === 'conflict') {
      const placeInput = scanner.getByRole('textbox', { name: 'Scan place', exact: true })
      await placeInput.fill('DST')
      await scanner.getByRole('button', { name: 'Done here' }).click()
      await expect(scanner.getByRole('alert')).toContainText('expectedToLocation changed')
      await expect(scanner).toContainText('move 2 MATERIAL from SRC to DST-R.')
      expect(scannerCompletions).toBe(0)
      await placeInput.fill('dst-r')
      await scanner.getByRole('button', { name: 'Done here' }).click()
      await expect(scanner.getByRole('status')).toContainText('2 moved to DST-R')
      expect(confirmations).toEqual(['DST', 'dst-r'])
      expect(scannerCompletions).toBe(1)
    } else if (change === 'cancelled') {
      await page.getByRole('row', { name: /WT-0001/ }).getByRole('button', { name: 'Cancel', exact: true }).click()
      await page.getByRole('textbox', { name: 'Why cancel' }).fill('No longer needed')
      await page.getByRole('button', { name: 'Cancel task', exact: true }).click()
      await expect(page.getByRole('row', { name: /WT-0001/ })).toHaveCount(0)
      await expect(scanner.getByRole('textbox', { name: 'Scan place', exact: true })).toHaveCount(0)
      await expect(scanner.getByRole('alert')).toContainText('no longer open')
    } else {
      await page.getByLabel('Assignee of WT-0001').selectOption('demo-owner')
      await expect(page.getByLabel('Assignee of WT-0001')).toHaveValue('demo-owner')
      await expect(scanner).toContainText(change === 'renamed' ? 'move 2 MATERIAL from SRC to DST-R.' : change === 'partial' ? 'move 1 MATERIAL from SRC to DST.' : 'move 2 MATERIAL from SRC to DST.')
      await scanner.getByRole('textbox', { name: 'Scan place', exact: true }).fill('DST')
      await scanner.getByRole('button', { name: 'Done here' }).click()
      if (change === 'renamed') {
        await expect(scanner.getByRole('alert')).toContainText('goes to DST-R')
        expect(scannerCompletions).toBe(0)
        await scanner.getByRole('textbox', { name: 'Scan place', exact: true }).fill('dst-r')
        await scanner.getByRole('button', { name: 'Done here' }).click()
      }
      await expect(scanner.getByRole('status')).toContainText(change === 'partial' || change === 'result' ? '1 moved to DST' : '2 moved to DST-R')
      expect(scannerCompletions).toBe(1)
    }
    if (change === 'cancelled') expect(scannerCompletions).toBe(0)
  })
}
