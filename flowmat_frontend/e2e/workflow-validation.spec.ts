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
