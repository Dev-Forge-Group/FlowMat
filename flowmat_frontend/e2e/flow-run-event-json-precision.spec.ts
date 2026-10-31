import { readFile } from 'node:fs/promises'
import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

const projectId = 'prj-event-json'
const workflowId = 'wf-event-json'
const revisionId = 'revision-event-json'
const runId = 'run-event-json'
const stepId = 'step-event-json'
const exactJson = '{"integer":9007199254740993,"decimal":9007199254740993.1234567890123456789,"exponent":1.234567890123456789e+40}'

type Event = {
  eventId: string
  eventType: string
  stepId: string | null
  payload: unknown
  payloadJson?: unknown
  requestId: string | null
  occurredAt: string
  actorType: string
  actorId: string
}

function event(eventId: string, payloadJson: string, overrides: Partial<Event> = {}): Event {
  return { eventId, eventType: 'step_completed', stepId, payload: JSON.parse(payloadJson) as unknown,
    payloadJson, requestId: `request-${eventId}`, occurredAt: '2026-10-28T01:02:03Z',
    actorType: 'user', actorId: 'demo-owner', ...overrides }
}

async function fixture(page: Page, events: Event[] = [event('event-exact', exactJson)]) {
  const state = { denied: false, reads: 0, writes: [] as string[] }
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') {
      state.writes.push(`${request.method()} ${pathname}`)
      return route.fulfill({ status: 405, contentType: 'application/json', body: JSON.stringify({
        success: false, data: null, message: 'Read-only event fixture.',
      }) })
    }
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Exact events', projectStatus: 'active' }])
    if (pathname === '/api/project-members') return ok(route, [{ projectMemberId: 'member-event-json',
      projectId, userId: 'demo-owner', projectRole: 'owner', memberStatus: 'active' }])
    if (pathname === '/api/workflows') return ok(route, [{ workflowId, projectId, workflowName: 'Event JSON flow' }])
    if (pathname.endsWith('/http-executions/attention')) return ok(route, { items: [], nextCursor: null })
    if (pathname.endsWith('/revisions')) return ok(route, [{ workflowRevisionId: revisionId, workflowId,
      revisionNo: 1, schemaVersion: 1, status: 'published', publishedAt: '2026-10-28T00:00:00Z' }])
    if (pathname.endsWith(`/revisions/${revisionId}`)) return ok(route, { workflowRevisionId: revisionId,
      revisionNo: 1, status: 'published', snapshot: { processes: [{ processId: 'node-event-json', processName: 'Exact process' }], connections: [] } })
    if (pathname === '/api/flow-runs') return ok(route, [{ flowRunId: runId, workflowRevisionId: revisionId,
      productionRunId: null, runType: 'test', executionMode: 'manual', status: 'finished',
      inputPayload: null, inputPayloadJson: null, outputPayload: null, outputPayloadJson: null,
      startedAt: '2026-10-28T01:00:00Z', endedAt: '2026-10-28T01:02:03Z' }])
    if (pathname === `/api/flow-runs/${runId}/steps`) return ok(route, [1, 2].map((sequenceNo) => ({
      stepId: sequenceNo === 1 ? stepId : 'step-other', nodeId: 'node-event-json', sourceConnectionId: null,
      sourceStepId: null, sequenceNo, status: 'completed', scheduledAt: null, errorCode: null, errorMessage: null,
      inputSnapshot: null, inputSnapshotJson: null, outputSnapshot: null, outputSnapshotJson: null,
    })))
    if (pathname === `/api/flow-runs/${runId}/events`) {
      state.reads += 1
      if (state.denied) return route.fulfill({ status: 403, contentType: 'application/json', body: JSON.stringify({
        success: false, data: null, message: 'Event access revoked.',
      }) })
      return ok(route, events)
    }
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/runs?view=flow-runs`)
  await expect(page.getByRole('button', { name: 'Download visible events (JSON)', exact: true })).toBeVisible()
  return state
}

async function downloadEvents(page: Page) {
  const pending = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Download visible events (JSON)', exact: true }).click()
  const download = await pending
  const path = await download.path()
  expect(path).not.toBeNull()
  return { text: await readFile(path!, 'utf8'), filename: download.suggestedFilename() }
}

test('event JSON download preserves server integer, decimal and exponent tokens instead of rounded parsed payload', async ({ page }) => {
  const state = await fixture(page)
  const exported = await downloadEvents(page)
  expect(exported.filename).toBe(`flow-run-${runId}-events.json`)
  expect(exported.text).toContain(exactJson)
  expect(exported.text).not.toContain('"payloadJson"')
  expect(exported.text).toContain('"flowRunId"')
  expect(exported.text).toContain(runId)
  expect(exported.text).toContain(revisionId)
  expect(exported.text).toContain('event-exact')
  expect(state.writes).toEqual([])
})

test('event type and step filters export only matching events in server order with existing metadata', async ({ page }) => {
  const state = await fixture(page, [
    event('event-first', exactJson),
    event('event-other-step', '{"other":true}', { stepId: 'step-other' }),
    event('event-other-type', '{"failed":true}', { eventType: 'step_failed' }),
    event('event-last', '9007199254740995', { actorType: 'system', actorId: 'flow-engine', requestId: null }),
  ])
  await page.getByRole('combobox', { name: 'Event type', exact: true }).selectOption('step_completed')
  await page.getByRole('combobox', { name: 'Event step', exact: true }).selectOption(stepId)
  await expect(page.getByText('2 of 4 events', { exact: true })).toBeVisible()
  const exported = await downloadEvents(page)
  expect(exported.text).toContain(exactJson)
  expect(exported.text).toContain('9007199254740995')
  expect(exported.text.indexOf('event-first')).toBeLessThan(exported.text.indexOf('event-last'))
  expect(exported.text).not.toContain('event-other-step')
  expect(exported.text).not.toContain('event-other-type')
  expect(exported.text).not.toContain('"payloadJson"')
  for (const value of ['eventId', 'eventType', 'stepId', 'payload', 'requestId', 'occurredAt', 'actorType', 'actorId']) {
    expect(exported.text).toContain(`"${value}"`)
  }
  for (const value of ['request-event-first', '2026-10-28T01:02:03Z', 'flow-engine', 'system', 'demo-owner']) {
    expect(exported.text).toContain(value)
  }
  expect(state.writes).toEqual([])
})

test('event details render exact payload JSON and stay inside a 375 pixel screen', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 })
  const state = await fixture(page)
  const details = page.locator('details').filter({ has: page.locator('summary').filter({ hasText: 'step_completed' }) })
  await details.locator(':scope > summary').click()
  const payload = details.getByText('Event payload', { exact: true }).locator('..').locator('pre')
  await expect(payload).toHaveText(exactJson)
  await expect(details).toContainText('Actor: user · demo-owner')
  await expect(details).toContainText('Request ID: request-event-exact')
  const bounds = await payload.boundingBox()
  expect(bounds).not.toBeNull()
  expect(bounds!.width).toBeLessThanOrEqual(375)
  expect(bounds!.x).toBeGreaterThanOrEqual(0)
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(375)
  expect(state.writes).toEqual([])
})

test('JSON null, large scalar, quoted number and boolean preserve their payload types in details and export', async ({ page }) => {
  const values = ['null', '9007199254740993', '"9007199254740993"', 'true']
  const state = await fixture(page, values.map((value, index) => event(`event-type-${index}`, value, {
    eventType: `payload_type_${index}`,
  })))
  for (const [index, value] of values.entries()) {
    const details = page.locator('details').filter({ has: page.locator('summary').filter({ hasText: `payload_type_${index}` }) })
    await details.locator(':scope > summary').click()
    await expect(details.getByText('Event payload', { exact: true }).locator('..').locator('pre')).toHaveText(value)
  }
  const exported = await downloadEvents(page)
  expect(exported.text).toMatch(/"payload"\s*:\s*null/)
  expect(exported.text).toMatch(/"payload"\s*:\s*9007199254740993\s*[,}]/)
  expect(exported.text).toMatch(/"payload"\s*:\s*"9007199254740993"/)
  expect(exported.text).toMatch(/"payload"\s*:\s*true/)
  expect(exported.text).not.toContain('"payloadJson"')
  expect(state.writes).toEqual([])
})

test('legacy events retain the old payload export shape and disclose possible numeric precision loss', async ({ page }) => {
  const legacy = event('event-legacy', exactJson)
  delete legacy.payloadJson
  const state = await fixture(page, [legacy])
  await page.locator('summary').filter({ hasText: 'step_completed' }).click()
  await expect(page.getByText(/Legacy JSON response: numeric precision may already be lost\./).first()).toBeVisible()
  const payload = page.getByText('Event payload', { exact: true }).locator('..').locator('pre')
  await expect(payload).toContainText('9007199254740992')
  const exported = await downloadEvents(page)
  expect(exported.text).toContain('9007199254740992')
  expect(exported.text).not.toContain('"payloadJson"')
  expect(exported.text).not.toMatch(/"payload"\s*:\s*"\{/)
  expect(state.writes).toEqual([])
})

test('an absent stored event payload stays distinct from explicit JSON null in details and exports as null', async ({ page }) => {
  const state = await fixture(page, [
    event('event-absent', 'null', { eventType: 'payload_absent', payloadJson: null }),
    event('event-null', 'null', { eventType: 'payload_null' }),
  ])
  for (const [type, text] of [['payload_absent', 'No JSON value.'], ['payload_null', 'null']]) {
    const details = page.locator('details').filter({ has: page.locator('summary').filter({ hasText: type }) })
    await details.locator(':scope > summary').click()
    await expect(details.getByText('Event payload', { exact: true }).locator('..').locator('pre')).toHaveText(text)
  }
  await expect(page.getByText(/Legacy JSON response: numeric precision may already be lost\./)).toHaveCount(0)
  const exported = await downloadEvents(page)
  expect(exported.text.match(/"payload"\s*:\s*null/g)).toHaveLength(2)
  expect(exported.text).not.toContain('"payloadJson"')
  expect(state.writes).toEqual([])
})

for (const [label, payloadJson] of [['malformed', '{"integer":'], ['wrong-type', { integer: 7 }]] as const) {
  test(`${label} exact event JSON blocks download and reports an error without exporting rounded fallback`, async ({ page }) => {
    const state = await fixture(page, [event('event-invalid', exactJson, { payloadJson })])
    const downloads: string[] = []
    page.on('download', (download) => downloads.push(download.suggestedFilename()))
    await expect(page.getByRole('alert').filter({ hasText: /event|payload|JSON/i })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Download visible events (JSON)', exact: true })).toBeDisabled()
    await page.locator('summary').filter({ hasText: 'step_completed' }).click()
    const details = page.locator('details').filter({ has: page.locator('summary').filter({ hasText: 'step_completed' }) })
    await expect(details).not.toContainText('9007199254740992')
    expect(downloads).toEqual([])
    expect(state.writes).toEqual([])
  })
}

test('a denied event revalidation removes cached payload details and the export button', async ({ page }) => {
  await page.clock.install()
  const state = await fixture(page)
  await page.locator('summary').filter({ hasText: 'step_completed' }).click()
  await expect(page.getByText('Event payload', { exact: true }).locator('..').locator('pre')).toHaveText(exactJson)
  const initialReads = state.reads
  state.denied = true
  await page.clock.fastForward(31_000)
  await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')))
  await expect.poll(() => state.reads).toBeGreaterThan(initialReads)
  await page.clock.fastForward(2_000)
  await expect(page.getByRole('alert').filter({ hasText: 'Event access revoked.' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Download visible events (JSON)', exact: true })).toHaveCount(0)
  await expect(page.getByText('Event payload', { exact: true })).toHaveCount(0)
  await expect(page.locator('pre').filter({ hasText: exactJson })).toHaveCount(0)
  expect(state.writes).toEqual([])
})
