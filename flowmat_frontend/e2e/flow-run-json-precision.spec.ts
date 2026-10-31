import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

const projectId = 'prj-json-exact'
const workflowId = 'wf-json-exact'
const runId = 'run-json-exact'
const stepId = 'step-json-exact'
const exactJson = '{"integer":9007199254740993,"decimal":9007199254740993.1234567890123456789,"exponent":1.234567890123456789e+40}'

type FixtureOptions = { mode?: 'manual' | 'graph'; stepStatus?: string | null; runStatus?: string; legacy?: boolean;
  outputJson?: string }

async function fixture(page: Page, options: FixtureOptions = {}) {
  const state = { writes: [] as { path: string; body: string }[] }
  const run: Record<string, unknown> = {
    flowRunId: runId, workflowRevisionId: 'revision-json-exact', productionRunId: null,
    runType: 'test', executionMode: options.mode ?? 'manual', status: options.runStatus ?? 'running',
    inputPayload: JSON.parse(exactJson) as unknown,
    outputPayload: options.outputJson === undefined ? null : JSON.parse(options.outputJson) as unknown,
    startedAt: '2026-10-28T01:00:00Z', endedAt: null,
    ...(options.legacy ? {} : { inputPayloadJson: exactJson, outputPayloadJson: options.outputJson ?? null }),
  }
  const step = { stepId, nodeId: 'node-json-exact', sourceConnectionId: null, sourceStepId: null,
    sequenceNo: 1, status: options.stepStatus ?? 'running', scheduledAt: null,
    errorCode: null, errorMessage: null, inputSnapshot: JSON.parse(exactJson) as unknown,
    outputSnapshot: options.outputJson === undefined ? null : JSON.parse(options.outputJson) as unknown,
    ...(options.legacy ? {} : { inputSnapshotJson: exactJson, outputSnapshotJson: options.outputJson ?? null }) }
  const steps = options.stepStatus === null ? [] : [step]
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') {
      state.writes.push({ path: pathname, body: request.postData() ?? '' })
      if (pathname === `/api/flow-runs/${runId}/steps`) {
        steps.push({ ...step, status: 'planned' })
        return ok(route, steps[steps.length - 1])
      }
      if (pathname.endsWith('/complete')) {
        step.status = 'completed'
        return ok(route, step)
      }
      if (pathname.endsWith('/preview')) return ok(route, [])
      if (pathname.endsWith('/finish')) {
        run.status = 'finished'
        return ok(route, run)
      }
      return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({
        success: false, data: null, message: `Unmocked write: ${pathname}`,
      }) })
    }
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Exact JSON', projectStatus: 'active' }])
    if (pathname === '/api/project-members') return ok(route, [{ projectMemberId: 'member-json-exact',
      projectId, userId: 'demo-owner', projectRole: 'owner', memberStatus: 'active' }])
    if (pathname === '/api/workflows') return ok(route, [{ workflowId, projectId, workflowName: 'Exact JSON flow' }])
    if (pathname.endsWith('/http-executions/attention')) return ok(route, { items: [], nextCursor: null })
    if (pathname.endsWith('/revisions')) return ok(route, [{ workflowRevisionId: 'revision-json-exact', workflowId,
      revisionNo: 1, schemaVersion: 1, status: 'published', publishedAt: '2026-10-28T00:00:00Z' }])
    if (pathname.endsWith('/revisions/revision-json-exact')) return ok(route, { workflowRevisionId: 'revision-json-exact',
      revisionNo: 1, status: 'published', snapshot: { processes: [{ processId: 'node-json-exact', processName: 'Exact process' }], connections: [] } })
    if (pathname === '/api/flow-runs') return ok(route, [run])
    if (pathname === `/api/flow-runs/${runId}/steps`) return ok(route, steps)
    if (pathname.endsWith('/lineage')) return ok(route, { step, ancestors: [], descendants: [] })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/runs?view=flow-runs`)
  await expect(page.getByText(`Status: ${options.runStatus ?? 'running'}`, { exact: false })).toBeVisible()
  return state
}

test('manual planned step preserves large integer, decimal and exponent JSON on the wire', async ({ page }) => {
  const state = await fixture(page, { stepStatus: null })
  await page.getByRole('textbox', { name: 'Input snapshot (JSON)', exact: true }).fill(exactJson)
  await page.getByRole('button', { name: 'Add planned step', exact: true }).click()
  await expect(page.getByRole('group', { name: 'Flow step 1', exact: true })).toBeVisible()
  expect(state.writes).toHaveLength(1)
  expect(state.writes[0].body).toContain(`"inputSnapshot":${exactJson}`)
})

test('manual completion preserves exact output JSON on the wire', async ({ page }) => {
  const state = await fixture(page)
  await page.getByRole('textbox', { name: 'Output snapshot for step #1 (JSON)', exact: true }).fill(exactJson)
  await page.getByRole('button', { name: 'Complete', exact: true }).click()
  await expect(page.getByRole('group', { name: 'Flow step 1', exact: true })).toContainText('completed')
  expect(state.writes).toEqual([{ path: `/api/flow-runs/${runId}/steps/${stepId}/complete`,
    body: `{"outputSnapshot":${exactJson}}` }])
})

test('graph route preview sends the exact output without completing the step', async ({ page }) => {
  const state = await fixture(page, { mode: 'graph' })
  await page.getByRole('textbox', { name: 'Output snapshot for step #1 (JSON)', exact: true }).fill(exactJson)
  await page.getByRole('button', { name: 'Preview routes', exact: true }).click()
  await expect(page.getByText('No outgoing connections.', { exact: true })).toBeVisible()
  expect(state.writes).toEqual([{ path: `/api/flow-runs/${runId}/steps/${stepId}/preview`,
    body: `{"outputSnapshot":${exactJson}}` }])
  await expect(page.getByRole('group', { name: 'Flow step 1', exact: true })).toContainText('running')
})

test('finishing a run preserves the exact final output on the wire', async ({ page }) => {
  const state = await fixture(page, { stepStatus: 'completed' })
  await page.getByRole('textbox', { name: 'Final run output (JSON)', exact: true }).fill(exactJson)
  await page.getByRole('button', { name: 'Finish Flow Run', exact: true }).click()
  await expect(page.getByText('Status: finished', { exact: false })).toBeVisible()
  expect(state.writes).toEqual([{ path: `/api/flow-runs/${runId}/finish`,
    body: `{"outputPayload":${exactJson}}` }])
})

for (const action of ['add', 'complete', 'preview', 'finish'] as const) {
  test(`invalid JSON prevents the ${action} request`, async ({ page }) => {
    const state = await fixture(page, { mode: action === 'preview' ? 'graph' : 'manual',
      stepStatus: action === 'add' ? null : action === 'finish' ? 'completed' : 'running' })
    const label = action === 'add' ? 'Input snapshot (JSON)' : action === 'finish'
      ? 'Final run output (JSON)' : 'Output snapshot for step #1 (JSON)'
    const button = { add: 'Add planned step', complete: 'Complete', preview: 'Preview routes', finish: 'Finish Flow Run' }[action]
    await page.getByRole('textbox', { name: label, exact: true }).fill('{"value":')
    await page.getByRole('button', { name: button, exact: true }).click()
    await expect(page.getByRole('alert')).toContainText('valid JSON')
    expect(state.writes).toEqual([])
  })
}

test('quoted numeric strings and JSON null remain their original JSON types', async ({ page }) => {
  const state = await fixture(page)
  await page.getByRole('textbox', { name: 'Output snapshot for step #1 (JSON)', exact: true }).fill('"9007199254740993"')
  await page.getByRole('button', { name: 'Complete', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Finish Flow Run', exact: true })).toBeEnabled()
  await page.getByRole('textbox', { name: 'Final run output (JSON)', exact: true }).fill('null')
  await page.getByRole('button', { name: 'Finish Flow Run', exact: true }).click()
  await expect(page.getByText('Status: finished', { exact: false })).toBeVisible()
  expect(state.writes).toEqual([
    { path: `/api/flow-runs/${runId}/steps/${stepId}/complete`, body: '{"outputSnapshot":"9007199254740993"}' },
    { path: `/api/flow-runs/${runId}/finish`, body: '{"outputPayload":null}' },
  ])
})

test('run and graph step snapshots display exact server JSON on a narrow screen', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 })
  const state = await fixture(page, { mode: 'graph', stepStatus: 'completed', runStatus: 'finished', outputJson: exactJson })
  await expect(page.getByText('Run input', { exact: true }).locator('..').locator('pre')).toHaveText(exactJson)
  await expect(page.getByText('Final run output', { exact: true }).locator('..').locator('pre')).toHaveText(exactJson)
  await page.getByRole('button', { name: 'Show attempts', exact: true }).click()
  const step = page.getByRole('group', { name: 'Flow step 1', exact: true })
  const snapshots = step.locator('pre')
  await expect(snapshots).toHaveCount(2)
  await expect(snapshots.nth(0)).toHaveText(exactJson)
  await expect(snapshots.nth(1)).toHaveText(exactJson)
  for (const snapshot of await snapshots.all()) {
    const bounds = await snapshot.boundingBox()
    expect(bounds).not.toBeNull()
    expect(bounds!.width).toBeLessThanOrEqual(375)
  }
  expect(state.writes).toEqual([])
})

test('an explicit JSON null snapshot is visible instead of being mistaken for absent output', async ({ page }) => {
  const state = await fixture(page, { stepStatus: 'completed', runStatus: 'finished', outputJson: 'null' })
  await expect(page.getByText('Final run output', { exact: true }).locator('..').locator('pre')).toHaveText('null')
  await page.getByRole('button', { name: 'Show attempts', exact: true }).click()
  const step = page.getByRole('group', { name: 'Flow step 1', exact: true })
  await expect(step.getByText('Output snapshot', { exact: true })).toBeVisible()
  await expect(step.locator('pre').nth(1)).toHaveText('null')
  expect(state.writes).toEqual([])
})

test('legacy snapshots keep their readable shape and disclose possible numeric precision loss', async ({ page }) => {
  const state = await fixture(page, { stepStatus: 'completed', runStatus: 'finished', legacy: true, outputJson: exactJson })
  await expect(page.getByText('Legacy JSON response: numeric precision may already be lost.').first()).toBeVisible()
  const input = page.getByText('Run input', { exact: true }).locator('..')
  await expect(input.locator('pre')).toContainText('"integer": 9007199254740992')
  expect(state.writes).toEqual([])
})
