import { expect, test, type Page, type Route } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

const projectId = 'prj-http-cached-access'
const workflowId = 'wf-http-cached-access'
const runId = 'run-http-cached-access'
const stepId = 'step-http-cached-access'
const privateOrigin = 'https://private-dispatch.example.com'
const privateOutput = 'customer-private-result-471'
const profile = { projectId, profileId: 'profile-private', profileVersionId: 'version-private-2', name: 'Private dispatch',
  enabled: true, version: 1, revision: 2, currentRevision: 2, baseOrigin: privateOrigin, credentialAlias: 'private-service-alias',
  operations: [{ operationId: 'send', method: 'POST', relativePath: '/private-jobs', successStatuses: [202] }],
  createdAt: '2026-10-25T00:00:00Z', updatedAt: '2026-10-25T00:00:00Z' }
const backupProfile = { ...profile, profileId: 'profile-backup', profileVersionId: 'version-backup-1', name: 'Backup dispatch',
  revision: 1, currentRevision: 1, baseOrigin: 'https://backup.example.com', credentialAlias: null }
const process = { processId: 'node-private-http', processName: 'Private HTTP step' }
const binding = { processId: process.processId, profileVersionId: profile.profileVersionId, operationId: 'send' }
const run = { flowRunId: runId, workflowRevisionId: 'revision-private-http', productionRunId: null,
  runType: 'actual', executionMode: 'graph', status: 'running', startedAt: '2026-10-25T00:00:00Z' }

async function denied(route: Route) {
  return route.fulfill({ status: 403, contentType: 'application/json', body: JSON.stringify({ success: false,
    data: null, message: 'Project read access was revoked.' }) })
}

async function setup(page: Page, withResult = false) {
  const state = { revoked: false, missingHistory: false, missingMetadata: false,
    deniedReads: 0, resultReads: 0, writes: [] as string[], previewReads: 0 }
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') {
      state.writes.push(`${request.method()} ${pathname}`)
      return route.fulfill({ status: 409, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: 'This scenario must never write.' }) })
    }
    const protectedRead = pathname.startsWith(`/api/projects/${projectId}/http-profiles`)
      || pathname.startsWith(`/api/flow-runs/${runId}/steps/${stepId}/`)
    if (state.revoked && protectedRead) { state.deniedReads += 1; return denied(route) }
    // The cached owner lookup intentionally remains unchanged: a denied resource read must itself hide its private data.
    if (pathname === '/api/project-members') return ok(route, [{ projectMemberId: 'member-private', projectId,
      userId: 'demo-owner', projectRole: 'owner', memberStatus: 'active' }])
    if (pathname === `/api/projects/${projectId}/http-profiles`) return ok(route, [profile, backupProfile])
    if (pathname.endsWith('/profile-private/versions')) {
      if (state.missingHistory) return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: 'Profile revision history no longer exists.' }) })
      return ok(route, [profile,
        { ...profile, profileVersionId: 'version-private-1', revision: 1, baseOrigin: 'https://previous-private.example.com' }])
    }
    if (pathname.endsWith(`/by-version/${profile.profileVersionId}/metadata`)) {
      if (state.missingMetadata) return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: 'Pinned HTTP profile is unavailable.' }) })
      return ok(route, profile)
    }
    if (pathname === '/api/workflows') return ok(route, [{ workflowId, projectId, workflowName: 'Private HTTP',
      workflowType: 'standard', workflowStatus: 'published' }])
    if (pathname.endsWith('/revisions')) return ok(route, [{ workflowRevisionId: run.workflowRevisionId, workflowId,
      revisionNo: 1, status: 'published' }])
    if (pathname.endsWith(`/revisions/${run.workflowRevisionId}`)) return ok(route,
      { snapshot: { processes: [process], nodeBindings: [binding] } })
    if (pathname === '/api/flow-runs') return ok(route, [run])
    if (pathname.endsWith(`/${runId}/steps`)) return ok(route,
      [{ stepId, nodeId: process.processId, sequenceNo: 1, status: 'running' }])
    if (pathname.endsWith(`/${stepId}/attempts`)) return ok(route, withResult
      ? [{ attemptId: 'attempt-private', attemptNo: 1, status: 'running' }] : [])
    if (pathname.endsWith(`/${stepId}/http-executions`)) {
      state.resultReads += 1
      return ok(route, withResult ? [{ executionId: 'execution-private',
        stepId, attemptId: 'attempt-private', requestId: 'request-private', transportStatus: 'SUCCEEDED',
        applyStatus: 'APPLY_FAILED', httpStatus: 202, errorCode: 'APPLY_FAILED', output: { reference: privateOutput },
        outputJson: JSON.stringify({ reference: privateOutput }), startedAt: '2026-10-25T00:00:00Z',
        completedAt: '2026-10-25T00:00:01Z' }] : [])
    }
    if (pathname.endsWith(`/${stepId}/http-request-preview`)) {
      state.previewReads += 1
      return ok(route, { profileVersionId: profile.profileVersionId, operationId: 'send', method: 'POST',
        target: `${privateOrigin}/private-jobs`, hasRequestBody: true, requestJson: '{"customer":"private-request-381"}' })
    }
    return ok(route, [])
  })
  await mockedLogin(page)
  return state
}

