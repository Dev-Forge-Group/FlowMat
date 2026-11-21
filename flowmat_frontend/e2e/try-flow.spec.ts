import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * Trying the flow (ADR-040 decision 7) against a mocked API: an input at the start goes through a Split and a lossy
 * connection to the end, in the browser and without asking the server; the outcome lists each step and the canvas shows
 * what reached each connection.
 */
const workflow = { workflowId: 'wf-try', projectId: 'prj-e2e', workflowName: 'Try line', workflowDesc: null,
  workflowType: 'standard', workflowStatus: 'draft' }
const node = (processId: string, processName: string, posX: number, posY: number, nodeType: string) => ({ processId,
  projectId: 'prj-e2e', workflowId: 'wf-try', processName, processType: 'process', nodeType, processStatus: 'ready',
  colorScheme: 'slate', posX, posY, width: 150, height: 72, processDesc: null, version: 1, versionNonce: 1 })
const connection = (connectionId: string, fromProcessId: string, toProcessId: string, extra: Record<string, unknown> = {}) => ({
  connectionId, projectId: 'prj-e2e', workflowId: 'wf-try', fromProcessId, toProcessId, fromIoId: null, toIoId: null,
  sourceHandle: 'out-default', targetHandle: 'in-default', connectionType: 'flow', connectionLabel: null, flowRate: null,
  unit: null, delayTimeSec: null, lossRate: null, priority: null, conditionExpr: null, capacity: null,
  failurePolicy: 'stop', defaultRoute: false, splitKind: null, splitValue: null, version: 1, versionNonce: 1, ...extra })

test('trying the flow follows a split and a loss to the end and shows what reached each connection', async ({ page }) => {
  const requests: string[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    requests.push(`${request.method()} ${pathname}`)
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/workflows/wf-try/canvas') {
      return ok(route, { workflow, graphSeq: 0, processes: [node('p-in', 'Receive', 60, 160, 'input'),
        node('p-split', 'Divide', 320, 160, 'split'), node('p-a', 'Line A', 600, 60, 'output'),
        node('p-b', 'Line B', 600, 280, 'output')],
      processIos: [], connections: [connection('c-in', 'p-in', 'p-split'),
        connection('c-a', 'p-split', 'p-a', { splitKind: 'share', splitValue: 60, lossRate: 0.1 }),
        connection('c-b', 'p-split', 'p-b', { splitKind: 'rest' })],
      annotations: [], currentUserRole: 'owner' })
    }
    if (pathname === '/api/workflows/wf-try/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    if (pathname === '/api/workflows') return ok(route, [workflow])
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/workflows/wf-try')
  await expect(page.locator('.react-flow__node')).toHaveCount(4, { timeout: 15_000 })

  const tryIt = page.getByRole('region', { name: 'Try the flow' })
  await tryIt.getByRole('button', { name: 'Try it' }).click()
  await tryIt.getByLabel('Quantity').fill('10')
  await tryIt.getByLabel('Unit').fill('kg')
  const before = requests.length
  await tryIt.getByRole('button', { name: 'Run through' }).click()

  await expect(tryIt.getByText('Reaches the end in 4 steps, losing 0.6 kg on the way')).toBeVisible()
  await expect(tryIt.getByRole('listitem')).toHaveText([
    'Receive: 10 kg (completed)', 'Divide: 10 kg (completed)', 'Line A: 5.4 kg (completed)', 'Line B: 4 kg (completed)'])
  await expect(page.getByLabel('Tried: 10 kg')).toBeVisible()
  await expect(page.getByLabel('Tried: 5.4 kg')).toBeVisible()
  await expect(page.getByLabel('Tried: 4 kg')).toBeVisible()
  // Nothing went to the server.
  expect(requests.slice(before).filter((entry) => !entry.startsWith('GET /api/units'))).toEqual([])

  // A step's button selects its process, which opens in the Semantic Passport.
  const passport = page.getByRole('region', { name: 'Line B', exact: true })
  await expect(passport).toHaveCount(0)
  await tryIt.getByRole('button', { name: 'Line B: 4 kg (completed)' }).click()
  await expect(passport).toContainText('Semantic passport')
})
