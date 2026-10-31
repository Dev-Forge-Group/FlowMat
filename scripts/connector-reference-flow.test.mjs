import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { verifyReferenceFlow } from './connector-reference-flow.mjs'

const contract = JSON.parse(await readFile(new URL('./connector-reference-contract.json', import.meta.url), 'utf8'))
const referenceOrigin = 'http://192.168.50.10:24001'
const projectId = 'prj-reference-unit'
const confidential = 'synthetic-private-error-value-do-not-reflect'
const copy = (value) => structuredClone(value)
const ok = (data) => ({ status: 200, body: { success: true, data: copy(data) } })

// This fixture only exercises the acceptance driver's assertions. It is not a real backend or provider.
function fixture({ change, previewSends = false, replaySends = false } = {}) {
  const calls = []
  const processes = []
  const ports = []
  const connections = []
  const steps = []
  const attempts = new Map()
  const counts = { requests: 0, processed: 0, replayed: 0 }
  const run = { flowRunId: 'run-reference', workflowRevisionId: 'revision-reference', productionRunId: null,
    status: 'running', executionMode: 'graph', runType: 'actual',
    startedAt: '2026-10-28T01:02:03.000Z', endedAt: null }
  let evidence
  let graphInput
  let binding
  let finished = false
  const events = []
  function event(eventType, stepId = null) {
    events.push({ eventId: `event-${events.length}`, eventType, stepId,
      payload: {}, payloadJson: '{}', actorType: 'user', actorId: 'reference-owner', occurredAt: '2026-10-28T01:02:03Z' })
  }
  function addStep(index, input, source) {
    const step = { stepId: `step-${index + 1}`, nodeId: processes[index].processId,
      sequenceNo: index + 1, status: 'planned', sourceStepId: source?.stepId ?? null,
      sourceConnectionId: source ? connections[index - 1].connectionId : null,
      inputSnapshot: copy(input), inputSnapshotJson: JSON.stringify(input), outputSnapshot: null, outputSnapshotJson: null }
    steps.push(step)
    event('step_created', step.stepId)
    return step
  }
  function complete(step, output) {
    step.status = 'completed'
    step.outputSnapshot = copy(output)
    step.outputSnapshotJson = JSON.stringify(output)
    attempts.set(step.stepId, [{ attemptId: `attempt-${step.stepId}`, attemptNo: 1, status: 'completed',
      startedAt: '2026-10-28T01:02:03.000Z', endedAt: '2026-10-28T01:02:04.000Z' }])
    event('step_completed', step.stepId)
    if (step.sequenceNo < 4) addStep(step.sequenceNo, output, step)
  }
  const request = async (options) => {
    const call = { actor: 'owner', ...copy(options) }
    calls.push(call)
    const { method, path, body, actor } = call
    let result
    if (method === 'POST' && path === '/workflows') result = ok({ workflowId: 'workflow-reference', ...body })
    else if (method === 'POST' && path === '/processes') {
      const process = { processId: `process-${processes.length + 1}`, ...body }
      processes.push(process)
      result = ok(process)
    } else if (method === 'POST' && path === '/process-ios') {
      const port = { processIoId: `port-${ports.length + 1}`, ...body }
      ports.push(port)
      result = ok(port)
    } else if (method === 'POST' && path === '/process-connections') {
      const connection = { connectionId: `connection-${connections.length + 1}`, ...body }
      connections.push(connection)
      result = ok(connection)
    } else if (method === 'POST' && path === `/projects/${projectId}/http-profiles`) {
      result = ok({ profileId: 'profile-reference', profileVersionId: 'profile-version-reference', version: 0, ...body })
    } else if (method === 'PUT' && path === '/processes/process-3/execution-binding') {
      binding = copy(body)
      result = ok({ processId: 'process-3', version: 1, ...body })
    } else if (method === 'POST' && path === '/workflows/workflow-reference/revisions') {
      result = ok({ workflowRevisionId: 'revision-reference', workflowId: 'workflow-reference', revisionNo: 1,
        schemaVersion: 4, status: 'published', snapshot: { schemaVersion: 4, processes, processIos: ports, connections,
          nodeBindings: [{ processId: 'process-3', ...binding }] } })
    } else if (method === 'POST' && path === '/flow-runs/graph') {
      graphInput = copy(body.inputPayload)
      run.inputPayload = graphInput
      run.inputPayloadJson = JSON.stringify(graphInput)
      event('run_started')
      addStep(0, graphInput)
      result = ok(run)
    } else if (method === 'GET' && path === '/flow-runs/run-reference/steps') result = ok(steps)
    else if (method === 'GET' && path === '/flow-runs/run-reference/events') result = ok(events)
    else if (method === 'GET' && path === '/flow-runs/run-reference') result = ok(run)
    else if (method === 'POST' && path === '/flow-runs/run-reference/finish') {
      finished = true
      run.status = 'finished'
      run.endedAt = '2026-10-28T01:02:05.000Z'
      run.outputPayload = copy(body.outputPayload)
      run.outputPayloadJson = JSON.stringify(body.outputPayload)
      event('run_finished')
      result = ok(run)
    } else {
      const match = /^\/flow-runs\/run-reference\/steps\/(step-[1-4])\/(start|complete|execute|attempts|http-request-preview|http-executions)$/.exec(path)
      assert.ok(match, `Unexpected acceptance request: ${method} ${path}`)
      const [, stepId, action] = match
      const step = steps.find((entry) => entry.stepId === stepId)
      assert.ok(step, 'The driver must follow steps created by the graph')
      if (action === 'start' && method === 'POST') { step.status = 'running'; event('step_started', stepId); result = ok(step) }
      else if (action === 'complete' && method === 'POST') { complete(step, body.outputSnapshot); result = ok(step) }
      else if (action === 'attempts' && method === 'GET') result = ok(attempts.get(stepId) ?? [])
      else if (action === 'http-executions' && method === 'GET') result = ok(evidence ? [evidence] : [])
      else if (action === 'http-request-preview' && method === 'GET') {
        if (previewSends) counts.requests += 1
        result = ok({ profileVersionId: 'profile-version-reference', operationId: 'validate-record', method: 'POST',
          target: `${referenceOrigin}/v1/records/validate`, hasRequestBody: true,
          requestJson: JSON.stringify(contract.samples.request) })
      } else if (action === 'execute' && method === 'POST') {
        if (actor !== 'owner') result = { status: 403, body: { success: false, message: confidential } }
        else if (evidence) {
          assert.equal(body.requestId, evidence.requestId)
          if (replaySends) counts.requests += 1
          result = ok(evidence)
        } else {
          counts.requests += 1
          counts.processed += 1
          evidence = { executionId: 'execution-reference', stepId, attemptId: `attempt-${stepId}`, requestId: body.requestId,
            transportStatus: 'SUCCEEDED', applyStatus: 'APPLIED', httpStatus: 200, errorCode: null,
            output: copy(contract.samples.outputPayload), outputJson: JSON.stringify(contract.samples.outputPayload),
            startedAt: '2026-10-28T01:02:03.000Z', completedAt: '2026-10-28T01:02:04.000Z' }
          event('step_started', stepId)
          complete(step, evidence.output)
          result = ok(evidence)
        }
      } else assert.fail(`Unexpected acceptance action: ${method} ${action}`)
    }
    return change ? change(call, result, { finished, steps, evidence }) : result
  }
  return { calls, processes, ports, connections, steps, counts,
    options: { request, contract: copy(contract), referenceOrigin, providerCounts: () => ({ ...counts }), projectId } }
}

