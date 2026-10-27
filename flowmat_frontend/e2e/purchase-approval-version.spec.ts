import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

test.use({ timezoneId: 'UTC', locale: 'en-US' })

const conflict = 'The purchasing settings changed since they were opened; reload them and try again.'
const projectId = 'prj-approval-version'

interface ApprovalStep {
  fromValue: number
  approverId: string
}

interface SettingsInput {
  projectId: string
  approvalLimit: number | null
  expectedVersion: number | null
  approvalSteps?: ApprovalStep[]
}

interface Settings {
  projectId: string
  approvalLimit: number | null
  version: number
  updatedBy: string
  updatedAt: string
  approvalSteps: (ApprovalStep & { canApprove: boolean })[]
}

async function openPurchasing(page: Page) {
  const project = { projectId, projectName: 'Approval versions', ownerId: 'demo-owner' }
  let settings: Settings = { projectId, approvalLimit: 1000, version: 0, updatedBy: 'demo-owner', updatedAt: '2041-09-01T08:00:00Z',
    approvalSteps: [{ fromValue: 100, approverId: 'kim', canApprove: true }] }
  const sent: SettingsInput[] = []
  const unexpectedApi: string[] = []
  const pageErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))
  const emptyLists = new Set(['/api/purchase-orders', '/api/items', '/api/units', '/api/inventories', '/api/suppliers',
    '/api/supplier-items', '/api/inspection-standards', '/api/nonconformities', '/api/storage-locations', '/api/organizations',
    '/api/notifications', '/api/workflows'])
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'PUT' && pathname === '/api/purchase-settings') {
      const body = request.postDataJSON() as SettingsInput
      sent.push(body)
      if (body.expectedVersion !== settings.version) {
        return route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ success: false,
          data: null, message: conflict }) })
      }
      settings = { ...settings, approvalLimit: body.approvalLimit, version: settings.version + 1,
        approvalSteps: body.approvalSteps?.map((step) => ({ ...step, canApprove: true })) ?? settings.approvalSteps }
      return ok(route, settings)
    }
    if (request.method() === 'GET') {
      if (pathname === '/api/purchase-settings') return ok(route, settings)
      if (pathname === '/api/projects') return ok(route, [project])
      if (pathname === `/api/projects/${projectId}`) return ok(route, project)
      if (pathname === `/api/projects/${projectId}/time-zone`) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
      if (pathname === '/api/project-members') return ok(route, [
        { projectMemberId: 'owner-member', projectId, userId: 'demo-owner', projectRole: 'owner', memberStatus: 'active' },
        { projectMemberId: 'editor-member', projectId, userId: 'kim', projectRole: 'editor', memberStatus: 'active' },
      ])
      if (pathname === '/api/notifications/unread-count') return ok(route, { unread: 0 })
      if (emptyLists.has(pathname)) return ok(route, [])
    }
    unexpectedApi.push(`${request.method()} ${pathname}`)
    return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ success: false,
      data: null, message: 'Unexpected mocked API request' }) })
  })
  await page.clock.install()
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=purchasing`)
  const approval = page.getByRole('group', { name: 'Purchase approval' })
  await expect(approval).toContainText('from 100 by kim', { timeout: 15_000 })
  return {
    approval,
    sent,
    version: () => settings.version,
    refreshFromOtherTab: async (patch: { approvalLimit?: number; approvalSteps?: ApprovalStep[] }) => {
      // The remote save changes the next GET; the existing form remains open through a real query refetch.
      settings = { ...settings, ...patch, version: settings.version + 1,
        approvalSteps: patch.approvalSteps?.map((step) => ({ ...step, canApprove: true })) ?? settings.approvalSteps }
      const refreshed = page.waitForResponse((response) => response.request().method() === 'GET'
        && new URL(response.url()).pathname === '/api/purchase-settings')
      await page.clock.fastForward(31_000)
      await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')))
      await refreshed
    },
    assertClean: () => {
      expect(unexpectedApi).toEqual([])
      expect(pageErrors).toEqual([])
    },
  }
}

for (const mode of ['limit', 'steps'] as const) {
  test(`the ${mode} editor keeps its opening version after a refetch and a reopened form uses the new version`, async ({ page }) => {
    const fixture = await openPurchasing(page)
    const { approval, sent } = fixture
    const opening = mode === 'limit' ? 'Change approval limit' : 'Change approval steps'
    const form = approval.getByRole('form', { name: mode === 'limit' ? 'Approval limit' : 'Approval steps' })
    const input = form.getByLabel(mode === 'limit' ? 'Limit' : 'Step 1 from', { exact: true })
    const save = form.getByRole('button', { name: mode === 'limit' ? 'Save limit' : 'Save steps', exact: true })
    const localValue = mode === 'limit' ? '1200' : '150'
    const externalValue = mode === 'limit' ? '900' : '200'
    const newValue = mode === 'limit' ? '950' : '250'
    await approval.getByRole('button', { name: opening, exact: true }).click()
    await input.fill(localValue)

    await fixture.refreshFromOtherTab(mode === 'limit' ? { approvalLimit: 900 }
      : { approvalSteps: [{ fromValue: 200, approverId: 'kim' }] })
    await expect(approval).toContainText(mode === 'limit' ? 'from 900 by the project owner' : 'from 200 by kim')
    await expect(input).toHaveValue(localValue)
    await save.click()
    await expect.poll(() => sent.length).toBe(1)
    expect(sent[0]).toEqual(mode === 'limit'
      ? { projectId, approvalLimit: 1200, expectedVersion: 0 }
      : { projectId, approvalLimit: 1000, expectedVersion: 0, approvalSteps: [{ fromValue: 150, approverId: 'kim' }] })
    await expect(approval.getByRole('alert')).toHaveText(conflict)
    await expect(input).toHaveValue(localValue)
    expect(fixture.version()).toBe(1)

    await form.getByRole('button', { name: mode === 'limit' ? 'Cancel' : 'Cancel steps', exact: true }).click()
    await approval.getByRole('button', { name: opening, exact: true }).click()
    await expect(input).toHaveValue(externalValue)
    await input.fill(newValue)
    await save.click()
    await expect(form).toHaveCount(0)
    expect(sent[1]).toEqual(mode === 'limit'
      ? { projectId, approvalLimit: 950, expectedVersion: 1 }
      : { projectId, approvalLimit: 1000, expectedVersion: 1, approvalSteps: [{ fromValue: 250, approverId: 'kim' }] })
    await expect(approval).toContainText(mode === 'limit' ? 'from 950 by the project owner' : 'from 250 by kim')
    expect(fixture.version()).toBe(2)
    fixture.assertClean()
  })
}

test('opening the limit editor cannot replace the version of an already open steps editor', async ({ page }) => {
  const fixture = await openPurchasing(page)
  const { approval, sent } = fixture
  await approval.getByRole('button', { name: 'Change approval steps' }).click()
  const steps = approval.getByRole('form', { name: 'Approval steps' })
  await steps.getByLabel('Step 1 from').fill('150')
  await fixture.refreshFromOtherTab({ approvalLimit: 900, approvalSteps: [{ fromValue: 200, approverId: 'kim' }] })
  await expect(approval).toContainText('from 200 by kim')
  await approval.getByRole('button', { name: 'Change approval limit' }).click()
  const limit = approval.getByRole('form', { name: 'Approval limit' })
  await expect(limit.getByLabel('Limit', { exact: true })).toHaveValue('900')
  await limit.getByLabel('Limit', { exact: true }).fill('950')

  await steps.getByRole('button', { name: 'Save steps' }).click()
  await expect.poll(() => sent.length).toBe(1)
  expect(sent[0]).toEqual({ projectId, approvalLimit: 900, expectedVersion: 0,
    approvalSteps: [{ fromValue: 150, approverId: 'kim' }] })
  await expect(approval.getByRole('alert')).toHaveText(conflict)
  await expect(steps.getByLabel('Step 1 from')).toHaveValue('150')
  await limit.getByRole('button', { name: 'Save limit' }).click()
  await expect(limit).toHaveCount(0)
  expect(sent[1]).toEqual({ projectId, approvalLimit: 950, expectedVersion: 1 })
  await expect(steps.getByLabel('Step 1 from')).toHaveValue('150')
  await expect(approval).toContainText('from 950 by the project owner')
  expect(fixture.version()).toBe(2)
  fixture.assertClean()
})
