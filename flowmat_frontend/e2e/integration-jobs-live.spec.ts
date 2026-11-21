import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { uniqueSuffix } from './support/uniqueSuffix'

// Integration jobs against the real backend and a stand-in external system (scripts/integration-target.mjs), which
// browser-e2e.yml starts on a private address and approves for the connector (docs/architecture/adr/ADR-038-integration-jobs.md).
const origin = process.env.INTEGRATION_TARGET_ORIGIN
const control = `http://127.0.0.1:${process.env.INTEGRATION_TARGET_CONTROL_PORT ?? '18091'}/state`

test.skip(!process.env.REAL_API_E2E || !origin, 'Needs the real backend, demo-owner seed and the integration target')
test.use({ trace: 'off' })

interface Envelope<T> { success: boolean; message?: string; data: T }

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
  return async function call<T>(method: string, path: string, data?: unknown): Promise<T> {
    const response = await request.fetch(`/api${path}`, { method, headers, data, timeout: 15_000 })
    const envelope = await response.json() as Envelope<T>
    expect(response.status(), `${method} ${path}: ${envelope.message ?? ''}`).toBe(200)
    return envelope.data
  }
}

async function target(request: APIRequestContext, state?: { items: unknown[]; stock: Record<string, number> }) {
  const response = state ? await request.put(control, { data: state }) : await request.get(control)
  expect(response.ok()).toBe(true)
  return await response.json() as { stock: Record<string, number>; received: { items: { itemCode: string; quantity: number }[] }[] }
}

test('an owner pulls items from another system, pushes stock back and schedules the pull', async ({ page, request }) => {
  test.setTimeout(120_000)
  const tag = uniqueSuffix()
  const oats = `LIVE-OATS-${tag}`
  const rye = `LIVE-RYE-${tag}`
  const call = api(request, await loginUi(page))
  const projectId = (await call<{ projectId: string }>('POST', '/projects',
    { projectName: `Integration ${tag}`, ownerId: 'demo-owner' })).projectId
  await call('POST', `/projects/${projectId}/http-profiles`, { name: 'Partner', baseOrigin: origin, enabled: true, operations: [
    { operationId: 'items', method: 'GET', relativePath: '/items', successStatuses: [200] },
    { operationId: 'stock', method: 'GET', relativePath: '/stock', successStatuses: [200] },
    { operationId: 'update', method: 'POST', relativePath: '/stock', successStatuses: [200] },
  ] })
  await target(request, { items: [{ sku: oats, name: 'Oats' }, { sku: rye, name: '' }], stock: {} })

  await page.goto(`/projects/${projectId}/settings`)
  const panel = page.getByRole('region', { name: 'Integration jobs' })
  await expect(panel).toContainText('No integration jobs yet.')

  // A pull: the record without a name is left out by the item import's own rule, the other is taken.
  await panel.getByRole('button', { name: 'Add integration job' }).click()
  let form = panel.getByRole('form', { name: 'Edit integration job' })
  await form.getByLabel('Job name').fill('Partner items')
  await expect(form.getByLabel('Operation that reads')).toHaveValue('items')
  await form.getByRole('button', { name: 'Save job' }).click()
  await expect(form).toBeHidden()
  const pull = panel.getByRole('article', { name: 'Integration job Partner items' })
  await pull.getByRole('button', { name: 'Preview Partner items' }).click()
  await expect(pull.getByRole('status', { name: 'Preview' })).toContainText('Partly done · 1 of 2 taken · 1 left out')
  expect((await call<{ itemCode: string }[]>('GET', `/items?projectId=${projectId}`)).map(item => item.itemCode)).not.toContain(oats)
  await pull.getByRole('button', { name: 'Run Partner items now' }).click()
  await expect(pull.getByRole('status', { name: 'Run result' })).toContainText('Run: Partly done · 1 of 2 taken · 1 left out')
  expect((await call<{ itemCode: string }[]>('GET', `/items?projectId=${projectId}`)).map(item => item.itemCode)).toEqual([oats])
  await pull.getByRole('button', { name: 'View runs of Partner items' }).click()
  await pull.getByLabel('Runs of Partner items').getByRole('button', { name: /by hand · demo-owner · Partly done/ }).click()
  await expect(pull.getByLabel('Runs of Partner items').getByRole('row').nth(1)).toContainText(`${oats}New`)

  // A push: only the item whose quantity differs is sent, then read back.
  await call('POST', '/inventories/import', { projectId, dryRun: false, rows: [{ itemCode: oats, quantity: '6' }] })
  await target(request, { items: [], stock: { [oats]: 1, [`THEIRS-${tag}`]: 3 } })
  await panel.getByRole('button', { name: 'Add integration job' }).click()
  form = panel.getByRole('form', { name: 'Edit integration job' })
  await form.getByLabel('Job name').fill('Partner stock')
  await form.getByLabel('Kind').selectOption('push_stock')
  await expect(form.getByLabel('Operation that sends')).toHaveValue('update')
  await form.getByLabel('Operation that reads the target').selectOption('stock')
  await form.getByRole('button', { name: 'Save job' }).click()
  await expect(form).toBeHidden()
  const push = panel.getByRole('article', { name: 'Integration job Partner stock' })
  await push.getByRole('button', { name: 'Preview Partner stock' }).click()
  await expect(push.getByRole('status', { name: 'Preview' }))
    .toContainText('1 items would be sent. 1 of the items the target lists are not in FlowMat.')
  expect((await target(request)).received).toEqual([])
  await push.getByRole('button', { name: 'Run Partner stock now' }).click()
  await expect(push.getByRole('status', { name: 'Run result' })).toContainText('Run: Succeeded · 1 of 2 taken · 1 left out')
  const after = await target(request)
  expect(after.stock[oats]).toBe(6)
  expect(after.received).toHaveLength(1)
  expect(after.received[0].items.map(item => item.itemCode)).toEqual([oats])
  await push.getByRole('button', { name: 'Run Partner stock now' }).click()
  await expect(push.getByRole('status', { name: 'Run result' })).toContainText('Every item already matches the target.')
  expect((await target(request)).received).toHaveLength(1)

  // The pull goes on a schedule as the owner, and comes off it.
  const schedule = pull.getByRole('form', { name: 'Schedule of Partner items' })
  await schedule.getByLabel(/Schedule/).fill('0 6 * * *')
  await schedule.getByRole('button', { name: 'Switch schedule on' }).click()
  await expect(pull).toContainText('Scheduled 0 6 * * * (project time) as demo-owner')
  await pull.getByRole('form', { name: 'Schedule of Partner items' }).getByRole('button', { name: 'Switch schedule off' }).click()
  await expect(pull).toContainText('Schedule off (0 6 * * *)')
})
