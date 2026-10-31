import { expect, test, type Page, type Route } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

const projectId = 'prj-http-review'
const otherProjectId = 'prj-http-review-other'
const workflowId = 'wf-http-review-first'
const targetWorkflowId = 'wf-http-review-target'
const targetRunId = 'run-http-review-target'
const targetStepId = 'step-http-review-target'
const title = 'HTTP requests needing review'
const hiddenValue = 'private-http-payload-must-not-appear'
const cursor = 'next/+?=& opaque cursor'

type ReviewItem = {
  executionId: string; flowRunId: string; workflowId: string; runStatus: string; stepId: string; nodeId: string
  stepStatus: string; attemptId: string; operationId: string; transportStatus: string; applyStatus: string
  httpStatus: number | null; errorCode: string | null; startedAt: string; completedAt: string | null
}
type ReviewPage = { items: ReviewItem[]; nextCursor: string | null }
type Reply = ReviewPage | { status: number; message: string }

function item(operationId: string, transportStatus = 'PENDING', applyStatus = 'PENDING'): ReviewItem {
  return { executionId: `execution-${operationId}`, flowRunId: targetRunId, workflowId: targetWorkflowId,
    runStatus: 'running', stepId: targetStepId, nodeId: 'node-target', stepStatus: 'running',
    attemptId: `attempt-${operationId}`, operationId, transportStatus, applyStatus,
    httpStatus: transportStatus === 'SUCCEEDED' ? 202 : null, errorCode: applyStatus === 'APPLY_FAILED' ? 'LOCAL_APPLY_FAILED' : null,
    startedAt: '2026-10-27T10:00:00Z', completedAt: transportStatus === 'PENDING' ? null : '2026-10-27T10:00:01Z' }
}

function run(flowRunId: string, revision: string) {
  return { flowRunId, workflowRevisionId: revision, productionRunId: null, runType: 'actual',
    executionMode: 'graph', status: 'running', inputPayload: null, outputPayload: null,
    startedAt: '2026-10-27T10:00:00Z', endedAt: null }
}

