import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { uniqueSuffix } from './support/uniqueSuffix'

test.skip(!process.env.REAL_API_E2E, 'Needs the real backend and demo seed')

/**
 * The storage location list through the real UI and API (docs/domain/storage-location.md). Uses its own project: once a
 * project lists a place, free-text stock locations stop working there, which would break other specs in the demo
 * project. The project is deleted at the end.
 */
const suffix = uniqueSuffix()
const LOOSE = `loose-${suffix}`
const WAREHOUSE = `WH-${suffix}`
const BIN = `B-${suffix}`

async function api(request: APIRequestContext) {
  const login = await request.post('/api/auth/login', { data: { userIdOrEmail: 'demo-owner', password: 'demo1234' } })
  expect(login.ok(), await login.text()).toBeTruthy()
  const headers = { Authorization: `Bearer ${(await login.json()).data.accessToken}` }
  return async (method: string, path: string, data?: unknown, expectOk = true) => {
    const response = await request.fetch('/api' + path, { method, headers, data })
    const body = await response.json()
    if (expectOk) expect(response.ok(), `${method} ${path}: ${body.message}`).toBeTruthy()
    return { status: response.status(), data: body.data, message: body.message as string }
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

test('a project lists its places and new stock only goes to listed ones', async ({ page, request }) => {
  const call = await api(request)
  const project = (await call('POST', '/projects', { projectName: `Locations E2E ${suffix}`, ownerId: 'demo-owner' })).data.projectId
  try {
    const item = (await call('POST', '/items', {
      projectId: project, itemCode: `LOC-${suffix}`, itemName: 'location e2e item', itemType: 'material', unitId: 'unit_kg',
    })).data
    const loose = (await call('POST', '/inventories', { projectId: project, itemId: item.itemId, quantity: 5, location: LOOSE })).data

    await login(page)
    await page.goto(`/projects/${project}/inventory?tab=locations`)
    await expect(page.getByText('No places are listed')).toBeVisible({ timeout: 15_000 })

    // The place the stock already uses is offered, and listing it keeps that stock at a listed place.
    await expect(page.getByRole('status').filter({ hasText: LOOSE })).toBeVisible()
    await page.getByRole('button', { name: 'Add them as locations' }).click()
    const table = page.getByRole('table', { name: 'Storage locations' })
    await expect(table.getByRole('row', { name: new RegExp(LOOSE) })).toContainText('1 record, 1 item')

    const form = page.getByRole('form', { name: 'Add location' })
    await form.getByLabel('Code').fill(WAREHOUSE)
    await form.getByLabel('Kind').selectOption('warehouse')
    await form.getByRole('button', { name: 'Save' }).click()
    await expect(table.getByRole('row', { name: new RegExp(WAREHOUSE) })).toBeVisible()

    await form.getByLabel('Code').fill(BIN)
    await form.getByLabel('Kind').selectOption('bin')
    await form.getByLabel('Inside').selectOption({ label: WAREHOUSE })
    await form.getByRole('button', { name: 'Save' }).click()
    await expect(table.getByRole('row', { name: new RegExp(BIN) })).toContainText(`${WAREHOUSE} / ${BIN}`)

    // Place labels (storage-location.md L10): one per listed place, each with a barcode of its code, in a sheet to print.
    const opening = page.waitForEvent('popup')
    await page.getByRole('button', { name: 'Print labels' }).click()
    const sheet = await opening
    await expect(sheet.locator('.label')).toHaveCount(3)
    await expect(sheet.getByRole('img', { name: `Barcode ${BIN}` })).toBeVisible()
    await expect(sheet.getByText(`${WAREHOUSE} / ${BIN} · bin`)).toBeVisible()
    await expect(sheet.getByText('3 labels')).toBeVisible()
    await sheet.close()

    // A place holding stock cannot be switched off.
    await table.getByRole('row', { name: new RegExp(LOOSE) }).getByRole('button', { name: 'Deactivate' }).click()
    await expect(page.getByRole('alert').filter({ hasText: 'still holds stock' })).toBeVisible()

    // New stock only at listed places, stored as spelled in the list; the stock form suggests them.
    const refused = await call('POST', '/inventories', { projectId: project, itemId: item.itemId, quantity: 1, location: 'nowhere' }, false)
    expect(refused.status).toBe(400)
    expect(refused.message).toContain("not in this project's location list")
    // A minimum of 5 opens a low alert on this record (stock-alert.md), used for the alerts by place below.
    const placed = await call('POST', '/inventories', {
      projectId: project, itemId: item.itemId, quantity: 1, location: BIN.toLowerCase(), minThreshold: 5,
    })
    expect(placed.data.location).toBe(BIN)
    await page.getByRole('tab', { name: 'Stock' }).click()
    await expect(page.locator(`#storage-location-options option[value="${WAREHOUSE}"]`)).toHaveCount(1)
    await expect(page.locator(`#storage-location-options option[value="${BIN}"]`)).toHaveCount(1)

    // A place with places inside cannot be deleted. The warehouse holds nothing itself but counts the bin inside it
    // (loaded afresh: the stock above was added through the API, which the page does not hear about).
    await page.goto(`/projects/${project}/inventory?tab=locations`)
    await expect(table.getByRole('row', { name: new RegExp(WAREHOUSE) }).first())
      .toContainText('empty · with places inside: 1 record, 1 item')

    // Counting one warehouse: the place filter takes it and the places inside it.
    await page.goto(`/projects/${project}/inventory?tab=count`)
    const count = page.getByRole('form', { name: 'Stock count' })
    await count.getByLabel('Place', { exact: true }).selectOption({ label: WAREHOUSE })
    await expect(count.getByRole('row', { name: new RegExp(BIN) })).toHaveCount(1)
    await expect(count.getByRole('row', { name: new RegExp(LOOSE) })).toHaveCount(0)

    // Analysing one warehouse: the stock in it and the places inside it, not the loose place (stock-analysis.md A5).
    await page.goto(`/projects/${project}/inventory?tab=analysis`)
    const analysed = page.getByRole('table', { name: 'Stock analysis' }).getByRole('row', { name: new RegExp(`LOC-${suffix}`) })
    await expect(analysed.getByRole('cell').nth(1)).toHaveText('6 kg', { timeout: 15_000 })
    await page.getByLabel('Place', { exact: true }).selectOption({ label: WAREHOUSE })
    await expect(analysed.getByRole('cell').nth(1)).toHaveText('1 kg')

    // Moves between places (stock-analysis.md "위치 간 이동"): 2 kg moved from the loose place into the bin.
    await call('POST', '/inventory-transfers', { fromInventoryId: loose.inventoryId, toLocation: BIN, quantity: 2, requestId: crypto.randomUUID() })
    await page.reload()
    await page.getByText('Moves between places').click()
    await expect(page.getByRole('table', { name: 'Moves by route' }).getByRole('row', { name: new RegExp(`${LOOSE} → ${BIN}`) }))
      .toContainText(`LOC-${suffix} 2 kg`, { timeout: 15_000 })
    await expect(page.getByLabel('Busiest places')).toContainText(`${LOOSE} 1 out · 0 in`)
    await expect(page.getByLabel('Busiest places')).toContainText(`${BIN} 0 out · 1 in`)

    // Stock alerts by place: the low bin record is in the warehouse, and the loose place has none.
    await page.goto(`/projects/${project}/inventory?tab=stock`)
    const alerts = page.getByRole('region', { name: 'Stock alerts' })
    await expect(alerts).toContainText('1 stock alert', { timeout: 15_000 })
    await alerts.getByLabel('Alerts at place').selectOption({ label: WAREHOUSE })
    await expect(alerts).toContainText(`1 stock alert at ${WAREHOUSE}`)
    await alerts.getByLabel('Alerts at place').selectOption({ label: LOOSE })
    await expect(alerts).toContainText(`No open stock alerts at ${LOOSE}`)
    await page.goto(`/projects/${project}/inventory?tab=locations`)
    page.once('dialog', (dialog) => void dialog.accept())
    await table.getByRole('row', { name: new RegExp(WAREHOUSE) }).first().getByRole('button', { name: 'Delete' }).click()
    await expect(page.getByRole('alert').filter({ hasText: 'Delete or move the places inside' })).toBeVisible()

    // A place holding stock takes a new code and its stock follows (storage-location.md L7).
    await table.getByRole('row', { name: new RegExp(BIN) }).getByRole('button', { name: 'Edit' }).click()
    const edit = page.getByRole('form', { name: 'Edit location' })
    await expect(edit).toContainText('A new code moves the 1 stock record(s)')
    await edit.getByLabel('Code').fill(`${BIN}-R`)
    page.once('dialog', (dialog) => void dialog.accept())
    await edit.getByRole('button', { name: 'Save' }).click()
    await expect(table.getByRole('row', { name: new RegExp(`${BIN}-R`) })).toContainText('1 record, 1 item')
    expect((await call('GET', `/inventories?projectId=${project}`)).data
      .filter((row: { location: string | null }) => row.location === `${BIN}-R`)).toHaveLength(1)
  } finally {
    await call('DELETE', `/projects/${project}`, undefined, false)
  }
})
