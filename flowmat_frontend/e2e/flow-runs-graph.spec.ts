import { expect, test, type Route } from '@playwright/test'

type GraphStep = {
  stepId: string
  nodeId: string
  sequenceNo: number
  status: string
  sourceStepId: string | null
  sourceConnectionId: string | null
  errorCode: string | null
}

test('graph Flow Run shows the exact source step and connection policy', async ({ page }) => {
  const steps: GraphStep[] = []
  const events: Record<string, unknown>[] = []
  let run: Record<string, unknown> | null = null
  let graphStartPayload: Record<string, unknown> | null = null
  let sourceCompleteOutput: unknown = null
  let otherCompleteOutput: unknown = null
  let targetFailureCode: unknown = null
  const now = new Date().toISOString()
  const ok = (route: Route, data: unknown) => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ success: true, data, message: null }),
  })

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    const method = request.method()
    if (pathname === '/api/auth/csrf') return route.fulfill({
      status: 200, headers: { 'content-type': 'application/json', 'set-cookie': 'XSRF-TOKEN=test-csrf; Path=/' },
      body: JSON.stringify({ success: true, data: 'test-csrf', message: null }),
    })
    if (pathname === '/api/auth/login') return route.fulfill({
      status: 200, headers: { 'content-type': 'application/json', 'set-cookie': 'flowmat_rt=test-refresh; HttpOnly; Path=/api/auth' },
      body: JSON.stringify({ success: true, data: {
        accessToken: 'eyJ.fake.access', refreshToken: null, deviceId: 'device-1', additionalInfoRequired: false,
      }, message: null }),
    })
    if (pathname === '/api/auth/refresh') return ok(route, { accessToken: 'eyJ.fake.access', refreshToken: null })
    if (pathname === '/api/users/me') return ok(route, { userId: 'demo-owner', userName: 'Demo Owner' })
    if (pathname === '/api/users/me/permissions') return ok(route, { canManageUsers: false })
    if (pathname === '/api/projects') return ok(route, [])
    if (pathname === '/api/workflows' && method === 'GET') {
      return ok(route, [{ workflowId: 'wf-graph', projectId: 'prj-graph', workflowName: 'Graph test' }])
    }
    if (pathname === '/api/workflows/wf-graph/revisions' && method === 'GET') {
      return ok(route, [{ workflowRevisionId: 'rev-graph', workflowId: 'wf-graph',
        revisionNo: 1, status: 'published', publishedAt: now }])
    }
    if (pathname === '/api/workflows/wf-graph/revisions/rev-graph') {
      return ok(route, { workflowRevisionId: 'rev-graph', revisionNo: 1, status: 'published',
        snapshot: { processes: [
          { processId: 'node-source', processName: 'Source' },
          { processId: 'node-other', processName: 'Other' },
          { processId: 'node-target', processName: 'Target' },
        ], connections: [
          { connectionId: 'edge-1', connectionLabel: 'Material flow', failurePolicy: 'skip' },
          { connectionId: 'edge-2', connectionLabel: 'Alternate flow', failurePolicy: 'stop' },
        ] } })
    }
    if (pathname === '/api/flow-runs' && method === 'GET') return ok(route, run ? [run] : [])
    if (pathname === '/api/flow-runs/graph' && method === 'POST') {
      graphStartPayload = request.postDataJSON() as Record<string, unknown>
      run = { flowRunId: 'run-graph', workflowRevisionId: 'rev-graph', productionRunId: null,
        executionMode: 'graph', runType: 'test', status: 'running', startedAt: now, endedAt: null }
      steps.push({ stepId: 'step-source', nodeId: 'node-source', sequenceNo: 1, status: 'planned',
        sourceStepId: null, sourceConnectionId: null, errorCode: null })
      steps.push({ stepId: 'step-other', nodeId: 'node-other', sequenceNo: 2, status: 'planned',
        sourceStepId: null, sourceConnectionId: null, errorCode: null })
      return ok(route, run)
    }
    if (pathname === '/api/flow-runs/run-graph/steps' && method === 'GET') return ok(route, steps)
    if (pathname === '/api/flow-runs/run-graph/steps/step-target/lineage' && method === 'GET') {
      return ok(route, { step: steps[2], ancestors: [steps[0]], descendants: [] })
    }
    if (pathname === '/api/flow-runs/run-graph/steps/step-source/start') {
      steps[0].status = 'running'
      return ok(route, steps[0])
    }
    if (pathname === '/api/flow-runs/run-graph/steps/step-other/start') {
      steps[1].status = 'running'
      return ok(route, steps[1])
    }
    if (pathname === '/api/flow-runs/run-graph/steps/step-source/preview') {
      const body = request.postDataJSON() as { outputSnapshot: { quantity: number } }
      return ok(route, [
        { connectionId: 'edge-1', targetNodeId: 'node-target',
          willRoute: body.outputSnapshot.quantity >= 2 },
        { connectionId: 'edge-2', targetNodeId: 'node-target', willRoute: false },
      ])
    }
    if (pathname === '/api/flow-runs/run-graph/steps/step-source/complete') {
      sourceCompleteOutput = (request.postDataJSON() as { outputSnapshot: unknown }).outputSnapshot
      steps[0].status = 'completed'
      steps.push({ stepId: 'step-target', nodeId: 'node-target', sequenceNo: 3, status: 'planned',
        sourceStepId: 'step-source', sourceConnectionId: 'edge-1', errorCode: null })
      events.push({ eventId: 'event-filtered', eventType: 'connection_filtered', stepId: 'step-source',
        payload: { connectionId: 'edge-2', targetNodeId: 'node-target', reason: 'condition_false' },
        occurredAt: now, actorId: 'demo-owner' })
      return ok(route, steps[0])
    }
    if (pathname === '/api/flow-runs/run-graph/steps/step-other/complete') {
      otherCompleteOutput = (request.postDataJSON() as { outputSnapshot: unknown }).outputSnapshot
      steps[1].status = 'completed'
      return ok(route, steps[1])
    }
    if (pathname === '/api/flow-runs/run-graph/steps/step-target/start') {
      steps[2].status = 'running'
      return ok(route, steps[2])
    }
    if (pathname === '/api/flow-runs/run-graph/steps/step-target/fail') {
      targetFailureCode = (request.postDataJSON() as { errorCode: unknown }).errorCode
      steps[2].status = 'failed'
      steps[2].errorCode = String(targetFailureCode)
      return ok(route, steps[2])
    }
    if (pathname === '/api/flow-runs/run-graph/events') return ok(route, events)
    if (method === 'GET' && ['/api/items', '/api/production-runs', '/api/work-orders', '/api/boms']
      .includes(pathname)) return ok(route, [])
    await route.fulfill({ status: 404, contentType: 'application/json',
      body: JSON.stringify({ success: false, data: null, message: `Unmocked ${method} ${pathname}` }) })
  })

  await page.goto('/')
  await page.locator('input').nth(0).fill('demo-owner')
  await page.locator('input[type="password"]').fill('demo1234')
  await page.getByRole('button', { name: 'Log in' }).click()
  await expect(page.getByRole('button', { name: 'Logout' })).toBeVisible()
  await page.goto('/projects/prj-graph/runs?view=flow-runs')

  await page.getByRole('combobox', { name: 'Execution mode' }).selectOption('graph')
  await page.getByRole('button', { name: 'Start Flow Run' }).click()
  await expect(page.getByText('Mode: graph')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Add planned step' })).toHaveCount(0)
  await page.getByText('#1 Source').locator('..').getByRole('button', { name: 'Start' }).click()
  await page.getByText('#2 Other').locator('..').getByRole('button', { name: 'Start' }).click()
  await page.getByRole('textbox', { name: 'Output snapshot for step #1 (JSON)' }).fill('{"quantity":1}')
  await page.getByRole('textbox', { name: 'Output snapshot for step #2 (JSON)' }).fill('{"quantity":99}')
  await page.getByRole('textbox', { name: 'Failure code for step #1' }).fill('SOURCE_FAILURE')
  await page.getByRole('textbox', { name: 'Failure code for step #2' }).fill('OTHER_FAILURE')
  await expect(page.getByRole('textbox', { name: 'Failure code for step #1' })).toHaveValue('SOURCE_FAILURE')
  await expect(page.getByRole('textbox', { name: 'Failure code for step #2' })).toHaveValue('OTHER_FAILURE')
  await page.getByText('#1 Source').locator('..').getByRole('button', { name: 'Preview routes' }).click()
  await expect(page.getByText('Condition false: Material flow → Target')).toBeVisible()
  expect(steps).toHaveLength(2)
  await page.getByRole('textbox', { name: 'Output snapshot for step #1 (JSON)' }).fill('{"quantity":2}')
  await expect(page.getByText('Condition false: Material flow → Target')).toHaveCount(0)
  await expect(page.getByRole('textbox', { name: 'Output snapshot for step #2 (JSON)' })).toHaveValue('{"quantity":99}')
  await page.getByText('#1 Source').locator('..').getByRole('button', { name: 'Preview routes' }).click()
  await expect(page.getByText('Will route: Material flow → Target')).toBeVisible()
  expect(steps).toHaveLength(2)
  await page.getByText('#1 Source').locator('..').getByRole('button', { name: 'Complete' }).click()
  await expect(page.getByText('#3 Target')).toBeVisible()
  expect(sourceCompleteOutput).toEqual({ quantity: 2 })
  await page.getByText('#2 Other').locator('..').getByRole('button', { name: 'Complete' }).click()
  await expect.poll(() => otherCompleteOutput).toEqual({ quantity: 99 })
  await expect(page.getByText('from step #1')).toBeVisible()
  await expect(page.getByText(/via Material flow · on failure: skip/)).toBeVisible()
  await expect(page.getByRole('listitem').filter({ hasText: 'Alternate flow (condition false)' }))
    .toContainText('connection_filtered')
  await page.getByText('#3 Target').locator('..').getByRole('button', { name: 'Show attempts' }).click()
  await expect(page.getByText('Upstream: #1')).toBeVisible()
  await expect(page.getByText('Downstream: none')).toBeVisible()
  await page.getByText('#3 Target').locator('..').getByRole('button', { name: 'Start' }).click()
  await page.getByRole('textbox', { name: 'Failure code for step #3' }).fill('TARGET_FAILURE')
  await page.getByText('#3 Target').locator('..').getByRole('button', { name: 'Fail' }).click()
  await expect.poll(() => targetFailureCode).toBe('TARGET_FAILURE')
  expect(graphStartPayload).toMatchObject({ workflowId: 'wf-graph', workflowRevisionId: 'rev-graph', runType: 'test' })
})