async function fixture(page: Page, reply: (url: URL) => Reply | Promise<Reply>, role = 'viewer',
  start?: (route: Route) => Promise<void>, revision?: (route: Route, pathname: string) => Promise<boolean>,
  readRuns?: (workflowId: string | null) => ReturnType<typeof run>[]) {
  const state = { reads: [] as URL[], writes: [] as string[], attempts: [] as string[], previews: 0 }
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route: Route) => {
    const request = route.request()
    const url = new URL(request.url())
    const { pathname } = url
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') {
      state.writes.push(`${request.method()} ${pathname}`)
      if (pathname === '/api/flow-runs' && request.method() === 'POST' && start) return start(route)
      return route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ success: false,
        data: null, message: 'Reviewing HTTP evidence must not change any data.' }) })
    }
    if (/\/projects\/[^/]+\/http-executions\/attention$/.test(pathname)) {
      state.reads.push(url)
      const response = await reply(url)
      if ('status' in response) return route.fulfill({ status: response.status, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: response.message }) })
      return ok(route, response)
    }
    if (pathname === '/api/projects') return ok(route, [
      { projectId, projectName: 'HTTP review project', projectStatus: 'active' },
      { projectId: otherProjectId, projectName: 'Other HTTP review project', projectStatus: 'active' },
    ])
    if (pathname === '/api/project-members') return ok(route, [{ projectMemberId: 'member-review',
      projectId: url.searchParams.get('projectId') ?? projectId, userId: 'demo-owner', projectRole: role, memberStatus: 'active' }])
    if (pathname === '/api/workflows') {
      const requestedProject = url.searchParams.get('projectId') ?? projectId
      return ok(route, requestedProject === otherProjectId ? [{ workflowId: 'wf-review-other', projectId: otherProjectId,
        workflowName: 'Other workflow', workflowType: 'standard', workflowStatus: 'published' }] : [
        { workflowId, projectId, workflowName: 'First workflow', workflowType: 'standard', workflowStatus: 'published' },
        { workflowId: targetWorkflowId, projectId, workflowName: 'Target HTTP workflow', workflowType: 'standard', workflowStatus: 'published' },
      ])
    }
    if (pathname === '/api/flow-runs') return ok(route, readRuns ? readRuns(url.searchParams.get('workflowId'))
      : url.searchParams.get('workflowId') === targetWorkflowId
        ? [run('run-http-review-before', 'revision-before'), run(targetRunId, 'revision-target')]
        : [run('run-http-review-first', 'revision-first')])
    if (pathname.endsWith('/revisions')) return ok(route, [
      { workflowRevisionId: 'revision-first', revisionNo: 1, status: 'published' },
      { workflowRevisionId: 'revision-before', revisionNo: 2, status: 'published' },
      { workflowRevisionId: 'revision-target', revisionNo: 3, status: 'published' },
    ])
    if (/\/revisions\/[^/]+$/.test(pathname) && revision && await revision(route, pathname)) return
    if (/\/revisions\/[^/]+$/.test(pathname)) return ok(route, { snapshot: { processes: [
      { processId: 'node-first', processName: 'First process' },
      { processId: 'node-target', processName: 'Target HTTP step' },
    ], nodeBindings: [{ processId: 'node-target', profileVersionId: 'profile-review-v1', operationId: 'target-operation' }] } })
    if (pathname.endsWith(`/${targetRunId}/steps`)) return ok(route, [
      { stepId: 'step-http-review-before', nodeId: 'node-first', sequenceNo: 1, status: 'completed', inputSnapshot: null },
      { stepId: targetStepId, nodeId: 'node-target', sequenceNo: 2, status: 'running', inputSnapshot: { selectedStep: true } },
    ])
    if (pathname.endsWith('/steps')) return ok(route, [])
    if (pathname.endsWith('/attempts')) {
      state.attempts.push(pathname)
      return ok(route, [{ attemptId: 'attempt-target', attemptNo: 2, status: 'running' }])
    }
    if (pathname.endsWith('/lineage')) return ok(route, { step: { stepId: targetStepId }, ancestors: [], descendants: [] })
    if (pathname.endsWith('/http-request-preview')) { state.previews += 1; return ok(route, null) }
    if (pathname.endsWith('/http-executions')) return ok(route, [])
    if (pathname.endsWith('/by-version/profile-review-v1/metadata')) return ok(route, {
      profileId: 'profile-review', profileVersionId: 'profile-review-v1', revision: 1,
      baseOrigin: 'https://api.example.com', credentialAlias: null,
      operations: [{ operationId: 'target-operation', method: 'POST', relativePath: '/jobs', successStatuses: [202] }],
    })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/runs?view=flow-runs`)
  const panel = page.getByRole('region', { name: title, exact: true })
  return { state, panel }
}

test('a viewer opens a read-only project review list with pending, unknown, apply-failed and late results on a narrow screen', async ({ page }) => {
  const items = [item('pending-operation'), item('unknown-operation', 'OUTCOME_UNKNOWN'),
    item('apply-failed-operation', 'SUCCEEDED', 'APPLY_FAILED'), item('late-operation', 'SUCCEEDED', 'LATE')]
  const { state, panel } = await fixture(page, () => ({ items: items.map((entry) => ({ ...entry,
    requestJson: hiddenValue, outputJson: hiddenValue, credentialAlias: hiddenValue })), nextCursor: null }))
  await expect(panel.getByRole('button', { name: 'Open review list', exact: true })).toBeVisible()
  expect(state.reads).toHaveLength(0)
  await panel.getByRole('button', { name: 'Open review list', exact: true }).click()
  await expect(panel.getByRole('link', { name: 'Open run', exact: true })).toHaveCount(4)
  for (const entry of items) await expect(panel.getByRole('row').filter({ hasText: entry.operationId })).toBeVisible()
  expect(state.reads[0].pathname).toBe(`/api/projects/${projectId}/http-executions/attention`)
  expect(state.reads[0].searchParams.get('limit')).toBe('25')
  expect(state.reads[0].searchParams.get('state')).toBe('ALL')
  await expect(panel).not.toContainText(hiddenValue)
  await expect(panel.getByRole('button', { name: /Execute|Apply saved result|Retry same request/ })).toHaveCount(0)
  await page.setViewportSize({ width: 375, height: 812 })
  const bounds = await panel.boundingBox()
  expect(bounds).not.toBeNull()
  expect(bounds!.x).toBeGreaterThanOrEqual(0)
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(375)
  expect(state.writes).toEqual([])
  expect(state.previews).toBe(0)
})

test('review paging preserves opaque cursors and filtering or refreshing starts again at the first page', async ({ page }) => {
  let empty = false
  const { state, panel } = await fixture(page, (url) => {
    if (empty) return { items: [], nextCursor: null }
    if (url.searchParams.get('state') === 'OUTCOME_UNKNOWN') return { items: [item('filtered-unknown', 'OUTCOME_UNKNOWN')], nextCursor: null }
    return url.searchParams.has('cursor') ? { items: [item('second-page')], nextCursor: null }
      : { items: [item('first-page')], nextCursor: cursor }
  })
  await panel.getByRole('button', { name: 'Open review list', exact: true }).click()
  await expect(panel).toContainText('first-page')
  await expect(panel.getByRole('button', { name: 'Back', exact: true })).toBeDisabled()
  await panel.getByRole('button', { name: 'Next', exact: true }).click()
  await expect(panel).toContainText('second-page')
  expect(state.reads[state.reads.length - 1].searchParams.get('cursor')).toBe(cursor)
  await expect(panel).not.toContainText('first-page')
  await panel.getByRole('button', { name: 'Back', exact: true }).click()
  await expect(panel).toContainText('first-page')
  await panel.getByRole('button', { name: 'Next', exact: true }).click()
  await expect(panel).toContainText('second-page')
  await panel.getByRole('combobox', { name: 'HTTP review state', exact: true }).selectOption('OUTCOME_UNKNOWN')
  await expect(panel).toContainText('filtered-unknown')
  expect(state.reads[state.reads.length - 1].searchParams.has('cursor')).toBe(false)
  await expect(panel.getByRole('button', { name: 'Back', exact: true })).toBeDisabled()
  empty = true
  await panel.getByRole('button', { name: 'Refresh review list', exact: true }).click()
  await expect(panel.getByRole('link', { name: 'Open run', exact: true })).toHaveCount(0)
  await expect(panel).not.toContainText('filtered-unknown')
  await expect(panel.getByRole('button', { name: 'Next', exact: true })).toBeDisabled()
  expect(state.writes).toEqual([])
})

for (const status of [403, 503]) {
  test(`a refused review refresh (${status}) removes cached evidence and can recover without a write`, async ({ page }) => {
    let refused = false
    let recovered = false
    const { state, panel } = await fixture(page, () => refused ? { status, message: 'HTTP review access is unavailable.' }
      : { items: [item(recovered ? 'recovered-operation' : 'previous-operation')], nextCursor: null })
    await panel.getByRole('button', { name: 'Open review list', exact: true }).click()
    await expect(panel).toContainText('previous-operation')
    refused = true
    await panel.getByRole('button', { name: 'Refresh review list', exact: true }).click()
    await expect(panel.getByRole('alert')).toContainText('HTTP review access is unavailable.')
    await expect(panel.getByRole('link', { name: 'Open run', exact: true })).toHaveCount(0)
    await expect(panel).not.toContainText('previous-operation')
    refused = false
    recovered = true
    await panel.getByRole('button', { name: 'Refresh review list', exact: true }).click()
    await expect(panel).toContainText('recovered-operation')
    await expect(panel.getByRole('alert')).toHaveCount(0)
    expect(state.writes).toEqual([])
  })
}

test('opening review evidence selects its workflow, non-first run and step rather than the first available execution', async ({ page }) => {
  const { state, panel } = await fixture(page, () => ({ items: [item('target-operation')], nextCursor: null }))
  await panel.getByRole('button', { name: 'Open review list', exact: true }).click()
  const link = panel.getByRole('link', { name: 'Open run', exact: true })
  const target = new URL(await link.getAttribute('href') as string, page.url())
  expect(target.pathname).toBe(`/projects/${projectId}/runs`)
  expect(Object.fromEntries(target.searchParams)).toMatchObject({ view: 'flow-runs', workflowId: targetWorkflowId,
    flowRunId: targetRunId, stepId: targetStepId })
  await link.click()
  await expect(page.getByRole('combobox', { name: 'Workflow', exact: true })).toHaveValue(targetWorkflowId)
  await expect(page.getByRole('combobox', { name: 'Flow Run', exact: true })).toHaveValue(targetRunId)
  const card = page.getByRole('group', { name: 'Flow step 2', exact: true })
  await expect(card.getByRole('button', { name: 'Hide attempts', exact: true })).toBeVisible()
  await expect(card).toContainText('Attempt 2: running')
  expect(state.attempts.some((path) => path.endsWith(`/${targetStepId}/attempts`))).toBe(true)
  expect(state.writes).toEqual([])
  expect(state.previews).toBe(0)
})

test('a review item from a workflow absent from the current selector remains visible with its identifiers', async ({ page }) => {
  const evidence = { ...item('historical-operation', 'SUCCEEDED', 'LATE'), workflowId: 'wf-review-archived',
    flowRunId: 'run-review-archived', stepId: 'step-review-archived' }
  const { state, panel } = await fixture(page, () => ({ items: [evidence], nextCursor: null }))
  await panel.getByRole('button', { name: 'Open review list', exact: true }).click()
  const row = panel.getByRole('row').filter({ hasText: evidence.operationId })
  await expect(row).toContainText(evidence.workflowId)
  await expect(row).toContainText(evidence.flowRunId)
  await expect(row).toContainText(evidence.stepId)
  await expect(row.getByRole('link', { name: 'Open run', exact: true })).toBeVisible()
  expect(state.writes).toEqual([])
})

test('an unavailable requested run does not silently select the first run in the workflow', async ({ page }) => {
  const { state } = await fixture(page, () => ({ items: [], nextCursor: null }))
  await page.goto(`/projects/${projectId}/runs?view=flow-runs&workflowId=${targetWorkflowId}&flowRunId=run-no-longer-readable&stepId=${targetStepId}`)
  await expect(page.getByRole('alert').filter({ hasText: /requested.*flow run.*unavailable/i })).toBeVisible()
  await expect(page.getByRole('group', { name: 'Flow step 2', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Hide attempts', exact: true })).toHaveCount(0)
  expect(state.writes).toEqual([])
})

test('an unavailable requested step is reported without expanding a different step', async ({ page }) => {
  const { state } = await fixture(page, () => ({ items: [], nextCursor: null }))
  await page.goto(`/projects/${projectId}/runs?view=flow-runs&workflowId=${targetWorkflowId}&flowRunId=${targetRunId}&stepId=step-no-longer-readable`)
  await expect(page.getByRole('alert').filter({ hasText: /requested.*step.*unavailable/i })).toBeVisible()
  await expect(page.getByRole('combobox', { name: 'Flow Run', exact: true })).toHaveValue(targetRunId)
  await expect(page.getByRole('button', { name: 'Hide attempts', exact: true })).toHaveCount(0)
  expect(state.writes).toEqual([])
})

test('a late review response from another project cannot populate the current project list', async ({ page }) => {
  let hold = false
  let release!: () => void
  const held = new Promise<void>((resolve) => { release = resolve })
  const { state, panel } = await fixture(page, async (url) => {
    if (url.pathname.includes(`/${otherProjectId}/`)) return { items: [item('other-project-operation')], nextCursor: null }
    if (hold) await held
    return { items: [item(hold ? 'late-old-project-operation' : 'first-project-operation')], nextCursor: null }
  })
  await panel.getByRole('button', { name: 'Open review list', exact: true }).click()
  await expect(panel).toContainText('first-project-operation')
  hold = true
  const previousReads = state.reads.length
  await panel.getByRole('button', { name: 'Refresh review list', exact: true }).click()
  await expect.poll(() => state.reads.length).toBeGreaterThan(previousReads)
  try {
    await page.getByRole('link', { name: 'Back to home', exact: true }).click()
    await page.getByRole('button').filter({ hasText: 'Other HTTP review project' }).click()
    await page.getByRole('link', { name: 'Runs', exact: true }).click()
    await page.getByRole('tab', { name: 'Flow executions', exact: true }).click()
    await expect(page).toHaveURL(new RegExp(`/projects/${otherProjectId}/runs`))
    const current = page.getByRole('region', { name: title, exact: true })
    await current.getByRole('button', { name: 'Open review list', exact: true }).click()
    await expect(current).toContainText('other-project-operation')
    release()
    await expect(current).not.toContainText('late-old-project-operation')
    await expect(current).not.toContainText('first-project-operation')
    await expect(current.getByRole('link', { name: 'Open run', exact: true })).toHaveCount(1)
    expect(state.writes).toEqual([])
  } finally { release() }
})

test('a late Flow Run creation response cannot restore the workflow that was left while creation was pending', async ({ page }) => {
  let release!: () => void
  const held = new Promise<void>((resolve) => { release = resolve })
  const { state } = await fixture(page, () => ({ items: [], nextCursor: null }), 'owner', async (route) => {
    await held
    await ok(route, run('run-http-review-late-created', 'revision-first'))
  })
  const workflow = page.getByRole('combobox', { name: 'Workflow', exact: true })
  await expect(workflow).toHaveValue(workflowId)
  await page.getByRole('button', { name: 'Start Flow Run', exact: true }).click()
  await expect.poll(() => state.writes).toEqual(['POST /api/flow-runs'])
  try {
    await workflow.selectOption(targetWorkflowId)
    await expect(workflow).toHaveValue(targetWorkflowId)
    const response = page.waitForResponse((reply) => new URL(reply.url()).pathname === '/api/flow-runs'
      && reply.request().method() === 'POST')
    release()
    await (await response).finished()
    await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
    await expect(workflow).toHaveValue(targetWorkflowId)
    expect(new URL(page.url()).searchParams.get('workflowId')).toBe(targetWorkflowId)
    expect(new URL(page.url()).searchParams.get('flowRunId')).not.toBe('run-http-review-late-created')
    expect(state.writes).toEqual(['POST /api/flow-runs'])
    expect(state.previews).toBe(0)
  } finally { release() }
})

test('losing the pinned revision does not turn an HTTP step into a manually reportable step', async ({ page }) => {
  await page.clock.install()
  let denied = false
  const { state } = await fixture(page, () => ({ items: [], nextCursor: null }), 'owner', undefined, async (route, pathname) => {
    if (!denied || !pathname.endsWith('/revisions/revision-target')) return false
    await route.fulfill({ status: 403, contentType: 'application/json', body: JSON.stringify({ success: false,
      data: null, message: 'Pinned revision access was revoked.' }) })
    return true
  })
  await page.goto(`/projects/${projectId}/runs?view=flow-runs&workflowId=${targetWorkflowId}&flowRunId=${targetRunId}&stepId=${targetStepId}`)
  await expect(page.getByRole('region', { name: 'HTTP execution for step 2', exact: true })).toBeVisible()
  denied = true
  await page.clock.fastForward(31_000)
  const selector = page.getByRole('combobox', { name: 'Flow Run', exact: true })
  await selector.selectOption('run-http-review-before')
  const refused = page.waitForResponse((reply) => new URL(reply.url()).pathname.endsWith('/revisions/revision-target')
    && reply.status() === 403)
  await selector.selectOption(targetRunId)
  await refused
  await expect(page.getByRole('region', { name: 'HTTP execution for step 2', exact: true })).toHaveCount(0)
  const card = page.getByRole('group', { name: 'Flow step 2', exact: true })
  await expect(card).toBeVisible()
  await expect(card.getByRole('button', { name: /^(Complete|Fail)$/ })).toHaveCount(0)
  await expect(card.getByRole('textbox', { name: /Output|Failure|Error/i })).toHaveCount(0)
  await expect(card.getByRole('button', { name: /Execute HTTP request|Retry same request|Apply saved result/ })).toHaveCount(0)
  expect(state.writes).toEqual([])
  expect(state.previews).toBe(0)
})


async function pendingSameWorkflowStart(page: Page) {
  const createdRunId = 'run-http-review-created-same-workflow'
  let created = false
  let release!: () => void
  const held = new Promise<void>((resolve) => { release = resolve })
  const { state } = await fixture(page, () => ({ items: [], nextCursor: null }), 'owner', async (route) => {
    await held
    created = true
    await ok(route, run(createdRunId, 'revision-target'))
  }, undefined, (selectedWorkflowId) => selectedWorkflowId === targetWorkflowId
    ? [...(created ? [run(createdRunId, 'revision-target')] : []),
      run('run-http-review-before', 'revision-before'), run(targetRunId, 'revision-target')]
    : [run('run-http-review-first', 'revision-first')])
  await page.getByRole('combobox', { name: 'Workflow', exact: true }).selectOption(targetWorkflowId)
  const selector = page.getByRole('combobox', { name: 'Flow Run', exact: true })
  await expect(selector).toHaveValue('run-http-review-before')
  await page.getByRole('button', { name: 'Start Flow Run', exact: true }).click()
  await expect.poll(() => state.writes).toEqual(['POST /api/flow-runs'])
  async function completeStart() {
    const response = page.waitForResponse((reply) => new URL(reply.url()).pathname === '/api/flow-runs'
      && reply.request().method() === 'POST')
    release()
    await (await response).finished()
    await expect(page.getByRole('button', { name: 'Start Flow Run', exact: true })).toBeEnabled()
    await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
  }
  return { state, selector, release, completeStart, createdRunId }
}

test('a delayed Flow Run creation preserves a newer run and expanded step selection in the same workflow', async ({ page }) => {
  const pending = await pendingSameWorkflowStart(page)
  try {
    await pending.selector.selectOption(targetRunId)
    const card = page.getByRole('group', { name: 'Flow step 2', exact: true })
    await card.getByRole('button', { name: 'Show attempts', exact: true }).click()
    await expect(card.getByRole('button', { name: 'Hide attempts', exact: true })).toBeVisible()
    await expect(card).toContainText('Attempt 2: running')
    await pending.completeStart()
    await expect(pending.selector).toHaveValue(targetRunId)
    expect(new URL(page.url()).searchParams.get('flowRunId')).toBe(targetRunId)
    expect(new URL(page.url()).searchParams.get('stepId')).toBe(targetStepId)
    await expect(card.getByRole('button', { name: 'Hide attempts', exact: true })).toBeVisible()
    expect(pending.state.writes).toEqual(['POST /api/flow-runs'])
  } finally { pending.release() }
})

test('a delayed Flow Run creation respects a deliberate selection away and back to the original run', async ({ page }) => {
  const pending = await pendingSameWorkflowStart(page)
  try {
    await pending.selector.selectOption(targetRunId)
    await expect(pending.selector).toHaveValue(targetRunId)
    await pending.selector.selectOption('run-http-review-before')
    await expect(pending.selector).toHaveValue('run-http-review-before')
    await pending.completeStart()
    await expect(pending.selector).toHaveValue('run-http-review-before')
    expect(new URL(page.url()).searchParams.get('flowRunId')).toBe('run-http-review-before')
    expect(new URL(page.url()).searchParams.has('stepId')).toBe(false)
    expect(pending.state.writes).toEqual(['POST /api/flow-runs'])
  } finally { pending.release() }
})

test('a delayed Flow Run creation selects its created run when the current selection is unchanged', async ({ page }) => {
  const pending = await pendingSameWorkflowStart(page)
  try {
    await pending.completeStart()
    await expect(pending.selector).toHaveValue(pending.createdRunId)
    expect(new URL(page.url()).searchParams.get('flowRunId')).toBe(pending.createdRunId)
    expect(new URL(page.url()).searchParams.has('stepId')).toBe(false)
    expect(pending.state.writes).toEqual(['POST /api/flow-runs'])
  } finally { pending.release() }
})
