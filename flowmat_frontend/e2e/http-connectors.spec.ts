import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

const projectId = 'prj-http'
const workflow = { workflowId: 'wf-http', projectId, workflowName: 'API pipeline', workflowType: 'standard', workflowStatus: 'draft' }
const operation = { operationId: 'send', method: 'POST', relativePath: '/jobs', successStatuses: [202] }
const profile = { profileId: 'profile-http', projectId, name: 'Dispatch', baseOrigin: 'https://api.example.com',
  credentialAlias: 'dispatch', enabled: true, version: 2, currentRevision: 3, profileVersionId: 'profile-v3',
  operations: [operation], createdAt: '', updatedAt: '' }
const process = { processId: 'node-http', projectId, workflowId: workflow.workflowId, processName: 'Send job', processType: 'process',
  nodeType: 'process', processStatus: 'ready', colorScheme: 'sky', posX: 200, posY: 160, width: 180, height: 80,
  version: 1, versionNonce: 1 }
const port = { processIoId: 'out-http', processId: process.processId, projectId, direction: 'output', ioName: 'Result',
  ioType: 'data', resourceType: 'data', itemId: null, quantity: null, unit: null, requiredYn: 'N', allowShortageYn: 'N', colorScheme: 'sky' }

async function setup(page: Page, handler: (pathname: string, method: string, route: import('@playwright/test').Route) => Promise<boolean>, role = 'owner') {
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (await handler(pathname, request.method(), route)) return
    if (request.method() !== 'GET') return route.fulfill({ status: 404, contentType: 'application/json',
      body: JSON.stringify({ success: false, data: null, message: 'Unexpected mutation' }) })
    if (pathname === '/api/project-members') return ok(route, [{ projectMemberId: 'member', projectId,
      userId: 'demo-owner', projectRole: role, memberStatus: 'active' }])
    if (pathname === '/api/workflows') return ok(route, [workflow])
    if (pathname === `/api/workflows/${workflow.workflowId}/canvas`) return ok(route, {
      workflow, graphSeq: 0, processes: [process], processIos: [port], connections: [], annotations: [], currentUserRole: role })
    if (pathname.endsWith('/editor-document')) return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 },
      nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    if (pathname.endsWith('/execution-policy')) return ok(route, { processId: process.processId, version: 0 })
    if (pathname === `/api/projects/${projectId}/http-profiles`) return ok(route, [profile])
    if (pathname === `/api/projects/${projectId}/http-profiles/by-version/${profile.profileVersionId}/metadata`) return ok(route,
      { ...profile, revision: 3 })
    return ok(route, [])
  })
  await mockedLogin(page)
}

const pinnedProfile = { ...profile, profileVersionId: 'profile-v1', revision: 1, operations: [operation,
  { operationId: 'inspect', method: 'GET', relativePath: '/old-status', successStatuses: [200] }] }
const pinnedBinding = { processId: process.processId, profileVersionId: 'profile-v1', operationId: 'send',
  outputPortId: port.processIoId, requestMappingJson: '{"literal":{"amount":9007199254740993.2300}}',
  responseMappingJson: '{"from":""}', version: 7 }

async function openHttpBinding(page: Page) {
  await page.goto(`/projects/${projectId}/workflows/${workflow.workflowId}`)
  await page.locator('.react-flow__node').filter({ hasText: 'Send job' }).click()
  const panel = page.getByRole('region', { name: 'HTTP execution binding' })
  await panel.getByRole('button', { name: 'Edit HTTP binding' }).click()
  return panel
}

