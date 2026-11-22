#!/usr/bin/env node
// The assembly sample (docs/status/ROADMAP-2026-11-23.md §8, next month 1): cut → sand → assemble → pack for 200 stools.
// Legs are cut from boards (4 legs a board, 5% scrap before sanding), a stool takes a seat, 4 legs and 8 screws, and a box
// holds one. Screws are short. Same use as melon-milk.mjs:
//
//   FLOWMAT_API=http://127.0.0.1:8080/api FLOWMAT_USER=demo-owner FLOWMAT_PASSWORD=... node scripts/demo/assembly.mjs

// Home's "견본으로 시작" builds the same sample from data (flowmat_frontend/src/features/sample-start/model/samples.ts):
// change both.

import { connect, printPlan, sample } from './lib.mjs'

const { call, user } = await connect()
const s = await sample(call, user, 'Stool assembly demo')
const board = await s.item('ST-BOARD', 'Oak board', 'material', 'unit_ea')
const seat = await s.item('ST-SEAT', 'Seat', 'material', 'unit_ea')
const screw = await s.item('ST-SCREW', 'Screw', 'material', 'unit_ea')
const box = await s.item('ST-BOX', 'Box', 'material', 'unit_ea')
const leg = await s.item('ST-LEG', 'Leg (cut)', 'semi', 'unit_ea')
const sanded = await s.item('ST-LEG-S', 'Leg (sanded)', 'semi', 'unit_ea')
const stoolRaw = await s.item('ST-RAW', 'Stool (assembled)', 'semi', 'unit_ea')
const stool = await s.item('ST-STOOL', 'Stool', 'product', 'unit_ea')
for (const [itemId, quantity] of [[board, 250], [seat, 220], [screw, 1200], [box, 300]]) await s.stock(itemId, quantity)
const saw = await s.equipment('SAW-1')
const sander = await s.equipment('SANDER-1')

const w = await s.workflow('Stool')
const cut = await w.node('Cut legs')
const sand = await w.node('Sand')
const assemble = await w.node('Assemble')
const pack = await w.node('Pack')
await w.port(cut, 'input', board, 1, 'ea')
const cutOut = await w.port(cut, 'output', leg, 4, 'ea')
const sandIn = await w.port(sand, 'input', leg, 1, 'ea')
const sandOut = await w.port(sand, 'output', sanded, 1, 'ea')
const assembleLegs = await w.port(assemble, 'input', sanded, 4, 'ea')
await w.port(assemble, 'input', seat, 1, 'ea')
await w.port(assemble, 'input', screw, 8, 'ea')
const assembleOut = await w.port(assemble, 'output', stoolRaw, 1, 'ea')
const packIn = await w.port(pack, 'input', stoolRaw, 1, 'ea')
await w.port(pack, 'input', box, 1, 'ea')
const packOut = await w.port(pack, 'output', stool, 1, 'ea')
await w.connect(cut, cutOut, sand, sandIn, 0.05)
await w.connect(sand, sandOut, assemble, assembleLegs)
await w.connect(assemble, assembleOut, pack, packIn)
await w.requirements(cut, [{ kind: 'equipment', ref: saw, amount: 1 }, { kind: 'time', amount: 2, unit: 'min' }])
await w.requirements(sand, [{ kind: 'equipment', ref: sander, amount: 1 }, { kind: 'time', amount: 45, unit: 's' }])
await w.requirements(assemble, [{ kind: 'crew', amount: 1 }, { kind: 'time', amount: 6, unit: 'min' }])
await w.requirements(pack, [{ kind: 'crew', amount: 1 }, { kind: 'time', amount: 1, unit: 'min' }])
await w.publish()
await printPlan(call, s.projectId, w.workflowId, { processIoId: packOut, quantity: 200, unit: 'ea' })
