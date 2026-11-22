import { expect, test } from '@playwright/test'

test.skip(!process.env.REAL_API_E2E, 'Needs the real backend and demo seed')

/**
 * The editor's "Try it" against the real server (ADR-040 decision 7, month plan 11-23 week 3): the same published flow
 * and input, once as a graph run on the server with every reported step passing on what it received, once in the
 * browser. Each step must have received the same amount and ended the same way.
 */
test('trying a flow in the editor gives what the server run of the same revision gives', async ({ page }) => {
  test.setTimeout(60_000)
  page.setDefaultTimeout(10_000)
  await page.goto('/')
  await page.locator('input').nth(0).fill('demo-owner')
  await page.locator('input').nth(1).fill('demo1234')
  const loginResponse = page.waitForResponse((response) =>
    response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/auth/login')
  await page.getByRole('button', { name: 'Log in' }).click()
  const login = await loginResponse
  expect(login.ok()).toBe(true)
  const headers = { Authorization: `Bearer ${(await login.json()).data.accessToken as string}` }
  await page.waitForLoadState('networkidle')

  async function call(method: 'get' | 'post', path: string, data?: Record<string, unknown>): Promise<any> {
    const response = await page.request[method](`/api${path}`, { headers, data })
    expect(response.ok(), `${method} ${path}: ${response.status()} ${await response.text()}`).toBe(true)
    return (await response.json()).data
  }

  const workflow = await call('post', '/workflows', { projectId: 'prj_demo_main', workflowName: `Try E2E ${Date.now()}` })
  const workflowId = workflow.workflowId as string
  try {
    const process = async (processName: string, nodeType: string, posX: number, posY: number) =>
      (await call('post', '/processes', { workflowId, processName, nodeType, posX, posY })).processId as string
    const receive = await process('Receive', 'input', 80, 200)
    const divide = await process('Divide', 'split', 360, 200)
    const lineA = await process('Line A', 'output', 660, 80)
    const lineB = await process('Line B', 'output', 660, 320)
    const names: Record<string, string> = { [receive]: 'Receive', [divide]: 'Divide', [lineA]: 'Line A', [lineB]: 'Line B' }
    await call('post', '/process-connections', { workflowId, fromProcessId: receive, toProcessId: divide })
    await call('post', '/process-connections', { workflowId, fromProcessId: divide, toProcessId: lineA,
      splitKind: 'share', splitValue: 60, lossRate: 0.1 })
    await call('post', '/process-connections', { workflowId, fromProcessId: divide, toProcessId: lineB, splitKind: 'rest' })
    const revision = await call('post', `/workflows/${workflowId}/revisions`)

    // The server's run: each step a person reports is completed with what it received.
    const run = await call('post', '/flow-runs/graph', { workflowId, workflowRevisionId: revision.workflowRevisionId,
      runType: 'simulation', inputPayload: { quantity: 10, unit: 'kg' } })
    for (;;) {
      const steps = await call('get', `/flow-runs/${run.flowRunId}/steps`)
      const next = steps.find((step: { status: string }) => step.status === 'planned')
      if (!next) break
      await call('post', `/flow-runs/${run.flowRunId}/steps/${next.stepId}/start`)
      await call('post', `/flow-runs/${run.flowRunId}/steps/${next.stepId}/complete`, { outputSnapshot: next.inputSnapshot })
    }
    const amount = (snapshot: { quantity?: number; unit?: string }) =>
      typeof snapshot.quantity === 'number' ? `${Number(snapshot.quantity.toFixed(4))}${snapshot.unit ? ` ${snapshot.unit}` : ''}` : 'nothing measured'
    const server = (await call('get', `/flow-runs/${run.flowRunId}/steps`)).map((step: any) =>
      `${names[step.nodeId]}: ${amount(step.inputSnapshot)} (${step.status})`)
    expect(server).toEqual(['Receive: 10 kg (completed)', 'Divide: 10 kg (completed)', 'Line A: 5.4 kg (completed)',
      'Line B: 4 kg (completed)'])

    // The editor's try of the same flow and input.
    await page.goto(`/projects/prj_demo_main/workflows/${workflowId}`)
    await expect(page.locator('.react-flow__node')).toHaveCount(4)
    await page.getByRole('button', { name: 'Try it' }).click()
    const tryIt = page.getByRole('region', { name: 'Try the flow' })
    await tryIt.getByLabel('Quantity').fill('10')
    await tryIt.getByLabel('Unit').fill('kg')
    await tryIt.getByRole('button', { name: 'Run through' }).click()
    await expect(tryIt.getByText('Reaches the end in 4 steps, losing 0.6 kg on the way')).toBeVisible()
    await expect(tryIt.getByRole('listitem')).toHaveText(server)
  } finally {
    await page.request.delete(`/api/workflows/${workflowId}`, { headers })
  }
})

/**
 * "Try it" with a work order against the real server (ADR-041): a press that works one hour a day, three hours from now,
 * and a flow whose second step comes a hundredth of a second after the first. The server's production run of the work
 * order holds that step to the press's next hour; the editor's try with the same work order says the same time and names
 * the press.
 */
test('trying a flow with a work order waits for the equipment as the production run does', async ({ page }) => {
  test.setTimeout(90_000)
  page.setDefaultTimeout(10_000)
  await page.goto('/')
  await page.locator('input').nth(0).fill('demo-owner')
  await page.locator('input').nth(1).fill('demo1234')
  const loginResponse = page.waitForResponse((response) =>
    response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/auth/login')
  await page.getByRole('button', { name: 'Log in' }).click()
  const login = await loginResponse
  expect(login.ok()).toBe(true)
  const headers = { Authorization: `Bearer ${(await login.json()).data.accessToken as string}` }
  await page.waitForLoadState('networkidle')

  async function call(method: 'get' | 'post' | 'put', path: string, data?: Record<string, unknown>): Promise<any> {
    const response = await page.request[method](`/api${path}`, { headers, data })
    expect(response.ok(), `${method} ${path}: ${response.status()} ${await response.text()}`).toBe(true)
    return (await response.json()).data
  }

  const project = 'prj_demo_main'
  const zone = (await call('get', `/projects/${project}/time-zone`)).timeZone as string
  const hourNow = Number(new Intl.DateTimeFormat('en-GB', { timeZone: zone, hour: '2-digit', hourCycle: 'h23' }).format(new Date()))
  const hour = (value: number) => `${String(value % 24).padStart(2, '0')}:00`
  const code = `TRYT-${Date.now().toString(36).toUpperCase()}`
  const press = (await call('post', '/equipments', { projectId: project, equipmentCode: code, equipmentName: code.toLowerCase(),
    equipmentType: 'machine' })).equipmentId as string
  await call('put', `/equipments/${press}/calendar`, { shiftStart: hour(hourNow + 3), shiftEnd: hour(hourNow + 4),
    workDays: [1, 2, 3, 4, 5, 6, 7] })

  const workflow = await call('post', '/workflows', { projectId: project, workflowName: `Try time E2E ${Date.now()}` })
  const workflowId = workflow.workflowId as string
  try {
    const mix = (await call('post', '/processes', { workflowId, processName: 'Mix', nodeType: 'process', posX: 80, posY: 200 })).processId
    const bake = (await call('post', '/processes', { workflowId, processName: 'Bake', nodeType: 'process', posX: 420, posY: 200 })).processId
    await call('post', '/process-connections', { workflowId, fromProcessId: mix, toProcessId: bake, delayTimeSec: 0.01 })
    const revision = await call('post', `/workflows/${workflowId}/revisions`)
    const order = await call('post', '/work-orders', { projectId: project, workflowId, workOrderTitle: `Try time ${Date.now()}`,
      targetQuantity: 10, plannedStartAt: '2030-01-07T09:00:00+09:00', plannedEndAt: '2030-01-07T17:00:00+09:00' })
    await call('put', `/work-orders/${order.workOrderId}/equipment`, { equipmentId: press })
    await call('post', `/work-orders/${order.workOrderId}/approve`)

    // The server's production run: Mix ends now, and Bake is held to the press's next hour.
    const run = await call('post', '/production-runs/start', { projectId: project, workflowId,
      workflowRevisionId: revision.workflowRevisionId, workOrderId: order.workOrderId, plannedOutputQty: 10, runType: 'simulation' })
    const stepOf = async (nodeId: string) =>
      (await call('get', `/production-runs/${run.productionRunId}/steps`)).find((step: { nodeId: string }) => step.nodeId === nodeId)
    const mixing = await stepOf(mix)
    await call('post', `/production-runs/${run.productionRunId}/steps/${mixing.stepId}/start`)
    await call('post', `/production-runs/${run.productionRunId}/steps/${mixing.stepId}/complete`, {})
    const baking = await stepOf(bake)
    expect(baking.held?.equipmentCode).toBe(code)
    // The browser shows times in its own offset, to the minute.
    const shown = await page.evaluate((iso: string) => {
      const at = new Date(iso)
      const pad = (value: number) => String(value).padStart(2, '0')
      return `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())} ${pad(at.getHours())}:${pad(at.getMinutes())}`
    }, baking.scheduledAt as string)

    // The editor's try with the same work order, starting now.
    await page.goto(`/projects/${project}/workflows/${workflowId}`)
    await expect(page.locator('.react-flow__node')).toHaveCount(2)
    await page.getByRole('button', { name: 'Try it' }).click()
    const tryIt = page.getByRole('region', { name: 'Try the flow' })
    await tryIt.getByLabel('Work order').selectOption(order.workOrderId)
    await tryIt.getByRole('button', { name: 'Run through' }).click()
    await expect(tryIt.getByText('Reaches the end in 2 steps')).toBeVisible()
    await expect(tryIt.getByRole('listitem').nth(1)).toContainText(`from ${shown} (waits for ${code})`)
  } finally {
    await page.request.delete(`/api/workflows/${workflowId}`, { headers })
  }
})
