import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * The editor's Plan panel (docs/status/ROADMAP-2026-11-23.md §8 week 3, ADR-043) against a mocked API: the product's
 * output is the default target, the plan's materials against stock, what can be made now, the bottleneck, and a
 * material's path lit on the canvas; then the confirmed plan against its finished runs.
 */
test('the Plan panel plans from a target and lights a material’s path', async ({ page }) => {
  const workflow = { workflowId: 'wf-plan', projectId: 'prj-e2e', workflowName: 'Melon milk', workflowDesc: null,
    workflowType: 'standard', workflowStatus: 'draft' }
  const node = (processId: string, processName: string, posX: number) => ({ processId, projectId: 'prj-e2e', workflowId: 'wf-plan',
    processName, processType: 'process', nodeType: 'process', processStatus: 'ready', colorScheme: 'slate', posX, posY: 160,
    width: 180, height: 80, processDesc: null, version: 1, versionNonce: 1 })
  const port = (processIoId: string, processId: string, direction: string, ioName: string, quantity: number, unit: string) => ({
    processIoId, processId, itemId: null, ioName, direction, ioType: 'material', quantity, unit, formula: null, colorScheme: 'sky',
    requiredYn: 'N', allowShortageYn: 'N', resourceType: 'material' })
  const processIos = [
    port('mix-milk', 'mix', 'input', 'Raw milk', 100, 'kg'), port('mix-out', 'mix', 'output', 'Mix', 112, 'kg'),
    port('pack-in', 'pack', 'input', 'Mix', 0.2, 'kg'), port('pack-out', 'pack', 'output', 'Melon milk', 1, 'ea'),
  ]
  const connections = [{ connectionId: 'c1', workflowId: 'wf-plan', fromProcessId: 'mix', toProcessId: 'pack', fromIoId: 'mix-out',
    toIoId: 'pack-in', sourceHandle: 'mix-out', targetHandle: 'pack-in', connectionType: 'flow', connectionLabel: null,
    flowRate: null, unit: null, delayTimeSec: null, lossRate: null, priority: 0 }]
  const trace = (processId: string, at: string) => ({ at, processId, rule: 'ratio', from: 1000, to: 1000, factor: 1 })
  const sent: unknown[] = []
  const confirmed: { target: unknown; basis: unknown; requestId: string; supplierName: string }[] = []

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/workflows/wf-plan/plan-previews') {
      sent.push(request.postDataJSON())
      return ok(route, {
        revisionId: 'rev-1', target: 1000, targetUnit: 'ea', feasible: true, buildable: 814,
        requirements: [{ at: 'mix-milk', processId: 'mix', itemId: 'milk', itemName: 'Raw milk', needed: 184.0564, available: 150,
          shortage: 34.0564, unit: 'kg', trace: [trace('pack', 'pack-out'), trace('mix', 'mix-milk')], resourceType: 'material',
          unitCost: 1.2, cost: 220.8677 },
        // Energy is drawn, not stocked (ADR-046): no stock, no shortage, its own cost.
        { at: 'mix-power', processId: 'mix', itemId: 'power', itemName: 'Electricity', needed: 27.6, available: null,
          shortage: null, unit: 'kWh', trace: [], resourceType: 'energy', unitCost: 0.2, cost: 5.52 }],
        resources: [{ resourceType: 'energy', unit: 'kWh', needed: 27.6, cost: 5.52 }],
        costs: [{ resourceType: 'material', cost: 220.8677, unknown: 0 }, { resourceType: 'energy', cost: 5.52, unknown: 0 }],
        totalCost: 226.3877,
        nodes: [{ processId: 'mix', batches: 1.8406, through: null, unit: null, minutes: 55.2169 },
          { processId: 'pack', batches: 1010.1011, through: null, unit: null, minutes: 50.5051 }],
        limits: [{ kind: 'stock', at: 'mix-milk', processId: 'mix', message: 'Raw milk is short by 34.0564 kg.' },
          { kind: 'equipment-time', at: 'mix', processId: 'mix', message: 'The longest equipment step takes 55.2169 minutes.' }],
        // A loop the plan goes round (ADR-048): what comes back to Mix each round is credited.
        assumptions: [{ kind: 'loop', at: 'mix', value: '18% of what passes comes back by c-back, for up to 10 rounds' }],
        basis: { revisionId: 'rev-1', available: { milk: 150 } },
      })
    }
    if (request.method() === 'POST' && pathname === '/api/workflows/wf-plan/plan-confirmations') {
      confirmed.push(request.postDataJSON())
      return ok(route, { requestId: 'k', revisionId: 'rev-1', workOrderId: 'wo-1', purchaseOrderIds: ['po-1'] })
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/workflows/wf-plan/canvas') {
      return ok(route, { workflow, graphSeq: 0, processes: [node('mix', 'Mix', 120), node('pack', 'Pack', 480)], processIos,
        connections, annotations: [], currentUserRole: 'owner' })
    }
    if (pathname === '/api/workflows/wf-plan/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    if (pathname === '/api/workflows') return ok(route, [workflow])
    if (pathname === '/api/workflows/wf-plan/plan-confirmations') {
      return ok(route, confirmed.length === 0 ? [] : [{ requestId: confirmed[0].requestId, revisionId: 'rev-1', targetPortId: 'pack-out',
        targetConnectionId: null, targetQuantity: 1000, workOrderId: 'wo-1', purchaseOrderIds: ['po-1'], confirmedAt: '2030-01-07T09:00:00Z' }])
    }
    if (pathname === '/api/work-orders/wo-1/plan-actuals') {
      return ok(route, { workOrderId: 'wo-1', workOrderStatus: 'in_progress', workflowId: 'wf-plan', revisionId: 'rev-1',
        confirmedAt: '2030-01-07T09:00:00Z', target: 1000, targetUnit: 'ea', output: 500, finishedRuns: 1, openRuns: 0,
        materialTolerancePercent: 5, timeTolerancePercent: 15,
        materials: [{ itemId: 'milk', itemName: 'Raw milk', unit: 'kg', plannedForTarget: 184.0564, planned: 92.0282, used: 97.6,
          variancePercent: 6.1, within: false }],
        steps: [{ processId: 'mix', processName: 'Mix', plannedForTarget: 55.2169, planned: 27.6085, actual: 30, measured: 1,
          variancePercent: 8.7, within: true }],
        complete: true, notes: [] })
    }
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/workflows/wf-plan')
  await expect(page.locator('.react-flow__node')).toHaveCount(2, { timeout: 15_000 })
  await page.getByRole('button', { name: 'Plan', exact: true }).click()
  const panel = page.getByRole('region', { name: 'Plan the flow' })
  await expect(panel.getByLabel('Output')).toHaveValue('pack-out')
  await panel.getByRole('button', { name: 'Plan', exact: true }).click()
  await expect(panel.getByRole('alert')).toHaveText('The target must be more than 0.')
  expect(sent).toHaveLength(0)

  await panel.getByLabel('Target').fill('1000')
  await panel.getByRole('button', { name: 'Plan', exact: true }).click()
  await expect(panel.getByRole('row', { name: /Raw milk/ })).toContainText('184.0564 kg')
  await expect(panel.getByRole('row', { name: /Raw milk/ })).toContainText('34.0564 kg short')
  await expect(panel.getByRole('row', { name: /Raw milk/ })).toContainText('220.8677')
  await expect(panel.getByRole('row', { name: /Electricity/ })).toContainText('not stocked')
  await expect(panel.getByLabel('Resources and cost')).toHaveText('Energy 27.6 kWh · Cost 226.3877')
  await expect(panel.getByText('Can make 814 now')).toBeVisible()
  await expect(panel.getByText('Bottleneck: Mix')).toBeVisible()
  await expect(panel.getByRole('list', { name: 'Steps' })).toContainText('Mix: 1.8406 batches, 55.2169 min')
  await expect(panel.getByRole('list', { name: 'Loops' })).toHaveText('Mix: 18% of what passes comes back by c-back, for up to 10 rounds')
  expect(sent[0]).toEqual({ target: { processIoId: 'pack-out', quantity: 1000, unit: 'ea' } })

  await panel.getByRole('row', { name: /Raw milk/ }).click()
  await expect(page.locator('.react-flow__node.is-traced')).toHaveCount(2)

  // Confirming: milk is short, so the draft purchase order needs a supplier.
  await panel.getByRole('button', { name: 'Confirm plan' }).click()
  await expect(panel.getByRole('alert')).toHaveText('Name the supplier for the draft purchase order of the shortages.')
  expect(confirmed).toHaveLength(0)
  await panel.getByLabel('Supplier for the shortages').fill('Dairy Co')
  await panel.getByRole('button', { name: 'Confirm plan' }).click()
  await expect(panel.getByRole('status')).toContainText('Draft work order made with a draft purchase order for the shortages.')
  expect(confirmed[0]).toMatchObject({ target: { processIoId: 'pack-out', quantity: 1000, unit: 'ea' },
    basis: { revisionId: 'rev-1', available: { milk: 150 } }, supplierName: 'Dairy Co' })
  expect(confirmed[0].requestId).toMatch(/^plan-/)

  // The confirmed plan, against what its finished runs did (docs/domain/plan-vs-actual.md).
  const plans = panel.getByRole('group', { name: 'Confirmed plans' })
  await plans.getByRole('button', { name: 'Plan vs actual' }).click()
  const comparison = panel.getByRole('group', { name: 'Plan vs actual' })
  await expect(comparison).toContainText('Made 500 of 1000 ea in 1 finished run(s).')
  await expect(comparison.getByRole('row', { name: 'Raw milk' })).toContainText('92.0282 kg')
  await expect(comparison.getByRole('row', { name: 'Raw milk' })).toContainText('+6.1% off')
  await expect(comparison.getByRole('row', { name: 'Mix' })).toContainText('+8.7%')
  await expect(comparison.getByRole('row', { name: 'Mix' })).not.toContainText('off')
})