test('an editor can reopen an old pinned revision and select one of its approved operations without upgrading it', async ({ page }) => {
  let saved = ''
  await setup(page, async (pathname, method, route) => {
    if (pathname.endsWith('/execution-binding')) {
      if (method === 'PUT') {
        saved = route.request().postData() ?? ''
        await ok(route, { ...pinnedBinding, ...JSON.parse(saved), version: 8 })
      } else await ok(route, pinnedBinding)
      return true
    }
    if (pathname.endsWith('/by-version/profile-v1/metadata')) { await ok(route, pinnedProfile); return true }
    if (pathname.endsWith('/profile-http/versions')) { await ok(route, [{ ...profile, revision: 3 }, pinnedProfile]); return true }
    return false
  }, 'editor')
  const panel = await openHttpBinding(page)
  await expect(panel.getByLabel('HTTP profile')).toHaveValue(profile.profileId)
  await expect(panel.getByLabel('Profile revision')).toHaveValue('profile-v1')
  await panel.getByLabel('Approved operation').selectOption('inspect')
  await expect(panel.getByLabel('Request mapping (JSON)')).toHaveValue(pinnedBinding.requestMappingJson)
  await panel.getByRole('button', { name: 'Save HTTP binding' }).click()
  await expect(panel).toContainText('Saved. Publish a revision')
  expect(JSON.parse(saved)).toMatchObject({ expectedVersion: 7, profileVersionId: 'profile-v1', operationId: 'inspect' })
  expect(saved).toContain('9007199254740993.2300')
})

test('late pinned metadata cannot undo an editor choosing another profile', async ({ page }) => {
  let release!: () => void
  let requested = false
  const held = new Promise<void>((resolve) => { release = resolve })
  const second = { ...profile, profileId: 'profile-second', profileVersionId: 'second-v1', currentRevision: 1, name: 'Other profile' }
  await setup(page, async (pathname, _method, route) => {
    if (pathname.endsWith('/execution-binding')) { await ok(route, pinnedBinding); return true }
    if (pathname === `/api/projects/${projectId}/http-profiles`) { await ok(route, [profile, second]); return true }
    if (pathname.endsWith('/by-version/profile-v1/metadata')) { requested = true; await held; await ok(route, pinnedProfile); return true }
    if (pathname.endsWith('/by-version/second-v1/metadata')) { await ok(route, { ...second, revision: 1 }); return true }
    if (pathname.endsWith('/profile-second/versions')) { await ok(route, [{ ...second, revision: 1 }]); return true }
    if (pathname.endsWith('/profile-http/versions')) { await ok(route, [pinnedProfile]); return true }
    return false
  }, 'editor')
  const panel = await openHttpBinding(page)
  try {
    await expect.poll(() => requested).toBe(true)
    await panel.getByLabel('HTTP profile').selectOption(second.profileId)
    await panel.getByLabel('Profile revision').selectOption(second.profileVersionId)
    await panel.getByLabel('Approved operation').selectOption('send')
    const oldResponse = page.waitForResponse((response) => response.url().endsWith('/by-version/profile-v1/metadata'))
    release()
    await oldResponse
    await expect(panel.getByLabel('HTTP profile')).toHaveValue(second.profileId)
    await expect(panel.getByLabel('Profile revision')).toHaveValue(second.profileVersionId)
    await expect(panel.getByLabel('Approved operation')).toHaveValue('send')
    await expect(panel.getByLabel('Request mapping (JSON)')).toHaveValue(pinnedBinding.requestMappingJson)
  } finally { release() }
})

test('choosing another historical revision keeps its known parent when a redundant metadata read fails', async ({ page }) => {
  let saved = ''
  const middle = { ...pinnedProfile, profileVersionId: 'profile-v2', revision: 2 }
  await setup(page, async (pathname, method, route) => {
    if (pathname.endsWith('/execution-binding')) {
      if (method === 'PUT') { saved = route.request().postData() ?? ''; await ok(route, { ...pinnedBinding, ...JSON.parse(saved), version: 8 }) }
      else await ok(route, pinnedBinding)
      return true
    }
    if (pathname.endsWith('/by-version/profile-v1/metadata')) { await ok(route, pinnedProfile); return true }
    if (pathname.endsWith('/profile-http/versions')) { await ok(route, [{ ...profile, revision: 3 }, middle, pinnedProfile]); return true }
    if (pathname.endsWith('/by-version/profile-v2/metadata')) {
      await route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: 'Redundant metadata is unavailable.' }) }); return true
    }
    return false
  }, 'editor')
  const panel = await openHttpBinding(page)
  await expect(panel.getByLabel('Profile revision').getByRole('option', { name: /Revision 2/ })).toHaveCount(1)
  await panel.getByLabel('Profile revision').selectOption('profile-v2')
  await expect(panel.getByLabel('HTTP profile')).toHaveValue(profile.profileId)
  await panel.getByLabel('Approved operation').selectOption('inspect')
  await expect(panel.getByRole('button', { name: 'Save HTTP binding' })).toBeEnabled()
  await panel.getByRole('button', { name: 'Save HTTP binding' }).click()
  await expect(panel).toContainText('Saved. Publish a revision')
  expect(JSON.parse(saved)).toMatchObject({ profileVersionId: 'profile-v2', operationId: 'inspect', expectedVersion: 7 })
})

