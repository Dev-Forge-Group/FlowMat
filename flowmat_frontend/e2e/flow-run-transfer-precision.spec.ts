import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

const projectId = 'prj-transfer-json'
const workflowId = 'wf-transfer-json'
const revisionId = 'revision-transfer-json'
const runId = 'run-transfer-json'
const sourceStepId = 'step-http-source'
const targetStepId = 'step-data-target'
const connectionId = 'connection-data-transfer'
const profileVersionId = 'profile-transfer-v1'
const legacyWarning = 'Legacy transfer JSON: numeric precision may be inaccurate.'

type Transfer = { sent: string; received: string; lost: string; lossRate: number; unit?: string; legacy?: boolean }

async function fixture(page: Page, transfer: Transfer) {
  const state = { writes: [] as string[] }
  const unit = transfer.unit ? `,"unit":${JSON.stringify(transfer.unit)}` : ''
  const sentJson = `{"quantity":${transfer.sent}${unit}}`
  const receivedJson = `{"quantity":${transfer.received}${unit}}`
  const lossJson = `{"connectionId":"${connectionId}","targetNodeId":"node-data-target",`
    + `"sent":${transfer.sent},"lossRate":${transfer.lossRate},"lost":${transfer.lost},"arrived":${transfer.received}${unit}}`
  const sourceStep = { stepId: sourceStepId, flowRunId: runId, nodeId: 'node-http-source', sourceConnectionId: null,
    sourceStepId: null, sequenceNo: 1, status: 'completed', scheduledAt: null, errorCode: null, errorMessage: null,
    inputSnapshot: {}, outputSnapshot: JSON.parse(sentJson) as unknown,
    ...(transfer.legacy ? {} : { inputSnapshotJson: '{}', outputSnapshotJson: sentJson }) }
  const targetStep = { stepId: targetStepId, flowRunId: runId, nodeId: 'node-data-target', sourceConnectionId: connectionId,
    sourceStepId, sequenceNo: 2, status: 'running', scheduledAt: null, errorCode: null, errorMessage: null,
    inputSnapshot: JSON.parse(receivedJson) as unknown, outputSnapshot: null,
    ...(transfer.legacy ? {} : { inputSnapshotJson: receivedJson, outputSnapshotJson: null }) }
  const lossEvent = { eventId: 'event-data-loss', eventType: 'transfer_loss', stepId: sourceStepId,
    requestId: null, actorType: 'system', actorId: 'flow-engine', occurredAt: '2026-10-28T01:00:01Z',
    payload: JSON.parse(lossJson) as unknown, ...(transfer.legacy ? {} : { payloadJson: lossJson }) }
  const run = { flowRunId: runId, workflowId, projectId, workflowRevisionId: revisionId, productionRunId: null,
    runType: 'actual', executionMode: 'graph', status: 'running', inputPayload: {}, inputPayloadJson: '{}',
    outputPayload: null, outputPayloadJson: null, startedAt: '2026-10-28T01:00:00Z', endedAt: null }
  const operation = { operationId: 'read-data', method: 'POST', relativePath: '/data', successStatuses: [200] }
  const profile = { profileId: 'profile-transfer', profileVersionId, revision: 1, baseOrigin: 'https://api.example.com',
    credentialAlias: null, operations: [operation], createdAt: '2026-10-28T00:00:00Z' }
  const port = (processIoId: string, processId: string, direction: 'input' | 'output') => ({
    processIoId, processId, direction, name: processIoId, ioType: 'data', resourceType: 'data', itemId: null,
    role: null, schemaJson: null, validationRule: null, unit: transfer.unit ?? null,
  })
  const snapshot = { schemaVersion: 4,
    processes: [{ processId: 'node-http-source', processName: 'HTTP source', nodeType: 'process' },
      { processId: 'node-data-target', processName: 'Data consumer', nodeType: 'process' }],
    processIos: [port('port-data-out', 'node-http-source', 'output'), port('port-data-in', 'node-data-target', 'input')],
    connections: [{ connectionId, fromProcessId: 'node-http-source', toProcessId: 'node-data-target',
      fromIoId: 'port-data-out', toIoId: 'port-data-in', connectionType: 'data', connectionLabel: 'Data transfer',
      itemId: null, unit: transfer.unit ?? null, capacity: null, conditionExpr: null, failurePolicy: 'stop',
      splitKind: null, splitValue: null, lossRate: transfer.lossRate }],
    nodeBindings: [{ processId: 'node-http-source', profileVersionId, operationId: operation.operationId,
      outputPortId: 'port-data-out', requestMapping: { from: '' }, responseMapping: { from: '' } }],
  }
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') {
      state.writes.push(`${request.method()} ${pathname}`)
      return route.fulfill({ status: 405, contentType: 'application/json', body: JSON.stringify({
        success: false, data: null, message: 'Read-only data transfer fixture.',
      }) })
    }
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Exact data transfer', projectStatus: 'active' }])
    if (pathname === '/api/project-members') return ok(route, [{ projectMemberId: 'member-transfer-json', projectId,
      userId: 'demo-owner', projectRole: 'viewer', memberStatus: 'active' }])
    if (pathname === '/api/workflows') return ok(route, [{ workflowId, projectId, workflowName: 'HTTP data transfer' }])
    if (pathname.endsWith('/http-executions/attention')) return ok(route, { items: [], nextCursor: null })
    if (pathname.endsWith('/revisions')) return ok(route, [{ workflowRevisionId: revisionId, workflowId,
      revisionNo: 1, schemaVersion: 4, status: 'published', publishedAt: '2026-10-28T00:00:00Z' }])
    if (pathname.endsWith(`/revisions/${revisionId}`)) return ok(route, { workflowRevisionId: revisionId,
      revisionNo: 1, status: 'published', snapshot })
    if (pathname === '/api/flow-runs') return ok(route, [run])
    if (pathname === `/api/flow-runs/${runId}/steps`) return ok(route, [sourceStep, targetStep])
    if (pathname === `/api/flow-runs/${runId}/events`) return ok(route, [lossEvent])
    if (pathname.endsWith(`/by-version/${profileVersionId}/metadata`)) return ok(route, profile)
    if (pathname.endsWith(`/${sourceStepId}/http-executions`)) return ok(route, [{ executionId: 'execution-http-source',
      stepId: sourceStepId, attemptId: 'attempt-http-source', requestId: '41a3bf55-cf6d-4b46-ad1b-c05f55c216c7',
      transportStatus: 'SUCCEEDED', applyStatus: 'APPLIED', httpStatus: 200, errorCode: null,
      output: JSON.parse(sentJson) as unknown, outputJson: sentJson,
      startedAt: '2026-10-28T01:00:00Z', completedAt: '2026-10-28T01:00:01Z' }])
    if (pathname.endsWith('/attempts')) return ok(route, [{
      attemptId: pathname.includes(sourceStepId) ? 'attempt-http-source' : 'attempt-data-target', attemptNo: 1,
      status: pathname.includes(sourceStepId) ? 'completed' : 'running', startedAt: '2026-10-28T01:00:00Z',
      endedAt: pathname.includes(sourceStepId) ? '2026-10-28T01:00:01Z' : null,
      retryAt: null, errorCode: null, errorMessage: null,
    }])
    if (pathname.endsWith('/lineage')) return ok(route, { step: targetStep, ancestors: [sourceStep], descendants: [] })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/runs?view=flow-runs`)
  const target = page.getByRole('group', { name: 'Flow step 2', exact: true })
  await expect(target.getByText('#2 Data consumer', { exact: true })).toBeVisible()
  return { state, target, receivedJson, lossJson }
}

for (const [name, sent, received] of [
  ['large integer', '18014398509481986', '9007199254740993.0000'],
  ['long decimal', '1999999999999998.2468', '999999999999999.1234'],
] as const) {
  test(`an HTTP data graph shows exact received and lost ${name} quantities instead of rounded API objects`, async ({ page }) => {
    const { state, target, receivedJson, lossJson } = await fixture(page, { sent, received, lost: received, lossRate: 0.5 })
    await expect(target.getByText(`received ${received} · lost ${received}`, { exact: true })).toBeVisible()
    await target.getByRole('button', { name: 'Show attempts', exact: true }).click()
    await expect(target.getByText('Input snapshot', { exact: true }).locator('..').locator('pre')).toHaveText(receivedJson)
    const loss = page.locator('details').filter({ has: page.locator('summary').filter({ hasText: 'transfer_loss' }) })
    await loss.locator(':scope > summary').click()
    await expect(loss.getByText('Event payload', { exact: true }).locator('..').locator('pre')).toHaveText(lossJson)
    expect(state.writes).toEqual([])
  })
}

test('ordinary data transfer quantities keep their unit and exact saved decimal tokens', async ({ page }) => {
  const { state, target } = await fixture(page, { sent: '70', received: '69.3000', lost: '0.7000', lossRate: 0.01, unit: 'records' })
  await expect(target.getByText('received 69.3000 records · lost 0.7000 records', { exact: true })).toBeVisible()
  expect(state.writes).toEqual([])
})

test('legacy transfer JSON without exact source fields warns that the numeric precision may already be lost', async ({ page }) => {
  const { state, target } = await fixture(page, { sent: '18014398509481986', received: '9007199254740993.0000',
    lost: '9007199254740993.0000', lossRate: 0.5, legacy: true })
  await expect(target.getByText(legacyWarning, { exact: true })).toBeVisible()
  await target.getByRole('button', { name: 'Show attempts', exact: true }).click()
  await expect(target.getByText('Legacy JSON response: numeric precision may already be lost.', { exact: true })).toBeVisible()
  expect(state.writes).toEqual([])
})

test('a browser without primitive JSON source context never substitutes rounded transfer quantities for exact JSON', async ({ page }) => {
  await page.addInitScript(() => {
    const parse = JSON.parse
    JSON.parse = (text: string, reviver?: (this: unknown, key: string, value: unknown) => unknown): unknown => {
      if (!reviver) return parse(text)
      return parse(text, function (this: unknown, key: string, value: unknown) {
        return reviver.call(this, key, value)
      })
    }
  })
  const { state, target, receivedJson, lossJson } = await fixture(page, { sent: '18014398509481986',
    received: '9007199254740993.0000', lost: '9007199254740993.0000', lossRate: 0.5 })
  await expect(target).toContainText('received quantity unavailable')
  await expect(target).toContainText('lost quantity unavailable')
  await expect(target).not.toContainText('received 9007199254740992')
  await expect(target).not.toContainText('lost 9007199254740992')
  await target.getByRole('button', { name: 'Show attempts', exact: true }).click()
  await expect(target.getByText('Input snapshot', { exact: true }).locator('..').locator('pre')).toHaveText(receivedJson)
  const loss = page.locator('details').filter({ has: page.locator('summary').filter({ hasText: 'transfer_loss' }) })
  await loss.locator(':scope > summary').click()
  await expect(loss.getByText('Event payload', { exact: true }).locator('..').locator('pre')).toHaveText(lossJson)
  expect(state.writes).toEqual([])
})

test('a valid rounded transfer keeps the exact exponent token of its small loss', async ({ page }) => {
  const { state, target, lossJson } = await fixture(page, { sent: '0.00010001', received: '0.0001', lost: '1E-8', lossRate: 0.5 })
  await expect(target.getByText('received 0.0001 · lost 1E-8', { exact: true })).toBeVisible()
  const loss = page.locator('details').filter({ has: page.locator('summary').filter({ hasText: 'transfer_loss' }) })
  await loss.locator(':scope > summary').click()
  await expect(loss.getByText('Event payload', { exact: true }).locator('..').locator('pre')).toHaveText(lossJson)
  expect(state.writes).toEqual([])
})
