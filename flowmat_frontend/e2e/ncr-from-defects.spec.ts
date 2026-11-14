import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { uniqueSuffix } from './support/uniqueSuffix'

test.skip(!process.env.REAL_API_E2E, 'Needs the real backend and demo seed')

/**
 * A nonconformity raised straight from a LOT's defect list (docs/domain/nonconformity.md): two open defects are ticked,
 * the NCR takes them, and the list then shows its number on both. The NCR is cancelled at the end, which lets them go.
 */
const PROJECT = 'prj_demo_main'
const suffix = uniqueSuffix()
const ITEM = `NCRL-${suffix}`
const LOT = `L-NCR-${suffix}`

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

test('open defects of a LOT are gathered into a new nonconformity from its defect list', async ({ page, request }) => {
  const call = await api(request)
  const item = await call('POST', '/items', {
    projectId: PROJECT, itemCode: ITEM, itemName: `ncr list ${suffix}`, itemType: 'product', unitId: 'unit_ea', lotManageYn: 'Y',
  })
  const lot = await call('POST', '/lots', { projectId: PROJECT, itemId: item.itemId, lotNo: LOT })
  for (const defectType of [`Crack ${suffix}`, `Dent ${suffix}`]) {
    await call('POST', '/defects', { projectId: PROJECT, itemId: item.itemId, lotId: lot.lotId, quantity: 1, defectType, severity: 'major' })
  }
  let nonconformityId: string | null = null
  try {
    await login(page)
    await page.goto(`/projects/${PROJECT}/inventory?tab=lots`)
    await page.getByRole('row', { name: new RegExp(LOT) }).click()
    const quality = page.getByRole('region', { name: 'LOT quality' })
    const defects = quality.getByRole('list', { name: 'Defects' })
    await defects.getByRole('listitem', { name: `Defect Crack ${suffix}` }).getByRole('checkbox', { name: 'For a new NCR' }).check()
    await defects.getByRole('listitem', { name: `Defect Dent ${suffix}` }).getByRole('checkbox', { name: 'For a new NCR' }).check()

    const raise = quality.getByRole('form', { name: 'Raise NCR from defects' })
    // The list is newest first, so the title starts from the defect logged last.
    await expect(raise.getByLabel('NCR title *')).toHaveValue(`Dent ${suffix} and 1 more on ${ITEM}`)
    await raise.getByRole('button', { name: 'Raise NCR from 2 defects' }).click()
    await expect(quality.getByRole('status')).toHaveText(/^Raised NCR-\d{4} from 2 defects\.$/)
    const ncrNo = (await quality.getByRole('status').textContent())!.match(/NCR-\d{4}/)![0]
    await expect(defects.getByRole('listitem', { name: `Defect Crack ${suffix}` }).getByLabel('Nonconformity')).toHaveText(ncrNo)
    await expect(defects.getByRole('listitem', { name: `Defect Dent ${suffix}` }).getByLabel('Nonconformity')).toHaveText(ncrNo)
    await expect(defects.getByRole('checkbox', { name: 'For a new NCR' })).toHaveCount(0)

    // A defect logged later is added to the same NCR from the list.
    await call('POST', '/defects', {
      projectId: PROJECT, itemId: item.itemId, lotId: lot.lotId, quantity: 1, defectType: `Chip ${suffix}`, severity: 'minor',
    })
    await page.reload()
    await page.getByRole('row', { name: new RegExp(LOT) }).click()
    await defects.getByRole('listitem', { name: `Defect Chip ${suffix}` }).getByRole('checkbox', { name: 'For a new NCR' }).check()
    await raise.getByLabel('Add to').selectOption({ label: `${ncrNo} · Dent ${suffix} and 1 more on ${ITEM}` })
    await expect(raise.getByLabel('NCR title *')).toHaveCount(0)
    await raise.getByRole('button', { name: `Add 1 defect to ${ncrNo}` }).click()
    await expect(quality.getByRole('status')).toHaveText(`Added 1 defect to ${ncrNo}.`)
    await expect(defects.getByRole('listitem', { name: `Defect Chip ${suffix}` }).getByLabel('Nonconformity')).toHaveText(ncrNo)

    const list = await call('GET', `/nonconformities?projectId=${PROJECT}&status=open`)
    nonconformityId = list.find((one: { ncrNo: string }) => one.ncrNo === ncrNo)?.nonconformityId ?? null
    expect(nonconformityId).not.toBeNull()
  } finally {
    if (nonconformityId) await call('POST', `/nonconformities/${nonconformityId}/cancel`, { note: 'E2E clean-up' })
  }
})
