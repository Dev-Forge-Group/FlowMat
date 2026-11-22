import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * A connection saved without ports is drawn even when its ends have port rows: it still counts in the workflow check
 * and in runs, so it must be on the canvas to be seen, selected and deleted.
 */
const workflow = { workflowId: 'wf-portless', projectId: 'prj-e2e', workflowName: 'Port-less line', workflowDesc: null,
  workflowType: 'standard', workflowStatus: 'draft' }
const node = (processId: string, processName: string, posX: number) => ({ processId, projectId: 'prj-e2e',
  workflowId: 'wf-portless', processName, processType: 'process', nodeType: 'process', processStatus: 'ready',
  colorScheme: 'slate', posX, posY: 160, width: 170, height: 96, processDesc: null, version: 1, versionNonce: 1 })
const port = (processIoId: string, processId: string, direction: 'input' | 'output') => ({ processIoId, processId,
  itemId: null, ioName: direction, direction, ioType: 'data', quantity: null, unit: null, formula: null, colorScheme: null,
  requiredYn: 'N', allowShortageYn: 'N', role: null, resourceType: 'data', schemaJson: null, validationRule: null })

test('a connection without ports is drawn between nodes that have ports', async ({ page }) => {
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/workflows/wf-portless/canvas') {
      return ok(route, { workflow, graphSeq: 0, processes: [node('p-a', 'Make', 80), node('p-b', 'Pack', 480)],
        processIos: [port('io-a', 'p-a', 'output'), port('io-b', 'p-b', 'input')],
        connections: [{ connectionId: 'c-plain', projectId: 'prj-e2e', workflowId: 'wf-portless', fromProcessId: 'p-a',
          toProcessId: 'p-b', fromIoId: null, toIoId: null, sourceHandle: 'out-default', targetHandle: 'in-default',
          connectionType: 'flow', connectionLabel: null, flowRate: null, unit: null, delayTimeSec: null, lossRate: null,
          priority: null, conditionExpr: null, capacity: null, failurePolicy: 'stop', version: 1, versionNonce: 1 }],
        annotations: [], currentUserRole: 'owner' })
    }
    if (pathname === '/api/workflows/wf-portless/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    if (pathname === '/api/workflows') return ok(route, [workflow])
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/workflows/wf-portless')
  await expect(page.locator('.react-flow__node')).toHaveCount(2, { timeout: 15_000 })
  await expect(page.locator('.react-flow__edge[data-id="c-plain"]')).toHaveCount(1)
  // The port rows keep their own handles; the default ones are there but not for drawing new lines.
  await expect(page.locator('.react-flow__handle[data-handleid="io-b"]')).toBeVisible()
  await expect(page.locator('.react-flow__node[data-id="p-b"] .react-flow__handle[data-handleid="in-default"]'))
    .toHaveCSS('opacity', '0')
})
