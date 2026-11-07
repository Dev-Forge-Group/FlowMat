import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * Layout warnings from the workflow check, against a mocked API (archify's geometry gates as warnings): measured from the
 * canvas as drawn, a line that passes over a process it does not join and two lines that cross. Mix → Pack runs straight
 * through Bake; the control lines Wash → Cool and Fill → Seal cross in an X.
 */
const workflow = { workflowId: 'wf-geo', projectId: 'prj-e2e', workflowName: 'Crowded line', workflowDesc: null,
  workflowType: 'standard', workflowStatus: 'draft' }
const node = (processId: string, processName: string, posX: number, posY: number) => ({ processId,
  projectId: 'prj-e2e', workflowId: 'wf-geo', processName, processType: 'process', nodeType: 'process', processStatus: 'ready',
  colorScheme: 'slate', posX, posY, width: 150, height: 72, processDesc: null, version: 1, versionNonce: 1 })
const connection = (connectionId: string, fromProcessId: string, toProcessId: string, connectionType = 'material_flow') => ({
  connectionId, projectId: 'prj-e2e', workflowId: 'wf-geo', fromProcessId, toProcessId, fromIoId: null, toIoId: null,
  sourceHandle: 'out-default', targetHandle: 'in-default', connectionType, connectionLabel: null, flowRate: null, unit: null,
  delayTimeSec: 0, lossRate: 0, priority: 1, conditionExpr: null, capacity: null, failurePolicy: 'stop', defaultRoute: false,
  splitKind: null, splitValue: null, version: 1, versionNonce: 1 })

test('checking the workflow also measures the drawn canvas for lines over processes and crossing lines', async ({ page }) => {
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/workflows/wf-geo/canvas') {
      return ok(route, { workflow, graphSeq: 0, processes: [node('proc-mix', 'Mix', 80, 120), node('proc-bake', 'Bake', 380, 120),
        node('proc-pack', 'Pack', 680, 120), node('proc-wash', 'Wash', 80, 400), node('proc-cool', 'Cool', 680, 600),
        node('proc-fill', 'Fill', 80, 600), node('proc-seal', 'Seal', 680, 400)], processIos: [],
      connections: [connection('conn-mp', 'proc-mix', 'proc-pack'), connection('conn-wc', 'proc-wash', 'proc-cool', 'control_flow'),
        connection('conn-fs', 'proc-fill', 'proc-seal', 'control_flow')], annotations: [], currentUserRole: 'owner' })
    }
    if (pathname === '/api/workflows/wf-geo/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    if (pathname === '/api/workflows/wf-geo/validation') return ok(route, { errors: 0, warnings: 0, issues: [] })
    if (pathname === '/api/workflows') return ok(route, [workflow])
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/workflows/wf-geo')
  await expect(page.locator('.react-flow__edge')).toHaveCount(3, { timeout: 15_000 })

  const check = page.getByRole('region', { name: 'Workflow validation' })
  await check.getByRole('button', { name: 'Check workflow', exact: true }).click()
  await expect(check.getByText(/^0 errors, 0 warnings$/)).toBeVisible()
  const layout = check.getByRole('region', { name: 'Layout' })
  await expect(layout).toContainText('Layout: 2 warnings, measured from the canvas as drawn.')
  await expect(layout.getByRole('button')).toHaveText([
    'warning: LINE_OVER_PROCESS — The connection from "Mix" to "Pack" passes over "Bake".',
    'warning: LINES_CROSS — The connections Wash → Cool and Fill → Seal cross.',
  ])

  // A warning selects its connection.
  await layout.getByRole('button', { name: /LINE_OVER_PROCESS/ }).click()
  await expect(page.locator('.react-flow__edge[data-id="conn-mp"]')).toHaveClass(/selected/)
})
