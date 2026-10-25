import { expect, test, type Page, type Route } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

const projectId = 'prj-http-readiness'
const otherProjectId = 'prj-http-other'
const profile = { profileId: 'profile-readiness', projectId, name: 'Dispatch', enabled: true, version: 1,
  currentRevision: 1, profileVersionId: 'readiness-v1', baseOrigin: 'https://api.example.com', credentialAlias: null,
  operations: [{ operationId: 'send', method: 'POST', relativePath: '/jobs', successStatuses: [202] }], createdAt: '', updatedAt: '' }
const ready = (profileVersionId = profile.profileVersionId) => ({ profileVersionId, configurationReady: true,
  blockers: [], credentialValuesChecked: false, connectionTested: false })

async function setupReadiness(page: Page, answer: (route: Route, current: typeof profile) => Promise<void>, role = 'owner') {
  let current = { ...profile }
  let reads = 0
  const writes: string[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (pathname.endsWith('/readiness')) { reads += 1; await answer(route, current); return }
    if (request.method() !== 'GET') {
      writes.push(`${request.method()} ${pathname}`)
      if (request.method() === 'PUT' && pathname.endsWith('/profile-readiness')) {
        const input = request.postDataJSON() as { name: string }
        current = { ...current, name: input.name, version: current.version + 1, profileVersionId: 'readiness-v2', currentRevision: 2 }
        await ok(route, current); return
      }
      if (request.method() === 'PATCH' && pathname.endsWith('/profile-readiness/status')) {
        const input = request.postDataJSON() as { enabled: boolean }
        current = { ...current, enabled: input.enabled, version: current.version + 1 }
        await ok(route, current); return
      }
      await route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: 'Unexpected write' }) }); return
    }
    if (pathname === '/api/project-members') {
      await ok(route, [projectId, otherProjectId].map((id) => ({ projectMemberId: `member-${id}`, projectId: id,
        userId: 'demo-owner', projectRole: role, memberStatus: 'active' }))); return
    }
    if (pathname === `/api/projects/${projectId}/http-profiles`) { await ok(route, [current]); return }
    if (pathname === `/api/projects/${otherProjectId}/http-profiles`) {
      await ok(route, [{ ...profile, projectId: otherProjectId, profileId: 'other-profile', profileVersionId: 'other-version' }]); return
    }
    await ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/settings`)
  return { profiles: page.getByRole('region', { name: 'HTTP profiles' }), reads: () => reads, writes }
}

test('an owner manually checks configuration and sees safe blockers without a connection or credential test', async ({ page }) => {
  let answers = 0
  const marker = 'INTERNAL_CONFIGURATION_DETAIL_DO_NOT_DISPLAY'
  const { profiles, reads, writes } = await setupReadiness(page, async (route) => {
    answers += 1
    if (answers === 1) await ok(route, ready())
    else if (answers === 2) await route.fulfill({ status: 500, contentType: 'application/json',
      body: JSON.stringify({ success: false, data: null, message: marker }) })
    else await ok(route, { ...ready(), configurationReady: false, blockers: [
      'CONNECTOR_DISABLED', 'PROFILE_RETIRED', 'PROFILE_DISABLED', 'TARGET_POLICY_REJECTED',
      'CREDENTIAL_CONFIGURATION_UNAVAILABLE', marker,
    ] })
  })
  const configuration = profiles.getByRole('region', { name: 'Execution configuration for Dispatch' })
  const check = configuration.getByRole('button', { name: 'Check execution configuration', exact: true })
  await expect(check).toBeVisible()
  expect(reads()).toBe(0)
  await check.click()
  await expect(configuration.getByRole('status')).toHaveText('Configuration ready')
  await expect(configuration).toContainText('Credential values have not been checked, and no connection has been tested.')
  await check.click()
  await expect(configuration.getByRole('alert')).toHaveText('Could not check execution configuration. Try again.')
  await expect(configuration.getByText('Configuration ready', { exact: true })).toHaveCount(0)
  await expect(configuration).not.toContainText(marker)
  await check.click()
  await expect(configuration).toContainText('HTTP execution is disabled in the server configuration.')
  await expect(configuration).toContainText('This profile has been removed.')
  await expect(configuration).toContainText('This profile is disabled.')
  await expect(configuration).toContainText('The destination does not meet the approved server target policy.')
  await expect(configuration).toContainText('The registered credential configuration is unavailable for this project.')
  await expect(configuration).toContainText('An execution configuration requirement could not be verified.')
  await expect(configuration).not.toContainText(marker)
  expect(reads()).toBe(3)
  expect(writes).toEqual([])
})

test('a viewer can read profiles without a configuration check button or request', async ({ page }) => {
  const { profiles, reads, writes } = await setupReadiness(page, async (route) => { await ok(route, ready()) }, 'viewer')
  await expect(profiles.getByText('Dispatch', { exact: true })).toBeVisible()
  await expect(profiles.getByRole('button', { name: 'Check execution configuration', exact: true })).toHaveCount(0)
  expect(reads()).toBe(0)
  expect(writes).toEqual([])
})

test('profile edits discard a late result and a mismatched version can never appear ready', async ({ page }) => {
  let release!: () => void
  const oldResult = new Promise<void>((resolve) => { release = resolve })
  let answers = 0
  const { profiles, reads, writes } = await setupReadiness(page, async (route, current) => {
    answers += 1
    if (answers === 1) { await oldResult; await ok(route, ready()); return }
    if (answers === 2) { await ok(route, ready('different-version')); return }
    await ok(route, { ...ready(current.profileVersionId), configurationReady: false, blockers: ['PROFILE_DISABLED'] })
  })
  await profiles.getByRole('button', { name: 'Check execution configuration', exact: true }).click()
  await expect.poll(reads).toBe(1)
  await profiles.getByRole('button', { name: 'Edit Dispatch', exact: true }).click()
  await profiles.getByLabel('Profile name').fill('Dispatch revised')
  await profiles.getByRole('button', { name: 'Save profile', exact: true }).click()
  const configuration = profiles.getByRole('region', { name: 'Execution configuration for Dispatch revised' })
  await expect(configuration.getByRole('button', { name: 'Check execution configuration', exact: true })).toBeVisible()
  const response = page.waitForResponse((result) => result.url().endsWith('/by-version/readiness-v1/readiness'))
  release()
  await response
  await expect(configuration.getByText('Configuration ready', { exact: true })).toHaveCount(0)
  expect(reads()).toBe(1)
  await configuration.getByRole('button', { name: 'Check execution configuration', exact: true }).click()
  await expect(configuration.getByRole('alert')).toHaveText('The profile changed while checking. Check the current revision again.')
  await expect(configuration.getByText('Configuration ready', { exact: true })).toHaveCount(0)
  await profiles.getByRole('button', { name: 'Disable Dispatch revised', exact: true }).click()
  await expect(configuration.getByRole('alert')).toHaveCount(0)
  await configuration.getByRole('button', { name: 'Check execution configuration', exact: true }).click()
  await expect(configuration).toContainText('This profile is disabled.')
  expect(writes).toEqual([`PUT /api/projects/${projectId}/http-profiles/profile-readiness`,
    `PATCH /api/projects/${projectId}/http-profiles/profile-readiness/status`])
})

test('a late configuration result from another project cannot populate the current project', async ({ page }) => {
  let release!: () => void
  const pending = new Promise<void>((resolve) => { release = resolve })
  const { profiles, reads, writes } = await setupReadiness(page, async (route) => { await pending; await ok(route, ready()) })
  await profiles.getByRole('button', { name: 'Check execution configuration', exact: true }).click()
  await expect.poll(reads).toBe(1)
  await page.evaluate((id) => {
    window.history.pushState({}, '', `/projects/${id}/settings`)
    window.dispatchEvent(new PopStateEvent('popstate'))
  }, otherProjectId)
  const configuration = profiles.getByRole('region', { name: 'Execution configuration for Dispatch' })
  await expect(configuration.getByRole('button', { name: 'Check execution configuration', exact: true })).toBeVisible()
  const response = page.waitForResponse((result) => result.url().endsWith('/by-version/readiness-v1/readiness'))
  release()
  await response
  await expect(configuration.getByText('Configuration ready', { exact: true })).toHaveCount(0)
  expect(reads()).toBe(1)
  expect(writes).toEqual([])
})