for (const state of ['disabled', 'removed'] as const) {
  test(`a ${state} pinned profile stays inspectable and clearable without becoming selectable for saving`, async ({ page }) => {
    let clearBody: Record<string, unknown> | undefined
    let historyReads = 0
    await setup(page, async (pathname, method, route) => {
      if (pathname === `/api/projects/${projectId}/http-profiles`) {
        await ok(route, state === 'removed' ? [] : [{ ...profile, enabled: false }]); return true
      }
      if (pathname.endsWith('/execution-binding')) {
        if (method === 'PUT') {
          clearBody = route.request().postDataJSON()
          await ok(route, { ...pinnedBinding, profileVersionId: null, operationId: null, outputPortId: null, version: 8 })
        } else await ok(route, pinnedBinding)
        return true
      }
      if (pathname.endsWith('/by-version/profile-v1/metadata')) { await ok(route, pinnedProfile); return true }
      if (pathname.endsWith('/profile-http/versions')) { historyReads += 1; await ok(route, [pinnedProfile]); return true }
      return false
    }, 'editor')
    const panel = await openHttpBinding(page)
    await expect(panel.getByLabel('HTTP profile')).toHaveValue(profile.profileId)
    await expect(panel.getByLabel('Approved operation').getByRole('option', { name: /inspect.*GET/ })).toHaveCount(1)
    await expect(panel).toContainText('disabled or removed')
    await expect(panel.getByRole('button', { name: 'Save HTTP binding' })).toBeDisabled()
    await panel.getByRole('button', { name: 'Clear HTTP binding' }).click()
    await expect(panel).toContainText('Saved. Publish a revision')
    expect(clearBody).toMatchObject({ expectedVersion: 7, profileVersionId: null, operationId: null })
    expect(historyReads).toBe(0)
  })
}

test('unavailable pinned metadata preserves mapping and version while allowing the owner to clear', async ({ page }) => {
  let clearBody: Record<string, unknown> | undefined
  await setup(page, async (pathname, method, route) => {
    if (pathname.endsWith('/execution-binding')) {
      if (method === 'PUT') {
        clearBody = route.request().postDataJSON()
        await ok(route, { ...pinnedBinding, profileVersionId: null, version: 8 })
      } else await ok(route, pinnedBinding)
      return true
    }
    if (pathname.endsWith('/by-version/profile-v1/metadata')) {
      await route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: 'Pinned revision unavailable.' }) }); return true
    }
    return false
  })
  const panel = await openHttpBinding(page)
  await expect(panel.getByRole('alert')).toContainText('Pinned revision unavailable.')
  await expect(panel.getByLabel('Profile revision')).toHaveValue('profile-v1')
  await expect(panel.getByLabel('Request mapping (JSON)')).toHaveValue(pinnedBinding.requestMappingJson)
  await expect(panel.getByRole('button', { name: 'Save HTTP binding' })).toBeDisabled()
  await panel.getByRole('button', { name: 'Clear HTTP binding' }).click()
  await expect(panel).toContainText('Saved. Publish a revision')
  expect(clearBody).toMatchObject({ expectedVersion: 7, profileVersionId: null })
})

