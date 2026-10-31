import { randomUUID } from 'node:crypto'
import { isIP } from 'node:net'
import { isDeepStrictEqual } from 'node:util'

class VerificationError extends Error {
  constructor(code) { super(`REFERENCE_FLOW_${code}`) }
}
const fail = (code) => { throw new VerificationError(code) }
const requireThat = (condition, code) => { if (!condition) fail(code) }
const equal = (actual, expected, code) => requireThat(isDeepStrictEqual(actual, expected), code)
const validId = (value) => typeof value === 'string' && /^[A-Za-z0-9_-]{1,100}$/.test(value)
function identifier(value, field) {
  requireThat(validId(value?.[field]), 'INVALID_IDENTIFIER')
  return value[field]
}
function exactJson(value, expected, code) {
  requireThat(typeof value === 'string', code)
  let parsed
  try { parsed = JSON.parse(value) } catch { fail(code) }
  equal(parsed, expected, code)
}
function privateOrigin(value) {
  try {
    const url = new URL(value)
    const [first, second] = url.hostname.split('.').map(Number)
    return value === url.origin && url.protocol === 'http:' && isIP(url.hostname) === 4 && url.port !== ''
      && (first === 10 || first === 172 && second >= 16 && second <= 31 || first === 192 && second === 168)
  } catch { return false }
}

/**
 * Programmatic acceptance driver for an isolated backend and the controlled reference API.
 * The caller owns the temporary database, credentials, servers and their cleanup. Importing
 * this module reads no environment, starts no server and sends no request. File and Transform
 * are manually recorded steps; this does not implement upload or automatic transformation.
 */
export async function verifyReferenceFlow(options = {}) {
  try { return await verify(options) }
  catch (error) {
    if (error instanceof VerificationError) throw error
    fail('UNEXPECTED')
  }
}

