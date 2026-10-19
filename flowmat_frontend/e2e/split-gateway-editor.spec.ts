import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * A Split gateway's connections in the workflow editor (docs/architecture/adr/ADR-008-quantity-split.md decision 11)
 * against a mocked API: a connection leaving the Split takes a percent, a fixed quantity or the rest, a bad percent is
 * refused before sending, and a connection leaving an ordinary node neither offers nor sends a share.
 */
test('a connection leaving a Split gateway takes a share', async ({ page }) => {
  const workflow = { workflowId: 'wf-split', projectId: 'prj-e2e', workflowName: 'Dividing', workflowDesc: null, workflowType: 'standard',
    workflowStatus: 'draft' }
  const node = (processId: string, processName: string, nodeType: string, posX: number) => ({ processId, projectId: 'prj-e2e',
    workflowId: 'wf-split', processName, processType: nodeType === 'split' ? 'gateway' : 'process', nodeType, processStatus: 'ready',
    colorScheme: 'slate', posX, posY: 160, width: 150, height: 72, processDesc: null, version: 1, versionNonce: 1 })
  const link = (connectionId: string, fromProcessId: string, toProcessId: string) => ({ connectionId, projectId: 'prj-e2e',
    workflowId: 'wf-split', fromProcessId, toProcessId, fromIoId: null, toIoId: null, sourceHandle: 'out-default',
    targetHandle: 'in-default', connectionType: 'material_flow', connectionLabel: null, flowRate: null, unit: null, delayTimeSec: null,
    lossRate: null, priority: 1, conditionExpr: null, capacity: null, failurePolicy: 'stop', defaultRoute: false, splitKind: null,
    splitValue: null, version: 1, versionNonce: 1 })
  const processes = [node('proc-mix', 'Mix', 'process', 80), node('proc-divide', 'Divide', 'split', 380),
    node('proc-fill', 'Fill', 'process', 680)]
  const connections = [link('conn-in', 'proc-mix', 'proc-divide'), link('conn-fill', 'proc-divide', 'proc-fill')]
  const sent: Record<string, unknown>[] = []

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    const update = pathname.match(/^\/api\/process-connections\/([^/]+)$/)
    if (request.method() === 'PUT' && update) {
      const body = request.postDataJSON() as Record<string, unknown>
      sent.push({ connectionId: update[1], ...body })
      const current = connections.find((one) => one.connectionId === update[1])!
      return ok(route, { ...current, splitKind: body.splitKind ?? null, splitValue: body.splitValue ?? null, version: current.version + 1 })
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/workflows/wf-split/canvas') {
      return ok(route, { workflow, graphSeq: 0, processes, processIos: [], connections, annotations: [], currentUserRole: 'owner' })
    }
    if (pathname === '/api/workflows/wf-split/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    if (pathname === '/api/workflows') return ok(route, [workflow])
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/workflows/wf-split')
  await expect(page.locator('.react-flow__node').filter({ hasText: 'Divide' })).toBeVisible({ timeout: 15_000 })
  await expect(page.locator('.react-flow__edge')).toHaveCount(2)
  await page.getByRole('button', { name: 'Pointer', exact: true }).first().click()
  // A quarter of the way along: the edge's Delete and Insert buttons sit at its middle.
  async function selectEdge(connectionId: string) {
    const edgePath = page.locator(`.react-flow__edge[data-id="${connectionId}"] .react-flow__edge-interaction`)
    await expect(edgePath).toBeAttached()
    const point = await edgePath.evaluate((element) => {
      const path = element as SVGPathElement
      const point = path.getPointAtLength(path.getTotalLength() / 4).matrixTransform(path.getScreenCTM()!)
      return { x: point.x, y: point.y }
    })
    await page.mouse.click(point.x, point.y)
  }
  const save = page.getByRole('button', { name: 'Save Connection' })

  // Into the Split: an ordinary node's connection, so no share is offered or sent.
  await selectEdge('conn-in')
  await expect(save).toBeVisible()
  await expect(page.getByRole('combobox', { name: 'Split share' })).toHaveCount(0)
  await save.click()
  await expect.poll(() => sent.length).toBe(1)
  expect(sent[0]).not.toHaveProperty('splitKind')
  expect(sent[0]).not.toHaveProperty('clearSplit')

  // Out of the Split: a percent above 100 is refused first; 70 goes.
  await selectEdge('conn-fill')
  const kind = page.getByRole('combobox', { name: 'Split share' })
  await kind.selectOption('share')
  await page.getByRole('spinbutton', { name: 'Split value' }).fill('120')
  await save.click()
  await expect(page.getByRole('alert').filter({ hasText: 'A percent share is above 0 and at most 100.' })).toBeVisible()
  expect(sent).toHaveLength(1)
  await page.getByRole('spinbutton', { name: 'Split value' }).fill('70')
  await save.click()
  await expect.poll(() => sent.length).toBe(2)
  expect(sent[1]).toMatchObject({ connectionId: 'conn-fill', splitKind: 'share', splitValue: 70 })

  // The rest needs no value.
  await kind.selectOption('rest')
  await expect(page.getByRole('spinbutton', { name: 'Split value' })).toHaveCount(0)
  await save.click()
  await expect.poll(() => sent.length).toBe(3)
  expect(sent[2]).toMatchObject({ connectionId: 'conn-fill', splitKind: 'rest' })
  expect(sent[2]).not.toHaveProperty('splitValue')
})