test('an owner creates an HTTP profile and disables its approved operations', async ({ page }) => {
  const bodies: unknown[] = []
  let profiles: typeof profile[] = []
  await setup(page, async (pathname, method, route) => {
    if (pathname === `/api/projects/${projectId}/http-profiles`) {
      if (method === 'POST') {
        bodies.push(route.request().postDataJSON())
        profiles = [profile]
        await ok(route, profile)
      } else await ok(route, profiles)
      return true
    }
    if (pathname.endsWith('/profile-http/status')) {
      bodies.push(route.request().postDataJSON())
      profiles = [{ ...profile, enabled: false, version: 3 }]
      await ok(route, profiles[0]); return true
    }
    return false
  })
  await page.goto(`/projects/${projectId}/settings`)
  const panel = page.getByRole('region', { name: 'HTTP profiles' })
  await panel.getByRole('button', { name: 'Add HTTP profile' }).click()
  await panel.getByLabel('Profile name').fill('Dispatch')
  await panel.getByLabel('Base origin').fill('https://api.example.com')
  await panel.getByLabel('Registered credential alias').fill('dispatch')
  await panel.getByLabel('Approved operations (JSON)').fill(JSON.stringify([operation]))
  await panel.getByRole('button', { name: 'Save profile' }).click()
  await expect(panel.getByText('Dispatch', { exact: true })).toBeVisible()
  expect(bodies[0]).toMatchObject({ name: 'Dispatch', credentialAlias: 'dispatch', operations: [operation] })
  expect(bodies[0]).not.toHaveProperty('password')
  await panel.getByRole('button', { name: 'Disable Dispatch' }).click()
  await expect(panel).toContainText('Disabled')
  expect(bodies[1]).toEqual({ expectedVersion: 2, enabled: false })
})

test('an editor binds a pinned profile revision and previews exact JSON without HTTP execution', async ({ page }) => {
  const exactMapping = '{"literal":{"amount":9007199254740993.2300}}'
  let savedBody = ''
  let previewBody = ''
  const empty = { processId: process.processId, profileVersionId: null, operationId: null, outputPortId: null,
    requestMapping: null, responseMapping: null, requestMappingJson: null, responseMappingJson: null, version: 0 }
  await setup(page, async (pathname, method, route) => {
    if (pathname.endsWith('/execution-binding')) {
      if (method === 'PUT') {
        savedBody = route.request().postData() ?? ''
        await ok(route, { ...JSON.parse(savedBody), processId: process.processId, version: 1,
          requestMappingJson: exactMapping, responseMappingJson: '{"from":""}' })
      } else await ok(route, empty)
      return true
    }
    if (pathname.endsWith('/profile-http/versions')) {
      await ok(route, [{ ...profile, revision: 3 }]); return true
    }
    if (pathname.endsWith('/json-mapping/preview')) {
      previewBody = route.request().postData() ?? ''
      await ok(route, { version: 1, result: {}, resultJson: '{"amount":9007199254740993.2300}' }); return true
    }
    return false
  }, 'editor')
  await page.goto(`/projects/${projectId}/workflows/${workflow.workflowId}`)
  await page.locator('.react-flow__node').filter({ hasText: 'Send job' }).click()
  const panel = page.getByRole('region', { name: 'HTTP execution binding' })
  await panel.getByRole('button', { name: 'Edit HTTP binding' }).click()
  await panel.getByLabel('HTTP profile').selectOption(profile.profileId)
  await panel.getByLabel('Profile revision').selectOption(profile.profileVersionId)
  await panel.getByLabel('Approved operation').selectOption('send')
  await panel.getByLabel('Result output port').selectOption(port.processIoId)
  await panel.getByLabel('Request mapping (JSON)').fill(exactMapping)
  await panel.getByLabel('Preview source (JSON)').fill('{}')
  await panel.getByRole('button', { name: 'Preview request mapping' }).click()
  await expect(panel.getByLabel('Mapping preview result')).toHaveText('{"amount":9007199254740993.2300}')
  expect(previewBody).toContain(exactMapping)
  await panel.getByRole('button', { name: 'Save HTTP binding' }).click()
  expect(savedBody).toContain(exactMapping)
  expect(JSON.parse(savedBody)).toMatchObject({ expectedVersion: 0, profileVersionId: 'profile-v3', outputPortId: 'out-http' })
  await expect(panel).toContainText('Saved. Publish a revision to use this binding in new runs.')
})

