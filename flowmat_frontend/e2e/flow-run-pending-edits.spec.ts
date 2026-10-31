import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

const projectId = 'prj-flow-pending'
const workflowId = 'wf-flow-pending'
const exactInput = '{"quantity":9007199254740993.123456789,"attrs":{"batch":"submitted"}}'
const nextInput = '{"quantity":9007199254740995.987654321,"attrs":{"batch":"next"}}'

type Step = { stepId: string; nodeId: string; sequenceNo: number; status: string;
  scheduledAt: string | null; inputSnapshot: unknown; outputSnapshot: unknown }
type Held = { rawBody: string; release: () => Promise<void> }

async function fixture(page: Page, graph = false) {
  const steps: Record<string, Step[]> = Object.fromEntries(['run-A', 'run-B'].map((runId) => [runId, [1, 2].map((n) => ({
    stepId: `${runId}-step-${n}`, nodeId: `node-${n}`, sequenceNo: n,
    status: graph ? 'running' : 'planned', scheduledAt: null, inputSnapshot: null, outputSnapshot: null,
  }))]))
  const state = { reads: [] as string[], writes: [] as { method: string; path: string; body: string }[] }
  const holds = new Map<string, (held: Held) => void>()
  function hold(method: string, path: string): Promise<Held> {
    return new Promise((resolve) => holds.set(`${method} ${path}`, resolve))
  }
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const method = request.method()
    if (await answerAuth(route, path)) return
    if (method === 'GET') state.reads.push(path)
    else state.writes.push({ method, path, body: request.postData() ?? '' })
    if (path === '/api/projects') return ok(route, [{ projectId, projectName: 'Pending edit project' }])
    if (path === '/api/project-members') return ok(route, [{ projectMemberId: 'member-pending', projectId,
      userId: 'demo-owner', projectRole: 'owner', memberStatus: 'active' }])
    if (path === '/api/workflows') return ok(route, [{ workflowId, projectId, workflowName: 'Pending edit workflow' }])
    if (path.endsWith('/revisions')) return ok(route, [{ workflowRevisionId: 'revision-pending', workflowId,
      revisionNo: 1, status: 'published' }])
    if (path.endsWith('/revisions/revision-pending')) return ok(route, { workflowRevisionId: 'revision-pending',
      revisionNo: 1, status: 'published', snapshot: { processes: [
        { processId: 'node-1', processName: 'Source process' }, { processId: 'node-2', processName: 'Next process' },
      ], connections: [{ connectionId: 'route-next', connectionLabel: 'Next route',
        sourceProcessId: 'node-1', targetProcessId: 'node-2' }] } })
    if (path === '/api/flow-runs' && method === 'GET') return ok(route, ['run-A', 'run-B'].map((flowRunId) => ({
      flowRunId, workflowRevisionId: 'revision-pending', productionRunId: null, runType: 'test',
      executionMode: graph ? 'graph' : 'manual', status: 'running', inputPayload: null, outputPayload: null,
      startedAt: '2026-10-28T10:00:00Z', endedAt: null,
    })))
    const match = /^\/api\/flow-runs\/(run-[AB])\/steps(?:\/([^/]+)\/(start|complete|schedule|preview))?$/.exec(path)
    if (match) {
      const [, runId, stepId, action] = match
      if (method === 'GET') return ok(route, steps[runId])
      const apply = async () => {
        const body = (request.postData() ? request.postDataJSON() : {}) as { scheduledAt?: string | null; inputSnapshot?: unknown }
        if (!stepId) {
          const step: Step = { stepId: `${runId}-step-${steps[runId].length + 1}`, nodeId: 'node-1',
            sequenceNo: steps[runId].length + 1, status: 'planned', scheduledAt: body.scheduledAt ?? null,
            inputSnapshot: body.inputSnapshot, outputSnapshot: null }
          steps[runId].push(step)
          return ok(route, step)
        }
        const step = steps[runId].find((entry) => entry.stepId === stepId)!
        if (action === 'preview') return ok(route, [{ connectionId: 'route-next', targetNodeId: 'node-2', willRoute: true }])
        if (action === 'schedule') step.scheduledAt = body.scheduledAt ?? null
        else step.status = action === 'start' ? 'running' : 'completed'
        return ok(route, step)
      }
      const key = `${method} ${path}`
      const held = holds.get(key)
      if (held) {
        holds.delete(key)
        held({ rawBody: request.postData() ?? '', release: apply })
        return
      }
      return apply()
    }
    if (path.endsWith('/events') || path.endsWith('/attempts')) return ok(route, [])
    if (path.endsWith('/lineage')) return ok(route, { ancestors: [], descendants: [] })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/runs?view=flow-runs&flowRunId=run-A`)
  await expect(page.getByRole('group', { name: 'Flow step 1', exact: true })).toBeVisible()
  return { state, hold }
}

function responseFor(page: Page, method: string, path: string) {
  return page.waitForResponse((response) => response.request().method() === method && new URL(response.url()).pathname === path)
}

async function submitHeldStep(page: Page, hold: (method: string, path: string) => Promise<Held>) {
  const request = hold('POST', '/api/flow-runs/run-A/steps')
  await page.getByRole('textbox', { name: 'Input snapshot (JSON)', exact: true }).fill(exactInput)
  await page.getByLabel('Scheduled start (optional)', { exact: true }).fill('2030-03-01T09:30')
  await page.getByRole('button', { name: 'Add planned step', exact: true }).click()
  const held = await request
  expect(held.rawBody).toContain(`"inputSnapshot":${exactInput}`)
  expect(held.rawBody).not.toContain('9007199254740994')
  return held
}

test('a delayed planned-step save preserves the next JSON and schedule draft on the same run', async ({ page }) => {
  const { hold } = await fixture(page)
  const held = await submitHeldStep(page, hold)
  await page.getByRole('textbox', { name: 'Input snapshot (JSON)', exact: true }).fill(nextInput)
  await page.getByLabel('Scheduled start (optional)', { exact: true }).fill('2030-03-02T11:45')
  const response = responseFor(page, 'POST', '/api/flow-runs/run-A/steps')
  await held.release()
  await response
  await expect(page.getByRole('button', { name: 'Add planned step', exact: true })).toBeEnabled()
  await expect(page.getByRole('textbox', { name: 'Input snapshot (JSON)', exact: true })).toHaveValue(nextInput)
  await expect(page.getByLabel('Scheduled start (optional)', { exact: true })).toHaveValue('2030-03-02T11:45')
})

test('a delayed schedule save preserves the date edited while the request was pending', async ({ page }) => {
  const { hold } = await fixture(page)
  const step = page.getByRole('group', { name: 'Flow step 1', exact: true })
  await step.getByRole('button', { name: 'Edit schedule', exact: true }).click()
  const input = step.getByLabel('Scheduled start for step #1', { exact: true })
  await input.fill('2030-03-01T09:30')
  const request = hold('PUT', '/api/flow-runs/run-A/steps/run-A-step-1/schedule')
  await step.getByRole('button', { name: 'Save schedule', exact: true }).click()
  const held = await request
  await input.fill('2030-03-03T14:15')
  const response = responseFor(page, 'PUT', '/api/flow-runs/run-A/steps/run-A-step-1/schedule')
  await held.release()
  await response
  await expect(input).toBeVisible()
  await expect(input).toHaveValue('2030-03-03T14:15')
  await expect(step.getByRole('button', { name: 'Save schedule', exact: true })).toBeEnabled()
})

test('a delayed action invalidates its origin run without selecting it over the current run', async ({ page }) => {
  const { hold, state } = await fixture(page)
  const request = hold('POST', '/api/flow-runs/run-A/steps/run-A-step-1/start')
  await page.getByRole('group', { name: 'Flow step 1', exact: true }).getByRole('button', { name: 'Start', exact: true }).click()
  const held = await request
  await page.getByRole('combobox', { name: 'Flow Run', exact: true }).selectOption('run-B')
  await expect.poll(() => state.reads.filter((path) => path === '/api/flow-runs/run-B/steps').length).toBeGreaterThan(0)
  const response = responseFor(page, 'POST', '/api/flow-runs/run-A/steps/run-A-step-1/start')
  await held.release()
  await response
  await expect(page.getByRole('group', { name: 'Flow step 1', exact: true }).getByRole('button', { name: 'Start', exact: true })).toBeEnabled()
  await expect(page.getByRole('combobox', { name: 'Flow Run', exact: true })).toHaveValue('run-B')
  await page.getByRole('combobox', { name: 'Flow Run', exact: true }).selectOption('run-A')
  await expect(page.getByRole('group', { name: 'Flow step 1', exact: true })).toContainText('running')
  await expect.poll(() => state.reads.filter((path) => path === '/api/flow-runs/run-A/steps').length).toBeGreaterThanOrEqual(2)
})

test('a preview response held across completion cannot restore obsolete routing evidence', async ({ page }) => {
  const { hold } = await fixture(page, true)
  const step = page.getByRole('group', { name: 'Flow step 1', exact: true })
  const request = hold('POST', '/api/flow-runs/run-A/steps/run-A-step-1/preview')
  await step.getByRole('button', { name: 'Preview routes', exact: true }).click()
  const held = await request
  await step.getByRole('button', { name: 'Complete', exact: true }).click()
  await expect(step).toContainText('completed')
  const response = responseFor(page, 'POST', '/api/flow-runs/run-A/steps/run-A-step-1/preview')
  await held.release()
  await response
  await expect(page.getByRole('group', { name: 'Flow step 2', exact: true })
    .getByRole('button', { name: 'Preview routes', exact: true })).toBeEnabled()
  await expect(page.getByText('Route preview', { exact: true })).toHaveCount(0)
  await page.getByRole('group', { name: 'Flow step 2', exact: true })
    .getByRole('button', { name: 'Preview routes', exact: true }).click()
  await expect(page.getByText('Route preview', { exact: true })).toBeVisible()
})


test('an untouched planned-step form clears after a successful save and keeps the submitted exact JSON', async ({ page }) => {
  const { hold, state } = await fixture(page)
  const held = await submitHeldStep(page, hold)
  const response = responseFor(page, 'POST', '/api/flow-runs/run-A/steps')
  await held.release()
  await response
  await expect(page.getByRole('group', { name: 'Flow step 3', exact: true })).toBeVisible()
  await expect(page.getByRole('textbox', { name: 'Input snapshot (JSON)', exact: true })).toHaveValue('{}')
  await expect(page.getByLabel('Scheduled start (optional)', { exact: true })).toHaveValue('')
  expect(state.writes).toHaveLength(1)
  expect(state.writes[0].body).toContain(`"inputSnapshot":${exactInput}`)
})

test('a delayed planned-step save cannot clear a draft entered for another run', async ({ page }) => {
  const { hold } = await fixture(page)
  const held = await submitHeldStep(page, hold)
  await page.getByRole('combobox', { name: 'Flow Run', exact: true }).selectOption('run-B')
  await page.getByRole('textbox', { name: 'Input snapshot (JSON)', exact: true }).fill(nextInput)
  await page.getByLabel('Scheduled start (optional)', { exact: true }).fill('2030-03-05T08:10')
  const response = responseFor(page, 'POST', '/api/flow-runs/run-A/steps')
  await held.release()
  await response
  await expect(page.getByRole('button', { name: 'Add planned step', exact: true })).toBeEnabled()
  await expect(page.getByRole('combobox', { name: 'Flow Run', exact: true })).toHaveValue('run-B')
  await expect(page.getByRole('textbox', { name: 'Input snapshot (JSON)', exact: true })).toHaveValue(nextInput)
  await expect(page.getByLabel('Scheduled start (optional)', { exact: true })).toHaveValue('2030-03-05T08:10')
  await page.getByRole('combobox', { name: 'Flow Run', exact: true }).selectOption('run-A')
  await expect(page.getByRole('group', { name: 'Flow step 3', exact: true })).toBeVisible()
})

test('an edit and restore while saving still counts as a newer planned-step draft', async ({ page }) => {
  const { hold } = await fixture(page)
  const held = await submitHeldStep(page, hold)
  await page.getByRole('textbox', { name: 'Input snapshot (JSON)', exact: true }).fill(nextInput)
  await page.getByRole('textbox', { name: 'Input snapshot (JSON)', exact: true }).fill(exactInput)
  const response = responseFor(page, 'POST', '/api/flow-runs/run-A/steps')
  await held.release()
  await response
  await expect(page.getByRole('button', { name: 'Add planned step', exact: true })).toBeEnabled()
  await expect(page.getByRole('textbox', { name: 'Input snapshot (JSON)', exact: true })).toHaveValue(exactInput)
  await expect(page.getByLabel('Scheduled start (optional)', { exact: true })).toHaveValue('2030-03-01T09:30')
})

test('an untouched schedule editor closes after its successful save', async ({ page }) => {
  const { hold } = await fixture(page)
  const step = page.getByRole('group', { name: 'Flow step 1', exact: true })
  await step.getByRole('button', { name: 'Edit schedule', exact: true }).click()
  await step.getByLabel('Scheduled start for step #1', { exact: true }).fill('2030-03-04T10:20')
  const request = hold('PUT', '/api/flow-runs/run-A/steps/run-A-step-1/schedule')
  await step.getByRole('button', { name: 'Save schedule', exact: true }).click()
  const held = await request
  const response = responseFor(page, 'PUT', '/api/flow-runs/run-A/steps/run-A-step-1/schedule')
  await held.release()
  await response
  await expect(step.getByRole('button', { name: 'Edit schedule', exact: true })).toBeEnabled()
  await expect(step.getByLabel('Scheduled start for step #1', { exact: true })).toHaveCount(0)
  await expect(step).toContainText('Scheduled:')
})

test('editing output and restoring it cannot make an earlier held preview current again', async ({ page }) => {
  const { hold } = await fixture(page, true)
  const step = page.getByRole('group', { name: 'Flow step 1', exact: true })
  const request = hold('POST', '/api/flow-runs/run-A/steps/run-A-step-1/preview')
  await step.getByRole('button', { name: 'Preview routes', exact: true }).click()
  const held = await request
  const output = step.getByRole('textbox', { name: 'Output snapshot for step #1 (JSON)', exact: true })
  await output.fill(nextInput)
  await output.fill('{}')
  const response = responseFor(page, 'POST', '/api/flow-runs/run-A/steps/run-A-step-1/preview')
  await held.release()
  await response
  await expect(step.getByRole('button', { name: 'Preview routes', exact: true })).toBeEnabled()
  await expect(page.getByText('Route preview', { exact: true })).toHaveCount(0)
  await step.getByRole('button', { name: 'Preview routes', exact: true }).click()
  await expect(page.getByText('Route preview', { exact: true })).toBeVisible()
})

test('an older preview cannot show after reselection or clear a newer preview pending state', async ({ page }) => {
  const { hold } = await fixture(page, true)
  const requestA = hold('POST', '/api/flow-runs/run-A/steps/run-A-step-1/preview')
  await page.getByRole('group', { name: 'Flow step 1', exact: true })
    .getByRole('button', { name: 'Preview routes', exact: true }).click()
  const heldA = await requestA
  await page.getByRole('combobox', { name: 'Flow Run', exact: true }).selectOption('run-B')
  const previewB = page.getByRole('group', { name: 'Flow step 1', exact: true })
    .getByRole('button', { name: 'Preview routes', exact: true })
  await expect(previewB).toBeEnabled()
  const requestB = hold('POST', '/api/flow-runs/run-B/steps/run-B-step-1/preview')
  await previewB.click()
  const heldB = await requestB
  const responseA = responseFor(page, 'POST', '/api/flow-runs/run-A/steps/run-A-step-1/preview')
  await heldA.release()
  await responseA
  // A browser-frame barrier lets the older request's promise/finally finish before checking B.
  await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => resolve())))
  await expect(previewB).toBeDisabled()
  await expect(page.getByText('Route preview', { exact: true })).toHaveCount(0)
  await expect(page.getByRole('combobox', { name: 'Flow Run', exact: true })).toHaveValue('run-B')
  const responseB = responseFor(page, 'POST', '/api/flow-runs/run-B/steps/run-B-step-1/preview')
  await heldB.release()
  await responseB
  await expect(previewB).toBeEnabled()
  await expect(page.getByText('Route preview', { exact: true })).toBeVisible()
  await page.getByRole('combobox', { name: 'Flow Run', exact: true }).selectOption('run-A')
  await expect(page.getByText('Route preview', { exact: true })).toHaveCount(0)
})
test('changing only the target node while saving preserves the whole next planned-step draft', async ({ page }) => {
  const { hold } = await fixture(page)
  const held = await submitHeldStep(page, hold)
  await page.getByRole('combobox', { name: 'Node in pinned revision', exact: true }).selectOption('node-2')
  const response = responseFor(page, 'POST', '/api/flow-runs/run-A/steps')
  await held.release()
  await response
  await expect(page.getByRole('button', { name: 'Add planned step', exact: true })).toBeEnabled()
  await expect(page.getByRole('combobox', { name: 'Node in pinned revision', exact: true })).toHaveValue('node-2')
  await expect(page.getByRole('textbox', { name: 'Input snapshot (JSON)', exact: true })).toHaveValue(exactInput)
  await expect(page.getByLabel('Scheduled start (optional)', { exact: true })).toHaveValue('2030-03-01T09:30')
})

test('editing and restoring a pending schedule keeps the newer editor open', async ({ page }) => {
  const { hold } = await fixture(page)
  const step = page.getByRole('group', { name: 'Flow step 1', exact: true })
  await step.getByRole('button', { name: 'Edit schedule', exact: true }).click()
  const input = step.getByLabel('Scheduled start for step #1', { exact: true })
  await input.fill('2030-03-01T09:30')
  const request = hold('PUT', '/api/flow-runs/run-A/steps/run-A-step-1/schedule')
  await step.getByRole('button', { name: 'Save schedule', exact: true }).click()
  const held = await request
  await input.fill('2030-03-08T12:20')
  await input.fill('2030-03-01T09:30')
  const response = responseFor(page, 'PUT', '/api/flow-runs/run-A/steps/run-A-step-1/schedule')
  await held.release()
  await response
  await expect(input).toBeVisible()
  await expect(input).toHaveValue('2030-03-01T09:30')
  await expect(step.getByRole('button', { name: 'Save schedule', exact: true })).toBeEnabled()
})
