import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * A failed background refresh of the workflow canvas against a mocked API: the open editor stays with what it last loaded,
 * including what it just reported (here a publish), and offers a retry. Before this the route replaced the whole editor
 * with its "Back to home" error page, which the real working-delay acceptance hit in CI.
 */
test('a failed canvas refresh keeps the open editor and offers a retry', async ({ page, context }) => {
  const workflow = { workflowId: 'wf-refresh', projectId: 'prj-e2e', workflowName: 'Bakery', workflowDesc: null,
    workflowType: 'standard', workflowStatus: 'draft' }
  const revision = (revisionNo: number) => ({ workflowRevisionId: `rev-${revisionNo}`, workflowId: 'wf-refresh', revisionNo,
    status: 'published', schemaVersion: 1, publishedBy: 'demo-owner', publishedAt: '2026-10-11T00:00:00Z', retiredBy: null,
    retiredAt: null })
  const revisions = [revision(1)]
  let canvasFails = false
  let canvasReads = 0

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/workflows/wf-refresh/revisions') {
      revisions.unshift(revision(2))
      return ok(route, { ...revision(2), snapshot: {} })
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/workflows/wf-refresh/canvas') {
      canvasReads += 1
      if (canvasFails) {
        return route.fulfill({ status: 503, contentType: 'application/json',
          body: JSON.stringify({ success: false, data: null, message: 'Service briefly unavailable' }) })
      }
      return ok(route, { workflow, graphSeq: 0, processes: [], processIos: [], connections: [], annotations: [],
        currentUserRole: 'owner' })
    }
    if (pathname === '/api/workflows/wf-refresh/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    if (pathname === '/api/workflows/wf-refresh/revisions') return ok(route, revisions)
    if (pathname === '/api/workflows') return ok(route, [workflow])
    return ok(route, [])
  })

  // The page clock runs normally; it is only moved on so the loaded canvas counts as stale.
  await page.clock.install()
  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/workflows/wf-refresh')
  const check = page.getByRole('region', { name: 'Workflow validation' })
  await expect(check).toContainText('Latest: v1 published by demo-owner', { timeout: 15_000 })
  await check.getByRole('button', { name: 'Publish revision' }).click()
  await expect(check.getByRole('status').filter({ hasText: 'Published v2.' })).toBeVisible()

  // Every canvas read now fails; once the canvas is stale, coming back online makes the open editor refresh it.
  canvasFails = true
  const readsBefore = canvasReads
  await page.clock.fastForward('00:31')
  await context.setOffline(true)
  await context.setOffline(false)
  const alert = page.getByRole('alert').filter({ hasText: 'Could not refresh the canvas' })
  await expect(alert).toBeVisible({ timeout: 10_000 })
  expect(canvasReads).toBeGreaterThan(readsBefore)
  // The editor and what it reported stay.
  await expect(page.getByText('Back to home')).toHaveCount(0)
  await expect(check.getByRole('status').filter({ hasText: 'Published v2.' })).toBeVisible()
  await expect(check.getByRole('button', { name: 'Publish revision' })).toBeEnabled()

  canvasFails = false
  await alert.getByRole('button', { name: 'Retry' }).click()
  await expect(alert).toHaveCount(0)
  await expect(check.getByRole('status').filter({ hasText: 'Published v2.' })).toBeVisible()
})
