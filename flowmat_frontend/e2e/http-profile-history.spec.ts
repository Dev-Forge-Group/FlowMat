import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

const projectId = 'prj-http-history'
const operations = [
  { operationId: 'send', method: 'POST', relativePath: '/jobs', successStatuses: [202, 200] },
  { operationId: 'z-remove', method: 'GET', relativePath: '/old', successStatuses: [200] },
]
const oldVersion = { profileVersionId: 'version-1', profileId: 'profile-history', revision: 1,
  baseOrigin: 'https://old.example.com', credentialAlias: null, operations, createdAt: '2026-10-24T00:00:00Z' }
const newVersion = { ...oldVersion, profileVersionId: 'version-2', revision: 2, baseOrigin: 'https://api.example.com',
  credentialAlias: 'dispatch', operations: [
    { operationId: 'send', method: 'PUT', relativePath: '/jobs/new', successStatuses: [204, 201] },
    { operationId: 'a-add', method: 'POST', relativePath: '/create', successStatuses: [201] },
  ] }
const thirdVersion = { ...newVersion, profileVersionId: 'version-3', revision: 3, operations: [
  newVersion.operations[1], { ...newVersion.operations[0], successStatuses: [201, 204] },
] }
const profile = { ...newVersion, projectId, name: 'Dispatch', enabled: true, version: 2, currentRevision: 2, updatedAt: '' }

async function setupHistory(page: Page, role = 'owner') {
  let phase = 0
  let mutations = 0
  const readVersions = () => phase === 0 ? [newVersion, oldVersion]
    : phase === 1 ? [thirdVersion, newVersion, oldVersion] : [thirdVersion, newVersion]
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') {
      mutations += 1
      if (pathname.endsWith('/profile-history/status') && request.method() === 'PATCH') {
        phase += 1
        await ok(route, { ...profile, ...thirdVersion, enabled: phase !== 1, version: 2 + phase, currentRevision: 3 }); return
      }
      await route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: 'Unexpected write' }) }); return
    }
    if (pathname === '/api/project-members') {
      await ok(route, [{ projectMemberId: 'member', projectId, userId: 'demo-owner', projectRole: role, memberStatus: 'active' }]); return
    }
    if (pathname === `/api/projects/${projectId}/http-profiles`) {
      await ok(route, [phase === 0 ? profile : { ...profile, ...thirdVersion, enabled: phase !== 1, version: 2 + phase, currentRevision: 3 }]); return
    }
    if (pathname.endsWith('/profile-history/versions')) { await ok(route, readVersions()); return }
    await ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/settings`)
  const profiles = page.getByRole('region', { name: 'HTTP profiles' })
  await profiles.getByRole('button', { name: 'View revisions for Dispatch' }).click()
  return { profiles, comparison: profiles.getByRole('region', { name: 'Revision comparison' }), mutations: () => mutations }
}

test('profile history compares fields and operation changes in stable order at phone width', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 })
  const { profiles, comparison, mutations } = await setupHistory(page)
  await profiles.getByRole('button', { name: 'Hide revisions for Dispatch' }).click()
  const pageWidthBefore = await page.evaluate(() => document.documentElement.scrollWidth)
  await profiles.getByRole('button', { name: 'View revisions for Dispatch' }).click()
  await expect(comparison).toBeVisible()
  await expect(comparison.getByLabel('From revision')).toHaveValue('version-1')
  await expect(comparison.getByLabel('To revision')).toHaveValue('version-2')
  await expect(comparison).toContainText('Before: https://old.example.com')
  await expect(comparison).toContainText('After: https://api.example.com')
  await expect(comparison).toContainText('Before: none')
  await expect(comparison).toContainText('After: dispatch')
  const changes = comparison.getByRole('article')
  await expect(changes).toHaveCount(3)
  await expect(changes.nth(0)).toContainText('a-add: Added')
  await expect(changes.nth(1)).toContainText('send: Changed')
  await expect(changes.nth(1)).toContainText('Before: POST')
  await expect(changes.nth(1)).toContainText('After: PUT')
  await expect(changes.nth(1)).toContainText('Before: /jobs')
  await expect(changes.nth(1)).toContainText('After: /jobs/new')
  await expect(changes.nth(1)).toContainText('Before: 200, 202')
  await expect(changes.nth(1)).toContainText('After: 201, 204')
  await expect(changes.nth(2)).toContainText('z-remove: Removed')
  const size = await comparison.evaluate((element) => ({ width: element.getBoundingClientRect().width,
    left: element.getBoundingClientRect().left, right: element.getBoundingClientRect().right,
    scroll: element.scrollWidth, viewport: window.innerWidth }))
  expect(size.scroll).toBeLessThanOrEqual(Math.ceil(size.width))
  expect(size.left).toBeGreaterThanOrEqual(0)
  expect(size.right).toBeLessThanOrEqual(size.viewport)
  // The existing Members table also affects page width; opening history must not increase it.
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(pageWidthBefore)
  expect(mutations()).toBe(0)
})

test('a viewer can compare the same revision without profile management or any write', async ({ page }) => {
  const { profiles, comparison, mutations } = await setupHistory(page, 'viewer')
  await comparison.getByLabel('From revision').selectOption('version-2')
  await expect(comparison).toContainText('No configuration changes between these revisions.')
  await expect(comparison.getByRole('article')).toHaveCount(0)
  await expect(profiles.getByRole('button', { name: 'Edit Dispatch', exact: true })).toHaveCount(0)
  await expect(profiles.getByRole('button', { name: 'Disable Dispatch', exact: true })).toHaveCount(0)
  expect(mutations()).toBe(0)
})

test('history refresh preserves selected IDs and refuses to mix a missing revision with current data', async ({ page }) => {
  const { profiles, comparison } = await setupHistory(page)
  await expect(comparison.getByLabel('From revision')).toHaveValue('version-1')
  await profiles.getByRole('button', { name: 'Disable Dispatch', exact: true }).click()
  await expect(profiles.getByRole('button', { name: 'Enable Dispatch', exact: true })).toBeVisible()
  await expect(comparison.getByLabel('To revision').getByRole('option', { name: 'Revision 3', exact: true })).toHaveCount(1)
  await expect(comparison.getByLabel('From revision')).toHaveValue('version-1')
  await expect(comparison.getByLabel('To revision')).toHaveValue('version-2')
  await expect(comparison).toContainText('Revision 1 → revision 2')
  await comparison.getByLabel('From revision').selectOption('version-2')
  await comparison.getByLabel('To revision').selectOption('version-3')
  await expect(comparison).toContainText('No configuration changes between these revisions.')
  await comparison.getByLabel('From revision').selectOption('version-1')
  await profiles.getByRole('button', { name: 'Enable Dispatch', exact: true }).click()
  await expect(comparison.getByLabel('From revision')).toHaveValue('version-1')
  await expect(comparison.getByLabel('From revision')).toHaveAttribute('aria-invalid', 'true')
  await expect(comparison).toContainText('Choose two available revisions to compare.')
  await expect(comparison.getByRole('article')).toHaveCount(0)
  await expect(comparison).not.toContainText('Before: https://old.example.com')
  await comparison.getByLabel('From revision').selectOption('version-2')
  await expect(comparison).toContainText('No configuration changes between these revisions.')
})
