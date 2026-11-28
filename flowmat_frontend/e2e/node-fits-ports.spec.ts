import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * A step's box grows to hold its ports. Stored at the default 180 × 80, a step with three inputs and an output used to
 * draw two rows and its output handle under the box, where a layout put the next step.
 */
test('a step with four ports holds them inside its box', async ({ page }) => {
  const workflow = { workflowId: 'wf-fit', projectId: 'prj-e2e', workflowName: 'Fit', workflowDesc: null, workflowType: 'standard',
    workflowStatus: 'draft' }
  const mix = { processId: 'mix', projectId: 'prj-e2e', workflowId: 'wf-fit', processName: 'Mix', processType: 'process',
    nodeType: 'process', processStatus: 'ready', colorScheme: 'slate', posX: 120, posY: 160, width: 180, height: 80,
    processDesc: null, version: 1, versionNonce: 1 }
  const port = (processIoId: string, direction: string) => ({ processIoId, processId: 'mix', itemId: null, ioName: processIoId, direction,
    ioType: 'material', quantity: 1, unit: 'kg', formula: null, colorScheme: 'sky', requiredYn: 'N', allowShortageYn: 'N',
    resourceType: 'material' })
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const { pathname } = new URL(route.request().url())
    if (await answerAuth(route, pathname)) return
    if (pathname === '/api/workflows/wf-fit/canvas') {
      return ok(route, { workflow, graphSeq: 0, processes: [mix],
        processIos: [port('milk', 'input'), port('syrup', 'input'), port('sugar', 'input'), port('mixed', 'output')],
        connections: [], annotations: [], currentUserRole: 'owner' })
    }
    if (pathname === '/api/workflows/wf-fit/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/workflows/wf-fit')
  const node = page.locator('.react-flow__node[data-id="mix"]')
  await expect(node).toBeVisible({ timeout: 15_000 })
  const fit = await node.evaluate((wrapper) => {
    const box = wrapper.querySelector('.canvas-node') as HTMLElement
    const outer = wrapper.getBoundingClientRect()
    const handle = wrapper.querySelector('.react-flow__handle.source[data-handleid="mixed"]')!.getBoundingClientRect()
    return { overflow: box.scrollHeight - box.clientHeight, handleInside: handle.top >= outer.top && handle.bottom <= outer.bottom + 1 }
  })
  expect(fit).toEqual({ overflow: 0, handleInside: true })
})
