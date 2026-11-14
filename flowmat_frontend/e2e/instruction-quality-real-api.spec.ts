import { expect, test, type APIRequestContext } from '@playwright/test'
import { uniqueSuffix } from './support/uniqueSuffix'

test.skip(!process.env.REAL_API_E2E, 'Needs a disposable backend with the demo seed')

async function authenticatedApi(request: APIRequestContext) {
  const login = await request.post('/api/auth/login', {
    data: { userIdOrEmail: 'demo-owner', password: 'demo1234' },
  })
  expect(login.ok()).toBeTruthy()
  const headers = { Authorization: `Bearer ${(await login.json()).data.accessToken}` }
  const api = async (method: string, path: string, data?: unknown) => {
    const response = await request.fetch(`/api${path}`, { method, headers, data })
    const body = await response.json()
    expect(response.ok(), `${method} ${path}: ${body.message}`).toBeTruthy()
    return body.data
  }
  return { headers, api }
}

test('instruction confirmation records one inspection and shows it in the real run UI', async ({ page, request }) => {
  const projectId = 'prj_demo_main'
  const suffix = `${uniqueSuffix()}-${test.info().workerIndex}`
  const { headers, api } = await authenticatedApi(request)

  const item = await api('POST', '/items', {
    projectId, itemCode: `WI-Q-${suffix}`, itemName: `Instruction product ${suffix}`, itemType: 'product', unitId: 'unit_ea',
  })
  const standard = await api('POST', '/inspection-standards', {
    projectId, itemId: item.itemId, inspectionType: 'Moisture', stage: 'production',
    standardMin: 10, standardMax: 12, unit: '%', required: true,
  })
  const instruction = await api('POST', '/work-instructions', {
    projectId, itemId: item.itemId, title: `Instruction ${suffix}`,
  })
  const withStep = await api('POST', `/work-instructions/${instruction.instructionId}/steps`, {
    text: 'Check moisture', required: true, recordsValue: true, standardId: standard.standardId,
  })
  const stepId = withStep.steps[0].stepId
  const standardUpdate = {
    inspectionType: 'Moisture', stage: 'production', standardMin: 10, standardMax: 12, unit: '%', required: true,
  }
  await api('PUT', `/inspection-standards/${standard.standardId}`, { ...standardUpdate, active: false })
  const rejected = await request.post(`/api/work-instructions/${instruction.instructionId}/release`, { headers })
  expect(rejected.status()).toBe(409)
  expect((await rejected.json()).message).toContain('no longer an active production standard')
  await api('PUT', `/inspection-standards/${standard.standardId}`, { ...standardUpdate, active: true })
  await api('POST', `/work-instructions/${instruction.instructionId}/release`)

  const run = await api('POST', '/production-runs/start', {
    projectId, workflowId: 'wf_demo_main', targetItemId: item.itemId, plannedOutputQty: 1, runType: 'simulation',
  })
  const path = `/production-runs/${run.productionRunId}/instruction/steps/${stepId}/check`
  const confirmations = await Promise.all([
    request.post(`/api${path}`, { headers, data: { value: '11' } }),
    request.post(`/api${path}`, { headers, data: { value: '11' } }),
  ])
  expect(confirmations.map((response) => response.status()).sort()).toEqual([200, 409])
  const checklist = await api('GET', `/production-runs/${run.productionRunId}/quality-checklist`)
  const inspection = checklist.lines.find((line: { standardId: string }) => line.standardId === standard.standardId)
  expect(inspection).toMatchObject({ status: 'pass' })
  const records = await api('GET', `/quality-inspections?projectId=${projectId}&productionRunId=${run.productionRunId}`)
  expect(records).toHaveLength(1)
  expect(records[0]).toMatchObject({ inspectionId: inspection.inspectionId, measuredValue: 11 })

  await page.goto('/')
  await page.getByRole('textbox', { name: 'demo-owner' }).fill('demo-owner')
  await page.getByRole('textbox', { name: '••••••••' }).fill('demo1234')
  await page.getByRole('button', { name: 'Log in' }).click()
  await expect(page.getByRole('button', { name: 'Logout' })).toBeVisible()
  await page.goto(`/projects/${projectId}/runs/${run.productionRunId}`)
  const step = page.getByRole('region', { name: 'Work instruction', exact: true })
    .getByRole('listitem').filter({ hasText: 'Check moisture' })
  await expect(step).toContainText('Moisture % 11')
  await expect(step).toContainText('inspection pass')
})

