import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

// The start typed into the panel is local time; Seoul keeps its offset (+09:00) fixed for the times below.
test.use({ timezoneId: 'Asia/Seoul' })

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

  await page.getByRole('button', { name: 'Try it' }).click()
  const tryIt = page.getByRole('region', { name: 'Try the flow' })
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

test('trying the flow with a work order says when each step could start and what it waits for', async ({ page }) => {
  const workingTime: string[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (await answerAuth(route, url.pathname)) return
    if (url.pathname === '/api/workflows/wf-try/canvas') {
      return ok(route, { workflow, graphSeq: 0, processes: [node('p-in', 'Receive', 60, 160, 'input'),
        node('p-bake', 'Bake', 320, 160, 'process'), node('p-pack', 'Pack', 600, 160, 'output')],
      processIos: [], connections: [
        // Half an hour of working time, then three and a half hours of all time.
        connection('c-bake', 'p-in', 'p-bake', { delayTimeSec: 1800, delayBasis: 'working' }),
        connection('c-pack', 'p-bake', 'p-pack', { delayTimeSec: 12600 })],
      annotations: [], currentUserRole: 'owner' })
    }
    if (url.pathname === '/api/workflows/wf-try/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    if (url.pathname === '/api/workflows') return ok(route, [workflow])
    if (url.pathname === '/api/work-orders') {
      return ok(route, [
        { workOrderId: 'wo-1', projectId: 'prj-e2e', workflowId: 'wf-try', workOrderNumber: 'WO-1', workOrderTitle: 'Morning bake',
          workOrderStatus: 'approved', equipmentId: 'eq-press' },
        { workOrderId: 'wo-2', projectId: 'prj-e2e', workflowId: 'wf-try', workOrderNumber: 'WO-2', workOrderTitle: 'Done',
          workOrderStatus: 'completed', equipmentId: null },
        { workOrderId: 'wo-3', projectId: 'prj-e2e', workflowId: 'wf-other', workOrderNumber: 'WO-3', workOrderTitle: 'Elsewhere',
          workOrderStatus: 'approved', equipmentId: null }])
    }
    if (url.pathname === '/api/work-orders/wo-1/working-time') {
      workingTime.push(url.searchParams.get('from') ?? '')
      return ok(route, { workOrderId: 'wo-1', from: '2026-12-21T07:00:00+09:00', until: '2026-12-28T07:00:00+09:00',
        allTime: false, equipmentId: 'eq-press', equipmentCode: 'PRESS', crew: ['kim'],
        stretches: [['2026-12-21T08:00:00+09:00', '2026-12-21T12:00:00+09:00'], ['2026-12-21T13:00:00+09:00', '2026-12-21T17:00:00+09:00']] })
    }
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/workflows/wf-try')
  await expect(page.locator('.react-flow__node')).toHaveCount(3, { timeout: 15_000 })

  await page.getByRole('button', { name: 'Try it' }).click()
  const tryIt = page.getByRole('region', { name: 'Try the flow' })
  // Only open orders on this workflow are offered.
  await expect(tryIt.getByLabel('Work order').locator('option')).toHaveText(['None (amounts only)', 'WO-1 Morning bake'])
  await tryIt.getByLabel('Work order').selectOption('wo-1')
  await tryIt.getByLabel('Starts').fill('2026-12-21T07:00')
  await tryIt.getByRole('button', { name: 'Run through' }).click()

  await expect(tryIt.getByText('Reaches the end in 3 steps')).toBeVisible()
  expect(workingTime).toEqual(['2026-12-21T07:00:00+09:00'])
  // The working-time delay ends half an hour into the morning shift; the next one ends at noon, when the press and kim
  // break, so Pack waits for the afternoon.
  await expect(tryIt.getByRole('listitem')).toHaveText([
    'Receive: 10 (completed)',
    'Bake: 10 (completed) from 2026-12-21 08:30',
    'Pack: 10 (completed) from 2026-12-21 13:00 (waits for PRESS, kim)'])
  await expect(tryIt.getByText('Ends 2026-12-21 13:00, 6 h after the start')).toBeVisible()
})
