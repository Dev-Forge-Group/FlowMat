import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * A connection's loss and delay in the workflow editor (docs/architecture/adr/ADR-009-connection-loss-rate.md decisions
 * 2, 10; docs/architecture/adr/ADR-010-connection-delay.md decision 8) against a mocked API: the stored fraction shows as
 * a percent, a percent goes as a fraction, and 100% or more or a negative delay is refused before sending.
 */
test('a connection takes its loss as a percent and sends a fraction', async ({ page }) => {
  const workflow = { workflowId: 'wf-loss', projectId: 'prj-e2e', workflowName: 'Losing', workflowDesc: null, workflowType: 'standard',
    workflowStatus: 'draft' }
  const node = (processId: string, processName: string, posX: number) => ({ processId, projectId: 'prj-e2e', workflowId: 'wf-loss',
    processName, processType: 'process', nodeType: 'process', processStatus: 'ready', colorScheme: 'slate', posX, posY: 160,
    width: 150, height: 72, processDesc: null, version: 1, versionNonce: 1 })
  const connection = { connectionId: 'conn-pipe', projectId: 'prj-e2e', workflowId: 'wf-loss', fromProcessId: 'proc-mix',
    toProcessId: 'proc-fill', fromIoId: null, toIoId: null, sourceHandle: 'out-default', targetHandle: 'in-default',
    connectionType: 'material_flow', connectionLabel: null, flowRate: null, unit: null, delayTimeSec: 0, lossRate: 0.02,
    priority: 1, conditionExpr: null, capacity: null, failurePolicy: 'stop', defaultRoute: false, splitKind: null, splitValue: null,
    version: 1, versionNonce: 1 }
  const sent: Record<string, unknown>[] = []

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'PUT' && pathname === '/api/process-connections/conn-pipe') {
      const body = request.postDataJSON() as Record<string, unknown>
      sent.push(body)
      return ok(route, { ...connection, lossRate: body.lossRate ?? 0, version: connection.version + sent.length })
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/workflows/wf-loss/canvas') {
      return ok(route, { workflow, graphSeq: 0, processes: [node('proc-mix', 'Mix', 80), node('proc-fill', 'Fill', 480)],
        processIos: [], connections: [connection], annotations: [], currentUserRole: 'owner' })
    }
    if (pathname === '/api/workflows/wf-loss/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    if (pathname === '/api/workflows') return ok(route, [workflow])
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/workflows/wf-loss')
  await expect(page.locator('.react-flow__edge')).toHaveCount(1, { timeout: 15_000 })
  // A quarter of the way along: the edge's Delete and Insert buttons sit at its middle.
  const box = await page.locator('.react-flow__edge[data-id="conn-pipe"] .react-flow__edge-interaction').boundingBox()
  expect(box).not.toBeNull()
  await page.mouse.click(box!.x + box!.width / 4, box!.y + box!.height / 2)

  const loss = page.getByRole('spinbutton', { name: 'Loss %' })
  await expect(loss).toHaveValue('2')
  const save = page.getByRole('button', { name: 'Save Connection' })
  const delay = page.getByRole('spinbutton', { name: 'Delay (s)' })
  await delay.fill('-5')
  await save.click()
  await expect(page.getByRole('alert').filter({ hasText: 'Delay is 0 or more seconds.' })).toBeVisible()
  expect(sent).toHaveLength(0)
  await delay.fill('0')
  await loss.fill('100')
  await save.click()
  await expect(page.getByRole('alert').filter({ hasText: 'Loss % is 0 or more and below 100.' })).toBeVisible()
  expect(sent).toHaveLength(0)

  await loss.fill('2.5')
  await save.click()
  await expect.poll(() => sent.length).toBe(1)
  expect(sent[0]).toMatchObject({ lossRate: 0.025 })
})