async function rejectsSafely(options) {
  await assert.rejects(verifyReferenceFlow(options), (error) => {
    assert.match(error.message, /^REFERENCE_FLOW_[A-Z0-9_]+$/)
    assert.equal(error.message.includes(confidential), false)
    return true
  })
}

test('the driver builds and verifies four recorded steps, mappings, permission refusals and one provider transmission', async () => {
  const f = fixture()
  const report = await verifyReferenceFlow(f.options)
  assert.equal(f.processes.length, 4)
  assert.equal(f.ports.length, 6)
  assert.equal(f.connections.length, 3)
  assert.equal(f.steps.length, 4)
  assert.ok(f.steps.every((step) => step.status === 'completed'))
  assert.deepEqual(Object.fromEntries(f.ports.map((port) => [`${port.processId}:${port.direction}`, port.resourceType])), {
    'process-1:output': 'file', 'process-2:input': 'file', 'process-2:output': 'data',
    'process-3:input': 'data', 'process-3:output': 'data', 'process-4:input': 'data',
  })
  for (let index = 0; index < 3; index += 1) {
    const connection = f.connections.find((entry) => entry.fromProcessId === `process-${index + 1}`)
    assert.equal(connection.toProcessId, `process-${index + 2}`)
    assert.equal(connection.fromIoId, f.ports.find((port) => port.processId === connection.fromProcessId && port.direction === 'output').processIoId)
    assert.equal(connection.toIoId, f.ports.find((port) => port.processId === connection.toProcessId && port.direction === 'input').processIoId)
  }
  for (const port of f.ports.filter((entry) => entry.processId === 'process-3' && entry.direction === 'output' || entry.processId === 'process-4')) {
    assert.deepEqual(port.schemaJson, contract.portContract.schemaJson)
    assert.equal(port.validationRule, contract.portContract.validationRule)
  }
  const profile = f.calls.find((call) => call.path === `/projects/${projectId}/http-profiles`)
  assert.equal(profile.body.baseOrigin, referenceOrigin)
  assert.equal(profile.body.credentialAlias, 'setup-reference')
  const binding = f.calls.find((call) => call.path.endsWith('/execution-binding'))
  assert.deepEqual(binding.body.requestMapping, contract.binding.requestMapping)
  assert.deepEqual(binding.body.responseMapping, contract.binding.responseMapping)
  const start = f.calls.find((call) => call.path === '/flow-runs/graph')
  assert.equal(start.body.runType, 'actual')
  assert.deepEqual(start.body.inputPayload, contract.samples.inputPayload)
  for (const step of f.steps.slice(0, 3)) assert.deepEqual(step.inputSnapshot, contract.samples.inputPayload)
  for (const step of f.steps.slice(0, 2)) assert.deepEqual(step.outputSnapshot, contract.samples.inputPayload)
  for (const actor of ['viewer', 'editor']) assert.ok(f.calls.some((call) => call.actor === actor && call.path.endsWith('/execute')))
  assert.ok(f.calls.some((call) => call.actor === 'owner' && call.method === 'GET' && call.path.endsWith('/http-request-preview')))
  const sends = f.calls.filter((call) => call.actor === 'owner' && call.path.endsWith('/execute'))
  assert.equal(sends.length, 3)
  assert.ok(sends.every((call) => /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(call.body.requestId)))
  assert.equal(new Set(sends.map((call) => call.body.requestId)).size, 1)
  assert.notEqual(sends[0].body.requestId.toLowerCase(), contract.samples.request.requestId.toLowerCase())
  assert.deepEqual(f.steps[3].inputSnapshot, contract.samples.outputPayload)
  assert.deepEqual(f.calls.find((call) => call.path.endsWith('/finish')).body, { outputPayload: contract.samples.outputPayload })
  assert.deepEqual(f.counts, { requests: 1, processed: 1, replayed: 0 })
  assert.equal(typeof report, 'object')
  assert.deepEqual(report, { workflowId: 'workflow-reference', flowRunId: 'run-reference', stepsCompleted: 4, attempts: 4,
    providerRequests: 1, providerProcessed: 1, providerReplayed: 0, permissionRefusals: 2, previewRequests: 1,
    replayChecks: 2, eventCount: 14, externalServiceAccepted: false })
  assert.equal(JSON.stringify(report).includes(confidential), false)
  assert.equal(JSON.stringify(report).includes(contract.samples.request.record.value), false)
})

