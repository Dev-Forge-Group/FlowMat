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
    const tryIt = page.getByRole('region', { name: 'Try the flow' })
    await tryIt.getByRole('button', { name: 'Try it' }).click()
    await tryIt.getByLabel('Quantity').fill('10')
    await tryIt.getByLabel('Unit').fill('kg')
    await tryIt.getByRole('button', { name: 'Run through' }).click()
    await expect(tryIt.getByText('Reaches the end in 4 steps, losing 0.6 kg on the way')).toBeVisible()
    await expect(tryIt.getByRole('listitem')).toHaveText(server)
  } finally {
    await page.request.delete(`/api/workflows/${workflowId}`, { headers })
  }
})
