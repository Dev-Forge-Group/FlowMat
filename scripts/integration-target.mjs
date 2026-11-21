// A stand-in for another company's system, for the real-API browser test of integration jobs
// (docs/architecture/adr/ADR-038-integration-jobs.md, flowmat_frontend/e2e/integration-jobs-live.spec.ts).
//
// The backend's connector only reaches approved origins and refuses loopback, so the system side listens on a private
// IPv4 address of this machine. It serves a supplier's item list and a warehouse's stock, and takes stock updates:
//   GET  /items  -> {"items": [...]}                     what a pull_items job reads
//   GET  /stock  -> {"stock": [{"sku", "onHand"}]}       what a push_stock job reads before and after sending
//   POST /stock  <- {"items": [{"itemCode", "quantity", "unit"}]}  what a push_stock job sends; quantities replace
// The test sets and reads that state on a second listener bound to 127.0.0.1 only:
//   PUT /state  <- {"items": [...], "stock": {"CODE": 5}}   GET /state -> {"stock": {...}, "received": [bodies]}
// It holds no credentials and keeps nothing on disk. It prints INTEGRATION_TARGET_ORIGIN=http://ip:port when ready.
import { createServer } from 'node:http'
import { networkInterfaces } from 'node:os'

const PORT = Number(process.env.INTEGRATION_TARGET_PORT ?? 18090)
const CONTROL_PORT = Number(process.env.INTEGRATION_TARGET_CONTROL_PORT ?? 18091)
const MAX_BODY_BYTES = 262144

let items = []
let stock = new Map()
let received = []

export function privateAddress(interfaces = networkInterfaces()) {
  for (const entries of Object.values(interfaces)) {
    for (const entry of entries ?? []) {
      if (entry.internal || !(entry.family === 'IPv4' || entry.family === 4)) continue
      const [first, second] = entry.address.split('.').map(Number)
      if (first === 10 || first === 172 && second >= 16 && second <= 31 || first === 192 && second === 168) return entry.address
    }
  }
  return null
}

function send(response, status, value) {
  const body = JSON.stringify(value)
  response.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'content-length': Buffer.byteLength(body),
    'cache-control': 'no-store' })
  response.end(body)
}

function readJson(request) {
  return new Promise((resolve, reject) => {
    const chunks = []
    let size = 0
    request.on('data', (chunk) => {
      size += chunk.length
      if (size > MAX_BODY_BYTES) { reject(new Error('too large')); request.destroy() } else chunks.push(chunk)
    })
    request.on('end', () => {
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || 'null')) } catch (error) { reject(error) }
    })
    request.on('error', reject)
  })
}

async function system(request, response) {
  const { pathname } = new URL(request.url, 'http://target')
  if (request.method === 'GET' && pathname === '/items') return send(response, 200, { items })
  if (request.method === 'GET' && pathname === '/stock') {
    return send(response, 200, { stock: [...stock].map(([sku, onHand]) => ({ sku, onHand })) })
  }
  if (request.method === 'POST' && pathname === '/stock') {
    let body
    try { body = await readJson(request) } catch { return send(response, 400, { error: 'BAD_JSON' }) }
    if (!body || !Array.isArray(body.items)) return send(response, 422, { error: 'NO_ITEMS' })
    received.push(body)
    for (const item of body.items) {
      if (typeof item?.itemCode === 'string' && typeof item.quantity === 'number') stock.set(item.itemCode, item.quantity)
    }
    return send(response, 200, { accepted: body.items.length })
  }
  return send(response, 404, { error: 'NOT_FOUND' })
}

async function control(request, response) {
  const { pathname } = new URL(request.url, 'http://control')
  if (pathname !== '/state') return send(response, 404, { error: 'NOT_FOUND' })
  if (request.method === 'GET') return send(response, 200, { items, stock: Object.fromEntries(stock), received })
  if (request.method === 'PUT') {
    let body
    try { body = await readJson(request) } catch { return send(response, 400, { error: 'BAD_JSON' }) }
    items = Array.isArray(body?.items) ? body.items : []
    stock = new Map(Object.entries(body?.stock ?? {}))
    received = []
    return send(response, 200, { ok: true })
  }
  return send(response, 405, { error: 'METHOD' })
}

function listen(server, port, host) {
  return new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(port, host, () => resolve())
  })
}

if (process.argv[1] && import.meta.url === new URL(`file://${process.argv[1]}`).href) {
  const host = process.env.INTEGRATION_TARGET_HOST ?? privateAddress()
  if (!host) {
    console.error('No private IPv4 address on this machine; the connector refuses loopback.')
    process.exit(1)
  }
  await listen(createServer((request, response) => { void system(request, response) }), PORT, host)
  await listen(createServer((request, response) => { void control(request, response) }), CONTROL_PORT, '127.0.0.1')
  console.log(`INTEGRATION_TARGET_ORIGIN=http://${host}:${PORT}`)
}