test('a denied owner profile request cannot expose backend error content', async () => {
  const f = fixture({ change: (call, result) => call.path.endsWith('/http-profiles')
    ? { status: 403, body: { success: false, message: confidential, data: { private: confidential } } } : result })
  await rejectsSafely(f.options)
})

test('a thrown request transport error is replaced by a fixed safe failure code', async () => {
  const f = fixture()
  f.options.request = async () => { throw new Error(confidential) }
  await rejectsSafely(f.options)
})

test('a successful HTTP status with a failed API envelope is refused', async () => {
  const f = fixture({ change: (_call, result) => ({ ...result, body: { success: false, message: confidential, data: result.body.data } }) })
  await rejectsSafely(f.options)
})

test('a revision that did not publish HTTP binding schema version four is refused', async () => {
  const f = fixture({ change: (call, result) => {
    if (call.path.endsWith('/revisions')) result.body.data.schemaVersion = 3
    return result
  } })
  await rejectsSafely(f.options)
})

test('incorrect mapped HTTP output fails rather than reporting acceptance', async () => {
  const f = fixture({ change: (call, result) => {
    if (call.actor === 'owner' && call.path.endsWith('/execute') && result.status === 200) {
      result.body.data.output.attrs.value = '9007199254740992.000000000'
      result.body.data.outputJson = JSON.stringify(result.body.data.output)
    }
    return result
  } })
  await rejectsSafely(f.options)
})

