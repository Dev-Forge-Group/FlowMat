import { test, expect, type Page } from '@playwright/test'
import { uniqueSuffix } from './support/uniqueSuffix'

/**
 * LOT genealogy through the real UI (docs/domain/inventory-bom-lot-contract.md §6): a run that consumes a material
 * LOT and produces a product LOT links them, and the LOTs tab traces both ways. Enabled with REAL_API_E2E.
 */
const PROJECT = 'prj_demo_main'
const suffix = `${uniqueSuffix()}G`
const RAW = `RAW-${suffix}`
const DOUGH = `DGH-${suffix}`
const RAW_LOT = `RL-${suffix}`
const DOUGH_LOT = `DL-${suffix}`

async function login(page: Page) {
  await page.goto('/')
  await page.getByRole('textbox', { name: 'demo-owner' }).fill('demo-owner')
  await page.getByRole('textbox', { name: '••••••••' }).fill('demo1234')
  await page.getByRole('button', { name: 'Log in' }).click()
  await expect(page.getByText('안녕하세요, Demo Owner님')).toBeVisible({ timeout: 15_000 })
  await page.waitForLoadState('networkidle')
}

const label = (code: string) => `${code} · ${code.toLowerCase()}`

async function addLotTrackedItem(page: Page, code: string, unit: string) {
  await page.getByRole('textbox', { name: 'Code *' }).fill(code)
  await page.getByRole('textbox', { name: 'Name *' }).fill(code.toLowerCase())
  await page.getByRole('combobox', { name: /^Unit/ }).selectOption({ label: unit })
  await page.getByRole('checkbox', { name: 'Track stock per LOT' }).check()
  await page.getByRole('button', { name: 'Add' }).click()
  await expect(page.getByRole('row', { name: new RegExp(code) })).toContainText('tracked')
}

async function registerLot(page: Page, itemCode: string, lotNo: string) {
  // After a registration the panel shows that LOT; go back to the form.
  const backToForm = page.getByRole('button', { name: 'Register LOT' })
  if (await backToForm.count()) await backToForm.click()
  await page.getByLabel('Item *').selectOption({ label: label(itemCode) })
  await page.getByRole('textbox', { name: 'LOT number *' }).fill(lotNo)
  await page.getByRole('button', { name: 'Register', exact: true }).click()
  await expect(page.getByRole('row', { name: new RegExp(lotNo) })).toBeVisible()
}

async function addLotStock(page: Page, itemCode: string, lotNo: string, quantity: string) {
  await page.getByLabel('Item *').selectOption({ label: label(itemCode) })
  await page.getByLabel('LOT *').selectOption({ label: lotNo })
  await page.getByRole('spinbutton', { name: 'On hand *' }).fill(quantity)
  await page.getByLabel('Location', { exact: true }).fill(`WH-${lotNo}`)
  await page.getByRole('button', { name: 'Add' }).click()
  await expect(page.getByRole('row', { name: new RegExp(lotNo) })).toBeVisible()
}

async function recordRunItem(page: Page, direction: 'input' | 'output', itemCode: string, lotNo: string, qty: string) {
  await page.getByRole('radio', { name: direction }).check()
  await page.getByLabel('Item *').selectOption({ label: label(itemCode) })
  await page.getByRole('spinbutton', { name: 'Planned *' }).fill(qty)
  await page.getByRole('spinbutton', { name: 'Actual', exact: true }).fill(qty)
  const lotSelect = page.getByLabel('LOT *')
  const value = await lotSelect.locator('option', { hasText: `LOT ${lotNo}` }).getAttribute('value')
  await lotSelect.selectOption(value ?? '')
  await page.getByRole('button', { name: 'Record', exact: true }).click()
  await expect(page.getByRole('row', { name: new RegExp(`LOT ${lotNo}`) })).toBeVisible()
}

test('a run links its input and output LOTs, and the LOTs tab traces both ways', async ({ page }) => {
  // This complete journey creates two items/LOTs, records production, traces both ways, then reverses an input.
  // Leave time for the final stock/genealogy refresh as well as the setup; keep each assertion's normal timeout.
  test.setTimeout(60_000)
  await login(page)

  await page.goto(`/projects/${PROJECT}/inventory`)
  await expect(page.getByRole('heading', { name: 'Inventory' })).toBeVisible({ timeout: 15_000 })
  await addLotTrackedItem(page, RAW, 'kg · Kilogram (mass)')
  await addLotTrackedItem(page, DOUGH, 'kg · Kilogram (mass)')

  await page.getByRole('tab', { name: 'LOTs' }).click()
  await registerLot(page, RAW, RAW_LOT)
  await registerLot(page, DOUGH, DOUGH_LOT)

  await page.getByRole('tab', { name: 'Stock' }).click()
  await addLotStock(page, RAW, RAW_LOT, '40')
  await addLotStock(page, DOUGH, DOUGH_LOT, '0')

  // Run: 10 kg of the raw LOT in, 12 kg of the dough LOT out.
  await page.goto(`/projects/${PROJECT}/runs`)
  await page.getByRole('spinbutton', { name: 'Planned output qty *' }).fill('12')
  // Runs start from a published workflow revision.
  await page.getByRole('button', { name: 'Publish current workflow' }).click()
  await expect(page.getByRole('button', { name: 'Start', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: 'Start' }).click()
  await expect(page.getByRole('heading', { name: /RUN-/ })).toBeVisible()
  const runUrl = page.url()
  await recordRunItem(page, 'input', RAW, RAW_LOT, '10')
  await recordRunItem(page, 'output', DOUGH, DOUGH_LOT, '12')

  // Trace both ways.
  await page.goto(`/projects/${PROJECT}/inventory?tab=lots`)
  await page.getByRole('row', { name: new RegExp(DOUGH_LOT) }).click()
  await expect(page.getByRole('tab', { name: 'Made from' })).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByRole('listitem').filter({ hasText: RAW_LOT })).toContainText('used 10')

  await page.getByRole('row', { name: new RegExp(RAW_LOT) }).click()
  await page.getByRole('tab', { name: 'Used in' }).click()
  await expect(page.getByRole('listitem').filter({ hasText: DOUGH_LOT })).toBeVisible()
  // The raw LOT's stock went down by what the run consumed.
  await expect(page.getByRole('row', { name: new RegExp(RAW_LOT) })).toContainText('30')

  // Cancelling the input on the run gives the stock back and removes the genealogy link.
  page.on('dialog', (dialog) => void dialog.accept('Wrong LOT picked'))
  await page.goto(runUrl)
  const inputRow = page.getByRole('row', { name: new RegExp(`LOT ${RAW_LOT}`) })
  await inputRow.getByRole('button', { name: 'Cancel' }).click()
  await expect(inputRow).toContainText('cancelled')
  await expect(inputRow).toHaveAttribute('title', /^Cancelled by demo-owner on .+: Wrong LOT picked$/)

  await page.goto(`/projects/${PROJECT}/inventory?tab=lots`)
  await expect(page.getByRole('row', { name: new RegExp(RAW_LOT) })).toContainText('40')
  await page.getByRole('row', { name: new RegExp(DOUGH_LOT) }).click()
  await expect(page.getByText('No material LOTs recorded for this LOT.')).toBeVisible()
})