test('a lost browser response reuses its request ID and local apply never sends again', async ({ page }) => {
  const requestIds: string[] = []
  let applied = 0
  let evidence: Record<string, unknown>[] = []
  const run = { flowRunId: 'run-http', workflowRevisionId: 'revision-http', productionRunId: null,
    runType: 'actual', executionMode: 'graph', status: 'running', inputPayload: {}, outputPayload: null,
    startedAt: '2026-10-24T00:00:00Z', endedAt: null }
  await setup(page, async (pathname, method, route) => {
    if (pathname.endsWith('/revisions') && method === 'GET') {
      await ok(route, [{ workflowRevisionId: 'revision-http', workflowId: workflow.workflowId, revisionNo: 1, status: 'published' }]); return true
    }
    if (pathname.endsWith('/revisions/revision-http')) {
      await ok(route, { workflowRevisionId: 'revision-http', status: 'published', snapshot: { processes: [process],
        nodeBindings: [{ processId: process.processId, profileVersionId: 'profile-v3', operationId: 'send' }] } }); return true
    }
    if (pathname === '/api/flow-runs') { await ok(route, [run]); return true }
    if (pathname.endsWith('/run-http/steps')) {
      await ok(route, [{ stepId: 'step-http', nodeId: process.processId, sequenceNo: 1, status: 'running', inputSnapshot: {} }]); return true
    }
    if (pathname.endsWith('/step-http/attempts')) {
      await ok(route, [{ attemptId: 'attempt-http', attemptNo: 1, status: 'running' }]); return true
    }
    if (pathname.endsWith('/step-http/http-executions')) { await ok(route, evidence); return true }
    if (pathname.endsWith('/step-http/execute')) {
      const body = route.request().postDataJSON() as { requestId: string }
      requestIds.push(body.requestId)
      if (requestIds.length === 1) { await route.abort('failed'); return true }
      evidence = [{ executionId: 'http-result', stepId: 'step-http', attemptId: 'attempt-http', requestId: body.requestId,
        transportStatus: 'SUCCEEDED', applyStatus: 'APPLY_FAILED', httpStatus: 202, output: { ok: true }, outputJson: '{"ok":true}',
        startedAt: '2026-10-24T00:00:00Z', completedAt: '2026-10-24T00:00:01Z' }]
      await ok(route, evidence[0]); return true
    }
    if (pathname.endsWith('/http-executions/http-result/apply')) {
      applied += 1; evidence = [{ ...evidence[0], applyStatus: 'APPLIED' }]
      await ok(route, evidence[0]); return true
    }
    return false
  })
  await page.goto(`/projects/${projectId}/runs?view=flow-runs`)
  const panel = page.getByRole('region', { name: 'HTTP execution for step 1' })
  await expect(panel).toContainText('POST https://api.example.com/jobs')
  await panel.getByLabel('I understand this sends an external HTTP request').check()
  await panel.getByRole('button', { name: 'Execute HTTP request' }).click()
  await expect(panel.getByRole('alert')).toContainText('same request ID')
  await page.reload()
  await panel.getByLabel('I understand this sends an external HTTP request').check()
  await panel.getByRole('button', { name: 'Retry same request' }).click()
  await expect(panel).toContainText('Applying the result failed')
  expect(requestIds).toHaveLength(2)
  expect(requestIds[0]).toBe(requestIds[1])
  await panel.getByRole('button', { name: 'Apply saved result' }).click()
  await expect(panel).toContainText('Result applied')
  expect(applied).toBe(1)
  expect(requestIds).toHaveLength(2)
})

test('a viewer can inspect HTTP profiles and bindings without management or send actions', async ({ page }) => {
  let mutations = 0
  await setup(page, async (pathname, method, route) => {
    if (method !== 'GET') { mutations += 1; return false }
    if (pathname.endsWith('/execution-binding')) {
      await ok(route, { processId: process.processId, profileVersionId: profile.profileVersionId, operationId: 'send',
        outputPortId: port.processIoId, requestMappingJson: '{"from":""}', responseMappingJson: '{"from":""}', version: 2 })
      return true
    }
    return false
  }, 'viewer')
  await page.goto(`/projects/${projectId}/settings`)
  const profiles = page.getByRole('region', { name: 'HTTP profiles' })
  await expect(profiles.getByText('Dispatch', { exact: true })).toBeVisible()
  await expect(profiles.getByRole('button', { name: 'Add HTTP profile' })).toHaveCount(0)
  await expect(profiles.getByRole('button', { name: 'Disable Dispatch' })).toHaveCount(0)
  await page.goto(`/projects/${projectId}/workflows/${workflow.workflowId}`)
  await page.locator('.react-flow__node').filter({ hasText: 'Send job' }).click()
  const binding = page.getByRole('region', { name: 'HTTP execution binding' })
  await expect(binding).toContainText('Operation send')
  await expect(binding.getByRole('button', { name: 'Edit HTTP binding' })).toHaveCount(0)
  expect(mutations).toBe(0)
})

