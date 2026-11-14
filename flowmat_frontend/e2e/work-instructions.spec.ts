import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { uniqueSuffix } from './support/uniqueSuffix'

test.skip(!process.env.REAL_API_E2E, 'Needs the real backend and demo seed')

/**
 * Work instructions through the real UI (docs/domain/work-instruction.md): a product's instruction is written, stepped
 * and released on the Instructions tab, and a run of the product confirms the steps, recording a value where asked. The
 * product gets a fresh code; the run is finished at the end.
 */
const PROJECT = 'prj_demo_main'
const WORKFLOW = 'wf_demo_main'
const suffix = uniqueSuffix()
const CODE = `WI-E2E-${suffix}`

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

test('an instruction is released and a run confirms its steps', async ({ page, request }) => {
  const call = await api(request)
  const item = await call('POST', '/items', {
    projectId: PROJECT, itemCode: CODE, itemName: `rolls ${suffix}`, itemType: 'product', unitId: 'unit_ea',
  })
  let runId: string | null = null
  try {
    await login(page)
    await page.goto(`/projects/${PROJECT}/inventory?tab=instructions`)
    await page.getByRole('combobox', { name: 'Product', exact: true }).selectOption({ label: `${CODE} · rolls ${suffix} · none` }, { timeout: 15_000 })
    await page.getByRole('form', { name: 'Start instruction' }).getByLabel('Title').fill('Baking rolls')
    await page.getByRole('button', { name: 'Start instruction' }).click()
    const detail = page.getByRole('region', { name: 'Instruction' })
    await expect(detail.getByRole('status')).toHaveText('draft')

    const addStep = detail.getByRole('form', { name: 'Add step' })
    await addStep.getByLabel('Step').fill('Preheat the oven')
    await addStep.getByLabel('Records a value').check()
    await addStep.getByLabel('Value label').fill('Oven °C')
    await addStep.getByLabel('Min').fill('200')
    await addStep.getByLabel('Max').fill('230')
    await addStep.getByRole('button', { name: 'Add step' }).click()
    await expect(detail.getByRole('list', { name: 'Steps' })).toContainText('records Oven °C (200–230)')
    await addStep.getByLabel('Step').fill('Shape the rolls')
    await addStep.getByRole('button', { name: 'Add step' }).click()
    await expect(detail.getByRole('list', { name: 'Steps' }).getByRole('listitem')).toHaveCount(2)
    await addStep.getByLabel('Step').fill('Sweep up')
    await addStep.getByLabel('Required').uncheck()
    await addStep.getByRole('button', { name: 'Add step' }).click()
    await expect(detail.getByRole('list', { name: 'Steps' }).getByRole('listitem')).toHaveCount(3)
    await detail.getByRole('button', { name: 'Release' }).click()
    await expect(detail.getByRole('status')).toHaveText('released')

    runId = (await call('POST', '/production-runs/start', {
      projectId: PROJECT, workflowId: WORKFLOW, targetItemId: item.itemId, plannedOutputQty: 10,
    })).productionRunId
    await page.goto(`/projects/${PROJECT}/runs/${runId}`)
    const checklist = page.getByRole('region', { name: 'Work instruction' })
    await expect(checklist.getByRole('status')).toHaveText('0 of 2 required steps done', { timeout: 15_000 })
    await expect(page.getByRole('note').filter({ hasText: 'required instruction steps not confirmed' })).toBeVisible()

    const steps = checklist.getByRole('list', { name: 'Instruction steps' })
    // Outside the step's limits the value is recorded all the same, and marked (R7).
    await steps.getByLabel('Oven °C').fill('250')
    await steps.getByRole('listitem').filter({ hasText: 'Preheat the oven' }).getByRole('button', { name: 'Done' }).click()
    const preheat = steps.getByRole('listitem').filter({ hasText: 'Preheat the oven' })
    await expect(preheat).toContainText('Oven °C 250 (outside 200–230) ')
    await expect(preheat).toContainText('· demo-owner')
    // The value outside its limits offers a nonconformity about the run (R8); once raised, its number shows instead.
    await preheat.getByRole('button', { name: 'Raise NCR' }).click()
    await expect(preheat).toContainText(/\(outside 200–230\) NCR-\d+/)
    await expect(preheat.getByRole('button', { name: 'Raise NCR' })).toHaveCount(0)
    await steps.getByRole('listitem').filter({ hasText: 'Shape the rolls' }).getByRole('button', { name: 'Done' }).click()
    await expect(checklist.getByRole('status')).toHaveText('All 2 required steps done')
    await expect(page.getByRole('note').filter({ hasText: 'required instruction step' })).toHaveCount(0)

    // Undoing keeps the confirmation as history, and the step can be done again.
    await steps.getByRole('listitem').filter({ hasText: 'Shape the rolls' }).getByRole('button', { name: 'Undo' }).click()
    await expect(checklist.getByRole('status')).toHaveText('1 of 2 required steps done')
    await steps.getByRole('listitem').filter({ hasText: 'Shape the rolls' }).getByRole('button', { name: 'Done' }).click()
    await expect(checklist.getByRole('status')).toHaveText('All 2 required steps done')
    await checklist.getByText('Undone confirmations (1)').click()
    await expect(checklist.getByRole('list', { name: 'Undone confirmations' }))
      .toContainText(/^Step 2 · confirmed by demo-owner .+ · undone by demo-owner /)
  } finally {
    if (runId) {
      await call('POST', `/production-runs/${runId}/finish`, {})
      const ncrs: { nonconformityId: string; productionRunId: string | null; status: string }[] = await call('GET', `/nonconformities?projectId=${PROJECT}`)
      for (const ncr of ncrs.filter((one) => one.productionRunId === runId && one.status === 'open')) {
        await call('POST', `/nonconformities/${ncr.nonconformityId}/cancel`, { note: 'e2e clean-up' })
      }
    }
  }
})
