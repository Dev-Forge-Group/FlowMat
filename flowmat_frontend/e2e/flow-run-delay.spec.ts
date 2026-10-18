import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * A graph Flow Run with a connection delay (docs/architecture/adr/ADR-010-connection-delay.md decision 9) against a mocked
 * API: a step the delay scheduled says so next to its time; one somebody rescheduled since does not.
 */
test('a step a connection delayed says how long next to its time', async ({ page }) => {
  const run = { flowRunId: 'run-delay', projectId: 'prj-delay', workflowId: 'wf-delay', workflowRevisionId: 'rev-delay',
    productionRunId: null, runType: 'test', executionMode: 'graph', status: 'running', inputPayload: {},
    outputPayload: null, startedAt: '2030-01-01T00:00:00Z', endedAt: null, requestedBy: 'demo-owner' }
  const step = (stepId: string, nodeId: string, sequenceNo: number, status: string, extra: Record<string, unknown> = {}) => ({
    stepId, flowRunId: 'run-delay', nodeId, sourceConnectionId: null, sourceStepId: null, status, sequenceNo, scheduledAt: null,
    startedAt: null, endedAt: null, inputSnapshot: {}, outputSnapshot: null, errorCode: null, errorMessage: null, ...extra })
  const steps = [
    step('s-a', 'n-a', 1, 'completed', { endedAt: '2030-01-01T09:00:00Z' }),
    step('s-b', 'n-b', 2, 'planned', { sourceStepId: 's-a', sourceConnectionId: 'c-ab', scheduledAt: '2030-01-01T11:00:00Z' }),
    step('s-c', 'n-c', 3, 'planned', { sourceStepId: 's-a', sourceConnectionId: 'c-ac', scheduledAt: '2030-01-01T12:00:00Z' }),
  ]
  const scheduled = (eventId: string, stepId: string, payload: Record<string, unknown>) => ({ eventId, eventType: 'step_scheduled',
    stepId, requestId: null, occurredAt: '2030-01-01T09:00:00Z', actorType: 'system', actorId: 'system', payload })
  const events = [
    scheduled('e-b', 's-b', { scheduledAt: '2030-01-01T11:00:00Z', reason: 'connection_delay', connectionId: 'c-ab', delaySeconds: 7200 }),
    scheduled('e-c', 's-c', { scheduledAt: '2030-01-01T09:30:00Z', reason: 'connection_delay', connectionId: 'c-ac', delaySeconds: 1800 }),
    scheduled('e-c2', 's-c', { scheduledAt: '2030-01-01T12:00:00Z' }),
  ]

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') throw new Error(`Unexpected write ${request.method()} ${pathname}`)
    if (pathname === '/api/workflows') return ok(route, [{ workflowId: 'wf-delay', projectId: 'prj-delay', workflowName: 'Delay flow' }])
    if (pathname === '/api/workflows/wf-delay/revisions') {
      return ok(route, [{ workflowRevisionId: 'rev-delay', workflowId: 'wf-delay', revisionNo: 1, status: 'published',
        publishedAt: '2030-01-01T00:00:00Z' }])
    }
    if (pathname === '/api/workflows/wf-delay/revisions/rev-delay') {
      return ok(route, { workflowRevisionId: 'rev-delay', revisionNo: 1, status: 'published', snapshot: {
        processes: [{ processId: 'n-a', processName: 'Mix' }, { processId: 'n-b', processName: 'Cure' },
          { processId: 'n-c', processName: 'Pack' }],
        connections: [{ connectionId: 'c-ab', connectionLabel: 'to cure' }, { connectionId: 'c-ac', connectionLabel: 'to pack' }] } })
    }
    if (pathname === '/api/flow-runs') return ok(route, [run])
    if (pathname === '/api/flow-runs/run-delay') return ok(route, run)
    if (pathname === '/api/flow-runs/run-delay/steps') return ok(route, steps)
    if (pathname === '/api/flow-runs/run-delay/events') return ok(route, events)
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-delay/runs?view=flow-runs')
  const card = (title: string) => page.getByText(title, { exact: true }).locator('..').locator('..')
  await expect(card('#2 Cure')).toContainText('Scheduled:')
  await expect(card('#2 Cure')).toContainText('after a 2 h delay')
  await expect(card('#3 Pack')).toContainText('Scheduled:')
  await expect(card('#3 Pack')).not.toContainText('delay')
})
