import { expect, test, type Page, type Route } from '@playwright/test'

type Account = 'owner' | 'viewer'
const accountToken = (account: Account, revision = 1) =>
  `fixture.${Buffer.from(JSON.stringify({ sub: account, revision })).toString('base64url')}.signature`

const pageErrors = new WeakMap<Page, Error[]>()
test.beforeEach(async ({ page }) => {
  const errors: Error[] = []
  pageErrors.set(page, errors)
  page.on('pageerror', (error) => errors.push(error))
})
test.afterEach(async ({ page }) => {
  expect(pageErrors.get(page) ?? []).toEqual([])
})

async function mockSessions(page: Page, holdOwner = false) {
  let account: Account = 'owner'
  let hasSession = false
  let refreshExpired = false
  let releaseOwner!: () => void
  let ownerStarted!: () => void
  let ownerFinished!: () => void
  const ownerWaiting = new Promise<void>((resolve) => { releaseOwner = resolve })
  const ownerRequest = new Promise<void>((resolve) => { ownerStarted = resolve })
  const ownerResponse = new Promise<void>((resolve) => { ownerFinished = resolve })
  const reads = { owner: 0, viewer: 0 }
  let refreshes = 0
  const send = (route: Route, data: unknown, status = 200) => route.fulfill({
    status, contentType: 'application/json',
    body: JSON.stringify({ success: status === 200, data, message: null }),
  })

  await page.route((url) => url.pathname.startsWith('/api/'), async (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/auth/login') {
      account = route.request().postDataJSON().userIdOrEmail === 'viewer' ? 'viewer' : 'owner'
      hasSession = true
      return send(route, { accessToken: accountToken(account) })
    }
    if (path === '/api/auth/csrf') return send(route, 'fixture-csrf')
    if (path === '/api/auth/logout') {
      hasSession = false
      return send(route, null)
    }
    if (path === '/api/auth/refresh') {
      refreshes += 1
      const rejected = !hasSession || refreshExpired
      return send(route, rejected ? null : { accessToken: accountToken(account, 2) }, rejected ? 401 : 200)
    }
    // Once the session has expired, the access token is refused everywhere (and so is the refresh, above).
    if (refreshExpired) return send(route, null, 401)

    const requester: Account = route.request().headers().authorization === `Bearer ${accountToken('owner')}`
      || route.request().headers().authorization === `Bearer ${accountToken('owner', 2)}` ? 'owner' : 'viewer'
    if (path === '/api/users/me') {
      reads[requester] += 1
      if (holdOwner && requester === 'owner') {
        ownerStarted()
        await ownerWaiting
      }
      await send(route, { userId: requester, userName: requester === 'owner' ? 'Owner Account' : 'Viewer Account' })
      if (requester === 'owner') ownerFinished()
      return
    }
    if (path === '/api/users/me/permissions') return send(route, { canManageUsers: requester === 'owner' })
    if (path === '/api/projects') return send(route, [{
      projectId: `${requester}-project`, ownerId: requester,
      projectName: requester === 'owner' ? 'Owner Private Project' : 'Viewer Project',
      projectStatus: 'active', visibility: 'private',
    }])
    if (path === '/api/notifications/unread-count') return send(route, { unread: 0 })
    return send(route, [])
  })

  return {
    reads, ownerRequest, ownerResponse, releaseOwner,
    expire: () => { refreshExpired = true },
    refreshCount: () => refreshes,
  }
}

async function login(page: Page, account: Account) {
  await page.getByPlaceholder('demo-owner').fill(account)
  await page.locator('input[type="password"]').fill('fixture-password')
  await page.getByRole('button', { name: 'Log in', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Logout', exact: true })).toBeVisible()
}

test('owner logout then viewer login replaces fresh identity, permissions and projects without a reload', async ({ page }) => {
  const session = await mockSessions(page)
  await page.goto('/')
  await login(page, 'owner')
  await expect(page.getByRole('heading', { name: /Owner Account/ })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Admin', exact: true })).toBeVisible()
  await expect(page.getByText('Owner Private Project', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Logout', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Log in', exact: true })).toBeVisible()
  await login(page, 'viewer')
  await expect(page.getByRole('heading', { name: /Viewer Account/ })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Admin', exact: true })).toHaveCount(0)
  await expect(page.getByText('Owner Private Project', { exact: true })).toHaveCount(0)
  await expect(page.getByText('Viewer Project', { exact: true })).toBeVisible()
  expect(session.reads.viewer).toBeGreaterThan(0)
})

test('a delayed owner identity response cannot populate the viewer dashboard', async ({ page }) => {
  const session = await mockSessions(page, true)
  await page.goto('/')
  await login(page, 'owner')
  await session.ownerRequest
  await page.getByRole('button', { name: 'Logout', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Log in', exact: true })).toBeVisible()
  await login(page, 'viewer')
  session.releaseOwner()
  await session.ownerResponse
  await expect(page.getByRole('heading', { name: /Viewer Account/ })).toBeVisible()
  await expect(page.getByRole('heading', { name: /Owner Account/ })).toHaveCount(0)
  await expect(page.getByRole('link', { name: 'Admin', exact: true })).toHaveCount(0)
  expect(session.reads.viewer).toBeGreaterThan(0)
})

test('expired refresh removes an already mounted protected route', async ({ page }) => {
  const session = await mockSessions(page)
  await page.goto('/')
  await login(page, 'owner')
  await page.getByRole('link', { name: 'Organizations', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Organizations', exact: true })).toBeVisible()
  const previousRefreshes = session.refreshCount()
  session.expire()
  // The next request the open screen makes meets the expired session; CI serves the built app, so the test cannot
  // import the HTTP client from /src.
  const create = page.getByRole('form', { name: 'New team organization' })
  await create.getByRole('textbox', { name: 'Organization name' }).fill('Night shift')
  await create.getByRole('button', { name: 'Create organization' }).click()
  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByRole('button', { name: 'Log in', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Organizations', exact: true })).toHaveCount(0)
  expect(session.refreshCount()).toBe(previousRefreshes + 1)
})
