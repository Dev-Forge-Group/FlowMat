import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * Publishing from the workflow editor (docs/domain/workflow-revision.md V8) against a mocked API: the editor names the
 * latest revision, a refused publish shows why and opens the workflow check, and a publish names the new revision with a
 * way to start a run.
 */
test('the editor publishes a revision, or shows the check that stopped it', async ({ page }) => {
  const workflow = { workflowId: 'wf-pub', projectId: 'prj-e2e', workflowName: 'Bakery', workflowDesc: null, workflowType: 'standard',
    workflowStatus: 'draft' }
  const revision = (revisionNo: number) => ({ workflowRevisionId: `rev-${revisionNo}`, workflowId: 'wf-pub', revisionNo,
    status: 'published', schemaVersion: 1, publishedBy: 'demo-owner', publishedAt: '2026-10-11T00:00:00Z', retiredBy: null, retiredAt: null })
  const revisions = [revision(1)]
  let publishes = 0

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/workflows/wf-pub/revisions') {
      publishes += 1
      if (publishes === 1) {
        // The server's check (V2) refuses a workflow with errors.
        return route.fulfill({ status: 409, contentType: 'application/json',
          body: JSON.stringify({ success: false, data: null, message: 'Workflow has 1 error(s): Mixer has no output.' }) })
      }
      revisions.unshift(revision(2))
      return ok(route, { ...revision(2), snapshot: {} })
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/workflows/wf-pub/canvas') {
      return ok(route, { workflow, graphSeq: 0, processes: [], processIos: [], connections: [], annotations: [], currentUserRole: 'owner' })
    }
    if (pathname === '/api/workflows/wf-pub/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    if (pathname === '/api/workflows/wf-pub/revisions') return ok(route, revisions)
    if (pathname === '/api/workflows/wf-pub/validation') {
      return ok(route, { errors: 1, warnings: 0, issues: [{ severity: 'error', code: 'NO_OUTPUT', processId: null, ioId: null,
        connectionId: null, message: 'Mixer has no output.' }] })
    }
    if (pathname === '/api/workflows') return ok(route, [workflow])
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/workflows/wf-pub')
  const check = page.getByRole('region', { name: 'Workflow validation' })
  await expect(check).toContainText('Latest: v1 published by demo-owner', { timeout: 15_000 })
  // The row's buttons look like buttons, not plain words (the base layer strips button chrome).
  for (const name of ['Check workflow', 'Publish revision', 'Try it', 'Plan']) {
    expect(await check.getByRole('button', { name, exact: true }).evaluate((button) => getComputedStyle(button).borderTopWidth))
      .toBe('1px')
  }

  await check.getByRole('button', { name: 'Publish revision' }).click()
  await expect(check.getByRole('alert')).toHaveText('Workflow has 1 error(s): Mixer has no output.')
  // The refusal opens the check, whose issues lead to the canvas.
  await expect(check).toContainText('1 errors, 0 warnings')
  await expect(check.getByRole('button', { name: 'error: NO_OUTPUT — Mixer has no output.' })).toBeVisible()

  await check.getByRole('button', { name: 'Publish revision' }).click()
  await expect(check.getByText('Published v2.')).toBeVisible()
  await expect(check.getByRole('link', { name: 'Start a run' })).toHaveAttribute('href', '/projects/prj-e2e/runs?workflowId=wf-pub')
  await expect(check).toContainText('Latest: v2 published by demo-owner')
  await expect(check.getByRole('alert')).toHaveCount(0)
})