test('a downstream step with altered decimal, timestamp or null evidence is refused', async () => {
  const f = fixture({ change: (call, result) => {
    if (call.path.endsWith('/steps') && result.body.data.length === 4) {
      result.body.data[3].inputSnapshot.attrs.note = 'not-null'
      result.body.data[3].inputSnapshotJson = JSON.stringify(result.body.data[3].inputSnapshot)
    }
    return result
  } })
  await rejectsSafely(f.options)
})

test('an unexpected extra attempt is not counted as a valid single execution', async () => {
  const f = fixture({ change: (call, result) => {
    if (call.path.endsWith('/attempts')) result.body.data.push({ attemptId: 'unexpected-attempt', attemptNo: 2, status: 'completed' })
    return result
  } })
  await rejectsSafely(f.options)
})

test('a finished run that still contains an incomplete step is refused', async () => {
  const f = fixture({ change: (call, result, state) => {
    if (state.finished && call.path.endsWith('/steps')) result.body.data[3].status = 'planned'
    return result
  } })
  await rejectsSafely(f.options)
})

test('a preview that causes provider traffic cannot pass the no-transmission assertion', async () => {
  const f = fixture({ previewSends: true })
  await rejectsSafely(f.options)
})

test('a replay that sends another provider request cannot pass acceptance', async () => {
  const f = fixture({ replaySends: true })
  await rejectsSafely(f.options)
})

test('a viewer execution unexpectedly accepted by the backend is refused', async () => {
  const f = fixture({ change: (call, result) => call.actor === 'viewer' && call.path.endsWith('/execute')
    ? ok({ unsafe: true }) : result })
  await rejectsSafely(f.options)
})

test('an unsafe identifier returned by the backend is rejected before building another API path', async () => {
  const f = fixture({ change: (call, result) => {
    if (call.path === '/workflows') result.body.data.workflowId = '../private?value=not-a-valid-id'
    return result
  } })
  await rejectsSafely(f.options)
  assert.equal(f.calls.length, 1)
})

test('the event count alone cannot hide an incorrect completion event set', async () => {
  const f = fixture({ change: (call, result) => {
    if (call.path.endsWith('/events')) result.body.data[0].eventType = 'unrelated_event'
    return result
  } })
  await rejectsSafely(f.options)
})

test('invalid callbacks, project, contract and origins are refused before any backend request', async () => {
  for (const patch of [
    { request: null }, { providerCounts: null }, { projectId: '' }, { contract: null },
    { contract: { ...copy(contract), contractVersion: 'unsupported' } },
    { referenceOrigin: 'http://127.0.0.1:24001' }, { referenceOrigin: 'http://0.0.0.0:24001' },
    { referenceOrigin: 'http://192.168.50.10:24001/extra' }, { referenceOrigin: 'https://api.example.test' },
  ]) {
    const f = fixture()
    await rejectsSafely({ ...f.options, ...patch })
    assert.equal(f.calls.length, 0)
  }
})

test('invalid provider counters cannot be used as acceptance evidence', async () => {
  const f = fixture()
  f.options.providerCounts = () => ({ requests: -1, processed: 0, replayed: 0 })
  await rejectsSafely(f.options)
  assert.equal(f.calls.length, 0)
})

test('a provider counter callback exception is replaced by a fixed safe failure code', async () => {
  const f = fixture()
  f.options.providerCounts = () => { throw new Error(confidential) }
  await rejectsSafely(f.options)
  assert.equal(f.calls.length, 0)
})

test('an extra prototype-named event cannot disappear from the completion event multiset', async () => {
  const f = fixture({ change: (call, result) => {
    if (call.path.endsWith('/events')) result.body.data.push({ eventType: '__proto__' })
    return result
  } })
  await rejectsSafely(f.options)
})
