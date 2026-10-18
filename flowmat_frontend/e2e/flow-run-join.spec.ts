import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * A graph Flow Run with a Join (docs/architecture/adr/ADR-007-join-gateway.md decision 11) against a mocked API: the
 * waiting Join says which steps arrived and how many branches can still bring something, and offers no Start; once passed
 * it lists what it joined.
 */
test('a waiting Join says what arrived and what it waits for', async ({ page }) => {
  const run = { flowRunId: 'run-join', projectId: 'prj-join', workflowId: 'wf-join', workflowRevisionId: 'rev-join',
    productionRunId: null, runType: 'test', executionMode: 'graph', status: 'running', inputPayload: {},
    outputPayload: null, startedAt: '2030-01-01T00:00:00Z', endedAt: null, requestedBy: 'demo-owner' }
  const step = (stepId: string, nodeId: string, sequenceNo: number, status: string, extra: Record<string, unknown> = {}) => ({
    stepId, flowRunId: 'run-join', nodeId, sourceConnectionId: null, sourceStepId: null, status, sequenceNo, scheduledAt: null,
    startedAt: null, endedAt: null, inputSnapshot: {}, outputSnapshot: null, errorCode: null, errorMessage: null, ...extra })
  let joinState = { status: 'planned', joinWaiting: 1, arrivals: [{ connectionId: 'c-bj', sourceStepId: 's-b', quantity: 2 }] }
  const steps = () => [
    step('s-a', 'n-a', 1, 'completed'),
    step('s-b', 'n-b', 2, 'completed', { sourceStepId: 's-a', sourceConnectionId: 'c-ab' }),
    step('s-c', 'n-c', 3, joinState.status === 'planned' ? 'running' : 'completed', { sourceStepId: 's-a', sourceConnectionId: 'c-ac' }),
    step('s-j', 'n-j', 4, joinState.status, { sourceStepId: 's-b', sourceConnectionId: 'c-bj', joinWaiting: joinState.joinWaiting,
      inputSnapshot: { arrivals: joinState.arrivals } }),
  ]

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') throw new Error(`Unexpected write ${request.method()} ${pathname}`)
    if (pathname === '/api/workflows') return ok(route, [{ workflowId: 'wf-join', projectId: 'prj-join', workflowName: 'Join flow' }])
    if (pathname === '/api/workflows/wf-join/revisions') {
      return ok(route, [{ workflowRevisionId: 'rev-join', workflowId: 'wf-join', revisionNo: 1, status: 'published',
        publishedAt: '2030-01-01T00:00:00Z' }])
    }
    if (pathname === '/api/workflows/wf-join/revisions/rev-join') {
      return ok(route, { workflowRevisionId: 'rev-join', revisionNo: 1, status: 'published', snapshot: {
        processes: [{ processId: 'n-a', processName: 'Mix' }, { processId: 'n-b', processName: 'Bake' },
          { processId: 'n-c', processName: 'Cool' }, { processId: 'n-j', processName: 'Gather', nodeType: 'join' }],
        connections: [] } })
    }
    if (pathname === '/api/flow-runs') return ok(route, [run])
    if (pathname === '/api/flow-runs/run-join') return ok(route, run)
    if (pathname === '/api/flow-runs/run-join/steps') return ok(route, steps())
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-join/runs?view=flow-runs')
  const gather = page.getByText('#4 Gather', { exact: true }).locator('..').locator('..')
  await expect(gather).toContainText('joins step #2 · waiting for 1 branch')
  await expect(gather.getByRole('button', { name: 'Start', exact: true })).toHaveCount(0)
  await expect(gather.getByRole('button', { name: 'Edit schedule', exact: true })).toHaveCount(0)
  await expect(gather).not.toContainText('from step #')

  joinState = { status: 'completed', joinWaiting: 0, arrivals: [...joinState.arrivals,
    { connectionId: 'c-cj', sourceStepId: 's-c', quantity: 3 }] }
  await page.reload()
  await expect(gather).toContainText('joins steps #2, #3')
  await expect(gather).not.toContainText('waiting for')
})
