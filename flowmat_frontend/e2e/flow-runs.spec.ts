import { expect, test, type Route } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { mockedLogin } from './support/mockApi'

type Step = { stepId: string; nodeId: string; sequenceNo: number; status: string;
  scheduledAt: string | null; errorCode: string | null; errorMessage: string | null }
type Attempt = { attemptId: string; attemptNo: number; status: string;
  startedAt: string; endedAt: string | null; retryAt: string | null;
  errorCode: string | null; errorMessage: string | null }

test('manual Flow Run records a failed attempt, retry, completion, and events', async ({ page }) => {
  const steps: Step[] = []
  const attempts: Attempt[] = []
  const events: { eventId: string; eventType: string; stepId: string | null; payload: unknown;
    occurredAt: string; actorType: string; actorId: string; requestId: string }[] = []
  let run: Record<string, unknown> | null = null
  let submittedRunInput: unknown = null
  let submittedRunOutput: unknown = null
  let submittedStepFailure: unknown = null
  let nextEvent = 0
  const addEvent = (eventType: string, stepId: string | null = null, payload: unknown = null) => {
    events.push({ eventId: `event-${++nextEvent}`, eventType, stepId,
      payload, requestId: `request-${nextEvent}`,
      occurredAt: new Date(Date.now() + nextEvent).toISOString(), actorType: 'user', actorId: 'demo-owner' })
  }
  const ok = async (route: Route, data: unknown) => {
    await route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ success: true, data, message: null }) })
  }

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
      return ok(route, [{ workflowId: 'wf-e2e', projectId: 'prj-e2e', workflowName: 'Flow test' }])
    }
    if (pathname === '/api/workflows/wf-e2e/revisions' && method === 'GET') {
      return ok(route, [{ workflowRevisionId: 'rev-e2e', workflowId: 'wf-e2e',
        revisionNo: 1, status: 'published', publishedAt: new Date().toISOString() }])
    }
    if (pathname === '/api/workflows/wf-e2e/revisions/rev-e2e') {
      return ok(route, { workflowRevisionId: 'rev-e2e', revisionNo: 1, status: 'published',
        snapshot: { processes: [{ processId: 'node-mix', processName: 'Mix' }] } })
    }
    if (pathname === '/api/flow-runs' && method === 'GET') return ok(route, run ? [run] : [])
    if (pathname === '/api/flow-runs' && method === 'POST') {
      submittedRunInput = (request.postDataJSON() as { inputPayload: unknown }).inputPayload
      run = { flowRunId: 'run-e2e', workflowRevisionId: 'rev-e2e', productionRunId: null,
        runType: 'test', status: 'running', inputPayload: submittedRunInput, outputPayload: null,
        startedAt: new Date().toISOString(), endedAt: null }
      addEvent('run_started')
      return ok(route, run)
    }
    if (pathname === '/api/flow-runs/run-e2e/steps' && method === 'GET') return ok(route, steps)
    if (pathname === '/api/flow-runs/run-e2e/steps' && method === 'POST') {
      const body = request.postDataJSON() as { scheduledAt: string | null }
      steps.push({ stepId: 'step-e2e', nodeId: 'node-mix', sequenceNo: 1, status: 'planned',
        scheduledAt: body.scheduledAt, errorCode: null, errorMessage: null })
      addEvent('step_created', 'step-e2e')
      return ok(route, steps[0])
    }
    if (pathname === '/api/flow-runs/run-e2e/steps/step-e2e/schedule' && method === 'PUT') {
      steps[0].scheduledAt = (request.postDataJSON() as { scheduledAt: string | null }).scheduledAt
      addEvent('step_scheduled', 'step-e2e', { scheduledAt: steps[0].scheduledAt })
      return ok(route, steps[0])
    }
    if (pathname === '/api/flow-runs/run-e2e/steps/step-e2e/start') {
      if (steps[0].scheduledAt && new Date(steps[0].scheduledAt).getTime() > Date.now()) {
        return route.fulfill({ status: 409, contentType: 'application/json',
          body: JSON.stringify({ success: false, data: null,
            message: 'Step cannot start before its scheduledAt.' }) })
      }
      steps[0].status = 'running'
      attempts.push({ attemptId: 'attempt-1', attemptNo: 1, status: 'running',
        startedAt: new Date().toISOString(), endedAt: null, retryAt: null,
        errorCode: null, errorMessage: null })
      addEvent('step_started', 'step-e2e')
      return ok(route, steps[0])
    }
    if (pathname === '/api/flow-runs/run-e2e/steps/step-e2e/fail') {
      submittedStepFailure = request.postDataJSON()
      steps[0].status = 'failed'
      steps[0].errorCode = 'MANUAL_FAILURE'
      steps[0].errorMessage = (submittedStepFailure as { errorMessage: string }).errorMessage
      attempts[0].status = 'failed'
      attempts[0].endedAt = new Date().toISOString()
      attempts[0].errorCode = 'MANUAL_FAILURE'
      attempts[0].errorMessage = 'Stopped for inspection'
      addEvent('step_failed', 'step-e2e')
      return ok(route, steps[0])
    }
    if (pathname === '/api/flow-runs/run-e2e/steps/step-e2e/retry') {
      steps[0].status = 'running'
      steps[0].errorCode = null
      steps[0].errorMessage = null
      attempts.push({ attemptId: 'attempt-2', attemptNo: 2, status: 'running',
        startedAt: new Date().toISOString(), endedAt: null, retryAt: null,
        errorCode: null, errorMessage: null })
      addEvent('step_retried', 'step-e2e')
      return ok(route, steps[0])
    }
    if (pathname === '/api/flow-runs/run-e2e/steps/step-e2e/complete') {
      steps[0].status = 'completed'
      attempts[1].status = 'completed'
      attempts[1].endedAt = new Date().toISOString()
      addEvent('step_completed', 'step-e2e')
      return ok(route, steps[0])
    }
    if (pathname === '/api/flow-runs/run-e2e/steps/step-e2e/attempts') return ok(route, attempts)
    if (pathname === '/api/flow-runs/run-e2e/events') return ok(route, events)
    if (pathname === '/api/flow-runs/run-e2e/finish') {
      submittedRunOutput = (request.postDataJSON() as { outputPayload: unknown }).outputPayload
      run = { ...run, status: 'finished', outputPayload: submittedRunOutput,
        endedAt: new Date().toISOString() }
      addEvent('run_finished', null, submittedRunOutput)
      return ok(route, run)
    }
    if (method === 'GET' && ['/api/items', '/api/production-runs', '/api/work-orders', '/api/boms']
      .includes(pathname)) return ok(route, [])
    await route.fulfill({ status: 404, contentType: 'application/json',
      body: JSON.stringify({ success: false, data: null, message: `Unmocked ${method} ${pathname}` }) })
  })

  // The next page load keeps the session only once the login has finished.
  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/runs?view=flow-runs')

  await expect(page.getByRole('tab', { name: 'Flow executions' })).toBeVisible()
  await page.getByRole('textbox', { name: 'Run input (JSON)', exact: true }).fill('{"batch":"A-1"}')
  await page.getByRole('button', { name: 'Start Flow Run' }).click()
  await expect.poll(() => submittedRunInput).toEqual({ batch: 'A-1' })
  await expect(page.getByText('Status: running')).toBeVisible()
  await expect(page.getByText('Run input', { exact: true }).locator('..')).toContainText('"batch": "A-1"')
  await page.getByLabel('Scheduled start (optional)').fill(new Date(Date.now() + 2 * 86_400_000)
    .toISOString().slice(0, 16))
  await page.getByRole('button', { name: 'Add planned step' }).click()
  await expect(page.getByText('#1 Mix')).toBeVisible()
  await expect(page.getByText(/Scheduled:/)).toBeVisible()
  await page.getByRole('button', { name: 'Start', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('scheduledAt')
  await page.getByRole('button', { name: 'Edit schedule' }).click()
  await page.getByLabel('Scheduled start for step #1').fill('')
  await page.getByRole('button', { name: 'Save schedule' }).click()
  await expect(page.getByRole('button', { name: 'Edit schedule' })).toBeVisible()
  await page.getByRole('button', { name: 'Start', exact: true }).click()
  await page.getByRole('textbox', { name: 'Failure reason for step #1' }).fill('Stopped for inspection')
  await page.getByRole('button', { name: 'Fail', exact: true }).click()
  await expect.poll(() => submittedStepFailure).toEqual({ errorCode: 'MANUAL_FAILURE', errorMessage: 'Stopped for inspection' })
  await expect(page.getByText('MANUAL_FAILURE', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Show attempts' }).click()
  await expect(page.getByText('Stopped for inspection').first()).toBeVisible()
  await page.getByRole('button', { name: 'Hide attempts' }).click()
  await page.getByRole('button', { name: 'Retry' }).click()
  await page.getByRole('button', { name: 'Complete' }).click()
  await page.getByRole('button', { name: 'Show attempts' }).click()
  await expect(page.getByText('Attempt 1: failed (MANUAL_FAILURE)')).toBeVisible()
  await expect(page.getByText('Attempt 2: completed')).toBeVisible()
  await expect(page.getByText('Attempt 1: failed (MANUAL_FAILURE)').locator('..')).toContainText('Started:')
  await expect(page.getByText('Attempt 1: failed (MANUAL_FAILURE)').locator('..')).toContainText('Ended:')
  await expect(page.getByText('Attempt 1: failed (MANUAL_FAILURE)').locator('..'))
    .toContainText('Reason: Stopped for inspection')
  await page.getByRole('textbox', { name: 'Final run output (JSON)' }).fill('{invalid')
  await page.getByRole('button', { name: 'Finish Flow Run' }).click()
  await expect(page.getByRole('alert')).toContainText('Enter valid JSON')
  expect(submittedRunOutput).toBeNull()
  await page.getByRole('textbox', { name: 'Final run output (JSON)' }).fill('{"result":"passed"}')
  await page.getByRole('button', { name: 'Finish Flow Run' }).click()
  await expect.poll(() => submittedRunOutput).toEqual({ result: 'passed' })
  await expect(page.getByText('Status: finished')).toBeVisible()
  await expect(page.getByText('Final run output').locator('..')).toContainText('"result": "passed"')
  await expect(page.locator('summary').filter({ hasText: 'run_finished' })).toBeVisible()
  await page.getByRole('combobox', { name: 'Event type' }).selectOption('run_finished')
  await expect(page.getByRole('status')).toHaveText('1 of 8 events')
  await page.locator('summary').filter({ hasText: 'run_finished' }).click()
  await expect(page.getByText('Request ID: request-8')).toBeVisible()
  await expect(page.getByText('Actor: user · demo-owner')).toBeVisible()
  await expect(page.getByText(/"result": "passed"/).last()).toBeVisible()
  await page.getByRole('combobox', { name: 'Event type' }).selectOption('')
  await page.getByRole('combobox', { name: 'Event step' }).selectOption('step-e2e')
  await expect(page.getByRole('status')).toHaveText('6 of 8 events')
  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Download visible events (JSON)' }).click()
  const download = await downloadPromise
  expect(download.suggestedFilename()).toBe('flow-run-run-e2e-events.json')
  const exported = JSON.parse(await readFile(await download.path(), 'utf8')) as {
    flowRunId: string; workflowRevisionId: string; events: { stepId: string }[]
  }
  expect(exported.flowRunId).toBe('run-e2e')
  expect(exported.workflowRevisionId).toBe('rev-e2e')
  expect(exported.events).toHaveLength(6)
  expect(exported.events.every((event) => event.stepId === 'step-e2e')).toBe(true)
})
