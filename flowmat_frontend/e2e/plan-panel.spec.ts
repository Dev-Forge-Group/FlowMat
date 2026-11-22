import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * The editor's Plan panel (docs/status/ROADMAP-2026-11-23.md §8 week 3, ADR-043) against a mocked API: the product's
 * output is the default target, the plan's materials against stock, what can be made now, the bottleneck, and a
 * material's path lit on the canvas.
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

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/workflows/wf-plan/plan-previews') {
      sent.push(request.postDataJSON())
      return ok(route, {
        revisionId: 'rev-1', target: 1000, targetUnit: 'ea', feasible: true, buildable: 814,
        requirements: [{ at: 'mix-milk', processId: 'mix', itemId: 'milk', itemName: 'Raw milk', needed: 184.0564, available: 150,
          shortage: 34.0564, unit: 'kg', trace: [trace('pack', 'pack-out'), trace('mix', 'mix-milk')] }],
        nodes: [{ processId: 'mix', batches: 1.8406, through: null, unit: null, minutes: 55.2169 },
          { processId: 'pack', batches: 1010.1011, through: null, unit: null, minutes: 50.5051 }],
        limits: [{ kind: 'stock', at: 'mix-milk', processId: 'mix', message: 'Raw milk is short by 34.0564 kg.' },
          { kind: 'equipment-time', at: 'mix', processId: 'mix', message: 'The longest equipment step takes 55.2169 minutes.' }],
        assumptions: [], basis: { revisionId: 'rev-1', available: { milk: 150 } },
      })
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
  await expect(panel.getByText('Can make 814 now')).toBeVisible()
  await expect(panel.getByText('Bottleneck: Mix')).toBeVisible()
  await expect(panel.getByRole('list', { name: 'Steps' })).toContainText('Mix: 1.8406 batches, 55.2169 min')
  expect(sent[0]).toEqual({ target: { processIoId: 'pack-out', quantity: 1000, unit: 'ea' } })

  await panel.getByRole('row', { name: /Raw milk/ }).click()
  await expect(page.locator('.react-flow__node.is-traced')).toHaveCount(2)
})
