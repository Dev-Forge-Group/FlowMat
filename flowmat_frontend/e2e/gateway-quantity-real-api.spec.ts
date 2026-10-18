import { expect, test } from '@playwright/test'

test.skip(process.env.REAL_API_E2E !== '1' || process.env.FLOWMAT_GATEWAY_E2E_DISPOSABLE !== '1',
  'Requires an explicitly disposable backend database')
test.use({ viewport: { width: 1920, height: 1080 }, trace: 'off' })

type Snapshot = { quantity?: number; unit?: string; arrivals?: { sourceStepId: string; quantity: number }[] }
type Step = { stepId: string; nodeId: string; sequenceNo: number; status: string; joinWaiting: number | null;
  inputSnapshot: Snapshot; outputSnapshot: Snapshot | null }
type Event = { stepId: string; eventType: string; actorType: string; payload: Record<string, unknown> }

test('real Split, transfer loss and Join preserve received quantities and show every parent', async ({ page }) => {
  test.setTimeout(180_000)
  let token = ''
  let projectId = ''
  let completed = false
  const headers = () => ({ Authorization: `Bearer ${token}` })
  const api = async <T>(method: string, path: string, body?: unknown): Promise<T> => {
    const response = await page.request.fetch(`/api${path}`, { method, headers: headers(), data: body })
    expect(response.ok(), `${method} ${path}: ${response.status()}`).toBe(true)
    const envelope = await response.json()
    expect(envelope.success).toBe(true)
    return envelope.data as T
  }
  const card = (sequence: number, name: string) =>
    page.getByText(`#${sequence} ${name}`, { exact: true }).locator('..').locator('..')
  const complete = async (sequence: number, name: string, output: Record<string, unknown>) => {
    await card(sequence, name).getByRole('button', { name: 'Start', exact: true }).click()
    await page.getByRole('textbox', { name: `Output snapshot for step #${sequence} (JSON)`, exact: true }).fill(JSON.stringify(output))
    await card(sequence, name).getByRole('button', { name: 'Complete', exact: true }).click()
    await expect(card(sequence, name)).toContainText('completed')
  }
  try {
    await page.goto('/')
    await page.locator('input').nth(0).fill('demo-owner')
    await page.locator('input[type="password"]').fill('demo1234')
    const login = page.waitForResponse((response) => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/auth/login')
    await page.getByRole('button', { name: 'Log in', exact: true }).click()
    const loggedIn = await login
    expect(loggedIn.ok()).toBe(true)
    token = (await loggedIn.json()).data.accessToken
    await expect(page.getByRole('button', { name: 'Logout', exact: true })).toBeVisible()
    projectId = (await api<{ projectId: string }>('POST', '/projects', {
      projectName: `Gateway-quantity-${Date.now()}`, ownerId: 'demo-owner',
    })).projectId
    const workflowId = (await api<{ workflowId: string }>('POST', '/workflows', {
      projectId, workflowName: 'Divide and gather',
    })).workflowId
    const nodes: Record<string, string> = {}
    for (const [processName, nodeType] of [['Measure', 'process'], ['Divide', 'split'], ['Bake', 'process'],
      ['Cool', 'process'], ['Gather', 'join'], ['Final', 'process']]) {
      nodes[processName] = (await api<{ processId: string }>('POST', '/processes', { workflowId, processName, nodeType })).processId
    }
    const link = async (from: string, to: string, options: Record<string, unknown> = {}) =>
      (await api<{ connectionId: string }>('POST', '/process-connections', {
        workflowId, fromProcessId: nodes[from], toProcessId: nodes[to], ...options,
      })).connectionId
    await link('Measure', 'Divide')
    const losing = await link('Divide', 'Bake', { splitKind: 'share', splitValue: 70, lossRate: 0.01 })
    await link('Divide', 'Cool', { splitKind: 'share', splitValue: 30 })
    await link('Bake', 'Gather')
    await link('Cool', 'Gather')
    await link('Gather', 'Final')
    const revision = await api<{ workflowRevisionId: string; schemaVersion: number }>('POST', `/workflows/${workflowId}/revisions`)
    expect(revision.schemaVersion).toBeGreaterThanOrEqual(2)
    await page.goto(`/projects/${projectId}/runs?workflowId=${workflowId}&view=flow-runs`)
    await page.getByRole('combobox', { name: 'Published revision', exact: true }).selectOption({ label: 'v1' })
    await page.getByRole('combobox', { name: 'Run type', exact: true }).selectOption('actual')
    await page.getByRole('combobox', { name: 'Execution mode', exact: true }).selectOption('graph')
    const started = page.waitForResponse((response) => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/flow-runs/graph')
    await page.getByRole('button', { name: 'Start Flow Run', exact: true }).click()
    const runId = (await (await started).json()).data.flowRunId as string
    const steps = () => api<Step[]>('GET', `/flow-runs/${runId}/steps`)

    await complete(1, 'Measure', { quantity: 100, unit: 'kg', attrs: { grade: 'A' } })
    await expect(card(2, 'Divide')).toContainText('completed')
    await expect(card(2, 'Divide').getByRole('button', { name: 'Start', exact: true })).toHaveCount(0)
    const divided = await steps()
    expect(divided.map((step) => step.nodeId)).toEqual(['Measure', 'Divide', 'Bake', 'Cool'].map((name) => nodes[name]))
    expect(divided[2].inputSnapshot).toMatchObject({ quantity: 69.3, unit: 'kg' })
    expect(divided[3].inputSnapshot).toMatchObject({ quantity: 30, unit: 'kg' })
    await expect(card(3, 'Bake')).toContainText('lost 0.7 kg')

    await complete(3, 'Bake', { quantity: 69.3, unit: 'kg', attrs: { branch: 'Bake' } })
    const gather = card(5, 'Gather')
    await expect(gather).toContainText('joins step #3 · waiting for 1 branch')
    await expect(gather.getByRole('button', { name: 'Start', exact: true })).toHaveCount(0)
    await expect(gather.getByRole('button', { name: 'Edit schedule', exact: true })).toHaveCount(0)
    const waiting = (await steps()).find((step) => step.nodeId === nodes.Gather)!
    expect(waiting).toMatchObject({ status: 'planned', joinWaiting: 1 })
    expect(waiting.inputSnapshot.arrivals).toHaveLength(1)
    const refused = await page.request.post(`/api/flow-runs/${runId}/steps/${waiting.stepId}/start`, { headers: headers() })
    expect(refused.status()).toBe(409)

    await complete(4, 'Cool', { quantity: 30, unit: 'kg', attrs: { branch: 'Cool' } })
    await expect(gather).toContainText('joins steps #3, #4')
    await expect(gather).not.toContainText('waiting for')
    const joined = await steps()
    expect(joined).toHaveLength(6)
    expect(joined[4]).toMatchObject({ status: 'completed', joinWaiting: 0,
      outputSnapshot: { quantity: 99.3, unit: 'kg' } })
    expect(joined[4].outputSnapshot?.arrivals?.map((arrival) => arrival.sourceStepId))
      .toEqual([joined[2].stepId, joined[3].stepId])
    expect(joined[5].inputSnapshot.quantity).toBe(99.3)
    const lineage = await api<{ ancestors: Step[] }>('GET', `/flow-runs/${runId}/steps/${joined[5].stepId}/lineage`)
    expect(lineage.ancestors.map((step) => step.nodeId))
      .toEqual(['Measure', 'Divide', 'Bake', 'Cool', 'Gather'].map((name) => nodes[name]))
    const events = await api<Event[]>('GET', `/flow-runs/${runId}/events`)
    expect(events.filter((event) => event.eventType === 'join_arrived')).toHaveLength(2)
    expect(events).toEqual(expect.arrayContaining([
      expect.objectContaining({ eventType: 'transfer_loss', payload: expect.objectContaining({
        connectionId: losing, sent: 70, arrived: 69.3, lost: 0.7,
      }) }),
      expect.objectContaining({ stepId: joined[4].stepId, eventType: 'step_completed', actorType: 'system' }),
    ]))
    await complete(6, 'Final', { quantity: 99.3, unit: 'kg' })
    await page.getByRole('button', { name: 'Finish Flow Run', exact: true }).click()
    await expect(page.getByRole('combobox', { name: 'Flow Run', exact: true })).toContainText('finished')
    expect(await api('GET', `/production-runs?workflowId=${workflowId}`)).toEqual([])
    for (const resource of ['boms', 'items', 'inventories', 'lots', 'work-orders']) {
      expect(await api('GET', `/${resource}?projectId=${projectId}`)).toEqual([])
    }
    completed = true
  } finally {
    if (projectId && token) {
      const response = await page.request.delete(`/api/projects/${projectId}`, { headers: headers() })
      if (completed) expect(response.ok(), `Project cleanup returned ${response.status()}`).toBe(true)
      else if (!response.ok()) test.info().annotations.push({ type: 'cleanup', description: `Project cleanup returned ${response.status()}` })
    }
  }
})