test('LOT release history groups moved rows and empty work order estimates stay read only', async ({ request }) => {
  const projectId = 'prj_demo_main'
  const suffix = uniqueSuffix()
  const { headers, api } = await authenticatedApi(request)
  const item = await api('POST', '/items', {
    projectId, itemCode: `REL-H-${suffix}`, itemName: `Release history ${suffix}`, unitId: 'unit_kg',
    lotManageYn: 'Y', lotReleaseRequiredYn: 'Y',
  })
  const lot = await api('POST', '/lots', { projectId, itemId: item.itemId, lotNo: `REL-H-${suffix}` })
  expect(lot.lotStatus).toBe('inspection_pending')
  for (const location of ['A', 'B']) {
    await api('POST', '/inventories', {
      projectId, itemId: item.itemId, lotId: lot.lotId, location: `${location}-${suffix}`, quantity: 2,
    })
  }
  expect(await api('GET', `/lots/${lot.lotId}/release-history`)).toMatchObject({ events: [] })
  await api('POST', `/lots/${lot.lotId}/release`)
  const history = await api('GET', `/lots/${lot.lotId}/release-history`)
  expect(history.events).toHaveLength(1)
  expect(history.events[0]).toMatchObject({ action: 'released', by: 'demo-owner', stockRows: 2 })

  const order = await api('POST', '/work-orders', {
    projectId, workOrderTitle: `Read models ${suffix}`, workflowId: 'wf_demo_main',
  })
  const waste = await api('GET', `/work-orders/${order.workOrderId}/waste-disposal-estimate`)
  expect(waste).toMatchObject({ workOrderId: order.workOrderId, bomId: null, lines: [] })
  expect(waste.problem).toBeTruthy()
  const setup = await api('GET', `/work-orders/${order.workOrderId}/setup-comparison`)
  expect(setup).toMatchObject({
    workOrderId: order.workOrderId, plannedMinutes: null, plannedCost: null,
    runs: 0, actualMinutes: 0, actualCost: 0, actualCostComplete: true,
  })
  expect(await api('GET', `/work-orders/${order.workOrderId}`)).toMatchObject({ workOrderStatus: 'draft' })

  // Date-aware BOM queries can be verified without creating any BOM.
  const on = '2030-02-01'
  expect(await api('GET', `/boms/cost-rollup?projectId=${projectId}&on=${on}`)).toMatchObject({ asOf: on })
  expect(await api('GET', `/boms/where-used/all-levels?projectId=${projectId}&itemId=${item.itemId}&on=${on}`))
    .toMatchObject({ asOf: on })
  const invalidDate = await request.get(`/api/boms/cost-rollup?projectId=${projectId}&on=2030-02-30`, { headers })
  expect(invalidDate.status()).toBe(400)
  expect((await invalidDate.json()).message).toContain('on')
})


test('a closed NCR can move or clear its check date until its effectiveness is recorded', async ({ request }) => {
  const { headers, api } = await authenticatedApi(request)
  const ncr = await api('POST', '/nonconformities', {
    projectId: 'prj_demo_main', title: `Verification date ${uniqueSuffix()}`, severity: 'minor',
  })
  const path = `/nonconformities/${ncr.nonconformityId}`
  await api('PUT', path, { rootCause: 'Worn tooling', disposition: 'rework' })
  const withAction = await api('POST', `${path}/actions`, { actionType: 'corrective', description: 'Replace tooling' })
  await api('POST', `${path}/actions/${withAction.actions[0].correctiveActionId}/complete`, { note: 'Tool replaced' })
  const due = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10)
  expect(await api('POST', `${path}/close`, { verificationDueDate: due }))
    .toMatchObject({ status: 'closed', verificationDueDate: due })
  expect(await api('PUT', `${path}/verification-due`, { verificationDueDate: null }))
    .toMatchObject({ verificationDueDate: null })
  expect(await api('PUT', `${path}/verification-due`, { verificationDueDate: due }))
    .toMatchObject({ verificationDueDate: due })
  await api('POST', `${path}/verify`, { result: 'effective', note: 'Next batch checked' })
  const locked = await request.put(`/api${path}/verification-due`, { headers, data: { verificationDueDate: null } })
  expect(locked.status()).toBe(409)
  expect(await api('GET', path)).toMatchObject({ verificationDueDate: due, verificationResult: 'effective' })
})
