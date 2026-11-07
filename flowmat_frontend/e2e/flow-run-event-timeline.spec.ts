import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * A graph Flow Run's events on one time axis on request, against a mocked API (archify's timeline): a lane for the run and
 * one for each step, a failure and a retry marked by shape, the two quiet days before the second step folded, and the
 * event filter deciding what is placed.
 */
// The browser is in New York and the project in Seoul: the axis follows the project.
test.use({ timezoneId: 'America/New_York' })

test('the event log shows its events on a timeline with folded quiet stretches', async ({ page }) => {
  const run = { flowRunId: 'run-tl', projectId: 'prj-tl', workflowId: 'wf-tl', workflowRevisionId: 'rev-tl',
    productionRunId: null, runType: 'test', executionMode: 'graph', status: 'running', inputPayload: {},
    outputPayload: null, startedAt: '2030-01-01T09:00:00Z', endedAt: null, requestedBy: 'demo-owner' }
  const step = (stepId: string, nodeId: string, sequenceNo: number, status: string) => ({ stepId, flowRunId: 'run-tl', nodeId,
    sourceConnectionId: null, sourceStepId: null, status, sequenceNo, scheduledAt: null, startedAt: null, endedAt: null,
    inputSnapshot: {}, outputSnapshot: null, errorCode: null, errorMessage: null })
  const steps = [step('s-mix', 'node-mix', 1, 'completed'), step('s-bake', 'node-bake', 2, 'running')]
  const event = (eventId: string, eventType: string, stepId: string | null, occurredAt: string) => ({ eventId, eventType, stepId,
    payload: {}, requestId: null, occurredAt, actorType: 'user', actorId: 'demo-owner' })
  const events = [
    event('e1', 'run_started', null, '2030-01-01T09:00:00Z'), event('e2', 'step_started', 's-mix', '2030-01-01T09:00:10Z'),
    event('e3', 'step_failed', 's-mix', '2030-01-01T09:00:20Z'), event('e4', 'step_retry_scheduled', 's-mix', '2030-01-01T09:00:30Z'),
    event('e5', 'step_started', 's-bake', '2030-01-03T09:00:30Z'),
  ]

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') throw new Error(`Unexpected write ${request.method()} ${pathname}`)
    if (pathname === '/api/workflows') return ok(route, [{ workflowId: 'wf-tl', projectId: 'prj-tl', workflowName: 'Timeline flow' }])
    if (pathname === '/api/workflows/wf-tl/revisions') {
      return ok(route, [{ workflowRevisionId: 'rev-tl', workflowId: 'wf-tl', revisionNo: 1, status: 'published',
        publishedAt: '2030-01-01T00:00:00Z' }])
    }
    if (pathname === '/api/workflows/wf-tl/revisions/rev-tl') {
      return ok(route, { workflowRevisionId: 'rev-tl', revisionNo: 1, status: 'published', snapshot: {
        processes: [{ processId: 'node-mix', processName: 'Mix' }, { processId: 'node-bake', processName: 'Bake' }],
        connections: [], nodePolicies: [] } })
    }
    if (pathname === '/api/flow-runs') return ok(route, [run])
    if (pathname === '/api/flow-runs/run-tl') return ok(route, run)
    if (pathname === '/api/flow-runs/run-tl/steps') return ok(route, steps)
    if (pathname === '/api/flow-runs/run-tl/events') return ok(route, events)
    if (pathname === '/api/projects/prj-tl/time-zone') return ok(route, { projectId: 'prj-tl', timeZone: 'Asia/Seoul', version: 0 })
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-tl/runs?view=flow-runs')
  const timeline = page.getByRole('region', { name: 'Event timeline' })
  await timeline.getByRole('button', { name: 'Show event timeline' }).click({ timeout: 15_000 })

  await expect(timeline.getByRole('rowheader')).toHaveText(['Run', 'Step 1 · Mix', 'Step 2 · Bake'])
  await expect(timeline.getByRole('row').filter({ hasText: 'Step 1 · Mix' })).toContainText('3')
  await expect(timeline.locator('.event-timeline__mark--alert')).toHaveCount(1)
  await expect(timeline.locator('.event-timeline__mark--recovery')).toHaveCount(1)
  // The two quiet days before Bake starts are folded, with their real length.
  await expect(timeline.locator('.event-timeline__fold-label')).toHaveText(['≈ 2 d'])
  // The axis starts at the first event, 09:00 UTC, written in the project's zone rather than the browser's.
  const [seoul, newYork] = await page.evaluate(() => ['Asia/Seoul', undefined].map((timeZone) => new Date(Date.UTC(2030, 0, 1, 9))
    .toLocaleString(undefined, timeZone ? { timeZone } : {})))
  expect(seoul).not.toBe(newYork)
  await expect(timeline.locator('.run-timing__axis > span').first()).toHaveText(seoul)
  await expect(timeline).toContainText('Times in Asia/Seoul.')

  // The log's step filter decides what is placed.
  await page.getByLabel('Event step').selectOption({ label: 'Step 2 · Bake' })
  await expect(timeline.getByRole('rowheader')).toHaveText(['Step 2 · Bake'])
  await expect(timeline.locator('.event-timeline__fold-label')).toHaveCount(0)

  await timeline.getByRole('button', { name: 'Hide event timeline' }).click()
  await expect(timeline.getByRole('table')).toHaveCount(0)
})