test('a successful response with no mapped output does not offer an impossible local apply', async ({ page }) => {
  await setup(page, async (pathname, _method, route) => {
    if (pathname.endsWith('/revisions')) {
      await ok(route, [{ workflowRevisionId: 'revision-http', revisionNo: 1, status: 'published' }]); return true
    }
    if (pathname.endsWith('/revisions/revision-http')) {
      await ok(route, { snapshot: { processes: [process], nodeBindings: [
        { processId: process.processId, profileVersionId: profile.profileVersionId, operationId: 'send' },
      ] } }); return true
    }
    if (pathname === '/api/flow-runs') {
      await ok(route, [{ flowRunId: 'run-http', workflowRevisionId: 'revision-http', productionRunId: null,
        runType: 'actual', executionMode: 'graph', status: 'running', startedAt: '2026-10-24T00:00:00Z' }]); return true
    }
    if (pathname.endsWith('/run-http/steps')) {
      await ok(route, [{ stepId: 'step-http', nodeId: process.processId, sequenceNo: 1, status: 'running' }]); return true
    }
    if (pathname.endsWith('/step-http/attempts')) {
      await ok(route, [{ attemptId: 'attempt-http', attemptNo: 1, status: 'running' }]); return true
    }
    if (pathname.endsWith('/step-http/http-executions')) {
      await ok(route, [{ executionId: 'http-result', stepId: 'step-http', attemptId: 'attempt-http', requestId: 'saved-request',
        transportStatus: 'SUCCEEDED', applyStatus: 'APPLY_FAILED', httpStatus: 200, errorCode: 'RESPONSE_MAPPING_FAILED',
        output: null, outputJson: null, startedAt: '2026-10-24T00:00:00Z', completedAt: '2026-10-24T00:00:01Z' }]); return true
    }
    return false
  })
  await page.goto(`/projects/${projectId}/runs?view=flow-runs`)
  const panel = page.getByRole('region', { name: 'HTTP execution for step 1' })
  await expect(panel).toContainText('No output was saved, so it cannot be applied again.')
  await expect(panel.getByRole('button', { name: 'Apply saved result' })).toHaveCount(0)
})

