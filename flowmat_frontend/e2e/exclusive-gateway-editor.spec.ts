import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * An Exclusive gateway's connections in the workflow editor (docs/architecture/adr/ADR-006-operation-types.md decision 8)
 * against a mocked API: a connection leaving the gateway offers "Default route" and saves it, and one leaving an ordinary
 * node neither offers nor sends it.
 */
test('a connection leaving an Exclusive gateway can be made its default route', async ({ page }) => {
  const workflow = { workflowId: 'wf-gate', projectId: 'prj-e2e', workflowName: 'Sorting', workflowDesc: null, workflowType: 'standard',
    workflowStatus: 'draft' }
  const node = (processId: string, processName: string, nodeType: string, posX: number) => ({ processId, projectId: 'prj-e2e',
    workflowId: 'wf-gate', processName, processType: nodeType === 'exclusive' ? 'gateway' : 'process', nodeType, processStatus: 'ready',
    colorScheme: 'slate', posX, posY: 160, width: 150, height: 72, processDesc: null, version: 1, versionNonce: 1 })
  const link = (connectionId: string, fromProcessId: string, toProcessId: string) => ({ connectionId, projectId: 'prj-e2e',
    workflowId: 'wf-gate', fromProcessId, toProcessId, fromIoId: null, toIoId: null, sourceHandle: 'out-default',
    targetHandle: 'in-default', connectionType: 'control_flow', connectionLabel: null, flowRate: null, unit: null, delayTimeSec: null,
    lossRate: null, priority: 1, conditionExpr: null, capacity: null, failurePolicy: 'stop', defaultRoute: false, version: 1,
    versionNonce: 1 })
  const processes = [node('proc-measure', 'Measure', 'process', 80), node('proc-choose', 'Choose', 'exclusive', 380),
    node('proc-rest', 'Rest', 'process', 680)]
  const connections = [link('conn-in', 'proc-measure', 'proc-choose'), link('conn-rest', 'proc-choose', 'proc-rest')]
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
      return ok(route, { ...current, defaultRoute: body.defaultRoute ?? current.defaultRoute, version: current.version + 1 })
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/workflows/wf-gate/canvas') {
      return ok(route, { workflow, graphSeq: 0, processes, processIos: [], connections, annotations: [], currentUserRole: 'owner' })
    }
    if (pathname === '/api/workflows/wf-gate/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    if (pathname === '/api/workflows') return ok(route, [workflow])
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/workflows/wf-gate')
  await expect(page.locator('.react-flow__node').filter({ hasText: 'Choose' })).toBeVisible({ timeout: 15_000 })
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

  // Into the gateway: an ordinary node's connection, so no default route is offered or sent.
  await selectEdge('conn-in')
  const save = page.getByRole('button', { name: 'Save Connection' })
  await expect(save).toBeVisible()
  await expect(page.getByRole('checkbox', { name: 'Default route' })).toHaveCount(0)
  await save.click()
  await expect.poll(() => sent.length).toBe(1)
  expect(sent[0].connectionId).toBe('conn-in')
  expect(sent[0]).not.toHaveProperty('defaultRoute')

  // Out of the gateway: the default route is offered and saved.
  await selectEdge('conn-rest')
  const defaultRoute = page.getByRole('checkbox', { name: 'Default route' })
  await expect(defaultRoute).not.toBeChecked()
  await expect(page.getByText('the default route is taken when none holds', { exact: false })).toBeVisible()
  await defaultRoute.check()
  await save.click()
  await expect.poll(() => sent.length).toBe(2)
  expect(sent[1]).toMatchObject({ connectionId: 'conn-rest', defaultRoute: true })
  await expect(defaultRoute).toBeChecked()
})
