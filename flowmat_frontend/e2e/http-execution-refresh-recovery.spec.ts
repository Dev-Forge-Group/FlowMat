import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

const projectId = 'prj-http-recovery'
const workflowId = 'wf-http-recovery'
const runId = 'run-http-recovery'
const stepId = 'step-http-recovery'
const storageKey = `flowmat.http-request:${projectId}:${runId}:${stepId}`
const storedRequestId = '714f095a-ec95-4193-b029-094b06584911'
const operation = { operationId: 'send', method: 'POST', relativePath: '/jobs', successStatuses: [202] }
const profile = { profileId: 'profile-recovery', profileVersionId: 'profile-recovery-v1', revision: 1,
  baseOrigin: 'https://api.example.com', credentialAlias: null, operations: [operation] }
const process = { processId: 'node-recovery', processName: 'Recovery step' }
const binding = { processId: process.processId, profileVersionId: profile.profileVersionId, operationId: operation.operationId }
const run = { flowRunId: runId, workflowRevisionId: 'revision-recovery', productionRunId: null,
  runType: 'actual', executionMode: 'graph', status: 'running', startedAt: '2026-10-24T00:00:00Z' }
const currentAttempt = { attemptId: 'attempt-current', attemptNo: 1, status: 'running' }

type ReadKind = 'attempts' | 'metadata' | 'results'

async function setup(page: Page, failure?: 'attempts' | 'metadata', lateResult = false) {
  const state = { healthy: !failure, hold: false, reads: { attempts: 0, metadata: 0, results: 0 },
    writes: [] as string[], previewReads: 0 }
  let release!: () => void
  const held = new Promise<void>((resolve) => { release = resolve })
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') {
      state.writes.push(pathname)
      return route.fulfill({ status: 409, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: 'This read-only recovery scenario must not send.' }) })
    }
    if (pathname.endsWith('/http-request-preview')) { state.previewReads += 1; return ok(route, null) }
    if (pathname === '/api/project-members') return ok(route, [{ projectMemberId: 'member-recovery', projectId,
      userId: 'demo-owner', projectRole: 'owner', memberStatus: 'active' }])
    if (pathname === '/api/workflows') return ok(route, [{ workflowId, projectId, workflowName: 'HTTP recovery',
      workflowType: 'standard', workflowStatus: 'published' }])
    if (pathname.endsWith('/revisions')) return ok(route, [{ workflowRevisionId: run.workflowRevisionId, workflowId,
      revisionNo: 1, status: 'published' }])
    if (pathname.endsWith(`/revisions/${run.workflowRevisionId}`)) return ok(route,
      { snapshot: { processes: [process], nodeBindings: [binding] } })
    if (pathname === '/api/flow-runs') return ok(route, [run])
    if (pathname.endsWith(`/${runId}/steps`)) return ok(route,
      [{ stepId, nodeId: process.processId, sequenceNo: 1, status: 'running' }])
    const kind: ReadKind | null = pathname.endsWith(`/${stepId}/attempts`) ? 'attempts'
      : pathname.endsWith(`/by-version/${profile.profileVersionId}/metadata`) ? 'metadata'
        : pathname.endsWith(`/${stepId}/http-executions`) ? 'results' : null
    if (kind) {
      state.reads[kind] += 1
      if (state.hold) await held
      if (failure === kind && !state.healthy) return route.fulfill({ status: 503, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `${kind} temporarily unavailable.` }) })
      if (kind === 'metadata') return ok(route, profile)
      if (kind === 'attempts') return ok(route, lateResult
        ? [{ attemptId: 'attempt-before-timeout', attemptNo: 1, status: 'failed' }, { ...currentAttempt, attemptNo: 2 }]
        : [currentAttempt])
      return ok(route, lateResult ? [{ executionId: 'late-execution', stepId, attemptId: 'attempt-before-timeout',
        requestId: storedRequestId, transportStatus: 'SUCCEEDED', applyStatus: 'LATE', httpStatus: 202, errorCode: null,
        output: { ok: true }, outputJson: '{"ok":true}', startedAt: '2026-10-24T00:00:00Z', completedAt: '2026-10-24T00:00:01Z' }] : [])
    }
    return ok(route, [])
  })
  await mockedLogin(page)
  if (failure === 'attempts') {
    await page.evaluate(({ key, requestId }) => window.sessionStorage.setItem(key, requestId),
      { key: storageKey, requestId: storedRequestId })
  }
  await page.goto(`/projects/${projectId}/runs?view=flow-runs`)
  return { state, release }
}

for (const failure of ['attempts', 'metadata'] as const) {
  test(`refresh recovers ${failure} after a transient error with unchanged empty HTTP results`, async ({ page }) => {
    const { state, release } = await setup(page, failure)
    const panel = page.getByRole('region', { name: 'HTTP execution for step 1' })
    const refresh = panel.getByRole('button', { name: 'Refresh saved results' })
    // Wait for the query's normal retry to fail; the test does not depend on an exact retry count.
    await expect(panel.getByRole('alert')).toContainText(`${failure} temporarily unavailable.`)
    await expect(refresh).toBeEnabled()
    await expect(panel.getByRole('button', { name: /^(Execute HTTP request|Retry same request)$/ })).toHaveCount(0)
    const before = { ...state.reads }
    state.healthy = true
    state.hold = true
    try {
      await refresh.click()
      await expect(refresh).toBeDisabled()
      await expect.poll(() => state.reads.results).toBeGreaterThan(before.results)
      release()
      state.hold = false
      await expect(panel.getByRole('alert')).toHaveCount(0)
      await expect.poll(() => state.reads.attempts).toBeGreaterThan(before.attempts)
      await expect.poll(() => state.reads.metadata).toBeGreaterThan(before.metadata)
      const checkbox = panel.getByLabel('I understand this sends an external HTTP request')
      await expect(checkbox).not.toBeChecked()
      const send = panel.getByRole('button', { name: failure === 'attempts' ? 'Retry same request' : 'Execute HTTP request', exact: true })
      await expect(send).toBeDisabled()
      await checkbox.check()
      await expect(send).toBeEnabled()
      await expect(refresh).toBeEnabled()
      expect(state.writes).toEqual([])
      expect(state.previewReads).toBe(0)
      expect(await page.evaluate((key) => window.sessionStorage.getItem(key), storageKey))
        .toBe(failure === 'attempts' ? storedRequestId : null)
    } finally { release() }
  })
}

test('a late transmitted result blocks another send even when timeout opened a new running attempt', async ({ page }) => {
  const { state } = await setup(page, undefined, true)
  const panel = page.getByRole('region', { name: 'HTTP execution for step 1' })
  await expect(panel).toContainText('recorded after the step ended')
  await expect(panel).toContainText('Another request is blocked; check the recorded result.')
  await expect(panel.getByRole('button', { name: /^(Execute HTTP request|Execute next attempt|Retry same request)$/ })).toHaveCount(0)
  await expect(panel.getByLabel('I understand this sends an external HTTP request')).toHaveCount(0)
  await expect(panel.getByRole('button', { name: 'Apply saved result' })).toHaveCount(0)
  expect(state.writes).toEqual([])
  expect(state.previewReads).toBe(0)
})
