import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * The first run in order (docs/status/ROADMAP-2026-11-23.md §8, next month 1) against a mocked API: the guide counts what
 * the project already has and links to where the rest is done, in the inspector while nothing is selected.
 */
test('the Start here guide counts what is done and leads to the rest', async ({ page }) => {
  const workflow = { workflowId: 'wf-start', projectId: 'prj-e2e', workflowName: 'New flow', workflowDesc: null,
    workflowType: 'standard', workflowStatus: 'draft' }
  const process = { processId: 'mix', projectId: 'prj-e2e', workflowId: 'wf-start', processName: 'Mix', processType: 'process',
    nodeType: 'process', processStatus: 'ready', colorScheme: 'slate', posX: 200, posY: 160, width: 180, height: 80, processDesc: null,
    version: 1, versionNonce: 1 }
  const port = { processIoId: 'mix-out', processId: 'mix', itemId: 'itm-mix', ioName: 'Mix', direction: 'output', ioType: 'material',
    quantity: 10, unit: 'kg', formula: null, colorScheme: 'emerald', requiredYn: 'N', allowShortageYn: 'N', resourceType: 'material' }

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/workflows/wf-start/canvas') {
      return ok(route, { workflow, graphSeq: 0, processes: [process], processIos: [port], connections: [], annotations: [],
        currentUserRole: 'owner' })
    }
    if (pathname === '/api/workflows/wf-start/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    if (pathname === '/api/items') return ok(route, [{ itemId: 'itm-mix', projectId: 'prj-e2e', itemCode: 'MIX', itemName: 'Mix' }])
    if (pathname === '/api/workflows') return ok(route, [workflow])
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/workflows/wf-start')
  const guide = page.getByRole('group', { name: 'Start here' })
  // Items and a measured port are there; stock and a published revision are not.
  await expect(guide.locator('summary')).toHaveText('Start here · 2 of 5 done', { timeout: 15_000 })
  await guide.locator('summary').click()
  await expect(guide.getByRole('link', { name: 'Stock' })).toHaveAttribute('href', '/projects/prj-e2e/inventory?tab=stock')
  // It lives in the inspector while nothing is selected; selecting a process replaces it.
  await page.locator('.react-flow__node').filter({ hasText: 'Mix' }).click()
  await expect(guide).toHaveCount(0)
})