test('a denied profile revalidation hides cached destinations, history and an open editor without writing', async ({ page }) => {
  await page.clock.install()
  const state = await setup(page)
  await page.goto(`/projects/${projectId}/settings`)
  const panel = page.getByRole('region', { name: 'HTTP profiles', exact: true })
  await expect(panel).toContainText(privateOrigin)
  await panel.getByRole('button', { name: 'View revisions for Private dispatch' }).click()
  await expect(panel.getByRole('region', { name: 'Revision comparison' })).toContainText('previous-private.example.com')
  await panel.getByRole('button', { name: 'Edit Private dispatch', exact: true }).click()
  await expect(panel.getByLabel('Base origin', { exact: true })).toHaveValue(privateOrigin)
  state.revoked = true
  await page.clock.fastForward(31_000)
  await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')))
  await expect.poll(() => state.deniedReads).toBeGreaterThan(0)
  await expect(panel.getByRole('alert').first()).toContainText('Project read access was revoked.')
  await expect(panel).not.toContainText(privateOrigin)
  await expect(panel).not.toContainText('private-service-alias')
  await expect(panel).not.toContainText('previous-private.example.com')
  await expect(panel.getByRole('region', { name: 'Revision comparison' })).toHaveCount(0)
  await expect(panel.getByRole('form', { name: 'Edit HTTP profile' })).toHaveCount(0)
  await expect(panel.getByRole('button', { name: /^(Add HTTP profile|Edit Private dispatch|Disable Private dispatch|Remove Private dispatch)$/ })).toHaveCount(0)
  state.revoked = false
  await panel.getByRole('button', { name: 'Refresh HTTP profiles', exact: true }).click()
  await expect(panel.getByRole('alert')).toHaveCount(0)
  await expect(panel).toContainText(privateOrigin)
  await expect(panel.getByRole('form', { name: 'Edit HTTP profile' })).toHaveCount(0)
  await expect(panel.getByRole('button', { name: 'Edit Private dispatch', exact: true })).toBeEnabled()
  expect(state.writes).toEqual([])
})

test('a denied saved-result refresh hides cached mapped output, request preview and apply action', async ({ page }) => {
  const state = await setup(page, true)
  await page.goto(`/projects/${projectId}/runs?view=flow-runs`)
  const panel = page.getByRole('region', { name: 'HTTP execution for step 1' })
  await panel.getByText('Mapped output', { exact: true }).click()
  await expect(panel).toContainText(privateOutput)
  await expect(panel.getByRole('button', { name: 'Apply saved result', exact: true })).toBeEnabled()
  await panel.getByRole('button', { name: 'Preview HTTP request', exact: true }).click()
  await expect(panel.getByLabel('HTTP request preview JSON')).toContainText('private-request-381')
  state.revoked = true
  await panel.getByRole('button', { name: 'Refresh saved results' }).click()
  await expect(panel.getByRole('alert').first()).toContainText('Project read access was revoked.')
  await expect(panel).not.toContainText(privateOutput)
  await expect(panel).not.toContainText(privateOrigin)
  await expect(panel).not.toContainText('request-private')
  await expect(panel).not.toContainText('private-request-381')
  await expect(panel.getByRole('button', { name: /^(Apply saved result|Preview HTTP request)$/ })).toHaveCount(0)
  expect(state.deniedReads).toBeGreaterThanOrEqual(3)
  expect(state.previewReads).toBe(1)
  state.revoked = false
  await panel.getByRole('button', { name: 'Refresh saved results' }).click()
  await expect(panel.getByRole('alert')).toHaveCount(0)
  await expect(panel).toContainText(privateOutput)
  await expect(panel.getByRole('button', { name: 'Apply saved result', exact: true })).toBeEnabled()
  await expect(panel.getByLabel('HTTP request preview JSON')).toHaveCount(0)
  expect(state.writes).toEqual([])
})

