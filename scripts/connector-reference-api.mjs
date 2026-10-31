import { createServer } from 'node:http'
import { isIP } from 'node:net'
import { networkInterfaces } from 'node:os'
import { timingSafeEqual } from 'node:crypto'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const CONTRACT_VERSION = 'flowmat-reference-v1'
const PATH = '/v1/records/validate'
const MAX_BODY_BYTES = 16384
const MAX_CACHE_ENTRIES = 1000
const REQUEST_FIELDS = ['schemaVersion', 'requestId', 'record']
const RECORD_FIELDS = ['recordId', 'observedAt', 'value', 'unit', 'note']
const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/
const RECORD_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/
const TIMESTAMP = /^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}Z$/
const DECIMAL = /^[+-]?(?:0|[1-9][0-9]{0,19})(?:\.[0-9]{1,9})?$/
const TOKEN = /^[A-Za-z0-9._~+/-]+=*$/
const UNITS = new Set(['records', 'samples', 'm', 'ms'])

function invalidConfiguration() {
  return new Error('Invalid reference API configuration.')
}

function fullMatch(pattern, value) {
  const match = pattern.exec(value)
  return match !== null && match[0] === value
}

function privateIpv4(host) {
  const [first, second] = host.split('.').map(Number)
  return first === 10 || first === 172 && second >= 16 && second <= 31 || first === 192 && second === 168
}

function allowedHost(host) {
  if (typeof host !== 'string' || isIP(host) !== 4) return false
  if (host.startsWith('127.')) return true
  if (!privateIpv4(host)) return false
  return Object.values(networkInterfaces()).some((entries) => Array.isArray(entries)
    && entries.some((entry) => entry.address === host && (entry.family === 'IPv4' || entry.family === 4)))
}

function hasOnlyRequiredFields(value, fields) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    && Object.keys(value).length === fields.length && fields.every((field) => Object.hasOwn(value, field))
}

function validNote(note) {
  if (note === null) return true
  if (typeof note !== 'string') return false
  let length = 0
  for (const character of note) {
    if (++length > 256) return false
    const point = character.codePointAt(0)
    if (point <= 31 || point >= 127 && point <= 159 || point === 0x2028 || point === 0x2029
      || point >= 0xd800 && point <= 0xdfff) return false
  }
  return true
}

function validatedRequest(value) {
  if (!hasOnlyRequiredFields(value, REQUEST_FIELDS) || value.schemaVersion !== 1
    || typeof value.requestId !== 'string' || !fullMatch(UUID, value.requestId)
    || !hasOnlyRequiredFields(value.record, RECORD_FIELDS)) return null
  const record = value.record
  if (typeof record.recordId !== 'string' || !fullMatch(RECORD_ID, record.recordId)
    || typeof record.observedAt !== 'string' || !fullMatch(TIMESTAMP, record.observedAt)
    || typeof record.value !== 'string' || !fullMatch(DECIMAL, record.value)
    || !UNITS.has(record.unit) || !validNote(record.note)) return null
  const timestamp = new Date(record.observedAt)
  if (!Number.isFinite(timestamp.getTime()) || timestamp.toISOString() !== record.observedAt) return null
  return {
    schemaVersion: 1,
    requestId: value.requestId.toLowerCase(),
    record: { recordId: record.recordId, observedAt: record.observedAt, value: record.value, unit: record.unit, note: record.note },
  }
}

function headerCount(request, name) {
  let count = 0
  for (let index = 0; index < request.rawHeaders.length; index += 2) {
    if (request.rawHeaders[index].toLowerCase() === name) count += 1
  }
  return count
}

function authorized(request, expected) {
  const header = request.headers.authorization
  if (typeof header !== 'string' || headerCount(request, 'authorization') !== 1
    || header.length !== expected.length) return false
  const provided = Buffer.from(header, 'utf8')
  return provided.length === expected.length && timingSafeEqual(provided, expected)
}

function supportedBodyHeaders(request) {
  const type = request.headers['content-type']
  if (typeof type !== 'string' || headerCount(request, 'content-type') !== 1
    || !/^application\/json(?:\s*;\s*charset\s*=\s*(?:utf-8|"utf-8"))?$/i.test(type.trim())) return false
  const encoding = request.headers['content-encoding']
  return encoding === undefined || typeof encoding === 'string' && headerCount(request, 'content-encoding') === 1
    && encoding.trim().toLowerCase() === 'identity'
}

function respond(response, status, body, headers = {}) {
  if (response.destroyed || response.writableEnded) return
  response.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body),
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
    ...headers,
  })
  response.end(body)
}

function reject(request, response, status, code, headers = {}) {
  response.shouldKeepAlive = false
  respond(response, status, JSON.stringify({ error: code }), headers)
  request.resume()
}

