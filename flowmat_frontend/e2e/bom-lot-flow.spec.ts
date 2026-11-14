import { test, expect, type Page } from '@playwright/test'
import { uniqueSuffix } from './support/uniqueSuffix'

/**
 * BOM + LOT happy path through the real UI and API (docs/domain/inventory-bom-lot-contract.md §5–6).
 * Needs a running backend with the demo seed; enabled with REAL_API_E2E. Codes get a fresh suffix so the test can run
 * against a database that already holds earlier runs.
 */
const PROJECT = 'prj_demo_main'
const suffix = uniqueSuffix()
const FLOUR = `FLR-${suffix}`
const BREAD = `BRD-${suffix}`
const LOT = `LOT-${suffix}`

async function login(page: Page) {
  await page.goto('/')
  await page.getByRole('textbox', { name: 'demo-owner' }).fill('demo-owner')
  await page.getByRole('textbox', { name: '••••••••' }).fill('demo1234')
  await page.getByRole('button', { name: 'Log in' }).click()
  await expect(page.getByText('안녕하세요, Demo Owner님')).toBeVisible({ timeout: 15_000 })
  // Let the session cookie settle before the full-page navigations below.
  await page.waitForLoadState('networkidle')
}

async function addItem(page: Page, code: string, unit: string, lotTracked: boolean) {
  await page.getByRole('textbox', { name: 'Code *' }).fill(code)
  await page.getByRole('textbox', { name: 'Name *' }).fill(code.toLowerCase())
  await page.getByRole('combobox', { name: /^Unit/ }).selectOption({ label: unit })
  await page.getByRole('checkbox', { name: 'Track stock per LOT' }).setChecked(lotTracked)
  const createResponse = page.waitForResponse((response) =>
    response.request().method() === 'POST' && new URL(response.url()).pathname.endsWith('/api/items')
  )
  await page.getByRole('button', { name: 'Add' }).click()
  const response = await createResponse
  const responseBody = response.ok() ? '' : await response.text()
  expect(response.ok(), responseBody).toBeTruthy()
  await expect(page.getByRole('row', { name: new RegExp(code) })).toBeVisible({ timeout: 15_000 })
}

