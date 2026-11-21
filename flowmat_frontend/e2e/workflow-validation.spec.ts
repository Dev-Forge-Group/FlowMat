import { expect, test } from '@playwright/test'

test.skip(!process.env.REAL_API_E2E, 'Needs the real backend and demo seed')

test('workflow check, inline condition error, and port deletion stay in sync', async ({ page }) => {
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
  const token = (await login.json()).data.accessToken as string
  const headers = { Authorization: `Bearer ${token}` }
  await page.waitForLoadState('networkidle')

  async function create(path: string, data: Record<string, unknown>): Promise<Record<string, string>> {
    const response = await page.request.post(`/api${path}`, { headers, data })
    expect(response.ok(), `${path}: ${response.status()}`).toBe(true)
    return (await response.json()).data as Record<string, string>
  }

  const workflow = await create('/workflows', {
    projectId: 'prj_demo_main', workflowName: `Validation E2E ${Date.now()}`,
  })
  const workflowId = workflow.workflowId
  try {
    const source = await create('/processes', {
      workflowId, processName: 'Source', posX: 120, posY: 180,
    })
    const target = await create('/processes', {
      workflowId, processName: 'Target', posX: 560, posY: 180,
    })
    const port = async (processId: string, direction: 'input' | 'output') => create('/process-ios', {
      processId, itemId: 'itm_demo_mix_output', direction, quantity: 1, unit: 'kg',
    })
    const output = await port(source.processId, 'output')
    const input = await port(target.processId, 'input')
    await create('/process-connections', {
      workflowId, fromProcessId: source.processId, toProcessId: target.processId,
      fromIoId: output.processIoId, toIoId: input.processIoId,
    })

    await page.goto(`/projects/prj_demo_main/workflows/${workflowId}`)
    await page.getByRole('button', { name: 'Check workflow' }).click()
    await expect(page.getByText(/^0 errors, \d+ warnings$/)).toBeVisible()

    const edge = page.locator('.react-flow__edge').first()
    await expect(edge).toBeVisible()
    await page.getByRole('button', { name: /SCHEMA_UNVERIFIED/ }).click()
    await expect(page.getByRole('button', { name: 'Save Connection' })).toBeVisible()
    await page.getByRole('textbox', { name: 'Condition expression' }).fill('sum(quantity) > 0')
    await page.getByRole('button', { name: 'Save Connection' }).click()
    await expect(page.getByRole('alert').filter({ hasText: 'Invalid condition at position' }).first())
      .toBeVisible()

    // The minimap can overlap the node center in a narrow canvas.
    await page.locator(`.react-flow__node[data-id="${target.processId}"]`)
      .click({ position: { x: 24, y: 18 } })
    await page.getByRole('button', { name: 'Delete Port' }).click()
    await expect(edge).toHaveCount(0)
    const connections = await page.request.get(`/api/process-connections?workflowId=${workflowId}`, { headers })
    expect(connections.ok()).toBe(true)
    expect((await connections.json()).data).toEqual([])
  } finally {
    await page.request.delete(`/api/workflows/${workflowId}`, { headers })
  }
})

test('the check as drawn agrees with the server check on a saved workflow', async ({ page }) => {
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

  async function create(path: string, data: Record<string, unknown>): Promise<Record<string, string>> {
    const response = await page.request.post(`/api${path}`, { headers, data })
    expect(response.ok(), `${path}: ${response.status()}`).toBe(true)
    return (await response.json()).data as Record<string, string>
  }

  const workflow = await create('/workflows', { projectId: 'prj_demo_main', workflowName: `As drawn E2E ${Date.now()}` })
  const workflowId = workflow.workflowId
  try {
    // A gateway with one route, a required input nothing feeds and a step on its own: errors and warnings both.
    const source = await create('/processes', { workflowId, processName: 'Receive', nodeType: 'input', posX: 80, posY: 180 })
    const gate = await create('/processes', { workflowId, processName: 'Grade?', nodeType: 'exclusive', posX: 360, posY: 180 })
    const target = await create('/processes', { workflowId, processName: 'Ship', nodeType: 'output', posX: 640, posY: 180 })
    await create('/processes', { workflowId, processName: 'Spare', posX: 360, posY: 420 })
    await create('/process-ios', { processId: target.processId, itemId: 'itm_demo_mix_output', direction: 'input',
      quantity: 1, unit: 'kg', requiredYn: 'Y' })
    await create('/process-connections', { workflowId, fromProcessId: source.processId, toProcessId: gate.processId })
    await create('/process-connections', { workflowId, fromProcessId: gate.processId, toProcessId: target.processId })

    await page.goto(`/projects/prj_demo_main/workflows/${workflowId}`)
    const check = page.getByRole('region', { name: 'Workflow validation' })
    await check.getByRole('button', { name: 'Check workflow', exact: true }).click()
    const counts = check.getByText(/^\d+ errors, \d+ warnings$/)
    await expect(counts).toBeVisible()
    const server = await counts.textContent()
    expect(server).not.toBe('0 errors, 0 warnings')

    const summary = check.getByRole('button', { name: /^As drawn:/ })
    const live = page.getByRole('region', { name: 'Check as drawn' })
    await expect(summary).toHaveText(`As drawn: ${server}`)
    await summary.click()
    const issues = (region: typeof check) => region.locator('.validation-issues > li > button').allTextContents()
    expect((await issues(live)).sort()).toEqual((await issues(check)).sort())
  } finally {
    await page.request.delete(`/api/workflows/${workflowId}`, { headers })
  }
})
