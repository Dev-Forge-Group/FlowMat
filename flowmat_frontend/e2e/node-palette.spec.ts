import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * The workspace's node palette against a mocked API: compact icon rows by default, a search over names and kinds, and
 * the detailed cards with each node's description as the other view, which the browser remembers.
 */
const workflow = { workflowId: 'wf-palette', projectId: 'prj-e2e', workflowName: 'Palette', workflowDesc: null,
  workflowType: 'standard', workflowStatus: 'draft' }

async function openWorkspace(page: Page) {
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/workflows/wf-palette/canvas') {
      return ok(route, { workflow, graphSeq: 0, processes: [], processIos: [], connections: [], annotations: [],
        currentUserRole: 'owner' })
    }
    if (pathname === '/api/workflows/wf-palette/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    if (pathname === '/api/workflows') return ok(route, [workflow])
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/workflows/wf-palette')
  await expect(page.locator('.react-flow__pane')).toBeVisible({ timeout: 15_000 })
}

test('the palette lists nodes as rows, finds them by name or kind and keeps the chosen view', async ({ page }) => {
  await openWorkspace(page)
  const palette = page.locator('.workspace-panel--left')
  const view = palette.getByRole('group', { name: 'Palette view' })
  await expect(view.getByRole('button', { name: 'compact' })).toHaveAttribute('aria-pressed', 'true')
  await expect(palette.getByRole('button', { name: 'Process', exact: true })).toHaveAttribute('title', /.+/)
  await expect(palette.getByText('Pick a workflow node, then click any empty canvas area.')).toHaveCount(0)

  // The search narrows the rows; Pointer stays only while it matches.
  await palette.getByRole('searchbox', { name: 'Search nodes' }).fill('equip')
  await expect(palette.getByRole('button', { name: /^(Pointer|Process|Equipment|Storage)$/ })).toHaveText(['Equipment'])
  await palette.getByRole('searchbox', { name: 'Search nodes' }).fill('nothing like this')
  await expect(palette.getByText('No node matches.')).toBeVisible()
  await palette.getByRole('searchbox', { name: 'Search nodes' }).fill('')

  // The detailed view is the earlier cards, with each node's description; a reload keeps it.
  await view.getByRole('button', { name: 'detailed' }).click()
  await expect(view.getByRole('button', { name: 'detailed' })).toHaveAttribute('aria-pressed', 'true')
  await expect(view.getByRole('button', { name: 'compact' })).toHaveAttribute('aria-pressed', 'false')
  await expect(palette.getByText('Pick a workflow node, then click any empty canvas area.')).toBeVisible()
  await expect(palette.getByRole('button', { name: /^Process\s+\S/ })).toBeVisible()
  await page.reload()
  await expect(palette.getByRole('group', { name: 'Palette view' }).getByRole('button', { name: 'detailed' }))
    .toHaveAttribute('aria-pressed', 'true', { timeout: 15_000 })

  // Either view picks the node to place next.
  await palette.getByRole('button', { name: /^Storage/ }).click()
  await expect(page.getByText(/Current tool: Storage\./)).toBeVisible()
  await palette.getByRole('button', { name: 'Pointer', exact: true }).click()
  await expect(page.getByText(/Current tool: Pointer\./)).toBeVisible()
})