async function verify({ request, contract, referenceOrigin, providerCounts, projectId = 'prj_demo_main' }) {
  requireThat(typeof request === 'function' && typeof providerCounts === 'function'
    && validId(projectId) && privateOrigin(referenceOrigin), 'INVALID_CONFIGURATION')
  requireThat(contract?.contractVersion === 'flowmat-reference-v1' && contract.scope?.controlledReferenceApi === true
    && contract.operation?.method === 'POST' && contract.operation.path === '/v1/records/validate'
    && contract.binding?.requestMapping && contract.binding.responseMapping
    && contract.samples?.inputPayload && contract.samples.outputPayload && contract.samples.request
    && contract.portContract?.resourceType === 'data', 'INVALID_CONTRACT')
  const expectedInput = structuredClone(contract.samples.inputPayload)
  const expectedOutput = structuredClone(contract.samples.outputPayload)
  equal(expectedOutput, { attrs: contract.samples.response?.result }, 'INVALID_CONTRACT')
  equal(contract.operation.successStatuses, [200], 'INVALID_CONTRACT')
  const profileBody = structuredClone(contract.profileTemplate)
  requireThat(profileBody?.credentialAlias === 'setup-reference' && profileBody.enabled === true
    && profileBody.operations?.length === 1 && profileBody.operations[0].operationId === 'validate-record'
    && profileBody.operations[0].method === 'POST' && profileBody.operations[0].relativePath === '/v1/records/validate', 'INVALID_CONTRACT')
  equal(profileBody.operations[0].successStatuses, [200], 'INVALID_CONTRACT')
  profileBody.baseOrigin = referenceOrigin

  function counts(expected) {
    let observed
    try { observed = providerCounts() } catch { fail('COUNTER_CALLBACK') }
    requireThat(observed && ['requests', 'processed', 'replayed'].every((key) =>
      Number.isSafeInteger(observed[key]) && observed[key] >= 0), 'INVALID_COUNTERS')
    equal({ requests: observed.requests, processed: observed.processed, replayed: observed.replayed }, expected, 'UNEXPECTED_PROVIDER_TRAFFIC')
  }
  const noTraffic = { requests: 0, processed: 0, replayed: 0 }
  const oneSend = { requests: 1, processed: 1, replayed: 0 }
  counts(noTraffic)
  async function call(method, path, body, actor = 'owner', expectedStatus = 200) {
    let result
    try { result = await request({ method, path, body, actor }) } catch { fail('API_TRANSPORT') }
    requireThat(result?.status === expectedStatus, 'API_STATUS')
    if (expectedStatus !== 200) return
    requireThat(result.body?.success === true && Object.hasOwn(result.body, 'data'), 'API_ENVELOPE')
    return result.body.data
  }
  const workflow = await call('POST', '/workflows', { projectId, workflowName: `Reference data flow ${randomUUID()}` })
  const workflowId = identifier(workflow, 'workflowId')
  const nodes = []
  for (const processName of ['File', 'Transform', 'HTTP', 'Data']) {
    const process = await call('POST', '/processes', { workflowId, processName })
    nodes.push(identifier(process, 'processId'))
  }
  const port = async (nodeIndex, direction, resourceType, responseContract = false) => {
    const body = { processId: nodes[nodeIndex], direction, ioName: `${resourceType} ${direction}`,
      resourceType, ioType: resourceType, requiredYn: 'Y' }
    if (responseContract) {
      body.schemaJson = structuredClone(contract.portContract.schemaJson)
      body.validationRule = contract.portContract.validationRule
    }
    return identifier(await call('POST', '/process-ios', body), 'processIoId')
  }
  const ports = [await port(0, 'output', 'file'), await port(1, 'input', 'file'),
    await port(1, 'output', 'data'), await port(2, 'input', 'data'),
    await port(2, 'output', 'data', true), await port(3, 'input', 'data', true)]
  const connections = []
  for (let index = 0; index < 3; index++) {
    const connection = await call('POST', '/process-connections', { workflowId,
      fromProcessId: nodes[index], toProcessId: nodes[index + 1],
      fromIoId: ports[index * 2], toIoId: ports[index * 2 + 1], connectionType: index === 0 ? 'file' : 'data' })
    connections.push(identifier(connection, 'connectionId'))
  }
  const profile = await call('POST', `/projects/${projectId}/http-profiles`, profileBody)
  identifier(profile, 'profileId')
  const profileVersionId = identifier(profile, 'profileVersionId')
  await call('PUT', `/processes/${nodes[2]}/execution-binding`, { expectedVersion: 0, profileVersionId,
    operationId: 'validate-record', outputPortId: ports[4],
    requestMapping: structuredClone(contract.binding.requestMapping),
    responseMapping: structuredClone(contract.binding.responseMapping) })
  const revision = await call('POST', `/workflows/${workflowId}/revisions`)
  requireThat(revision?.schemaVersion === 4, 'REVISION_SCHEMA')
  const revisionId = identifier(revision, 'workflowRevisionId')
  const run = await call('POST', '/flow-runs/graph', { workflowId, workflowRevisionId: revisionId,
    runType: 'actual', inputPayload: expectedInput })
  const flowRunId = identifier(run, 'flowRunId')
  const base = `/flow-runs/${flowRunId}`
  counts(noTraffic)
  async function steps() {
    const list = await call('GET', `${base}/steps`)
    requireThat(Array.isArray(list), 'STEP_LIST')
    for (const step of list) { identifier(step, 'stepId'); identifier(step, 'nodeId') }
    return list
  }
  async function planned(index, expected) {
    const list = await steps()
    requireThat(list.length === index + 1, 'STEP_COUNT')
    const step = list.find((entry) => entry.nodeId === nodes[index])
    requireThat(step?.status === 'planned' && step.sequenceNo === index + 1, 'STEP_STATE')
    equal(step.inputSnapshot, expected, 'INPUT_CHANGED')
    exactJson(step.inputSnapshotJson, expected, 'INPUT_JSON_CHANGED')
    if (index > 0) {
      const source = list.find((entry) => entry.nodeId === nodes[index - 1])
      requireThat(step.sourceStepId === source?.stepId && step.sourceConnectionId === connections[index - 1], 'STEP_LINEAGE')
    }
    return identifier(step, 'stepId')
  }
  async function complete(stepId, output) {
    const path = `${base}/steps/${stepId}`
    await call('POST', `${path}/start`)
    await call('POST', `${path}/complete`, { attemptNo: 1, outputSnapshot: output })
  }
  for (const index of [0, 1]) {
    await complete(await planned(index, expectedInput), expectedInput)
    counts(noTraffic)
  }
  const httpStep = await planned(2, expectedInput)
  const httpBase = `${base}/steps/${httpStep}`
  const command = { requestId: randomUUID() }
  requireThat(command.requestId !== contract.samples.request.requestId, 'REQUEST_ID_COLLISION')
  for (const actor of ['viewer', 'editor']) {
    await call('POST', `${httpBase}/execute`, command, actor, 403)
    counts(noTraffic)
  }
  const preview = await call('GET', `${httpBase}/http-request-preview`)
  requireThat(preview?.profileVersionId === profileVersionId && preview.operationId === 'validate-record'
    && preview.method === 'POST' && preview.target === `${referenceOrigin}/v1/records/validate`
    && preview.hasRequestBody === true, 'PREVIEW_METADATA')
  exactJson(preview.requestJson, contract.samples.request, 'PREVIEW_REQUEST')
  counts(noTraffic)
  const evidence = await call('POST', `${httpBase}/execute`, command)
  identifier(evidence, 'executionId')
  identifier(evidence, 'attemptId')
  requireThat(evidence.stepId === httpStep && evidence.requestId === command.requestId
    && evidence.transportStatus === 'SUCCEEDED' && evidence.applyStatus === 'APPLIED'
    && evidence.httpStatus === 200 && evidence.errorCode === null, 'HTTP_EVIDENCE')
  equal(evidence.output, expectedOutput, 'OUTPUT_CHANGED')
  exactJson(evidence.outputJson, expectedOutput, 'OUTPUT_JSON_CHANGED')
  counts(oneSend)
  const dataStep = await planned(3, expectedOutput)
  equal(await call('POST', `${httpBase}/execute`, command), evidence, 'REPLAY_CHANGED')
  counts(oneSend)
  await complete(dataStep, expectedOutput)
  const finished = await call('POST', `${base}/finish`, { outputPayload: expectedOutput })
  requireThat(finished?.status === 'finished', 'RUN_NOT_FINISHED')
  equal(finished.outputPayload, expectedOutput, 'FINAL_OUTPUT_CHANGED')
  exactJson(finished.outputPayloadJson, expectedOutput, 'FINAL_JSON_CHANGED')
  equal(await call('POST', `${httpBase}/execute`, command), evidence, 'FINISHED_REPLAY_CHANGED')
  counts(oneSend)
  const storedRun = await call('GET', base)
  requireThat(storedRun?.status === 'finished' && storedRun.endedAt != null, 'STORED_RUN')
  equal(storedRun.outputPayload, expectedOutput, 'STORED_OUTPUT_CHANGED')
  exactJson(storedRun.outputPayloadJson, expectedOutput, 'STORED_JSON_CHANGED')
  const finalSteps = await steps()
  requireThat(finalSteps.length === 4, 'FINAL_STEP_COUNT')
  for (let index = 0; index < 4; index++) {
    const step = finalSteps.find((entry) => entry.nodeId === nodes[index])
    requireThat(step?.status === 'completed' && step.sequenceNo === index + 1, 'INCOMPLETE_STEP')
    const expectedStepInput = index < 3 ? expectedInput : expectedOutput
    const expectedStepOutput = index < 2 ? expectedInput : expectedOutput
    equal(step.inputSnapshot, expectedStepInput, 'FINAL_STEP_INPUT')
    exactJson(step.inputSnapshotJson, expectedStepInput, 'FINAL_STEP_INPUT_JSON')
    equal(step.outputSnapshot, expectedStepOutput, 'FINAL_STEP_OUTPUT')
    exactJson(step.outputSnapshotJson, expectedStepOutput, 'FINAL_STEP_OUTPUT_JSON')
    const attempts = await call('GET', `${base}/steps/${step.stepId}/attempts`)
    requireThat(Array.isArray(attempts) && attempts.length === 1 && attempts[0].attemptNo === 1
      && attempts[0].status === 'completed' && attempts[0].startedAt != null && attempts[0].endedAt != null, 'ATTEMPTS')
  }
  const executions = await call('GET', `${httpBase}/http-executions`)
  equal(executions, [evidence], 'STORED_HTTP_EVIDENCE')
  const events = await call('GET', `${base}/events`)
  requireThat(Array.isArray(events), 'EVENT_LIST')
  const eventCounts = new Map()
  for (const event of events) {
    requireThat(typeof event.eventType === 'string', 'EVENT_TYPE')
    eventCounts.set(event.eventType, (eventCounts.get(event.eventType) ?? 0) + 1)
  }
  equal(Object.fromEntries(eventCounts), { run_started: 1, step_created: 4, step_started: 4, step_completed: 4, run_finished: 1 }, 'EVENT_SET')
  counts(oneSend)
  return { workflowId, flowRunId, stepsCompleted: 4, attempts: 4,
    providerRequests: 1, providerProcessed: 1, providerReplayed: 0, permissionRefusals: 2,
    previewRequests: 1, replayChecks: 2, eventCount: events.length, externalServiceAccepted: false }
}