test('LOT-tracked stock, quarantine, BOM approval and a run planned from the BOM', async ({ page }) => {
  page.on('dialog', (dialog) => void dialog.accept())
  await login(page)

  // Items: a LOT-tracked material and a product.
  await page.goto(`/projects/${PROJECT}/inventory`)
  await expect(page.getByRole('heading', { name: 'Inventory' })).toBeVisible({ timeout: 15_000 })
  await addItem(page, FLOUR, 'kg · Kilogram (mass)', true)
  await addItem(page, BREAD, 'ea · Each (count)', false)
  await expect(page.getByRole('row', { name: new RegExp(FLOUR) })).toContainText('tracked')

  // LOT registration.
  await page.getByRole('tab', { name: 'LOTs' }).click()
  await page.getByLabel('Item *').selectOption({ label: `${FLOUR} · ${FLOUR.toLowerCase()}` })
  await page.getByRole('textbox', { name: 'LOT number *' }).fill(LOT)
  await page.getByRole('button', { name: 'Register' }).click()
  await expect(page.getByRole('row', { name: new RegExp(LOT) })).toContainText('available')

  // Stock must name the LOT; quarantine covers the whole LOT and can be released.
  await page.getByRole('tab', { name: 'Stock' }).click()
  await page.getByLabel('Item *').selectOption({ label: `${FLOUR} · ${FLOUR.toLowerCase()}` })
  await page.getByLabel('LOT *').selectOption({ label: LOT })
  await page.getByRole('spinbutton', { name: 'On hand *' }).fill('100')
  await page.getByLabel('Location', { exact: true }).fill(`WH-${suffix}`)
  await page.getByRole('button', { name: 'Add' }).click()
  const stockRow = page.getByRole('row', { name: new RegExp(LOT) })
  await expect(stockRow).toContainText('100')
  await stockRow.getByRole('button', { name: 'Quarantine' }).click()
  await expect(stockRow).toContainText('quarantined')
  await stockRow.getByRole('button', { name: 'Release' }).click()
  await expect(stockRow).toContainText('available')

  // Movements go through the command API: issue, a refused over-issue, and a reversal with a reason.
  page.removeAllListeners('dialog')
  page.on('dialog', (dialog) => void dialog.accept(dialog.type() === 'prompt' ? 'Issued to the wrong order' : undefined))
  await stockRow.getByRole('button', { name: 'History' }).click()
  const movementForm = page.getByRole('form', { name: 'Record stock movement' })
  await movementForm.getByLabel('Movement').selectOption('issue')
  await movementForm.getByLabel('Quantity').fill('30')
  await movementForm.getByRole('button', { name: 'Record' }).click()
  await expect(stockRow).toContainText('70')
  await movementForm.getByLabel('Quantity').fill('500')
  await movementForm.getByRole('button', { name: 'Record' }).click()
  await expect(movementForm).toContainText('Not enough available stock')
  await page.getByRole('row', { name: /issue/ }).getByRole('button', { name: 'Reverse' }).click()
  await expect(page.getByRole('row', { name: /issue/ })).toContainText('reversed')
  await expect(stockRow).toContainText('100')

  // BOM: 20,000 g of flour per 100 ea of bread, approved, then 250 ea needs 50 kg.
  await page.getByRole('tab', { name: 'BOMs' }).click()
  await page.getByLabel('Product *').selectOption({ label: `${BREAD} · ${BREAD.toLowerCase()}` })
  await page.getByRole('textbox', { name: 'Name *' }).fill(`Bread ${suffix}`)
  await page.getByRole('spinbutton', { name: 'Makes *' }).fill('100')
  await page.getByRole('button', { name: 'Create draft' }).click()
  await page.getByLabel('Material', { exact: true }).selectOption({ label: `${FLOUR} · ${FLOUR.toLowerCase()}` })
  await page.getByLabel('Material quantity').fill('20000')
  await page.getByLabel('Material unit').selectOption('g')
  await page.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(page.getByText('20,000 g')).toBeVisible()
  await page.getByRole('button', { name: 'Submit for approval' }).click()
  await page.getByRole('button', { name: 'Approve' }).click()
  await expect(page.getByText(/Approved by demo-owner/)).toBeVisible()
  await page.getByRole('spinbutton', { name: 'Materials needed to make' }).fill('250')
  await expect(page.getByText('50 kg', { exact: true })).toBeVisible()

  // A run started from the BOM freezes its plan.
  await page.goto(`/projects/${PROJECT}/runs`)
  await page.getByLabel('Target item').selectOption({ label: `${BREAD} · ${BREAD.toLowerCase()}` })
  await page.getByRole('spinbutton', { name: 'Planned output qty *' }).fill('250')
  await expect(page.getByRole('checkbox', { name: /Plan materials from BOM/ })).toBeChecked()
  await page.getByRole('button', { name: 'Publish current workflow' }).click()
  await expect(page.getByRole('button', { name: 'Start', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: 'Start' }).click()
  await expect(page.getByText('Workflow revision', { exact: true }).locator('..'))
    .toContainText(/v\d+ \(fixed at start\)/)
  const plannedRow = page.getByRole('row', { name: new RegExp(`${FLOUR}.*BOM`) })
  await expect(plannedRow).toContainText('50')
  await expect(plannedRow).toContainText('kg')

  // Recording against the plan line pre-fills the form; the plan line then shows what was recorded.
  await plannedRow.getByRole('button', { name: 'Record' }).click()
  await expect(page.getByRole('spinbutton', { name: 'Actual', exact: true })).toHaveValue('50')
  const lotSelect = page.getByLabel('LOT *')
  const lotValue = await lotSelect.locator('option', { hasText: `LOT ${LOT}` }).getAttribute('value')
  await lotSelect.selectOption(lotValue ?? '')
  await page.getByRole('button', { name: 'Record', exact: true }).last().click()
  await expect(page.getByRole('row', { name: new RegExp(`LOT ${LOT}`) })).toBeVisible()
  await expect(plannedRow.getByRole('cell').nth(3)).toHaveText('50')

  // A work order for the product picks the approved BOM, and runs started from the order plan from it.
  await page.goto(`/projects/${PROJECT}/runs?view=work-orders`)
  await page.getByRole('textbox', { name: 'Title *' }).fill(`Order ${suffix}`)
  await page.getByLabel('Target item').selectOption({ label: `${BREAD} · ${BREAD.toLowerCase()}` })
  await expect(page.getByLabel('BOM')).toHaveValue(/.+/)
  await page.getByRole('spinbutton', { name: 'Quantity' }).fill('100')
  await page.getByRole('button', { name: 'Create draft' }).click()
  const orderRow = page.getByRole('row', { name: new RegExp(`Order ${suffix}`) })
  await expect(orderRow).toContainText('BOM v1')
  await orderRow.getByRole('button', { name: 'Approve' }).click()
  await expect(orderRow).toContainText('approved')

  await page.getByRole('tab', { name: 'Runs' }).click()
  const orderSelect = page.getByLabel('Work order')
  const orderValue = await orderSelect.locator('option', { hasText: `Order ${suffix}` }).getAttribute('value')
  await orderSelect.selectOption(orderValue ?? '')
  await expect(page.getByText(/planned from the work order.s BOM/)).toBeVisible()
  // The chosen order's readiness shows before the run starts.
  await expect(page.locator('[aria-label="Start readiness"]')).toContainText(/^(Ready|Not ready)/)
  await page.getByRole('button', { name: 'Start' }).click()
  await expect(page.getByText('Workflow revision', { exact: true }).locator('..'))
    .toContainText(/v\d+ \(fixed at start\)/)
})
