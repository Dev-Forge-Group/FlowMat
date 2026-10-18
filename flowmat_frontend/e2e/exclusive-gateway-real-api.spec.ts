import { expect, test, type APIResponse } from '@playwright/test'

test.skip(process.env.REAL_API_E2E !== '1' || process.env.FLOWMAT_GATEWAY_E2E_DISPOSABLE !== '1',
  'Requires an explicitly disposable backend database')
test.use({ viewport: { width: 1920, height: 1080 }, trace: 'off' })

type Step = { stepId: string; nodeId: string; status: string; errorCode: string | null; sourceConnectionId: string | null;
  outputSnapshot: { quantity?: number } }
type Event = { stepId: string | null; eventType: string; actorType: string;
  payload: { connectionId?: string; reason?: string; errorCode?: string } }

async function data<T>(response: APIResponse): Promise<T> {
  expect(response.ok(), `API returned ${response.status()}`).toBe(true)
  const envelope = await response.json()
  expect(envelope.success).toBe(true)
  return envelope.data as T
}

test('Exclusive gateway: the real editor saves a default, graph runs choose one route, and revisions retain routing', async ({ page }) => {
  test.setTimeout(180_000)
  let token = ''
  let projectId = ''
  let completed = false
  const headers = () => ({ Authorization: `Bearer ${token}` })
  const get = async <T>(path: string) => data<T>(await page.request.get(`/api${path}`, { headers: headers() }))
  const post = async <T>(path: string, body?: unknown) => data<T>(await page.request.post(`/api${path}`, { headers: headers(), data: body }))
  const put = async <T>(path: string, body: unknown) => data<T>(await page.request.put(`/api${path}`, { headers: headers(), data: body }))
  try {
    await test.step('one login and a new project without manufacturing data', async () => {
      await page.goto('/')
      await page.locator('input').nth(0).fill('demo-owner')
      await page.locator('input[type="password"]').fill('demo1234')
      const login = page.waitForResponse((response) => response.request().method() === 'POST'
        && new URL(response.url()).pathname === '/api/auth/login')
      await page.getByRole('button', { name: 'Log in', exact: true }).click()
      const response = await login
      expect(response.ok()).toBe(true)
      token = (await response.json()).data.accessToken
      expect(typeof token).toBe('string')
      await expect(page.getByText('안녕하세요, Demo Owner님', { exact: true })).toBeVisible()
      projectId = (await post<{ projectId: string }>('/projects', {
        projectName: `Exclusive-E2E-${Date.now()}`, ownerId: 'demo-owner',
      })).projectId
    })

    const workflowId = (await post<{ workflowId: string }>('/workflows', { projectId, workflowName: 'Sorting' })).workflowId
    const node = async (processName: string, posX: number, posY: number, nodeType = 'process') =>
      (await post<{ processId: string }>('/processes', { workflowId, processName, nodeType, posX, posY })).processId
    const measure = await node('Measure', 80, 160)
    const choose = await node('Choose', 380, 160, 'exclusive')
    const high = await node('High', 680, -80)
    const medium = await node('Medium', 680, 400)
    const rest = await node('Rest', 680, 160)
    const connect = async (fromProcessId: string, toProcessId: string, priority: number, conditionExpr?: string) =>
      (await post<{ connectionId: string }>('/process-connections', {
        workflowId, fromProcessId, toProcessId, priority, conditionExpr,
      })).connectionId
    await connect(measure, choose, 0)
    const toHigh = await connect(choose, high, 1, 'quantity > 10')
    const toMedium = await connect(choose, medium, 2, 'quantity > 5')
    const toRest = await connect(choose, rest, 9)
    await test.step('the editor persists Default route to the real API', async () => {
      await page.goto(`/projects/${projectId}/workflows/${workflowId}`)
      await expect(page.locator('.react-flow__node')).toHaveCount(5)
      await page.getByRole('button', { name: 'Fit View', exact: true }).click()
      const edge = page.locator(`.react-flow__edge[data-id="${toRest}"] .react-flow__edge-interaction`)
      await expect(edge).toHaveCount(1)
      const point = await edge.evaluate((element) => {
        const path = element as SVGPathElement
        const matrix = path.getScreenCTM()
        if (!matrix) throw new Error('Connection has no screen transform')
        const position = path.getPointAtLength(path.getTotalLength() * 0.8)
        const screen = new DOMPoint(position.x, position.y).matrixTransform(matrix)
        return { x: screen.x, y: screen.y }
      })
      await page.mouse.click(point.x, point.y)
      const checkbox = page.getByRole('checkbox', { name: 'Default route', exact: true })
      await expect(checkbox).not.toBeChecked()
      await checkbox.check()
      const saved = page.waitForResponse((response) => response.request().method() === 'PUT'
        && new URL(response.url()).pathname === `/api/process-connections/${toRest}`)
      await page.getByRole('button', { name: 'Save Connection', exact: true }).click()
      expect((await saved).ok()).toBe(true)
      await expect(checkbox).toBeChecked()
      const connections = await get<{ connectionId: string; defaultRoute: boolean }[]>(`/process-connections?workflowId=${workflowId}`)
      expect(connections.find((connection) => connection.connectionId === toRest)?.defaultRoute).toBe(true)
      await page.getByRole('button', { name: 'Check workflow', exact: true }).click()
      await expect(page.getByText(/^0 errors, \d+ warnings$/)).toBeVisible()
    })

    let revision = ''
    let firstRun = ''
    const stepCard = (index: number, name: string) => page.getByText(`#${index} ${name}`, { exact: true }).locator('..').locator('..')
    await test.step('publish and run through the UI: two true conditions still choose just High', async () => {
      await page.goto(`/projects/${projectId}/runs?workflowId=${workflowId}`)
      const published = page.waitForResponse((response) => response.request().method() === 'POST'
        && new URL(response.url()).pathname === `/api/workflows/${workflowId}/revisions`)
      await page.getByRole('button', { name: 'Publish current workflow', exact: true }).click()
      revision = (await (await published).json()).data.workflowRevisionId
      await page.getByRole('tab', { name: 'Flow executions', exact: true }).click()
      await page.getByRole('combobox', { name: 'Published revision', exact: true }).selectOption({ label: 'v1' })
      await page.getByRole('combobox', { name: 'Run type', exact: true }).selectOption('actual')
      await page.getByRole('combobox', { name: 'Execution mode', exact: true }).selectOption('graph')
      const started = page.waitForResponse((response) => response.request().method() === 'POST'
        && new URL(response.url()).pathname === '/api/flow-runs/graph')
      await page.getByRole('button', { name: 'Start Flow Run', exact: true }).click()
      firstRun = (await (await started).json()).data.flowRunId
      await stepCard(1, 'Measure').getByRole('button', { name: 'Start', exact: true }).click()
      await page.getByRole('textbox', { name: 'Output snapshot for step #1 (JSON)', exact: true }).fill('{"quantity":12}')
      await stepCard(1, 'Measure').getByRole('button', { name: 'Complete', exact: true }).click()
      await expect(stepCard(2, 'Choose')).toContainText('completed')
      await expect(stepCard(2, 'Choose').getByRole('button', { name: 'Start', exact: true })).toHaveCount(0)
      await expect(stepCard(3, 'High')).toBeVisible()
      await expect(page.getByText(/#\d+ (Medium|Rest)$/, { exact: true })).toHaveCount(0)
      const steps = await get<Step[]>(`/flow-runs/${firstRun}/steps`)
      const refused = await page.request.post(`/api/flow-runs/${firstRun}/steps/${steps[1].stepId}/start`, { headers: headers() })
      expect(refused.status()).toBe(409)
      const duplicate = await page.request.post(`/api/flow-runs/${firstRun}/steps/${steps[0].stepId}/complete`, {
        headers: headers(), data: { outputSnapshot: { quantity: 12 } },
      })
      expect(duplicate.status()).toBe(409)
      expect(await get<Step[]>(`/flow-runs/${firstRun}/steps`)).toHaveLength(3)
      await stepCard(3, 'High').getByRole('button', { name: 'Start', exact: true }).click()
      await stepCard(3, 'High').getByRole('button', { name: 'Complete', exact: true }).click()
      await page.getByRole('button', { name: 'Finish Flow Run', exact: true }).click()
      await expect(page.getByRole('combobox', { name: 'Flow Run', exact: true })).toContainText('finished')
    })
    await test.step('stored steps, attempts and system routing events agree', async () => {
      const steps = await get<Step[]>(`/flow-runs/${firstRun}/steps`)
      expect(steps.map((step) => step.nodeId)).toEqual([measure, choose, high])
      expect(steps.map((step) => step.status)).toEqual(['completed', 'completed', 'completed'])
      expect(steps[1].outputSnapshot.quantity).toBe(12)
      expect(steps[2].sourceConnectionId).toBe(toHigh)
      for (const step of steps) {
        const attempts = await get<{ status: string }[]>(`/flow-runs/${firstRun}/steps/${step.stepId}/attempts`)
        expect(attempts).toHaveLength(1)
        expect(attempts[0].status).toBe('completed')
      }
      const events = await get<Event[]>(`/flow-runs/${firstRun}/events`)
      expect(events.filter((event) => event.stepId === steps[1].stepId && event.eventType === 'step_completed'))
        .toEqual([expect.objectContaining({ actorType: 'system' })])
      const route = (connectionId: string, reason: string, eventType: string) =>
        expect(events).toEqual(expect.arrayContaining([expect.objectContaining({ eventType,
          payload: expect.objectContaining({ connectionId, reason }) })]))
      route(toHigh, 'first_match', 'gateway_routed')
      route(toMedium, 'exclusive_not_chosen', 'connection_filtered')
      route(toRest, 'default_not_needed', 'connection_filtered')
      expect(await get<Step[]>(`/flow-runs/${firstRun}/steps`)).toHaveLength(3)
    })

    const run = async (publishedRevision: string, quantity: number) => {
      const id = (await post<{ flowRunId: string }>('/flow-runs/graph', {
        workflowId, workflowRevisionId: publishedRevision, runType: 'test',
      })).flowRunId
      const root = (await get<Step[]>(`/flow-runs/${id}/steps`))[0]
      await post(`/flow-runs/${id}/steps/${root.stepId}/start`)
      await post(`/flow-runs/${id}/steps/${root.stepId}/complete`, { outputSnapshot: { quantity } })
      return { id, steps: await get<Step[]>(`/flow-runs/${id}/steps`) }
    }
    const finish = async (id: string, leaf: Step) => {
      await post(`/flow-runs/${id}/steps/${leaf.stepId}/start`)
      await post(`/flow-runs/${id}/steps/${leaf.stepId}/complete`, {})
      await post(`/flow-runs/${id}/finish`, { outputPayload: {} })
      expect((await get<{ status: string }>(`/flow-runs/${id}`)).status).toBe('finished')
    }
    await test.step('one matching condition uses Medium; no match uses the default', async () => {
      for (const [quantity, target, connection] of [[7, medium, toMedium], [3, rest, toRest]] as const) {
        const result = await run(revision, quantity)
        expect(result.steps.map((step) => step.nodeId)).toEqual([measure, choose, target])
        expect(result.steps[2].sourceConnectionId).toBe(connection)
        if (quantity === 3) expect(await get<Event[]>(`/flow-runs/${result.id}/events`))
          .toEqual(expect.arrayContaining([expect.objectContaining({ eventType: 'gateway_routed',
            payload: expect.objectContaining({ connectionId: toRest, reason: 'default' }) })]))
        await finish(result.id, result.steps[2])
      }
    })
    await test.step('a newer revision can fail with NO_ROUTE without changing v1', async () => {
      await put(`/process-connections/${toRest}`, { defaultRoute: false, conditionExpr: 'quantity > 100' })
      const strict = (await post<{ workflowRevisionId: string }>(`/workflows/${workflowId}/revisions`)).workflowRevisionId
      const failed = await run(strict, 3)
      expect((await get<{ status: string }>(`/flow-runs/${failed.id}`)).status).toBe('failed')
      expect(failed.steps).toHaveLength(2)
      expect(failed.steps[1]).toMatchObject({ nodeId: choose, status: 'failed', errorCode: 'NO_ROUTE' })
      expect(await get<Event[]>(`/flow-runs/${failed.id}/events`)).toEqual(expect.arrayContaining([
        expect.objectContaining({ eventType: 'run_failed', payload: expect.objectContaining({ errorCode: 'NO_ROUTE' }) }),
      ]))
      const unchanged = await run(revision, 3)
      expect(unchanged.steps.map((step) => step.nodeId)).toEqual([measure, choose, rest])
      expect(unchanged.steps[2].sourceConnectionId).toBe(toRest)
      await finish(unchanged.id, unchanged.steps[2])
    })
    await test.step('manufacturing lists remain empty', async () => {
      expect(await get<unknown[]>(`/production-runs?workflowId=${workflowId}`)).toEqual([])
      for (const resource of ['work-orders', 'items', 'inventories', 'lots', 'boms']) {
        expect(await get<unknown[]>(`/${resource}?projectId=${projectId}`)).toEqual([])
      }
    })
    completed = true
  } finally {
    if (projectId && token) {
      try {
        const response = await page.request.delete(`/api/projects/${projectId}`, { headers: headers() })
        if (completed) expect(response.ok(), `Project cleanup returned ${response.status()}`).toBe(true)
        else if (!response.ok()) test.info().annotations.push({ type: 'cleanup', description: `Project cleanup returned ${response.status()}` })
      } catch (error) {
        if (completed) throw error
        test.info().annotations.push({ type: 'cleanup', description: 'Project cleanup request failed after the primary failure' })
      }
    }
  }
})
