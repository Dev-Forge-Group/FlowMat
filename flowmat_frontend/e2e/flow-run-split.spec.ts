import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * A graph Flow Run with a Split (docs/architecture/adr/ADR-008-quantity-split.md decision 11) against a mocked API: each
 * step a Split sent on says the share it received, and what its connection lost on the way
 * (docs/architecture/adr/ADR-009-connection-loss-rate.md decision 10); a step that came along an ordinary connection
 * says neither.
 */
test('each branch of a Split says the share it received and what its connection lost', async ({ page }) => {
  const run = { flowRunId: 'run-split', projectId: 'prj-split', workflowId: 'wf-split', workflowRevisionId: 'rev-split',
    productionRunId: null, runType: 'test', executionMode: 'graph', status: 'running', inputPayload: {},
    outputPayload: null, startedAt: '2030-01-01T00:00:00Z', endedAt: null, requestedBy: 'demo-owner' }
  const step = (stepId: string, nodeId: string, sequenceNo: number, status: string, extra: Record<string, unknown> = {}) => ({
    stepId, flowRunId: 'run-split', nodeId, sourceConnectionId: null, sourceStepId: null, status, sequenceNo, scheduledAt: null,
    startedAt: null, endedAt: null, inputSnapshot: {}, outputSnapshot: null, errorCode: null, errorMessage: null, ...extra })
  const steps = [
    step('s-a', 'n-a', 1, 'completed', { outputSnapshot: { quantity: 100, unit: 'kg' } }),
    step('s-s', 'n-s', 2, 'completed', { sourceStepId: 's-a', sourceConnectionId: 'c-as', inputSnapshot: { quantity: 100, unit: 'kg' },
      outputSnapshot: { quantity: 100, unit: 'kg' } }),
    step('s-b', 'n-b', 3, 'planned', { sourceStepId: 's-s', sourceConnectionId: 'c-sb', inputSnapshot: { quantity: 69.3, unit: 'kg' } }),
    step('s-c', 'n-c', 4, 'planned', { sourceStepId: 's-s', sourceConnectionId: 'c-sc', inputSnapshot: { quantity: 30, unit: 'kg' } }),
  ]

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') throw new Error(`Unexpected write ${request.method()} ${pathname}`)
    if (pathname === '/api/workflows') return ok(route, [{ workflowId: 'wf-split', projectId: 'prj-split', workflowName: 'Split flow' }])
    if (pathname === '/api/workflows/wf-split/revisions') {
      return ok(route, [{ workflowRevisionId: 'rev-split', workflowId: 'wf-split', revisionNo: 1, status: 'published',
        publishedAt: '2030-01-01T00:00:00Z' }])
    }
    if (pathname === '/api/workflows/wf-split/revisions/rev-split') {
      return ok(route, { workflowRevisionId: 'rev-split', revisionNo: 1, status: 'published', snapshot: {
        processes: [{ processId: 'n-a', processName: 'Mix' }, { processId: 'n-s', processName: 'Divide', nodeType: 'split' },
          { processId: 'n-b', processName: 'Bake' }, { processId: 'n-c', processName: 'Sample' }],
        connections: [{ connectionId: 'c-as', connectionLabel: 'to divide', splitKind: null },
          { connectionId: 'c-sb', connectionLabel: 'main', splitKind: 'share', splitValue: 70 },
          { connectionId: 'c-sc', connectionLabel: 'sample', splitKind: 'rest', splitValue: null }] } })
    }
    if (pathname === '/api/flow-runs') return ok(route, [run])
    if (pathname === '/api/flow-runs/run-split') return ok(route, run)
    if (pathname === '/api/flow-runs/run-split/steps') return ok(route, steps)
    if (pathname === '/api/flow-runs/run-split/events') {
      return ok(route, [{ eventId: 'e-loss', eventType: 'transfer_loss', stepId: 's-s', requestId: null,
        occurredAt: '2030-01-01T00:00:00Z', actorType: 'system', actorId: 'system',
        payload: { connectionId: 'c-sb', targetNodeId: 'n-b', sent: 70, lossRate: 0.01, lost: 0.7, arrived: 69.3, unit: 'kg' } }])
    }
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-split/runs?view=flow-runs')
  const card = (title: string) => page.getByText(title, { exact: true }).locator('..').locator('..')
  await expect(card('#3 Bake')).toContainText('via main')
  await expect(card('#3 Bake')).toContainText('received 69.3 kg · lost 0.7 kg')
  await expect(card('#4 Sample')).toContainText('received 30 kg')
  await expect(card('#4 Sample')).not.toContainText('lost')
  await expect(card('#2 Divide')).toContainText('via to divide')
  await expect(card('#2 Divide')).not.toContainText('received')
})
