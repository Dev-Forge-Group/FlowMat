#!/usr/bin/env node
// Performance baseline (docs/operations/performance-baseline.md, month plan week 3 item 4): times the three calculations
// that a separate flowmat-compute (or Rust) would take over, on fixed, representative inputs built in a new project of a
// running backend. Run from the repository root against a local backend started with the dev profile:
//   node scripts/perf/baseline.mjs
// Settings (environment): FLOWMAT_API (http://127.0.0.1:8080/api), RUNS (30), WARMUP (5). Writes only to its own project.
import os from 'node:os'

const API = process.env.FLOWMAT_API ?? 'http://127.0.0.1:8080/api'
const RUNS = Number(process.env.RUNS ?? 30)
const WARMUP = Number(process.env.WARMUP ?? 5)
// The tree: one product, 5 assemblies, 25 sub-assemblies and 100 materials; each parent takes 2 of each child.
const FAN_OUT = [5, 5, 4]
const ORDERS = 40
const PROCESSES = 60

let token = ''
async function call(method, path, body, expected = 200) {
  const response = await fetch(API + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const envelope = await response.json()
  if (response.status !== expected) throw new Error(`${method} ${path}: ${response.status} ${envelope.message ?? ''}`)
  return envelope.data
}

/** Milliseconds of each timed call after the warm-up, as min, median, 95th percentile and max. */
async function time(label, run) {
  for (let i = 0; i < WARMUP; i++) await run()
  const samples = []
  let last
  for (let i = 0; i < RUNS; i++) {
    const start = performance.now()
    last = await run()
    samples.push(performance.now() - start)
  }
  samples.sort((a, b) => a - b)
  const at = (q) => samples[Math.min(samples.length - 1, Math.floor(q * samples.length))]
  const round = (value) => Math.round(value * 10) / 10
  return { label, runs: RUNS, minMs: round(samples[0]), p50Ms: round(at(0.5)), p95Ms: round(at(0.95)), maxMs: round(samples.at(-1)), last }
}

async function item(projectId, code, itemType) {
  return (await call('POST', '/items', { projectId, itemCode: code, itemName: code.toLowerCase(), itemType, unitId: 'unit_ea' })).itemId
}

async function approvedBom(projectId, parent, children) {
  const { bomId } = await call('POST', '/boms', { projectId, targetItemId: parent, bomName: `PERF ${parent}`, baseQuantity: 1, baseUnit: 'ea' })
  for (const child of children) await call('POST', `/boms/${bomId}/lines`, { childItemId: child, quantity: 2, unit: 'ea' })
  await call('POST', `/boms/${bomId}/submit`)
  await call('POST', `/boms/${bomId}/approve`)
  return bomId
}

async function main() {
  token = (await call('POST', '/auth/login', { userIdOrEmail: 'demo-owner', password: 'demo1234' })).accessToken
  const tag = Date.now().toString()
  const { projectId } = await call('POST', '/projects', { projectName: `Performance baseline ${tag}`, ownerId: 'demo-owner' })

  // 1. A four-level BOM tree, built from the materials up so that every child has its approved BOM first.
  const levels = [[await item(projectId, `PERF-P-${tag}`, 'product')]]
  for (const [depth, fan] of FAN_OUT.entries()) {
    const next = []
    for (let i = 0; i < levels[depth].length * fan; i++) {
      next.push(await item(projectId, `PERF-L${depth + 1}-${i}-${tag}`, depth + 1 === FAN_OUT.length ? 'material' : 'semi_product'))
    }
    levels.push(next)
  }
  const boms = new Map()
  for (let depth = FAN_OUT.length - 1; depth >= 0; depth--) {
    const fan = FAN_OUT[depth]
    for (const [i, parent] of levels[depth].entries()) {
      boms.set(parent, await approvedBom(projectId, parent, levels[depth + 1].slice(i * fan, (i + 1) * fan)))
    }
  }
  const topBom = boms.get(levels[0][0])
  const explosion = await time('BOM explosion (4 levels, 131 items, 30 BOMs), 10 of the product', () =>
    call('GET', `/boms/${topBom}/explosion?quantity=10`))

  // 2. Simple MRP over approved work orders of the product and the assemblies, with stock on every material.
  for (const material of levels.at(-1)) {
    await call('POST', '/inventories', { projectId, itemId: material, quantity: 50, location: 'PERF' })
  }
  const targets = [levels[0][0], ...levels[1]]
  for (let i = 0; i < ORDERS; i++) {
    const target = targets[i % targets.length]
    const { workOrderId } = await call('POST', '/work-orders', { projectId, workOrderTitle: `PERF ${i}`, targetItemId: target,
      targetQuantity: 1 + (i % 7), bomId: boms.get(target) })
    await call('POST', `/work-orders/${workOrderId}/approve`)
  }
  const mrp = await time(`Simple MRP (${ORDERS} approved orders, 100 stocked materials)`, () =>
    call('GET', `/material-requirements?projectId=${projectId}`))

  // 3. A simulation graph run of a published workflow: a line of processes with a side branch every tenth one.
  const { workflowId } = await call('POST', '/workflows', { projectId, workflowName: `PERF graph ${tag}` })
  const processes = []
  for (let i = 0; i < PROCESSES; i++) {
    processes.push((await call('POST', '/processes', { workflowId, processName: `P${i}`, nodeType: 'process',
      posX: i * 40, posY: (i % 10) * 30 })).processId)
  }
  let connections = 0
  for (let i = 1; i < PROCESSES; i++) {
    await call('POST', '/process-connections', { workflowId, fromProcessId: processes[i - 1], toProcessId: processes[i] })
    connections++
    if (i % 10 === 0 && i + 5 < PROCESSES) {
      await call('POST', '/process-connections', { workflowId, fromProcessId: processes[i], toProcessId: processes[i + 5] })
      connections++
    }
  }
  const revision = await call('POST', `/workflows/${workflowId}/revisions`)
  const revisionId = revision.workflowRevisionId
  const graph = await time(`Simulation graph run start (${PROCESSES} processes, ${connections} connections)`, () =>
    call('POST', '/flow-runs/graph', { workflowId, workflowRevisionId: revisionId, runType: 'simulation', inputPayload: {} }))

  const results = [explosion, mrp, graph].map(({ last, ...rest }) => rest)
  const machine = { cpus: os.cpus().length, cpu: os.cpus()[0]?.model, memoryGb: Math.round(os.totalmem() / 2 ** 30), node: process.version }
  console.log(JSON.stringify({ when: new Date().toISOString(), api: API, projectId, machine, results,
    sizes: { explosionLines: explosion.last?.lines?.length ?? null, mrpLines: mrp.last?.lines?.length ?? null } }, null, 2))
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
