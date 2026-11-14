import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { uniqueSuffix } from './support/uniqueSuffix'

test.skip(!process.env.REAL_API_E2E, 'Needs the real backend and demo seed')

/**
 * Inspection standards through the real UI and API (docs/domain/inspection-standard.md): the Quality tab lists an
 * item's standards, a run that makes the item shows them as a checklist, and following a standard in the inspection form
 * fills in its check and limits. Codes get a fresh suffix; the standards are deleted at the end.
 */
const PROJECT = 'prj_demo_main'
const suffix = uniqueSuffix()
const PRODUCT = `STD-P-${suffix}`
const RAW = `STD-R-${suffix}`

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

test('an item\'s standards become the run checklist and fill in the inspection form', async ({ page, request }) => {
  const call = await api(request)
  const item = (code: string) => call('POST', '/items', {
    projectId: PROJECT, itemCode: code, itemName: `standard e2e ${code}`, itemType: 'material', unitId: 'unit_kg',
  })
  const raw = await item(RAW)
  const product = await item(PRODUCT)
  const rawStock = await call('POST', '/inventories', { projectId: PROJECT, itemId: raw.itemId, quantity: 10, location: `STD-${suffix}` })
  const productStock = await call('POST', '/inventories', { projectId: PROJECT, itemId: product.itemId, quantity: 0, location: `STD-${suffix}` })
  const standard = (check: string, limits: object) => call('POST', '/inspection-standards', {
    projectId: PROJECT, itemId: product.itemId, inspectionType: check, stage: 'production', required: true, ...limits,
  })
  const standards = [
    await standard('Moisture', { standardMin: 10, standardMax: 12, unit: '%' }),
    await standard('Visual', {}),
  ]
  try {
    const run = await call('POST', '/production-runs/start', { projectId: PROJECT, workflowId: 'wf_demo_main', plannedOutputQty: 1 })
    const record = (inventoryId: string, itemId: string, direction: string, qty: number) =>
      call('POST', `/production-runs/${run.productionRunId}/items`, { inventoryId, itemId, direction, plannedQty: qty, actualQty: qty, unit: 'kg' })
    await record(rawStock.inventoryId, raw.itemId, 'input', 2)
    await record(productStock.inventoryId, product.itemId, 'output', 1)

    await login(page)
    await page.goto(`/projects/${PROJECT}/inventory?tab=quality`)
    const table = page.getByRole('table', { name: 'Standards' })
    await expect(table.getByRole('row', { name: new RegExp(`${PRODUCT}.*Moisture`) })).toContainText('10–12 %', { timeout: 15_000 })
    await expect(table.getByRole('row', { name: new RegExp(`${PRODUCT}.*Visual`) })).toContainText('pass/fail')

    await page.goto(`/projects/${PROJECT}/runs/${run.productionRunId}`)
    const checklist = page.getByRole('region', { name: 'Quality checklist' })
    await expect(checklist.getByRole('status')).toHaveText('0 of 2 required checks passed · 2 missing', { timeout: 15_000 })

    await page.getByRole('button', { name: 'Record inspection' }).click()
    const form = page.getByRole('form', { name: 'Record inspection' })
    await form.getByRole('combobox', { name: /^Standard/ }).selectOption({ label: 'Moisture · 10–12 %' })
    await expect(form.getByLabel('Check *')).toHaveValue('Moisture')
    await expect(form.getByLabel('Max')).toHaveValue('12')
    await form.getByLabel('Measured').fill('11')
    await form.getByRole('button', { name: 'Save inspection' }).click()
    await expect(checklist.getByRole('status')).toHaveText('1 of 2 required checks passed · 1 missing')
    await expect(checklist.getByRole('row', { name: /Moisture/ })).toContainText('✓ Pass (11 %)')

    // Finishing warns about the required check still missing, next to the button and in the confirmation.
    const warning = `Quality: 1 required check is not recorded (${PRODUCT} Visual).`
    await expect(page.getByRole('note', { name: 'Quality before finishing' })).toHaveText(warning)
    // A click that opens a confirm only returns once the dialog is answered, so answer it from a handler.
    let question = ''
    page.once('dialog', (dialog) => {
      question = dialog.message()
      void dialog.dismiss()
    })
    await page.getByRole('button', { name: 'Finish', exact: true }).click()
    await expect.poll(() => question).toContain(warning)
  } finally {
    for (const one of standards) await call('DELETE', `/inspection-standards/${one.standardId}`)
  }
})
