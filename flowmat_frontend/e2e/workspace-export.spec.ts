import { readFile } from 'node:fs/promises'
import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * Exporting the workflow as a picture, against a mocked API (archify exports without view state): the SVG is a vector
 * drawing of the workflow only, each line in its kind's colour and dash with its label, and nothing of the editor, even
 * with a process and a line selected. Mix → Bake is a labelled material line, Bake → Pack a dashed control line.
 */
const workflow = { workflowId: 'wf-exp', projectId: 'prj-e2e', workflowName: 'Export line', workflowDesc: null,
  workflowType: 'standard', workflowStatus: 'draft' }
const node = (processId: string, processName: string, posX: number, nodeType = 'process') => ({ processId,
  projectId: 'prj-e2e', workflowId: 'wf-exp', processName, processType: 'process', nodeType, processStatus: 'ready',
  colorScheme: 'slate', posX, posY: 120, width: 150, height: 72, processDesc: null, version: 1, versionNonce: 1 })
const connection = (connectionId: string, fromProcessId: string, toProcessId: string, connectionType: string,
  connectionLabel: string | null) => ({ connectionId, projectId: 'prj-e2e', workflowId: 'wf-exp', fromProcessId, toProcessId,
  fromIoId: null, toIoId: null, sourceHandle: 'out-default', targetHandle: 'in-default', connectionType, connectionLabel,
  flowRate: null, unit: null, delayTimeSec: 0, lossRate: 0, priority: 1, conditionExpr: null, capacity: null,
  failurePolicy: 'stop', defaultRoute: false, splitKind: null, splitValue: null, version: 1, versionNonce: 1 })

test('the workflow exports as a clean vector picture, and the PNG leaves the selection out', async ({ page }) => {
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/workflows/wf-exp/canvas') {
      return ok(route, { workflow, graphSeq: 0, processes: [node('proc-mix', 'Mix', 80), node('proc-bake', 'Bake & Co', 380, 'equipment'),
        node('proc-pack', 'Pack', 680, 'output')], processIos: [],
      connections: [connection('conn-mb', 'proc-mix', 'proc-bake', 'material_flow', 'Feed'),
        connection('conn-bp', 'proc-bake', 'proc-pack', 'control_flow', null)], annotations: [], currentUserRole: 'owner' })
    }
    if (pathname === '/api/workflows/wf-exp/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    if (pathname === '/api/workflows') return ok(route, [workflow])
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/workflows/wf-exp')
  await expect(page.locator('.react-flow__edge')).toHaveCount(2, { timeout: 15_000 })

  // Select a process, then a line, so the export has view state to leave out.
  await page.locator('.react-flow__node[data-id="proc-mix"]').click({ position: { x: 24, y: 18 } })
  // The line may sit outside the small test window, so the click is sent to it directly.
  await page.locator('.react-flow__edge[data-id="conn-bp"] .react-flow__edge-interaction').dispatchEvent('click')
  await expect(page.locator('.react-flow__edge[data-id="conn-bp"]')).toHaveClass(/selected/)

  const svgDownload = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Export SVG' }).click()
  const svgFile = await svgDownload
  expect(svgFile.suggestedFilename()).toBe('Export line.svg')
  const svg = await readFile(await svgFile.path(), 'utf8')
  expect(svg.startsWith('<svg xmlns="http://www.w3.org/2000/svg"')).toBe(true)
  expect(svg).toContain('<title>Export line</title>')
  expect(svg).toContain('>Mix</text>')
  expect(svg).toContain('>Bake &amp; Co</text>')
  expect(svg).toContain('>EQUIPMENT</text>')
  expect(svg).toContain('>Feed</text>')
  // The control line keeps its own red dash although it is selected; the material line its grey.
  expect(svg).toMatch(/stroke="#f87171" stroke-width="1.5" stroke-dasharray="8 4"/)
  expect(svg).toMatch(/stroke="#94a3b8" stroke-width="1.5" marker-end/)
  expect(svg).not.toMatch(/6366f1|selected|reading-|edge-insert|Del</)
  expect(svg.match(/<path d="[^"]*" fill="none"/g)?.length).toBe(2)

  const pngDownload = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Export PNG' }).click()
  const pngFile = await pngDownload
  expect(pngFile.suggestedFilename()).toBe('Export line.png')
  // The export state is gone again and the selection is still there, as drawn, for the person working.
  await expect(page.locator('.workspace-canvas-viewport.reading-exporting')).toHaveCount(0)
  await expect(page.locator('.react-flow__edge[data-id="conn-bp"]')).toHaveClass(/selected/)
  await expect(page.locator('.react-flow__edge[data-id="conn-bp"] path.react-flow__edge-path'))
    .toHaveAttribute('style', /stroke: rgb\(99, 102, 241\); stroke-width: 2\.5/)
})
