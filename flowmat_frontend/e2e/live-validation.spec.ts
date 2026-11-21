import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * The check as drawn (ADR-040 decision 7) against a mocked API: the flow kernel in the browser finds what the server's
 * check would, without "Check workflow", and finds it gone once the drawing changes. The mocked server keeps the graph,
 * so deleting a connection goes through the app's own mutation and canvas reload.
 */
const workflow = { workflowId: 'wf-live', projectId: 'prj-e2e', workflowName: 'Live check', workflowDesc: null,
  workflowType: 'standard', workflowStatus: 'draft' }
const node = (processId: string, processName: string, posX: number, nodeType: string) => ({ processId,
  projectId: 'prj-e2e', workflowId: 'wf-live', processName, processType: 'process', nodeType, processStatus: 'ready',
  colorScheme: 'slate', posX, posY: 160, width: 150, height: 72, processDesc: null, version: 1, versionNonce: 1 })
const connection = (connectionId: string, fromProcessId: string, toProcessId: string) => ({ connectionId,
  projectId: 'prj-e2e', workflowId: 'wf-live', fromProcessId, toProcessId, fromIoId: null, toIoId: null,
  sourceHandle: 'out-default', targetHandle: 'in-default', connectionType: 'flow', connectionLabel: null, flowRate: null,
  unit: null, delayTimeSec: null, lossRate: null, priority: null, conditionExpr: null, capacity: null,
  failurePolicy: 'stop', defaultRoute: false, splitKind: null, splitValue: null, version: 1, versionNonce: 1 })

test('the check as drawn finds and marks an issue without asking the server and drops it once it is fixed', async ({ page }) => {
  let connections = [connection('c-in-mix', 'p-in', 'p-mix'), connection('c-mix-out', 'p-mix', 'p-out'),
    connection('c-loop', 'p-mix', 'p-mix')]
  let validationRequests = 0
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'DELETE' && pathname.startsWith('/api/process-connections/')) {
      const id = decodeURIComponent(pathname.split('/').pop() ?? '')
      connections = connections.filter((entry) => entry.connectionId !== id)
      return ok(route, null)
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/workflows/wf-live/canvas') {
      return ok(route, { workflow, graphSeq: 0, processes: [node('p-in', 'Receive', 80, 'input'),
        node('p-mix', 'Mix', 360, 'process'), node('p-out', 'Ship', 640, 'output')],
      processIos: [], connections, annotations: [], currentUserRole: 'owner' })
    }
    if (pathname === '/api/workflows/wf-live/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    if (pathname === '/api/workflows/wf-live/validation') {
      validationRequests += 1
      return ok(route, { errors: 0, warnings: 0, issues: [] })
    }
    if (pathname === '/api/units') return ok(route, [{ unitId: 'u-kg', unitCode: 'kg', unitName: 'Kilogram', unitType: 'mass',
      baseUnitCode: null, conversionRate: 1, activeYn: 'Y' }])
    if (pathname === '/api/workflows') return ok(route, [workflow])
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/workflows/wf-live')
  await expect(page.locator('.react-flow__node')).toHaveCount(3, { timeout: 15_000 })

  // The canvas marks the connection the check is about.
  const mark = page.getByRole('img', { name: '1 error as drawn: CONNECTION_INCOMPATIBLE' })
  await expect(mark).toBeVisible()

  const live = page.getByRole('region', { name: 'Check as drawn' })
  const summary = live.getByRole('button', { name: /^As drawn:/ })
  await expect(summary).toHaveText('As drawn: 1 errors, 0 warnings')
  await summary.click()
  const issue = live.getByRole('button', { name: /^error: CONNECTION_INCOMPATIBLE/ })
  await expect(issue).toHaveText('error: CONNECTION_INCOMPATIBLE — A connection cannot link a process to itself.')
  expect(validationRequests).toBe(0)

  // The issue selects its connection; deleting it clears the issue as soon as the canvas has it.
  await issue.click()
  await page.getByRole('button', { name: 'Delete connection' }).click()
  await expect(summary).toHaveText('As drawn: 0 errors, 0 warnings')
  await expect(live.getByText('Nothing to fix as drawn.')).toBeVisible()
  await expect(page.getByRole('img', { name: /as drawn:/ })).toHaveCount(0)
  expect(validationRequests).toBe(0)
})
