import test from 'node:test'
import assert from 'node:assert/strict'
import { request as httpRequest } from 'node:http'
import { networkInterfaces } from 'node:os'
import { randomUUID } from 'node:crypto'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { createReferenceApi } from './connector-reference-api.mjs'

const TOKEN = 'flowmat-reference-synthetic-test-token-20261028-0123456789'
const contract = JSON.parse(await readFile(new URL('./connector-reference-contract.json', import.meta.url), 'utf8'))
const implementation = fileURLToPath(new URL('./connector-reference-api.mjs', import.meta.url))
const record = () => ({ recordId: 'setup-record-001', observedAt: '2026-10-28T01:02:03.000Z',
  value: '9007199254740993.123456789', unit: 'records', note: null })
const body = () => ({ schemaVersion: 1, requestId: randomUUID(), record: record() })

async function fixture(context, options = {}) {
  const api = await createReferenceApi({ token: TOKEN, ...options })
  context.after(() => api.close())
  return api
}

function request(api, { method = 'POST', path = '/v1/records/validate', data = body(), headers = {}, chunks } = {}) {
  const bytes = Buffer.isBuffer(data) ? data : Buffer.from(typeof data === 'string' ? data : JSON.stringify(data))
  const outgoing = { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json', ...headers }
  for (const [key, value] of Object.entries(outgoing)) if (value === null) delete outgoing[key]
  if (!chunks && outgoing['content-length'] === undefined) outgoing['content-length'] = String(bytes.length)
  return new Promise((resolve, reject) => {
    const target = new URL(api.origin)
    const call = httpRequest({ hostname: target.hostname, port: target.port, path, method, headers: outgoing, agent: false }, (response) => {
      const received = []
      response.on('data', (chunk) => received.push(chunk))
      response.on('error', reject)
      response.on('end', () => {
        try {
          const text = Buffer.concat(received).toString('utf8')
          resolve({ status: response.statusCode, headers: response.headers, text, json: JSON.parse(text) })
        } catch (error) { reject(error) }
      })
    })
    call.on('error', reject)
    if (chunks) for (const chunk of chunks) call.write(chunk)
    call.end(chunks ? undefined : bytes)
  })
}

async function errorResponse(api, options, status, code) {
  const response = await request(api, options)
  assert.equal(response.status, status)
  assert.deepEqual(response.json, { error: code })
  assert.match(response.headers['content-type'], /^application\/json(?:;|$)/i)
  assert.equal(response.text.includes(TOKEN), false)
  return response
}

test('the contract sample validates without changing the decimal string, timestamp, null or output shape', async (context) => {
  const api = await fixture(context)
  assert.match(api.origin, /^http:\/\/127\.0\.0\.1:[0-9]+$/)
  const response = await request(api, { data: contract.samples.request })
  assert.equal(response.status, 200)
  assert.deepEqual(response.json, contract.samples.response)
  assert.deepEqual({ attrs: response.json.result }, contract.samples.outputPayload)
  assert.equal(typeof response.json.result.value, 'string')
  assert.equal(response.json.result.value, '9007199254740993.123456789')
  assert.equal(response.json.result.note, null)
  assert.equal(response.text.includes(TOKEN), false)
  assert.deepEqual(api.counts, { requests: 1, processed: 1, replayed: 0 })
  assert.equal(contract.binding.requestMapping.object.requestId.from, '/attrs/referenceRequestId')
  assert.deepEqual(contract.profileTemplate.operations[0].successStatuses, [200])
  assert.equal(contract.scope.externalServiceAccepted, false)
  assert.equal(contract.portContract.validationRule, 'attrs.accepted = true')
})

test('object key order and whitespace do not create another provider operation for the same UUID', async (context) => {
  const api = await fixture(context)
  const input = body()
  const first = await request(api, { data: input })
  const reordered = { record: { note: null, unit: input.record.unit, value: input.record.value,
    observedAt: input.record.observedAt, recordId: input.record.recordId }, requestId: input.requestId, schemaVersion: 1 }
  const second = await request(api, { data: JSON.stringify(reordered, null, 2) })
  assert.equal(first.status, 200)
  assert.equal(second.status, 200)
  assert.equal(second.text, first.text)
  assert.deepEqual(api.counts, { requests: 2, processed: 1, replayed: 1 })
})

test('UUID case identifies the same cached request and response uses the canonical UUID', async (context) => {
  const api = await fixture(context)
  const input = body()
  const first = await request(api, { data: input })
  const second = await request(api, { data: { ...input, requestId: input.requestId.toUpperCase() } })
  assert.equal(second.status, 200)
  assert.deepEqual(second.json, first.json)
  assert.equal(second.json.requestId, input.requestId)
  assert.equal(api.counts.processed, 1)
  assert.equal(api.counts.replayed, 1)
})

test('concurrent duplicate requests process once and return the identical response', async (context) => {
  const api = await fixture(context)
  const input = body()
  const replies = await Promise.all(Array.from({ length: 12 }, () => request(api, { data: input })))
  for (const reply of replies) {
    assert.equal(reply.status, 200)
    assert.equal(reply.text, replies[0].text)
  }
  assert.deepEqual(api.counts, { requests: 12, processed: 1, replayed: 11 })
})

test('changing any validated content under the same requestId conflicts without overwriting the receipt', async (context) => {
  const api = await fixture(context)
  const input = body()
  input.record.value = '1.2300'
  const first = await request(api, { data: input })
  for (const patch of [{ note: 'changed' }, { value: '1.23' }, { unit: 'samples' }]) {
    await errorResponse(api, { data: { ...input, record: { ...input.record, ...patch } } }, 409, 'REQUEST_ID_CONFLICT')
  }
  const recovered = await request(api, { data: input })
  assert.equal(recovered.text, first.text)
  assert.equal(api.counts.processed, 1)
})

test('a full cache rejects new IDs but keeps the old receipt replayable without eviction', async (context) => {
  const api = await fixture(context, { maxCacheEntries: 1 })
  const input = body()
  const first = await request(api, { data: input })
  await errorResponse(api, { data: body() }, 503, 'CAPACITY_EXCEEDED')
  const replay = await request(api, { data: input })
  assert.equal(replay.text, first.text)
  assert.deepEqual(api.counts, { requests: 3, processed: 1, replayed: 1 })
})

for (const value of ['99999999999999999999.999999999', '-99999999999999999999.999999999', '+0.123456789', '-0', '0']) {
  test(`valid decimal string ${value} is returned exactly`, async (context) => {
    const api = await fixture(context)
    const input = body()
    input.record.value = value
    const result = await request(api, { data: input })
    assert.equal(result.status, 200)
    assert.equal(result.json.result.value, value)
  })
}

test('note length counts Unicode characters and preserves ordinary non-ASCII text', async (context) => {
  const api = await fixture(context)
  const input = body()
  input.record.note = '🙂'.repeat(256)
  const result = await request(api, { data: input })
  assert.equal(result.status, 200)
  assert.equal(result.json.result.note, input.record.note)
})

const invalidInputs = [
  ['root null', () => null],
  ['root array', () => []],
  ['extra root key', () => ({ ...body(), unexpected: true })],
  ['missing schema version', () => { const input = body(); delete input.schemaVersion; return input }],
  ['text schema version', () => ({ ...body(), schemaVersion: '1' })],
  ['fractional schema version', () => ({ ...body(), schemaVersion: 1.5 })],
  ['invalid UUID', () => ({ ...body(), requestId: 'not-a-uuid' })],
  ['nonobject record', () => ({ ...body(), record: [] })],
  ['extra record key', () => ({ ...body(), record: { ...record(), unexpected: true } })],
  ['missing nullable note', () => { const input = body(); delete input.record.note; return input }],
  ['oversized record identifier', () => ({ ...body(), record: { ...record(), recordId: 'a'.repeat(65) } })],
  ['path-like identifier', () => ({ ...body(), record: { ...record(), recordId: '../record' } })],
  ['invalid calendar date', () => ({ ...body(), record: { ...record(), observedAt: '2026-02-30T01:02:03.000Z' } })],
  ['offset timestamp', () => ({ ...body(), record: { ...record(), observedAt: '2026-10-28T01:02:03.000+00:00' } })],
  ['timestamp without milliseconds', () => ({ ...body(), record: { ...record(), observedAt: '2026-10-28T01:02:03Z' } })],
  ['numeric value instead of string', () => ({ ...body(), record: { ...record(), value: 9007199254740992 } })],
  ['leading-zero decimal', () => ({ ...body(), record: { ...record(), value: '01.23' } })],
  ['exponent decimal', () => ({ ...body(), record: { ...record(), value: '1e9' } })],
  ['too many integer digits', () => ({ ...body(), record: { ...record(), value: '100000000000000000000' } })],
  ['too many fraction digits', () => ({ ...body(), record: { ...record(), value: '1.1234567890' } })],
  ['invalid unit', () => ({ ...body(), record: { ...record(), unit: 'kg' } })],
  ['too long note', () => ({ ...body(), record: { ...record(), note: '🙂'.repeat(257) } })],
  ['control character', () => ({ ...body(), record: { ...record(), note: `${TOKEN}\n` } })],
  ['line separator', () => ({ ...body(), record: { ...record(), note: 'note\u2028value' } })],
  ['lone high surrogate', () => ({ ...body(), record: { ...record(), note: '\ud800' } })],
  ['lone low surrogate', () => ({ ...body(), record: { ...record(), note: '\udc00' } })],
]
for (const [name, makeInput] of invalidInputs) {
  test(`invalid request: ${name}`, async (context) => {
    const api = await fixture(context)
    await errorResponse(api, { data: makeInput() }, 400, 'INVALID_REQUEST')
    assert.equal(api.counts.processed, 0)
  })
}

test('malformed JSON and invalid UTF-8 return a fixed error without reflecting raw input', async (context) => {
  const api = await fixture(context)
  for (const data of ['{"note":', Buffer.from([0x7b, 0x22, 0xc3, 0x28, 0x22, 0x3a, 0x31, 0x7d])]) {
    await errorResponse(api, { data }, 400, 'INVALID_REQUEST')
  }
  assert.equal(api.counts.processed, 0)
})

test('authorization is required and invalid credentials are not echoed or processed', async (context) => {
  const api = await fixture(context)
  for (const authorization of [null, 'Basic synthetic', 'Bearer incorrect-synthetic-token-value-0123456789']) {
    await errorResponse(api, { headers: { authorization } }, 401, 'UNAUTHORIZED')
  }
  assert.equal(api.counts.processed, 0)
})

test('the path and method are exact and do not create alternate execution routes', async (context) => {
  const api = await fixture(context)
  for (const path of ['/v1/records/validate/', '/v1/records/validate?extra=true', '/v1/records/%76alidate', '/unknown']) {
    await errorResponse(api, { path }, 404, 'NOT_FOUND')
  }
  const wrongMethod = await errorResponse(api, { method: 'GET' }, 405, 'METHOD_NOT_ALLOWED')
  assert.equal(wrongMethod.headers.allow, 'POST')
  assert.equal(api.counts.processed, 0)
})

test('only JSON and identity encoding are accepted', async (context) => {
  const api = await fixture(context)
  for (const headers of [{ 'content-type': null }, { 'content-type': 'text/plain' }, { 'content-type': 'application/jsonp' },
    { 'content-type': 'application/json; charset=iso-8859-1' }, { 'content-encoding': 'gzip' }]) {
    await errorResponse(api, { headers }, 415, 'UNSUPPORTED_MEDIA_TYPE')
  }
  const response = await request(api, { headers: { 'content-type': 'application/json; charset=utf-8', 'content-encoding': 'identity' } })
  assert.equal(response.status, 200)
  assert.equal(api.counts.processed, 1)
})

test('the body limit is checked in bytes with and without Content-Length', async (context) => {
  const api = await fixture(context)
  const text = JSON.stringify(body())
  const boundary = text + ' '.repeat(16384 - Buffer.byteLength(text))
  assert.equal((await request(api, { data: boundary })).status, 200)
  await errorResponse(api, { data: boundary + ' ' }, 413, 'BODY_TOO_LARGE')
  const tooLarge = Buffer.alloc(16385, 0x20)
  await errorResponse(api, { data: '', headers: { 'transfer-encoding': 'chunked' },
    chunks: [tooLarge.subarray(0, 8192), tooLarge.subarray(8192)] }, 413, 'BODY_TOO_LARGE')
  assert.equal(api.counts.processed, 1)
})

const addresses = Object.values(networkInterfaces()).flat().filter(Boolean).map((entry) => entry.address)
const privateIpv4 = (address) => /^(?:10\.|172\.(?:1[6-9]|2[0-9]|3[01])\.|192\.168\.)/.test(address)
const ownedPrivate = addresses.find(privateIpv4)
const unownedPrivate = ['10.254.254.254', '172.31.254.254', '192.168.254.254'].find((address) => !addresses.includes(address))

for (const host of ['0.0.0.0', '8.8.8.8', '169.254.169.254', '::1', 'localhost', '192.0.2.1', '224.0.0.1', unownedPrivate]) {
  test(`unsafe or unowned bind host ${host} is rejected`, async () => {
    await assert.rejects(createReferenceApi({ host, token: TOKEN }))
  })
}

test('an actually owned private IPv4 address can bind an exact private origin', { skip: !ownedPrivate }, async (context) => {
  const api = await fixture(context, { host: ownedPrivate })
  assert.equal(new URL(api.origin).hostname, ownedPrivate)
  assert.equal((await request(api)).status, 200)
})

for (const options of [{ port: -1 }, { port: 65536 }, { port: 1.5 }, { maxCacheEntries: 0 }, { maxCacheEntries: 1001 },
  { token: '' }, { token: 'too-short' }, { token: `${TOKEN}\n` }]) {
  test(`invalid server configuration is rejected: ${Object.keys(options).join(',')}`, async () => {
    await assert.rejects(createReferenceApi({ token: TOKEN, ...options }))
  })
}

test('close is idempotent and returns only numeric counters without retained request or credential values', async (context) => {
  const api = await fixture(context)
  await request(api)
  assert.deepEqual(Object.keys(api.counts).sort(), ['processed', 'replayed', 'requests'])
  assert.equal(Object.values(api.counts).every((value) => Number.isInteger(value) && value >= 0), true)
  await api.close()
  await api.close()
})

function childEnvironment(token) {
  const environment = {}
  for (const key of ['SystemRoot', 'WINDIR', 'TEMP', 'TMP']) if (process.env[key]) environment[key] = process.env[key]
  if (token !== undefined) environment.FLOWMAT_REFERENCE_TOKEN = token
  return environment
}

async function runChild(arguments_, token) {
  const child = spawn(process.execPath, arguments_, { env: childEnvironment(token), windowsHide: true,
    stdio: ['ignore', 'pipe', 'pipe'] })
  let stdout = ''
  let stderr = ''
  child.stdout.on('data', (chunk) => { stdout += chunk.toString() })
  child.stderr.on('data', (chunk) => { stderr += chunk.toString() })
  const [code, signal] = await once(child, 'close')
  return { code, signal, stdout, stderr }
}

test('importing the module has no server or CLI side effect and needs no token', async () => {
  const result = await runChild(['--input-type=module', '--eval',
    `await import(${JSON.stringify(new URL('./connector-reference-api.mjs', import.meta.url).href)}); process.stdout.write('imported')`])
  assert.equal(result.code, 0)
  assert.equal(result.stdout, 'imported')
  assert.equal(result.stderr, '')
})

for (const [name, arguments_, token] of [
  ['missing token', ['--port', '0'], undefined],
  ['short token', ['--port', '0'], 'short'],
  ['token argument', ['--token', TOKEN], TOKEN],
  ['unknown option', ['--unknown', 'value'], TOKEN],
  ['missing option value', ['--port'], TOKEN],
  ['fractional port', ['--port', '1.5'], TOKEN],
  ['duplicate option', ['--port', '0', '--port', '0'], TOKEN],
]) {
  test(`CLI rejects ${name} without printing input or credential data`, async () => {
    const result = await runChild([implementation, ...arguments_], token)
    assert.notEqual(result.code, 0)
    assert.equal(result.stdout, '')
    assert.equal(result.stderr.includes(TOKEN), false)
    assert.equal(result.stderr.includes('Error:'), false)
    assert.equal(result.stderr.includes(' at '), false)
  })
}

test('CLI reports only origin and contractVersion and stops its owned process on SIGTERM', { timeout: 10000 }, async (context) => {
  const child = spawn(process.execPath, [implementation, '--host', '127.0.0.1', '--port', '0'], {
    env: childEnvironment(TOKEN), windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'],
  })
  const exited = once(child, 'close')
  let stdout = ''
  let stderr = ''
  child.stdout.on('data', (chunk) => { stdout += chunk.toString() })
  child.stderr.on('data', (chunk) => { stderr += chunk.toString() })
  context.after(async () => {
    if (child.exitCode === null && child.signalCode === null) child.kill('SIGTERM')
    await exited
  })
  const ready = await Promise.race([
    new Promise((resolve, reject) => {
      child.stdout.on('data', () => {
        if (!stdout.includes('\n')) return
        try { resolve(JSON.parse(stdout.trim())) } catch (error) { reject(error) }
      })
    }),
    exited.then(() => { throw new Error('The owned reference process exited before readiness.') }),
  ])
  assert.deepEqual(Object.keys(ready).sort(), ['contractVersion', 'origin'])
  assert.equal(ready.contractVersion, 'flowmat-reference-v1')
  assert.match(ready.origin, /^http:\/\/127\.0\.0\.1:[0-9]+$/)
  assert.equal((await request(ready)).status, 200)
  child.kill('SIGTERM')
  await exited
  assert.equal(stderr, '')
  assert.equal(stdout.includes(TOKEN), false)
  assert.equal(stdout.trim().split('\n').length, 1)
})

for (const literal of ['1.00000000000000000001', '0.99999999999999999999', '1.0', '1e0']) {
  test(`schemaVersion requires the canonical integer token: ${literal}`, async (context) => {
    const api = await fixture(context)
    const data = JSON.stringify(body()).replace('"schemaVersion":1', `"schemaVersion":${literal}`)
    await errorResponse(api, { data }, 400, 'INVALID_REQUEST')
    assert.equal(api.counts.processed, 0)
  })
}

test('a parser without primitive source cannot start a server with ambiguous version validation', async () => {
  const originalParse = JSON.parse
  JSON.parse = (text, reviver) => originalParse(text, reviver && function (key, value) {
    return reviver.call(this, key, value)
  })
  try {
    await assert.rejects(createReferenceApi({ token: TOKEN }), /Invalid reference API configuration/)
  } finally {
    JSON.parse = originalParse
  }
})
