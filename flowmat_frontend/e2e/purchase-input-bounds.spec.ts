import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

async function approvalFixture(page: Page) {
  const projectId = 'prj-purchase-bounds'
  let settings: Record<string, unknown> = {
    projectId, approvalLimit: 100, version: 7, approvalSteps: [], updatedBy: 'demo-owner', updatedAt: null,
  }
  const sent: Record<string, unknown>[] = []
  const rawBodies: string[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'PUT' && pathname === '/api/purchase-settings') {
      const body = request.postDataJSON() as Record<string, unknown>
      sent.push(body)
      rawBodies.push(request.postData() ?? '')
      settings = { ...settings, ...body, version: 8 }
      return ok(route, settings)
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/purchase-settings') return ok(route, settings)
    if (pathname === '/api/project-members') return ok(route, [
      { projectMemberId: 'owner', projectId, userId: 'demo-owner', projectRole: 'owner', memberStatus: 'active' },
      { projectMemberId: 'editor', projectId, userId: 'editor', projectRole: 'editor', memberStatus: 'active' },
    ])
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Bounds', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Bounds', ownerId: 'demo-owner' })
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=purchasing`)
  const approval = page.getByRole('group', { name: 'Purchase approval' })
  await expect(approval).toContainText('Purchase orders worth 100 or more', { timeout: 15_000 })
  return { approval, sent, rawBodies, projectId }
}

test('an eleven-digit approval limit is refused without changing approval settings', async ({ page }) => {
  const f = await approvalFixture(page)
  await f.approval.getByRole('button', { name: 'Change approval limit' }).click()
  const input = f.approval.getByLabel('Limit', { exact: true })
  await input.fill('10000000000')
  // This is valid for type=number; the purchasing contract must refuse it itself.
  expect(await input.evaluate((element) => (element as HTMLInputElement).checkValidity())).toBe(true)
  await f.approval.getByRole('button', { name: 'Save limit' }).click()
  await expect(f.approval.getByRole('alert')).toHaveText('Enter 0 or more with at most four decimals, or leave it empty for no approval. Use at most 10 digits before the decimal.')
  await expect(input).toHaveValue('10000000000')
  expect(f.sent).toHaveLength(0)
})

test('an explicitly empty approval limit still clears it as null', async ({ page }) => {
  const f = await approvalFixture(page)
  await f.approval.getByRole('button', { name: 'Change approval limit' }).click()
  await f.approval.getByLabel('Limit', { exact: true }).fill('')
  const saved = page.waitForResponse((response) => response.request().method() === 'PUT'
    && new URL(response.url()).pathname === '/api/purchase-settings')
  await f.approval.getByRole('button', { name: 'Save limit' }).click()
  await saved
  await expect(f.approval).toContainText('Purchase orders do not need approval.')
  expect(f.sent).toEqual([{ projectId: f.projectId, approvalLimit: null, expectedVersion: 7 }])
})

test('the largest allowed approval limit retains its decimal value after leading zeros', async ({ page }) => {
  const f = await approvalFixture(page)
  await f.approval.getByRole('button', { name: 'Change approval limit' }).click()
  await f.approval.getByLabel('Limit', { exact: true }).fill('0000009999999999.9999')
  const saved = page.waitForResponse((response) => response.request().method() === 'PUT'
    && new URL(response.url()).pathname === '/api/purchase-settings')
  await f.approval.getByRole('button', { name: 'Save limit' }).click()
  await saved
  await expect(f.approval.getByRole('button', { name: 'Change approval limit' })).toBeVisible()
  expect(f.sent).toEqual([{ projectId: f.projectId, approvalLimit: 9999999999.9999, expectedVersion: 7 }])
  expect(f.rawBodies[0]).toContain('"approvalLimit":9999999999.9999')
})

test('an eleven-digit member approval step is refused before any settings request', async ({ page }) => {
  const f = await approvalFixture(page)
  await f.approval.getByRole('button', { name: 'Change approval steps' }).click()
  await f.approval.getByRole('button', { name: 'Add step' }).click()
  await f.approval.getByLabel('Step 1 from').fill('10000000000')
  await f.approval.getByLabel('Step 1 approver').selectOption('editor')
  await f.approval.getByRole('button', { name: 'Save steps' }).click()
  await expect(f.approval.getByRole('alert')).toHaveText('Step 1: enter the value it starts from, 0 or more with at most four decimals. Use at most 10 digits before the decimal.')
  expect(f.sent).toHaveLength(0)
})