/** A standalone, controlled provider fixture. It does not call FlowMat or any other service. */
export async function createReferenceApi({ host = '127.0.0.1', port = 0, token, maxCacheEntries = MAX_CACHE_ENTRIES } = {}) {
  if (!allowedHost(host) || !Number.isInteger(port) || port < 0 || port > 65535
    || typeof token !== 'string' || token.length < 32 || token.length > 4096 || !fullMatch(TOKEN, token)
    || !Number.isInteger(maxCacheEntries) || maxCacheEntries < 1 || maxCacheEntries > MAX_CACHE_ENTRIES
    || JSON.parse('1', (_key, _value, context) => context?.source) !== '1') {
    throw invalidConfiguration()
  }
  const expectedAuthorization = Buffer.from(`Bearer ${token}`, 'utf8')
  const receipts = new Map()
  const counts = { requests: 0, processed: 0, replayed: 0 }
  const server = createServer({ maxHeaderSize: 16384, requestTimeout: 10000, headersTimeout: 10000 }, (request, response) => {
    counts.requests += 1
    request.on('error', () => {})
    if (!authorized(request, expectedAuthorization)) return reject(request, response, 401, 'UNAUTHORIZED')
    if (request.url !== PATH) return reject(request, response, 404, 'NOT_FOUND')
    if (request.method !== 'POST') return reject(request, response, 405, 'METHOD_NOT_ALLOWED', { allow: 'POST' })
    if (!supportedBodyHeaders(request)) return reject(request, response, 415, 'UNSUPPORTED_MEDIA_TYPE')
    if (Number(request.headers['content-length']) > MAX_BODY_BYTES) return reject(request, response, 413, 'BODY_TOO_LARGE')
    let bytes = 0
    let chunks = []
    let finished = false
    const finish = () => {
      finished = true
      chunks = []
      clearTimeout(timer)
    }
    const timer = setTimeout(() => {
      if (finished) return
      finish()
      reject(request, response, 400, 'INVALID_REQUEST')
    }, 5000)
    timer.unref()
    request.on('aborted', finish)
    response.on('close', finish)
    request.on('data', (chunk) => {
      if (finished) return
      bytes += chunk.length
      if (bytes > MAX_BODY_BYTES) {
        finish()
        reject(request, response, 413, 'BODY_TOO_LARGE')
      } else chunks.push(chunk)
    })
    request.on('end', () => {
      if (finished) return
      const buffer = Buffer.concat(chunks, bytes)
      finish()
      let input
      try {
        const json = new TextDecoder('utf-8', { fatal: true }).decode(buffer)
        input = validatedRequest(JSON.parse(json, (key, value, context) =>
          key === 'schemaVersion' && context?.source !== '1' ? null : value))
      } catch {
        return reject(request, response, 400, 'INVALID_REQUEST')
      }
      if (!input) return reject(request, response, 400, 'INVALID_REQUEST')
      const fingerprint = JSON.stringify(input)
      const previous = receipts.get(input.requestId)
      if (previous) {
        if (previous.fingerprint !== fingerprint) return reject(request, response, 409, 'REQUEST_ID_CONFLICT')
        counts.replayed += 1
        return respond(response, 200, previous.body)
      }
      if (receipts.size >= maxCacheEntries) return reject(request, response, 503, 'CAPACITY_EXCEEDED')
      const body = JSON.stringify({ schemaVersion: 1, requestId: input.requestId,
        result: { ...input.record, accepted: true }, provider: { contractVersion: CONTRACT_VERSION } })
      receipts.set(input.requestId, { fingerprint, body })
      counts.processed += 1
      respond(response, 200, body)
    })
  })
  server.maxHeadersCount = 64
  server.keepAliveTimeout = 1000
  server.on('clientError', (_error, socket) => {
    if (!socket.writable) return
    const body = '{"error":"INVALID_REQUEST"}'
    socket.end(`HTTP/1.1 400 Bad Request\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${Buffer.byteLength(body)}\r\nConnection: close\r\n\r\n${body}`)
  })
  await new Promise((resolveListening, rejectListening) => {
    const failed = () => rejectListening(new Error('Reference API could not start.'))
    server.once('error', failed)
    server.listen(port, host, () => {
      server.removeListener('error', failed)
      resolveListening()
    })
  })
  const address = server.address()
  let closing
  const close = () => {
    if (!closing) closing = new Promise((resolveClosed, rejectClosed) => {
      server.close((error) => {
        receipts.clear()
        if (error && error.code !== 'ERR_SERVER_NOT_RUNNING') rejectClosed(new Error('Reference API could not close.'))
        else resolveClosed()
      })
      server.closeAllConnections()
    })
    return closing
  }
  return Object.freeze({
    origin: `http://${address.address}:${address.port}`,
    get counts() { return Object.freeze({ ...counts }) },
    close,
  })
}

function cliOptions(arguments_) {
  const options = {}
  for (let index = 0; index < arguments_.length; index += 2) {
    const option = arguments_[index]
    const value = arguments_[index + 1]
    if (!['--host', '--port'].includes(option) || value === undefined || Object.hasOwn(options, option.slice(2))) {
      throw invalidConfiguration()
    }
    if (option === '--host') options.host = value
    else {
      if (!fullMatch(/^(?:0|[1-9][0-9]{0,4})$/, value)) throw invalidConfiguration()
      options.port = Number(value)
    }
  }
  return options
}

async function main() {
  const api = await createReferenceApi({ ...cliOptions(process.argv.slice(2)), token: process.env.FLOWMAT_REFERENCE_TOKEN })
  process.stdout.write(`${JSON.stringify({ origin: api.origin, contractVersion: CONTRACT_VERSION })}\n`)
  let stopping = false
  const stop = () => {
    if (stopping) return
    stopping = true
    void api.close().catch(() => {
      process.stderr.write('REFERENCE_API_STOP_FAILED\n')
      process.exitCode = 1
    })
  }
  process.once('SIGTERM', stop)
  process.once('SIGINT', stop)
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  void main().catch(() => {
    process.stderr.write('REFERENCE_API_START_FAILED\n')
    process.exitCode = 1
  })
}
