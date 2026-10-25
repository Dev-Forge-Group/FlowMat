import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/** The owner chooses to refuse stock in a place kept otherwise than the item (docs/domain/storage-location.md SL5-SL7), mocked. */
test('the owner chooses to refuse stock in a place with another storage condition', async ({ page }) => {
  const consoleErrors: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text())
  })
  const projectId = 'prj-condition-policy'
  let setting: Record<string, unknown> = { projectId, storageConditionPolicy: 'warn', version: null, updatedBy: null, updatedAt: null }
  const sent: unknown[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'PUT' && pathname === '/api/inventory-settings') {
      const body = request.postDataJSON() as Record<string, unknown>
      sent.push(body)
      setting = { ...setting, storageConditionPolicy: body.storageConditionPolicy, version: 0, updatedBy: 'demo-owner', updatedAt: '2041-09-01T10:00:00Z' }
      return ok(route, setting)
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/inventory-settings') return ok(route, setting)
    if (pathname === '/api/storage-locations') {
      return ok(route, [{ locationId: 'l1', projectId, locationCode: 'COLD', locationName: null, locationType: 'warehouse', parentLocationId: null,
        active: true, note: null, storageCondition: 'Chilled', path: 'COLD', depth: 0, stockRecords: 0, itemCount: 0, version: 0 }])
    }
    if (pathname === '/api/project-members') {
      return ok(route, [{ projectMemberId: 'm1', projectId, userId: 'demo-owner', projectRole: 'owner', memberStatus: 'active', joinedAt: null }])
    }
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Conditions', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Conditions', ownerId: 'demo-owner' })
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=locations`)
  const choice = page.getByRole('group', { name: 'Storage condition policy' }).getByLabel('Different storage condition')
  await expect(choice).toHaveValue('warn', { timeout: 15_000 })
  await choice.selectOption('refuse')
  await expect(choice).toHaveValue('refuse')
  expect(sent).toEqual([{ projectId, storageConditionPolicy: 'refuse', expectedVersion: null }])
  expect(consoleErrors).toEqual([])
})
