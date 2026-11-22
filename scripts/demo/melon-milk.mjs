#!/usr/bin/env node
// The melon milk demo (docs/status/ROADMAP-2026-11-23.md §8): a new project with raw milk in → mix → fill → pack, its
// materials, stock (raw milk short), equipment and execution requirements, published so a plan can be asked for at once.
// Everything goes through the public API, so stock is written by the inventory command as any receipt is: no migration,
// and it works the same on a local, pilot or demo deployment.
//
//   FLOWMAT_API=http://127.0.0.1:8080/api FLOWMAT_USER=demo-owner FLOWMAT_PASSWORD=... node scripts/demo/melon-milk.mjs
//
// It prints the project and workflow, and the plan for 1,000 bottles.

const api = (process.env.FLOWMAT_API ?? 'http://127.0.0.1:8080/api').replace(/\/$/, '')
const user = process.env.FLOWMAT_USER
const password = process.env.FLOWMAT_PASSWORD
if (!user || !password) {
  console.error('Set FLOWMAT_USER and FLOWMAT_PASSWORD (and FLOWMAT_API when the server is not local).')
  process.exit(2)
}

let token = ''
async function call(method, path, body) {
  const response = await fetch(`${api}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const envelope = await response.json().catch(() => ({}))
  if (!response.ok || envelope.success === false) {
    throw new Error(`${method} ${path}: ${response.status} ${envelope.message ?? ''}`)
  }
  return envelope.data
}

token = (await call('POST', '/auth/login', { userIdOrEmail: user, password })).accessToken
const tag = new Date().toISOString().slice(0, 16).replace(/[-:T]/g, '')
const { projectId } = await call('POST', '/projects', { projectName: `Melon milk demo ${tag}`, ownerId: user })
await call('POST', '/storage-locations', { projectId, locationCode: 'RAW', locationType: 'location' })

const item = async (code, itemName, itemType, unitId) =>
  (await call('POST', '/items', { projectId, itemCode: `MM-${code}`, itemName, itemType, unitId })).itemId
const milk = await item('MILK', 'Raw milk', 'material', 'unit_kg')
const syrup = await item('SYRUP', 'Melon syrup', 'material', 'unit_kg')
const sugar = await item('SUGAR', 'Sugar', 'material', 'unit_kg')
const bottle = await item('BOTTLE', 'Bottle', 'material', 'unit_ea')
const box = await item('BOX', 'Box of 24', 'material', 'unit_ea')
const mixed = await item('MIX', 'Melon milk mix', 'semi', 'unit_kg')
const filled = await item('FILLED', 'Filled bottle', 'semi', 'unit_ea')
const melonMilk = await item('PRODUCT', 'Melon milk', 'product', 'unit_ea')
// Raw milk is short for 1,000 bottles: 150 kg of the 184.0564 kg needed.
for (const [itemId, quantity] of [[milk, 150], [syrup, 20], [sugar, 50], [bottle, 2000], [box, 50]]) {
  await call('POST', '/inventories', { projectId, itemId, location: 'RAW', quantity })
}
const equipment = async (code) => (await call('POST', '/equipments', {
  projectId, equipmentCode: code, equipmentName: code.toLowerCase(), equipmentType: 'machine',
})).equipmentId
const mixer = await equipment('MIXER-1')
const filler = await equipment('FILLER-1')

const { workflowId } = await call('POST', '/workflows', { projectId, workflowName: 'Melon milk' })
const node = async (processName, posX) =>
  (await call('POST', '/processes', { workflowId, processName, nodeType: 'process', posX, posY: 240 })).processId
// Materials drawn from stock have no connection in, so they are not required inputs.
const port = async (processId, direction, itemId, quantity, unit) => (await call('POST', '/process-ios', {
  processId, itemId, direction, quantity, unit, resourceType: itemId === melonMilk ? 'product' : 'material', requiredYn: 'N',
})).processIoId
const receive = await node('Receive milk', 80)
const mix = await node('Mix', 420)
const fill = await node('Fill', 760)
const pack = await node('Pack', 1100)
await port(receive, 'input', milk, 1, 'kg')
const receiveOut = await port(receive, 'output', milk, 1, 'kg')
const mixMilk = await port(mix, 'input', milk, 100, 'kg')
await port(mix, 'input', syrup, 10, 'kg')
await port(mix, 'input', sugar, 5, 'kg')
const mixOut = await port(mix, 'output', mixed, 112, 'kg')
const fillMix = await port(fill, 'input', mixed, 0.2, 'kg')
await port(fill, 'input', bottle, 1, 'ea')
const fillOut = await port(fill, 'output', filled, 1, 'ea')
const packFilled = await port(pack, 'input', filled, 24, 'ea')
await port(pack, 'input', box, 1, 'ea')
const packOut = await port(pack, 'output', melonMilk, 24, 'ea')
const connect = (fromProcessId, fromIoId, toProcessId, toIoId, lossRate) =>
  call('POST', '/process-connections', { workflowId, fromProcessId, toProcessId, fromIoId, toIoId, ...(lossRate ? { lossRate } : {}) })
await connect(receive, receiveOut, mix, mixMilk)
await connect(mix, mixOut, fill, fillMix, 0.02)
await connect(fill, fillOut, pack, packFilled, 0.01)
await call('PUT', `/processes/${mix}/requirements`, { requirements: [
  { kind: 'equipment', ref: mixer, amount: 1 }, { kind: 'time', amount: 30, unit: 'min' }] })
await call('PUT', `/processes/${fill}/requirements`, { requirements: [
  { kind: 'equipment', ref: filler, amount: 1 }, { kind: 'time', amount: 3, unit: 's', per: 'output_unit' }] })
await call('PUT', `/processes/${pack}/requirements`, { requirements: [
  { kind: 'crew', amount: 2 }, { kind: 'time', amount: 1, unit: 'min' }] })
await call('POST', `/workflows/${workflowId}/revisions`)

const plan = await call('POST', `/workflows/${workflowId}/plan-previews`, {
  target: { processIoId: packOut, quantity: 1000, unit: 'ea' },
})
console.log(`Project ${projectId}, workflow ${workflowId} (/projects/${projectId}/workflows/${workflowId})`)
console.log(`For 1,000 bottles: can make ${plan.buildable} now`)
for (const row of plan.requirements) {
  console.log(`  ${row.itemName}: ${row.needed} ${row.unit} needed, ${row.available} usable, ${row.shortage} short`)
}
for (const limit of plan.limits) console.log(`  limit: ${limit.kind} — ${limit.message}`)
