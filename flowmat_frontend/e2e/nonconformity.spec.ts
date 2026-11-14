import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { uniqueSuffix } from './support/uniqueSuffix'

test.skip(!process.env.REAL_API_E2E, 'Needs the real backend and demo seed')

/**
 * A nonconformity through the real UI (docs/domain/nonconformity.md): raised from an open defect, closed only once its
 * root cause, disposition and actions are done. Codes get a fresh suffix; a closed nonconformity stays as a record.
 */
const PROJECT = 'prj_demo_main'
const suffix = uniqueSuffix()
const ITEM = `NCR-E2E-${suffix}`
const DEFECT = `Crack ${suffix}`
const TITLE = `Cracked housings ${suffix}`

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

test('a nonconformity is raised from a defect and closed once its cause, disposition and actions are done', async ({ page, request }) => {
  const call = await api(request)
  const item = await call('POST', '/items', {
    projectId: PROJECT, itemCode: ITEM, itemName: `ncr e2e ${suffix}`, itemType: 'material', unitId: 'unit_kg',
  })
  await call('POST', '/defects', { projectId: PROJECT, itemId: item.itemId, quantity: 2, defectType: DEFECT, severity: 'major' })

  await login(page)
  await page.goto(`/projects/${PROJECT}/inventory?tab=quality`)
  const section = page.getByRole('region', { name: 'Nonconformities' })
  await section.getByRole('button', { name: 'Raise nonconformity' }).click({ timeout: 15_000 })
  const raise = section.getByRole('form', { name: 'Raise nonconformity' })
  await raise.getByLabel('Title *').fill(TITLE)
  await raise.getByRole('checkbox', { name: new RegExp(DEFECT) }).check()
  await raise.getByRole('button', { name: 'Raise' }).click()

  const detail = section.getByRole('article', { name: /Nonconformity NCR-\d{4}/ })
  await expect(detail.getByRole('heading')).toContainText(TITLE)
  await expect(detail).toContainText('major')
  await expect(detail.getByRole('list', { name: 'Gathered defects' })).toContainText(DEFECT)
  await expect(detail.getByRole('list', { name: 'Before closing' })).toContainText('Record the root cause.')
  await expect(detail.getByRole('button', { name: 'Close nonconformity' })).toBeDisabled()

  const add = detail.getByRole('form', { name: 'Add action' })
  await add.getByLabel('Action').fill('Replace the worn mould')
  await add.getByRole('button', { name: 'Add action' }).click()
  const actions = detail.getByRole('table', { name: 'Corrective actions' })
  await actions.getByRole('row', { name: /Replace the worn mould/ }).getByRole('button', { name: 'Done' }).click()
  await detail.getByRole('form', { name: 'Finish action' }).getByRole('textbox').fill('New mould fitted')
  await detail.getByRole('button', { name: 'Mark done' }).click()
  await expect(actions.getByRole('row', { name: /Replace the worn mould/ })).toContainText('done')

  await detail.getByLabel('Root cause').fill('Worn mould')
  await detail.getByLabel('Disposition').selectOption('rework')
  await detail.getByRole('button', { name: 'Save cause and disposition' }).click()
  await expect(detail.getByRole('list', { name: 'Before closing' })).toHaveCount(0)
  await detail.getByLabel('Closing note').fill('Verified on the next run')
  await detail.getByRole('button', { name: 'Close nonconformity' }).click()

  // Closed ones leave the open list; the closed filter shows it.
  await expect(section.getByRole('table', { name: 'Nonconformity list' }).getByRole('row', { name: new RegExp(TITLE) })).toHaveCount(0)
  await section.getByLabel('Nonconformity status').selectOption('closed')
  const row = section.getByRole('table', { name: 'Nonconformity list' }).getByRole('row', { name: new RegExp(TITLE) })
  // Closing suggests checking the actions a month later (N13).
  await expect(row).toContainText(/closed · check by \d{4}-\d{2}-\d{2}/)

  // Whether the actions worked is checked once on the closed nonconformity (N12); a "no" offers a follow-up.
  const check = detail.getByRole('form', { name: 'Check the actions' })
  await check.getByLabel('No', { exact: true }).check()
  await check.getByRole('button', { name: 'Record check' }).click()
  await expect(check.getByRole('alert')).toHaveText('Say what still goes wrong.')
  await check.getByLabel('What was checked').fill('Cracks again on the next lot')
  await check.getByRole('button', { name: 'Record check' }).click()
  const effectiveness = detail.getByRole('region', { name: 'Effectiveness' })
  await expect(effectiveness).toContainText('The actions did not work')
  await expect(effectiveness).toContainText('Cracks again on the next lot')
  await expect(row).toContainText('not effective')
  await effectiveness.getByRole('button', { name: 'Raise a follow-up' }).click()
  const followUp = section.getByRole('form', { name: 'Raise nonconformity' })
  await expect(followUp.getByLabel('Title *')).toHaveValue(new RegExp(`^Follow-up to NCR-\\d{4}: ${TITLE}$`))
  await expect(followUp).toContainText(`about ${ITEM}`)
  await followUp.getByRole('button', { name: 'Cancel' }).click()
  await expect(followUp).toHaveCount(0)
})