test('a denied refresh removes a confirmed send and its cached pinned destination without executing', async ({ page }) => {
  const state = await setup(page)
  await page.goto(`/projects/${projectId}/runs?view=flow-runs`)
  const panel = page.getByRole('region', { name: 'HTTP execution for step 1' })
  await expect(panel).toContainText(`${privateOrigin}/private-jobs`)
  await panel.getByLabel('I understand this sends an external HTTP request').check()
  await expect(panel.getByRole('button', { name: 'Execute HTTP request', exact: true })).toBeEnabled()
  state.revoked = true
  await panel.getByRole('button', { name: 'Refresh saved results' }).click()
  await expect(panel.getByRole('alert').first()).toContainText('Project read access was revoked.')
  await expect(panel).not.toContainText(privateOrigin)
  await expect(panel.getByLabel('I understand this sends an external HTTP request')).toHaveCount(0)
  await expect(panel.getByRole('button', { name: /^(Execute HTTP request|Retry same request|Preview HTTP request)$/ })).toHaveCount(0)
  expect(state.deniedReads).toBeGreaterThanOrEqual(3)
  expect(state.previewReads).toBe(0)
  state.revoked = false
  await panel.getByRole('button', { name: 'Refresh saved results' }).click()
  await expect(panel.getByRole('alert')).toHaveCount(0)
  await expect(panel).toContainText(`${privateOrigin}/private-jobs`)
  await expect(panel.getByLabel('I understand this sends an external HTTP request')).not.toBeChecked()
  await expect(panel.getByRole('button', { name: 'Execute HTTP request', exact: true })).toBeDisabled()
  expect(state.writes).toEqual([])
})

test('a missing history resource does not hide another readable profile or prevent opening its editor', async ({ page }) => {
  const state = await setup(page)
  state.missingHistory = true
  await page.goto(`/projects/${projectId}/settings`)
  const panel = page.getByRole('region', { name: 'HTTP profiles', exact: true })
  await expect(panel.getByRole('button', { name: 'Edit Backup dispatch', exact: true })).toBeEnabled()
  await panel.getByRole('button', { name: 'View revisions for Private dispatch' }).click()
  await expect(panel.getByRole('alert').first()).toContainText('Profile revision history no longer exists.')
  await expect(panel).toContainText(backupProfile.baseOrigin)
  await expect(panel.getByRole('button', { name: 'Add HTTP profile', exact: true })).toBeEnabled()
  await panel.getByRole('button', { name: 'Edit Backup dispatch', exact: true }).click()
  await expect(panel.getByLabel('Profile name', { exact: true })).toHaveValue(backupProfile.name)
  await expect(panel.getByLabel('Base origin', { exact: true })).toHaveValue(backupProfile.baseOrigin)
  expect(state.deniedReads).toBe(0)
  expect(state.writes).toEqual([])
})

test('a missing pinned profile blocks another send but preserves readable saved output and its apply action', async ({ page }) => {
  const state = await setup(page, true)
  state.missingMetadata = true
  await page.goto(`/projects/${projectId}/runs?view=flow-runs`)
  const panel = page.getByRole('region', { name: 'HTTP execution for step 1' })
  await expect.poll(() => state.resultReads).toBeGreaterThan(0)
  await expect(panel.getByRole('alert').first()).toContainText('Pinned HTTP profile is unavailable.')
  await expect(panel).toContainText(privateOutput)
  await expect(panel.getByRole('button', { name: 'Apply saved result', exact: true })).toBeEnabled()
  await expect(panel.getByRole('button', { name: /^(Execute HTTP request|Execute next attempt|Retry same request)$/ })).toHaveCount(0)
  await expect(panel.getByLabel('I understand this sends an external HTTP request')).toHaveCount(0)
  expect(state.deniedReads).toBe(0)
  expect(state.writes).toEqual([])
})
