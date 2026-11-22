#!/usr/bin/env node
// The melon milk demo (docs/status/ROADMAP-2026-11-23.md §8): a new project with raw milk in → mix → fill → pack, its
// materials, stock (raw milk short), equipment and execution requirements, published so a plan can be asked for at once.
// Everything goes through the public API, so stock is written by the inventory command as any receipt is: no migration,
// and it works the same on a local, pilot or demo deployment.
//
//   FLOWMAT_API=http://127.0.0.1:8080/api FLOWMAT_USER=demo-owner FLOWMAT_PASSWORD=... node scripts/demo/melon-milk.mjs
//
// It prints the project and workflow, and the plan for 1,000 bottles.

// Home's "견본으로 시작" builds the same sample from data (flowmat_frontend/src/features/sample-start/model/samples.ts):
// change both.

import { connect, printPlan, sample } from './lib.mjs'

const { call, user } = await connect()
const s = await sample(call, user, 'Melon milk demo')
const milk = await s.item('MM-MILK', 'Raw milk', 'material', 'unit_kg')
const syrup = await s.item('MM-SYRUP', 'Melon syrup', 'material', 'unit_kg')
const sugar = await s.item('MM-SUGAR', 'Sugar', 'material', 'unit_kg')
const bottle = await s.item('MM-BOTTLE', 'Bottle', 'material', 'unit_ea')
const box = await s.item('MM-BOX', 'Box of 24', 'material', 'unit_ea')
const mixed = await s.item('MM-MIX', 'Melon milk mix', 'semi', 'unit_kg')
const filled = await s.item('MM-FILLED', 'Filled bottle', 'semi', 'unit_ea')
const melonMilk = await s.item('MM-PRODUCT', 'Melon milk', 'product', 'unit_ea')
// Raw milk is short for 1,000 bottles: 150 kg of the 184.0564 kg needed.
for (const [itemId, quantity] of [[milk, 150], [syrup, 20], [sugar, 50], [bottle, 2000], [box, 50]]) await s.stock(itemId, quantity)
const mixer = await s.equipment('MIXER-1')
const filler = await s.equipment('FILLER-1')

const w = await s.workflow('Melon milk')
const receive = await w.node('Receive milk')
const mix = await w.node('Mix')
const fill = await w.node('Fill')
const pack = await w.node('Pack')
await w.port(receive, 'input', milk, 1, 'kg')
const receiveOut = await w.port(receive, 'output', milk, 1, 'kg')
const mixMilk = await w.port(mix, 'input', milk, 100, 'kg')
await w.port(mix, 'input', syrup, 10, 'kg')
await w.port(mix, 'input', sugar, 5, 'kg')
const mixOut = await w.port(mix, 'output', mixed, 112, 'kg')
const fillMix = await w.port(fill, 'input', mixed, 0.2, 'kg')
await w.port(fill, 'input', bottle, 1, 'ea')
const fillOut = await w.port(fill, 'output', filled, 1, 'ea')
const packFilled = await w.port(pack, 'input', filled, 24, 'ea')
await w.port(pack, 'input', box, 1, 'ea')
const packOut = await w.port(pack, 'output', melonMilk, 24, 'ea')
await w.connect(receive, receiveOut, mix, mixMilk)
await w.connect(mix, mixOut, fill, fillMix, 0.02)
await w.connect(fill, fillOut, pack, packFilled, 0.01)
await w.requirements(mix, [{ kind: 'equipment', ref: mixer, amount: 1 }, { kind: 'time', amount: 30, unit: 'min' }])
await w.requirements(fill, [{ kind: 'equipment', ref: filler, amount: 1 }, { kind: 'time', amount: 3, unit: 's', per: 'output_unit' }])
await w.requirements(pack, [{ kind: 'crew', amount: 2 }, { kind: 'time', amount: 1, unit: 'min' }])
await w.publish()
await printPlan(call, s.projectId, w.workflowId, { processIoId: packOut, quantity: 1000, unit: 'ea' })
