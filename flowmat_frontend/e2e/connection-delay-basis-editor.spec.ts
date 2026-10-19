import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * What a connection's delay counts, in the workflow editor (docs/architecture/adr/ADR-022-working-time-delay.md) against a
 * mocked API: a connection without a basis shows All time, and Working time goes with the save.
 */
test('a connection delay can count only working time', async ({ page }) => {
  const workflow = { workflowId: 'wf-basis', projectId: 'prj-e2e', workflowName: 'Curing', workflowDesc: null, workflowType: 'standard',
    workflowStatus: 'draft' }
  const node = (processId: string, processName: string, posX: number) => ({ processId, projectId: 'prj-e2e', workflowId: 'wf-basis',
    processName, processType: 'process', nodeType: 'process', processStatus: 'ready', colorScheme: 'slate', posX, posY: 160,
    width: 150, height: 72, processDesc: null, version: 1, versionNonce: 1 })
  // An older answer: no delayBasis.
  const connection = { connectionId: 'conn-wait', projectId: 'prj-e2e', workflowId: 'wf-basis', fromProcessId: 'proc-mix',
    toProcessId: 'proc-check', fromIoId: null, toIoId: null, sourceHandle: 'out-default', targetHandle: 'in-default',
    connectionType: 'material_flow', connectionLabel: null, flowRate: null, unit: null, delayTimeSec: 7200, lossRate: 0,
    priority: 1, conditionExpr: null, capacity: null, failurePolicy: 'stop', defaultRoute: false, splitKind: null, splitValue: null,
    version: 1, versionNonce: 1 }
  const sent: Record<string, unknown>[] = []

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'PUT' && pathname === '/api/process-connections/conn-wait') {
      const body = request.postDataJSON() as Record<string, unknown>
      sent.push(body)
      return ok(route, { ...connection, delayBasis: body.delayBasis ?? 'elapsed', version: connection.version + sent.length })
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/workflows/wf-basis/canvas') {
      return ok(route, { workflow, graphSeq: 0, processes: [node('proc-mix', 'Mix', 80), node('proc-check', 'Check', 480)],
        processIos: [], connections: [connection], annotations: [], currentUserRole: 'owner' })
    }
    if (pathname === '/api/workflows/wf-basis/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    if (pathname === '/api/workflows') return ok(route, [workflow])
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/workflows/wf-basis')
  await expect(page.locator('.react-flow__edge')).toHaveCount(1, { timeout: 15_000 })
  // A quarter of the way along: the edge's Delete and Insert buttons sit at its middle.
  await page.getByRole('button', { name: 'Pointer', exact: true }).first().click()
  const edgePath = page.locator('.react-flow__edge[data-id="conn-wait"] .react-flow__edge-interaction')
  await expect(edgePath).toBeAttached()
  const point = await edgePath.evaluate((element) => {
    const path = element as SVGPathElement
    const point = path.getPointAtLength(path.getTotalLength() / 4).matrixTransform(path.getScreenCTM()!)
    return { x: point.x, y: point.y }
  })
  await page.mouse.click(point.x, point.y)

  const basis = page.getByRole('combobox', { name: 'Delay counts' })
  await expect(basis).toHaveValue('elapsed')
  await basis.selectOption('working')
  await expect(page.getByText("In a production run, only the time its work order's equipment and crew work counts.")).toBeVisible()
  await page.getByRole('button', { name: 'Save Connection' }).click()
  await expect.poll(() => sent.length).toBe(1)
  expect(sent[0]).toMatchObject({ delayTimeSec: 7200, delayBasis: 'working' })
})
