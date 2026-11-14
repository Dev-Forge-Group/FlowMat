import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { uniqueSuffix } from './support/uniqueSuffix'

test.skip(!process.env.REAL_API_E2E, 'Needs the real backend and demo seed')

/**
 * Putaway and pick tasks through the real UI (docs/domain/warehouse-task.md): a planned putaway moves nothing until it is
 * done, and a pick list for given items plans picks from free stock and says what is short. Codes get a fresh suffix;
 * tasks still open at the end are cancelled.
 */
const PROJECT = 'prj_demo_main'
const suffix = uniqueSuffix()
const ITEM = `WT-E2E-${suffix}`
const DOCK = `DOCK-${suffix}`
const SHELF = `SHELF-${suffix}`
const LINE = `LINE-${suffix}`

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

test('a putaway moves stock only when done, and a pick list plans from free stock', async ({ page, request }) => {
  const call = await api(request)
  const item = await call('POST', '/items', {
    projectId: PROJECT, itemCode: ITEM, itemName: `task e2e ${suffix}`, itemType: 'material', unitId: 'unit_ea',
  })
  const stock = await call('POST', '/inventories', { projectId: PROJECT, itemId: item.itemId, quantity: 10, location: DOCK })
  try {
    await login(page)
    await page.goto(`/projects/${PROJECT}/inventory?tab=tasks`)

    const putaway = page.getByRole('form', { name: 'New putaway' })
    await putaway.getByLabel('Stock record').selectOption({ label: `${ITEM} · ${DOCK} · 10 free` }, { timeout: 15_000 })
    await putaway.getByLabel('Quantity').fill('6')
    await putaway.getByLabel('To', { exact: true }).fill(SHELF)
    await putaway.getByRole('button', { name: 'Plan putaway' }).click()

    const tasks = page.getByRole('table', { name: 'Task list' })
    const row = tasks.getByRole('row', { name: new RegExp(`${ITEM}.*${SHELF}`) })
    await expect(row).toContainText('open')
    expect((await call('GET', `/inventories/${stock.inventoryId}`)).quantity).toBe(10)

    await row.getByRole('button', { name: 'Done' }).click()
    await expect(row).toHaveCount(0)
    expect((await call('GET', `/inventories/${stock.inventoryId}`)).quantity).toBe(4)
    await page.getByLabel('Task status').selectOption('done')
    await expect(tasks.getByRole('row', { name: new RegExp(`${ITEM}.*${SHELF}`) })).toContainText('done')

    // 12 needed at the line: 4 free at the dock and 6 on the shelf can be planned, 2 are short.
    const pick = page.getByRole('form', { name: 'Pick list' })
    await pick.getByRole('radio', { name: 'Items' }).check()
    await pick.getByLabel('Item 1').selectOption({ label: `${ITEM} · task e2e ${suffix}` })
    await pick.getByLabel('Quantity 1').fill('12')
    await pick.getByLabel('Pick to').fill(LINE)
    await pick.getByRole('button', { name: 'Plan picks' }).click()
    await expect(pick.getByRole('status')).toContainText('2 picks planned.')
    await expect(pick.getByRole('status')).toContainText(`Short: ${ITEM} 2`)
    await page.getByLabel('Task status').selectOption('open')
    await expect(tasks.getByRole('row', { name: new RegExp(`${ITEM}.*${LINE}`) })).toHaveCount(2)

    // Who does a task (W7): given to a member, it shows under Mine and leaves Not assigned.
    const givenRow = tasks.getByRole('row', { name: new RegExp(`${ITEM}.*${LINE}`) }).first()
    const givenNo = (await givenRow.getByRole('cell').nth(0).textContent())!.trim()
    await givenRow.getByLabel(`Assignee of ${givenNo}`).selectOption('demo-owner')
    await expect(tasks.getByLabel(`Assignee of ${givenNo}`)).toHaveValue('demo-owner')
    await page.getByLabel('Assigned to').selectOption('me')
    await expect(tasks.getByRole('row', { name: new RegExp(`${ITEM}.*${LINE}`) })).toHaveCount(1)
    await page.getByLabel('Assigned to').selectOption('nobody')
    await expect(tasks.getByRole('row', { name: new RegExp(`^${givenNo}`) })).toHaveCount(0)
    await expect(tasks.getByRole('row', { name: new RegExp(`${ITEM}.*${LINE}`) })).toHaveCount(1)
    await page.getByLabel('Assigned to').selectOption('anyone')

    // Part of a pick: 1 moves now as a done task of its own, and the pick stays open with the rest.
    const pickRow = tasks.getByRole('row', { name: new RegExp(`${ITEM}.*${LINE}`) }).first()
    const pickNo = (await pickRow.getByRole('cell').nth(0).textContent())!.trim()
    const before = Number((await pickRow.getByRole('cell').nth(3).textContent())!.trim())
    await pickRow.getByRole('button', { name: 'Part…' }).click()
    const part = page.getByRole('form', { name: `Move part of ${pickNo}` })
    await part.getByLabel('Quantity moved').fill(String(before + 1))
    await part.getByRole('button', { name: 'Move' }).click()
    await expect(part.getByRole('alert')).toHaveText(`Enter a quantity above 0 and at most ${before}.`)
    await part.getByLabel('Quantity moved').fill('1')
    await part.getByRole('button', { name: 'Move' }).click()
    await expect(tasks.getByRole('row', { name: new RegExp(`^${pickNo}`) }).getByRole('cell').nth(3)).toHaveText(String(before - 1))
    await page.getByLabel('Task status').selectOption('done')
    await expect(tasks.getByRole('row', { name: new RegExp(`Part of ${pickNo}`) })).toContainText('done')

    // Doing a pick by scanning (W8): the item finds its open picks, a wrong place is refused, the line does it.
    await page.getByLabel('Task status').selectOption('open')
    const scan = page.getByRole('region', { name: 'Scan to do a task' })
    await scan.getByLabel('Scan item').fill(ITEM.toLowerCase())
    await scan.getByRole('button', { name: 'Find task' }).click()
    await scan.getByRole('group', { name: 'Tasks for this item' }).getByRole('button').first().click()
    await scan.getByLabel('Scan place').fill(DOCK)
    await scan.getByRole('button', { name: 'Done here' }).click()
    await expect(scan.getByRole('alert')).toContainText(`goes to ${LINE}`)
    await scan.getByLabel('Scan place').fill(LINE.toLowerCase())
    await scan.getByRole('button', { name: 'Done here' }).click()
    await expect(scan.getByRole('status')).toContainText(`moved to ${LINE}`)
    await expect(tasks.getByRole('row', { name: new RegExp(`${ITEM}.*${LINE}`) })).toHaveCount(1)

    // A handheld's view (W9): only the scan box and my open tasks, kept in the address, and back to the full view.
    await page.getByRole('button', { name: 'Scanner view' }).click()
    await expect(page).toHaveURL(/view=scanner/)
    const scanner = page.getByRole('region', { name: 'Scanner view' })
    await expect(scanner.getByRole('region', { name: 'Scan to do a task' })).toBeVisible()
    await expect(scanner.getByLabel('Scan item')).toBeFocused()
    await expect(scanner.getByRole('list', { name: 'My open tasks' })).toContainText(`${ITEM}`)
    await expect(page.getByRole('form', { name: 'New putaway' })).toHaveCount(0)
    await scanner.getByRole('button', { name: 'Full view' }).click()
    await expect(page.getByRole('form', { name: 'New putaway' })).toBeVisible()
    await expect(page).not.toHaveURL(/view=scanner/)
  } finally {
    const open = await call('GET', `/warehouse-tasks?projectId=${PROJECT}&status=open`)
    for (const task of open.filter((one: { itemId: string }) => one.itemId === item.itemId)) {
      await call('POST', `/warehouse-tasks/${task.taskId}/cancel`, { reason: 'e2e cleanup' })
    }
  }
})
