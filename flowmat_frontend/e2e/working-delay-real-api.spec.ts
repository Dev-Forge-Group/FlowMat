import { expect, test } from '@playwright/test'

test.skip(process.env.REAL_API_E2E !== '1' || process.env.FLOWMAT_WORKING_DELAY_E2E_DISPOSABLE !== '1',
  'Requires an explicitly disposable backend for working-delay acceptance')

test.use({ viewport: { width: 1600, height: 1000 }, trace: 'off' })

test('real editor publishes a working delay and production steps wait for the equipment shift', async ({ page, request }) => {
  test.setTimeout(90_000)
  await page.goto('/')
  await page.getByRole('textbox', { name: 'demo-owner', exact: true }).fill('demo-owner')
  await page.locator('input[type="password"]').fill('demo1234')
  const loginReply = page.waitForResponse((reply) => reply.request().method() === 'POST'
    && new URL(reply.url()).pathname === '/api/auth/login')
  await page.getByRole('button', { name: 'Log in', exact: true }).click()
  const login = await loginReply
  expect(login.ok()).toBe(true)
  const headers = { Authorization: `Bearer ${(await login.json()).data.accessToken}` }
  await expect(page.getByRole('button', { name: 'Logout', exact: true })).toBeVisible()
  const api = async (method: string, path: string, data?: unknown) => {
    const reply = await request.fetch(`/api${path}`, { method, headers, data, timeout: 10_000 })
    const envelope = await reply.json()
    expect(reply.ok(), `${method} ${path}: ${envelope.message}`).toBe(true)
    return envelope.data
  }
  const projectId = (await api('POST', '/projects', {
    projectName: `Working delay acceptance ${Date.now()}`, ownerId: 'demo-owner',
  })).projectId as string
  await api('PUT', `/projects/${projectId}/time-zone`, { timeZone: 'UTC', expectedVersion: 0 })
  const workflowId = (await api('POST', '/workflows', { projectId, workflowName: 'Working delay' })).workflowId as string
  const from = (await api('POST', '/processes', { workflowId, processName: 'Mix', nodeType: 'process',
    posX: 80, posY: 160, width: 150, height: 72 })).processId as string
  const to = (await api('POST', '/processes', { workflowId, processName: 'Bake', nodeType: 'process',
    posX: 480, posY: 160, width: 150, height: 72 })).processId as string
  const connectionId = (await api('POST', '/process-connections', { workflowId, fromProcessId: from, toProcessId: to,
    sourceHandle: 'out-default', targetHandle: 'in-default' })).connectionId as string

  await page.goto(`/projects/${projectId}/workflows/${workflowId}`)
  const edge = page.locator(`.react-flow__edge[data-id="${connectionId}"] .react-flow__edge-interaction`)
  const chooseConnection = async () => {
    await page.getByRole('button', { name: 'Pointer', exact: true }).first().click()
    await expect(edge).toBeAttached()
    const point = await edge.evaluate((element) => {
      const path = element as SVGPathElement
      const point = path.getPointAtLength(path.getTotalLength() / 4).matrixTransform(path.getScreenCTM()!)
      return { x: point.x, y: point.y }
    })
    await page.mouse.click(point.x, point.y)
  }
  await chooseConnection()
  await expect(page.getByRole('combobox', { name: 'Delay counts' })).toHaveValue('elapsed')
  await page.getByLabel('Delay (s)', { exact: true }).fill('1800')
  await page.getByRole('combobox', { name: 'Delay counts' }).selectOption('working')
  const savedReply = page.waitForResponse((reply) => reply.request().method() === 'PUT'
    && new URL(reply.url()).pathname === `/api/process-connections/${connectionId}`)
  await page.getByRole('button', { name: 'Save Connection', exact: true }).click()
  const saved = await savedReply
  expect(saved.ok()).toBe(true)
  expect((await saved.json()).data).toMatchObject({ delayTimeSec: 1800, delayBasis: 'working' })
  await page.reload()
  await chooseConnection()
  await expect(page.getByRole('combobox', { name: 'Delay counts' })).toHaveValue('working')
  await expect(page.getByLabel('Delay (s)', { exact: true })).toHaveValue('1800')
  const publishedReply = page.waitForResponse((reply) => reply.request().method() === 'POST'
    && new URL(reply.url()).pathname === `/api/workflows/${workflowId}/revisions`)
  await page.getByRole('button', { name: 'Publish revision', exact: true }).click()
  const published = await publishedReply
  expect(published.ok()).toBe(true)
  const revision = (await published.json()).data
  expect(revision.snapshot.connections).toEqual(expect.arrayContaining([
    expect.objectContaining({ connectionId, delayTimeSec: 1800, delayBasis: 'working' }),
  ]))
  await expect(page.getByRole('status').filter({ hasText: 'Published v1.' })).toBeVisible()

  const equipmentId = (await api('POST', '/equipments', { projectId, equipmentCode: `DELAY-${Date.now()}`,
    equipmentName: 'Curing press', equipmentType: 'machine', details: { capacityPerHour: 10 } })).equipmentId as string
  const opens = new Date(Date.now() + 4 * 60 * 60 * 1000)
  opens.setUTCMinutes(0, 0, 0)
  const ends = new Date(opens.getTime() + 60 * 60 * 1000)
  const time = (at: Date) => at.toISOString().slice(11, 16)
  await api('PUT', `/equipments/${equipmentId}/calendar`, {
    shiftStart: time(opens), shiftEnd: time(ends), workDays: [1, 2, 3, 4, 5, 6, 7],
  })
  const orderId = (await api('POST', '/work-orders', { projectId, workflowId, workOrderTitle: 'Working delay acceptance',
    targetQuantity: 10, plannedStartAt: opens.toISOString(), plannedEndAt: ends.toISOString() })).workOrderId as string
  await api('PUT', `/work-orders/${orderId}/equipment`, { equipmentId })
  await api('POST', `/work-orders/${orderId}/approve`)
  const run = await api('POST', '/production-runs/start', { projectId, workflowId,
    workflowRevisionId: revision.workflowRevisionId, workOrderId: orderId, plannedOutputQty: 10, runType: 'simulation' })
  expect(run.bomId).toBeNull()
  const runPath = `/production-runs/${run.productionRunId}`
  await page.goto(`/projects/${projectId}/runs/${run.productionRunId}`)
  const steps = page.getByRole('region', { name: 'Process steps', exact: true })
  await steps.getByRole('button', { name: 'Start Mix', exact: true }).click()
  await expect(steps).toContainText('Mix running')
  await steps.getByRole('button', { name: 'Complete Mix', exact: true }).click()
  const bakeRow = steps.getByRole('listitem').filter({ has: page.getByText('Bake', { exact: true }) })
  await expect(bakeRow).toContainText('Bake planned')
  await expect(bakeRow).toContainText('from')
  const storedSteps = await api('GET', `${runPath}/steps`) as { nodeId: string; stepId: string; scheduledAt: string }[]
  const bake = storedSteps.find((step) => step.nodeId === to)!
  expect(new Date(bake.scheduledAt).getTime()).toBe(opens.getTime() + 30 * 60 * 1000)
  const flowRuns = await api('GET', `/flow-runs?workflowId=${workflowId}`) as { productionRunId: string; flowRunId: string }[]
  const linked = flowRuns.find((flow) => flow.productionRunId === run.productionRunId)!
  const events = await api('GET', `/flow-runs/${linked.flowRunId}/events`) as {
    eventType: string; stepId: string; payload: Record<string, unknown>
  }[]
  expect(events).toEqual(expect.arrayContaining([expect.objectContaining({ eventType: 'step_scheduled', stepId: bake.stepId,
    payload: expect.objectContaining({ delayBasis: 'working' }) })]))
  const refusedReply = page.waitForResponse((reply) => reply.request().method() === 'POST'
    && new URL(reply.url()).pathname === `/api${runPath}/steps/${bake.stepId}/start`)
  await bakeRow.getByRole('button', { name: 'Start Bake', exact: true }).click()
  expect((await refusedReply).status()).toBe(409)
  await expect(steps.getByRole('alert')).toContainText('scheduledAt')
  await page.reload()
  await expect(steps).toContainText('Mix completed')
  await expect(steps).toContainText('Bake planned')
  await expect(steps.getByRole('listitem').filter({ has: page.getByText('Bake', { exact: true }) })).toContainText('from')
})