test('refresh recovers an applied HTTP result after a lost response and updates the parent run without another send', async ({ page }) => {
  let committed = false
  let sends = 0
  let runReads = 0
  let stepReads = 0
  let requestId = ''
  await setup(page, async (pathname, _method, route) => {
    if (pathname.endsWith('/revisions')) {
      await ok(route, [{ workflowRevisionId: 'revision-http', revisionNo: 1, status: 'published' }]); return true
    }
    if (pathname.endsWith('/revisions/revision-http')) {
      await ok(route, { snapshot: { processes: [process, { processId: 'node-after', processName: 'After HTTP' }], nodeBindings: [
        { processId: process.processId, profileVersionId: profile.profileVersionId, operationId: 'send' },
      ] } }); return true
    }
    if (pathname === '/api/flow-runs') {
      runReads += 1
      await ok(route, [{ flowRunId: 'run-http', workflowRevisionId: 'revision-http', productionRunId: null,
        runType: 'actual', executionMode: 'graph', status: 'running', startedAt: '2026-10-24T00:00:00Z' }]); return true
    }
    if (pathname.endsWith('/run-http/steps')) {
      stepReads += 1
      await ok(route, [{ stepId: 'step-http', nodeId: process.processId, sequenceNo: 1, status: committed ? 'completed' : 'running' },
        ...(committed ? [{ stepId: 'step-after', nodeId: 'node-after', sequenceNo: 2, status: 'planned' }] : [])]); return true
    }
    if (pathname.endsWith('/step-http/attempts')) {
      await ok(route, [{ attemptId: 'attempt-http', attemptNo: 1, status: committed ? 'completed' : 'running' }]); return true
    }
    if (pathname.endsWith('/step-http/http-executions')) {
      await ok(route, committed ? [{ executionId: 'http-result', stepId: 'step-http', attemptId: 'attempt-http', requestId,
        transportStatus: 'SUCCEEDED', applyStatus: 'APPLIED', httpStatus: 202, errorCode: null, output: { ok: true },
        outputJson: '{"ok":true}', startedAt: '2026-10-24T00:00:00Z', completedAt: '2026-10-24T00:00:01Z' }] : []); return true
    }
    if (pathname.endsWith('/step-http/execute')) {
      sends += 1
      requestId = (route.request().postDataJSON() as { requestId: string }).requestId
      committed = true
      await route.abort('failed'); return true
    }
    return false
  })
  await page.goto(`/projects/${projectId}/runs?view=flow-runs`)
  const panel = page.getByRole('region', { name: 'HTTP execution for step 1' })
  await panel.getByLabel('I understand this sends an external HTTP request').check()
  await panel.getByRole('button', { name: 'Execute HTTP request' }).click()
  await expect(panel.getByRole('alert')).toContainText('same request ID')
  const previousRunReads = runReads
  const previousStepReads = stepReads
  await panel.getByRole('button', { name: 'Refresh saved results' }).click()
  await expect(panel).toContainText('Result applied.')
  await expect(page.getByText('#2 After HTTP', { exact: true })).toBeVisible()
  await expect(page.getByText('#1 Send job', { exact: true }).locator('..')).toContainText('completed')
  expect(runReads).toBeGreaterThan(previousRunReads)
  expect(stepReads).toBeGreaterThan(previousStepReads)
  expect(sends).toBe(1)
})

async function setupPreviewRun(page: Page, preview: (route: import('@playwright/test').Route) => Promise<void>,
  state = { completed: false }, method = 'POST') {
  const writes: string[] = []
  await setup(page, async (pathname, requestMethod, route) => {
    if (requestMethod !== 'GET') writes.push(pathname)
    if (pathname.endsWith('/http-request-preview')) { await preview(route); return true }
    if (pathname.endsWith('/revisions')) {
      await ok(route, [{ workflowRevisionId: 'revision-http', revisionNo: 1, status: 'published' }]); return true
    }
    if (pathname.endsWith('/revisions/revision-http')) {
      await ok(route, { snapshot: { processes: [process], nodeBindings: [
        { processId: process.processId, profileVersionId: profile.profileVersionId, operationId: 'send' },
      ] } }); return true
    }
    if (pathname.endsWith('/by-version/profile-v3/metadata')) {
      await ok(route, { ...profile, revision: 3, operations: [{ ...operation, method }] }); return true
    }
    if (pathname === '/api/flow-runs') {
      await ok(route, [{ flowRunId: 'run-http', workflowRevisionId: 'revision-http', productionRunId: null,
        runType: 'actual', executionMode: 'graph', status: 'running', startedAt: '2026-10-24T00:00:00Z' }]); return true
    }
    if (pathname.endsWith('/run-http/steps')) {
      await ok(route, [{ stepId: 'step-http', nodeId: process.processId, sequenceNo: 1,
        status: state.completed ? 'completed' : 'running' }]); return true
    }
    if (pathname.endsWith('/step-http/attempts')) {
      await ok(route, [{ attemptId: 'attempt-http', attemptNo: 1, status: state.completed ? 'completed' : 'running' }]); return true
    }
    if (pathname.endsWith('/step-http/http-executions')) {
      await ok(route, state.completed ? [{ executionId: 'http-result', stepId: 'step-http', attemptId: 'attempt-http', requestId: 'done',
        transportStatus: 'SUCCEEDED', applyStatus: 'APPLIED', httpStatus: 202, errorCode: null, output: { ok: true },
        outputJson: '{"ok":true}', startedAt: '2026-10-24T00:00:00Z', completedAt: '2026-10-24T00:00:01Z' }] : []); return true
    }
    return false
  })
  await page.goto(`/projects/${projectId}/runs?view=flow-runs`)
  return writes
}

