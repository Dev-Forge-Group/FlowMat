import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { uniqueSuffix } from './support/uniqueSuffix'

test.skip(!process.env.REAL_API_E2E, 'Needs the real backend and demo seed')

/**
 * A LOT's receipt checklist (docs/domain/inspection-standard.md): the receipt checks of its item show on the LOT, and an
 * inspection that follows one of them marks it passed. The standards are deleted at the end.
 */
const PROJECT = 'prj_demo_main'
const suffix = uniqueSuffix()
const ITEM = `RCV-${suffix}`
const LOT = `L-RCV-${suffix}`

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

test('a LOT shows the receipt checks of its item and which are done', async ({ page, request }) => {
  const call = await api(request)
  const item = await call('POST', '/items', {
    projectId: PROJECT, itemCode: ITEM, itemName: `receipt ${suffix}`, itemType: 'material', unitId: 'unit_kg', lotManageYn: 'Y',
  })
  const lot = await call('POST', '/lots', { projectId: PROJECT, itemId: item.itemId, lotNo: LOT })
  const standard = (inspectionType: string, extra: object) => call('POST', '/inspection-standards', {
    projectId: PROJECT, itemId: item.itemId, inspectionType, stage: 'receipt', required: true, ...extra,
  })
  const standards = [
    await standard('Seal', {}),
    await standard('Temperature', { standardMin: 0, standardMax: 5, unit: '°C' }),
    await standard('Moisture', { stage: 'production' }),
  ]
  try {
    await login(page)
    await page.goto(`/projects/${PROJECT}/inventory?tab=lots`)
    await page.getByRole('row', { name: new RegExp(LOT) }).click()
    const checklist = page.getByRole('region', { name: 'Receipt checklist' })
    await expect(checklist.getByRole('status')).toHaveText('0 of 2 required checks passed · 2 missing', { timeout: 15_000 })
    // The production check is not a receipt check.
    await expect(checklist.getByRole('row', { name: /Moisture/ })).toHaveCount(0)

    // An inspection of the LOT that follows the Temperature standard: 3 °C is inside 0–5 °C.
    await call('POST', '/quality-inspections', {
      projectId: PROJECT, itemId: item.itemId, lotId: lot.lotId, inspectionType: 'Temperature', measuredValue: 3,
      standardId: standards[1].standardId,
    })
    await page.reload()
    await page.getByRole('row', { name: new RegExp(LOT) }).click()
    await expect(checklist.getByRole('status')).toHaveText('1 of 2 required checks passed · 1 missing')
    await expect(checklist.getByRole('row', { name: /Temperature/ })).toContainText('✓ Pass (3 °C)')
    await expect(checklist.getByRole('row', { name: /Seal/ })).toContainText('Not checked')

    // The LOT list finds the LOTs whose receipt checks are not done.
    const filters = page.getByRole('search', { name: 'Filter LOTs' })
    const row = page.getByRole('table', { name: 'LOTs' }).getByRole('row', { name: new RegExp(LOT) })
    await filters.getByLabel('Search LOTs').fill(LOT)
    await filters.getByLabel('Receipt checks').selectOption('missing')
    await expect(row).toContainText('1 required check missing')
    await filters.getByLabel('Receipt checks').selectOption('failed')
    await expect(row).toHaveCount(0)
    // A failed Seal check: nothing missing any more, one failed.
    await call('POST', '/quality-inspections', {
      projectId: PROJECT, itemId: item.itemId, lotId: lot.lotId, inspectionType: 'Seal', result: 'fail', standardId: standards[0].standardId,
    })
    await page.reload()
    await filters.getByLabel('Search LOTs').fill(LOT)
    await filters.getByLabel('Receipt checks').selectOption('failed')
    await expect(row).toContainText('1 failed')
    await filters.getByLabel('Receipt checks').selectOption('missing')
    await expect(row).toHaveCount(0)

    // Receiving stock of the LOT shows its receipt checks right there, and one can be recorded on the spot.
    await page.goto(`/projects/${PROJECT}/inventory?tab=stock`)
    await page.getByRole('combobox', { name: /^Item \*/ }).selectOption(item.itemId)
    await page.getByRole('combobox', { name: /^LOT \*/ }).selectOption(lot.lotId)
    await page.getByLabel('On hand *').fill('5')
    await page.getByRole('button', { name: 'Add', exact: true }).click()
    const received = page.getByRole('region', { name: `Receipt checks for LOT ${LOT}` })
    await expect(received.getByRole('status')).toHaveText('1 of 2 required checks passed · 1 failed')
    await received.getByRole('button', { name: 'Record inspection' }).click()
    const record = received.getByRole('form', { name: 'Record inspection' })
    await record.getByRole('combobox', { name: /^Standard/ }).selectOption({ label: 'Seal · pass/fail (receipt)' })
    await record.getByRole('combobox', { name: /^Result/ }).selectOption('pass')
    await record.getByRole('button', { name: 'Save inspection' }).click()
    await expect(received.getByRole('status')).toHaveText('2 of 2 required checks passed')
    await received.getByRole('button', { name: 'Done', exact: true }).click()
    await expect(received).toHaveCount(0)
  } finally {
    for (const one of standards) await call('DELETE', `/inspection-standards/${one.standardId}`)
  }
})