test('an owner manually previews exact run JSON without sending and a failed refresh removes the old preview', async ({ page }) => {
  let reads = 0
  const exact = '{"amount":9007199254740993.2300,"date":"2026-10-24","html":"<script>window.previewExecuted=true</script>"}'
  const writes = await setupPreviewRun(page, async (route) => {
    reads += 1
    if (reads === 1) await ok(route, { profileVersionId: profile.profileVersionId, operationId: 'send', method: 'POST',
      target: 'https://api.example.com/jobs', hasRequestBody: true, requestJson: exact })
    else await route.fulfill({ status: 409, contentType: 'application/json',
      body: JSON.stringify({ success: false, data: null, message: 'The step changed. Reload before previewing.' }) })
  })
  const panel = page.getByRole('region', { name: 'HTTP execution for step 1' })
  await expect(panel.getByRole('button', { name: 'Preview HTTP request', exact: true })).toBeVisible()
  expect(reads).toBe(0)
  await panel.getByRole('button', { name: 'Preview HTTP request', exact: true }).click()
  await expect(panel.getByLabel('HTTP request preview JSON')).toHaveText(exact)
  await expect(panel).toContainText('This preview does not send a request or reserve an attempt.')
  expect(await page.evaluate(() => 'previewExecuted' in window)).toBe(false)
  expect(writes).toEqual([])
  await panel.getByRole('button', { name: 'Preview HTTP request', exact: true }).click()
  await expect(panel.getByRole('alert')).toContainText('The step changed.')
  await expect(panel.getByLabel('HTTP request preview JSON')).toHaveCount(0)
  expect(reads).toBe(2)
  expect(writes).toEqual([])
})

test('a GET preview shows no request body rather than JSON null', async ({ page }) => {
  const writes = await setupPreviewRun(page, async (route) => {
    await ok(route, { profileVersionId: profile.profileVersionId, operationId: 'send', method: 'GET',
      target: 'https://api.example.com/jobs', hasRequestBody: false, requestJson: null })
  }, { completed: false }, 'GET')
  const panel = page.getByRole('region', { name: 'HTTP execution for step 1' })
  await panel.getByRole('button', { name: 'Preview HTTP request', exact: true }).click()
  await expect(panel.getByText('No request body (GET).', { exact: true })).toBeVisible()
  await expect(panel.getByLabel('HTTP request preview JSON')).toHaveCount(0)
  expect(writes).toEqual([])
})

test('a POST null preview is a body and a late preview cannot return after the step completes', async ({ page }) => {
  let reads = 0
  let releaseLate!: () => void
  const late = new Promise<void>((resolve) => { releaseLate = resolve })
  const state = { completed: false }
  const writes = await setupPreviewRun(page, async (route) => {
    reads += 1
    if (reads > 1) await late
    await ok(route, { profileVersionId: profile.profileVersionId, operationId: 'send', method: 'POST',
      target: 'https://api.example.com/jobs', hasRequestBody: true, requestJson: reads === 1 ? 'null' : '{"late":true}' })
  }, state)
  const panel = page.getByRole('region', { name: 'HTTP execution for step 1' })
  await panel.getByRole('button', { name: 'Preview HTTP request', exact: true }).click()
  await expect(panel.getByLabel('HTTP request preview JSON')).toHaveText('null')
  await expect(panel.getByText('No request body (GET).', { exact: true })).toHaveCount(0)
  await panel.getByRole('button', { name: 'Preview HTTP request', exact: true }).click()
  await expect.poll(() => reads).toBe(2)
  await expect(panel.getByLabel('HTTP request preview JSON')).toHaveCount(0)
  state.completed = true
  await panel.getByRole('button', { name: 'Refresh saved results' }).click()
  await expect(panel.getByRole('button', { name: 'Preview HTTP request', exact: true })).toHaveCount(0)
  const lateResponse = page.waitForResponse((response) => response.url().endsWith('/step-http/http-request-preview'))
  releaseLate()
  await lateResponse
  await expect(panel.getByLabel('HTTP request preview JSON')).toHaveCount(0)
  await expect(panel).not.toContainText('{"late":true}')
  expect(writes).toEqual([])
})
