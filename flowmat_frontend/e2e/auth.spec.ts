import { test, expect, type Page } from '@playwright/test'

const accessToken = 'eyJ.fake.access'

async function mockAuthApi(page: Page) {
  await page.route('**/api/auth/csrf', async (route) => {
    await route.fulfill({
      status: 200,
      headers: {
        'content-type': 'application/json',
        'set-cookie': 'XSRF-TOKEN=test-csrf; Path=/',
      },
      body: JSON.stringify({ success: true, data: 'test-csrf', message: null }),
    })
  })

  await page.route('**/api/auth/login', async (route) => {
    await route.fulfill({
      status: 200,
      headers: {
        'content-type': 'application/json',
        'set-cookie': 'flowmat_rt=test-refresh; HttpOnly; Path=/api/auth',
      },
      body: JSON.stringify({
        success: true,
        data: {
          accessToken,
          refreshToken: null,
          deviceId: 'device-1',
          additionalInfoRequired: false,
        },
        message: null,
      }),
    })
  })

  await page.route('**/api/auth/refresh', async (route) => {
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        success: true,
        data: { accessToken, refreshToken: null },
        message: null,
      }),
    })
  })

  await page.route('**/api/auth/logout', async (route) => {
    await route.fulfill({
      status: 200,
      headers: {
        'content-type': 'application/json',
        'set-cookie': 'flowmat_rt=; Max-Age=0; HttpOnly; Path=/api/auth',
      },
      body: JSON.stringify({ success: true, data: null, message: null }),
    })
  })

  await page.route('**/api/users/me', async (route) => {
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        success: true,
        data: { userId: 'demo-owner', userName: 'Demo Owner' },
        message: null,
      }),
    })
  })

  for (const [path, data] of [
    ['/notifications', []],
    ['/notifications/unread-count', { unread: 0 }],
  ] as const) {
    await page.route(`**/api${path}`, async (route) => {
      await route.fulfill({ status: 200, headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ success: true, data, message: null }) })
    })
  }

  await page.route('**/api/organizations', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true, data: [], message: null }),
    })
  })

  await page.route('**/api/projects', async (route) => {
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ success: true, data: [], message: null }),
    })
  })

  await page.route('**/api/users/me/permissions', async (route) => {
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        success: true,
        data: { canManageUsers: false },
        message: null,
      }),
    })
  })

  await page.route('**/api/items**', async (route) => {
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ success: true, data: [], message: null }),
    })
  })
}

async function mockWorkspaceApi(page: Page) {
  await page.route('**/api/processes/process-e2e/execution-policy', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ success: true, data: {
        processId: 'process-e2e', timeoutSeconds: null, retryLimit: null,
        retryDelaySeconds: null, retryBackoff: null, maxRetryDelaySeconds: null,
        concurrencyLimit: null, version: 0,
      }, message: null }) })
  })

  await page.route('**/api/processes/process-e2e/execution-binding', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ success: true, data: {
        processId: 'process-e2e', profileVersionId: null, operationId: null,
        outputPortId: null, requestMapping: null, responseMapping: null,
        requestMappingJson: null, responseMappingJson: null, version: 0,
      }, message: null }) })
  })

  await page.route('**/api/projects/prj-e2e/http-profiles', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ success: true, data: [], message: null }) })
  })

  await page.route('**/api/project-members?projectId=prj-e2e', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ success: true, data: [{
        projectMemberId: 'member-e2e-owner', projectId: 'prj-e2e', userId: 'demo-owner',
        projectRole: 'owner', memberStatus: 'active', joinedAt: null,
      }], message: null }) })
  })

  await page.route('**/api/workflows/wf-e2e/revisions', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ success: true, data: [], message: null }) })
  })

  await page.route('**/api/workflows/wf-e2e/canvas', async (route) => {
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        success: true,
        data: {
          workflow: {
            workflowId: 'wf-e2e',
            projectId: 'prj-e2e',
            workflowName: 'E2E Workflow',
            workflowDesc: 'Browser test workflow',
            workflowType: 'main',
            workflowStatus: 'active',
          },
          graphSeq: 1,
          currentUserRole: 'owner',
          processes: [
            {
              processId: 'process-e2e',
              projectId: 'prj-e2e',
              workflowId: 'wf-e2e',
              processName: 'Input Node',
              processType: 'input',
              nodeType: 'input',
              processStatus: 'active',
              colorScheme: 'sky',
              posX: 100,
              posY: 100,
              width: 180,
              height: 88,
              processDesc: 'E2E node',
              version: 1,
              versionNonce: 1,
            },
          ],
          processIos: [],
          connections: [],
          annotations: [],
        },
        message: null,
      }),
    })
  })

  await page.route('**/api/workflows/wf-e2e/editor-document', async (route) => {
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        success: true,
        data: {
          schemaVersion: 1,
          camera: { x: 0, y: 0, zoom: 1 },
          nextElementSeq: 1,
          version: 1,
          versionNonce: 1,
          elements: [],
        },
        message: null,
      }),
    })
  })

  await page.route('**/api/workflows/wf-e2e/graph-changes**', async (route) => {
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ success: true, data: { currentSeq: 1, resetRequired: false, changes: [] }, message: null }),
    })
  })

  await page.route('**/api/workflows/wf-e2e/presence', async (route) => {
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ success: true, data: [], message: null }),
    })
  })

  await page.route('**/api/workflows?**', async (route) => {
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ success: true, data: [{ workflowId: 'wf-e2e', workflowName: 'E2E Workflow' }], message: null }),
    })
  })
}

test('login stores access token only in memory and refreshes through cookie', async ({ page }) => {
  await mockAuthApi(page)
  await page.goto('/')

  await page.locator('input').nth(0).fill('demo-owner')
  await page.locator('input[type="password"]').fill('demo1234')
  await page.getByRole('button', { name: 'Log in' }).click()

  await expect(page.getByText('안녕하세요, Demo Owner님')).toBeVisible()
  expect(await page.evaluate(() => localStorage.getItem('access_token'))).toBeNull()
  expect(await page.evaluate(() => localStorage.getItem('refresh_token'))).toBeNull()
  expect(await page.evaluate(() => document.cookie)).not.toContain('flowmat_rt')

  await page.reload()
  await expect(page.getByText('안녕하세요, Demo Owner님')).toBeVisible()
})

test('logout clears the client session and returns to login screen', async ({ page }) => {
  await mockAuthApi(page)
  await page.goto('/')
  await page.locator('input').nth(0).fill('demo-owner')
  await page.locator('input[type="password"]').fill('demo1234')
  await page.getByRole('button', { name: 'Log in' }).click()
  await expect(page.getByText('안녕하세요, Demo Owner님')).toBeVisible()

  await page.getByRole('button', { name: 'Logout' }).click()
  await expect(page.locator('input').nth(0)).toBeVisible()
  expect(await page.evaluate(() => localStorage.getItem('access_token'))).toBeNull()
})

test('two tabs keep separate in-memory access tokens while sharing the cookie session hint', async ({ browser }) => {
  const context = await browser.newContext()
  const first = await context.newPage()
  const second = await context.newPage()
  await mockAuthApi(first)
  await mockAuthApi(second)

  await first.goto('/')
  await first.locator('input').nth(0).fill('demo-owner')
  await first.locator('input[type="password"]').fill('demo1234')
  await first.getByRole('button', { name: 'Log in' }).click()
  await expect(first.getByText('안녕하세요, Demo Owner님')).toBeVisible()

  await mockWorkspaceApi(second)
  await second.unroute('**/api/auth/refresh')
  await second.route('**/api/auth/refresh', async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 100))
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        success: true,
        data: { accessToken, refreshToken: null },
        message: null,
      }),
    })
  })
  await second.goto('/projects/prj-e2e/workflows/wf-e2e')
  await expect(second.getByText('E2E Workflow')).toBeVisible()
  await context.close()
})

test('authenticated home keeps organization lookup inside the mocked API', async ({ page }) => {
  let backendOrganizationRequests = 0
  // A leaked fake token would be rejected by the real backend running in CI.
  await page.context().route('**/api/organizations', async (route) => {
    backendOrganizationRequests += 1
    await route.fulfill({ status: 401, contentType: 'application/json',
      body: JSON.stringify({ success: false, data: null, message: 'Unmocked organization request' }) })
  })
  await mockAuthApi(page)
  await page.goto('/')
  await page.locator('input').nth(0).fill('demo-owner')
  await page.locator('input[type="password"]').fill('demo1234')
  const organizations = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/organizations')
  await page.getByRole('button', { name: 'Log in' }).click()
  expect((await organizations).status()).toBe(200)
  await expect(page.getByText('안녕하세요, Demo Owner님')).toBeVisible()
  expect(backendOrganizationRequests).toBe(0)
})

test('authenticated home keeps notification queries inside the mocked API', async ({ page }) => {
  let backendNotificationRequests = 0
  await page.context().route('**/api/notifications**', async (route) => {
    backendNotificationRequests += 1
    await route.fulfill({ status: 401, contentType: 'application/json',
      body: JSON.stringify({ success: false, data: null, message: 'Unmocked notification request' }) })
  })
  await mockAuthApi(page)
  await page.goto('/')
  await page.locator('input').nth(0).fill('demo-owner')
  await page.locator('input[type="password"]').fill('demo1234')
  const list = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/notifications')
  const count = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/notifications/unread-count')
  await page.getByRole('button', { name: 'Log in' }).click()
  expect((await list).status()).toBe(200)
  const counted = await count
  expect(counted.status()).toBe(200)
  expect((await counted.json()).data).toEqual({ unread: 0 })
  await expect(page.getByRole('region', { name: 'Notifications' })).toContainText('All read')
  expect(backendNotificationRequests).toBe(0)
})

test('simultaneous tabs serialize rotating refresh cookies', async ({ browser }) => {
  const context = await browser.newContext()
  let releaseRefresh = () => {}
  const unmockedApiPaths: string[] = []
  try {
    // Reject API calls that escape the fixtures, as the real backend rejects the fake access token.
    await context.route((url) => url.pathname.startsWith('/api/'), async (route) => {
      unmockedApiPaths.push(new URL(route.request().url()).pathname)
      await route.fulfill({ status: 401, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: 'Unmocked API request' }) })
    })
    const first = await context.newPage()
    const second = await context.newPage()
    await mockAuthApi(first)
    await mockAuthApi(second)
    await mockWorkspaceApi(first)
    await mockWorkspaceApi(second)

    await first.goto('/')
    await first.locator('input').nth(0).fill('demo-owner')
    await first.locator('input[type="password"]').fill('demo1234')
    await first.getByRole('button', { name: 'Log in' }).click()
    await expect(first.getByText('안녕하세요, Demo Owner님')).toBeVisible()

    const usedTokens = new Set<string>()
    let currentToken = 'test-refresh'
    let refreshStarted = false
    const release = new Promise<void>((resolve) => { releaseRefresh = resolve })
    let refreshRequests = 0
    let completedRefreshes = 0
    let rejectedRefreshes = 0
    const rotatingRefresh = async (route: import('@playwright/test').Route) => {
      refreshRequests += 1
      const cookie = await route.request().headerValue('cookie') ?? ''
      const presented = /(?:^|;\s*)flowmat_rt=([^;]+)/.exec(cookie)?.[1]
      if (presented !== currentToken || usedTokens.has(presented)) {
        rejectedRefreshes += 1
        await route.fulfill({ status: 401, contentType: 'application/json',
          body: JSON.stringify({ success: false, data: null, message: 'Token was already consumed' }) })
        return
      }
      usedTokens.add(presented)
      if (usedTokens.size === 1) {
        refreshStarted = true
        await release
      }
      currentToken = `refresh-${usedTokens.size + 1}`
      await route.fulfill({ status: 200,
        headers: { 'content-type': 'application/json', 'set-cookie': `flowmat_rt=${currentToken}; HttpOnly; Path=/api/auth` },
        body: JSON.stringify({ success: true, data: { accessToken, refreshToken: null }, message: null }),
      })
      completedRefreshes += 1
    }
    await first.unroute('**/api/auth/refresh')
    await second.unroute('**/api/auth/refresh')
    await first.route('**/api/auth/refresh', rotatingRefresh)
    await second.route('**/api/auth/refresh', rotatingRefresh)

    await first.goto('/projects/prj-e2e/workflows/wf-e2e', { waitUntil: 'commit' })
    await expect.poll(() => refreshStarted, { message: 'The first refresh must reach the response gate' }).toBe(true)
    await second.goto('/projects/prj-e2e/workflows/wf-e2e', { waitUntil: 'commit' })
    await expect.poll(async () => {
      const locks = await second.evaluate(async () => {
        const snapshot = await navigator.locks.query()
        const isRefreshLock = (lock: { name?: string; mode?: string }) =>
          lock.name === 'flowmat-refresh' && lock.mode === 'exclusive'
        return {
          held: snapshot.held?.filter(isRefreshLock).length ?? 0,
          pending: snapshot.pending?.filter(isRefreshLock).length ?? 0,
        }
      })
      return { ...locks, refreshRequests, completedRefreshes, rejectedRefreshes, consumedTokens: usedTokens.size }
    }, { message: 'The second tab must wait for the held refresh lock before sending its refresh' }).toEqual({
      held: 1, pending: 1, refreshRequests: 1, completedRefreshes: 0, rejectedRefreshes: 0, consumedTokens: 1,
    })
    releaseRefresh()
    await expect.poll(() => ({
      refreshRequests, completedRefreshes, rejectedRefreshes, consumedTokens: usedTokens.size,
    }), { message: 'Both rotating refreshes must finish successfully before checking the workspace UI' }).toEqual({
      refreshRequests: 2, completedRefreshes: 2, rejectedRefreshes: 0, consumedTokens: 2,
    })
    await expect(first.getByText('E2E Workflow')).toBeVisible()
    await expect(second.getByText('E2E Workflow')).toBeVisible()
    expect(unmockedApiPaths, 'Auth fixtures must cover every workspace API request').toEqual([])
    expect(rejectedRefreshes).toBe(0)
    expect(usedTokens.size).toBe(2)
  } finally {
    releaseRefresh()
    await context.close()
  }
})

test('logout waits for an in-flight refresh in another tab', async ({ browser }) => {
  const context = await browser.newContext()
  let releaseRefresh = () => {}
  try {
    const first = await context.newPage()
    const second = await context.newPage()
    await mockAuthApi(first)
    await mockAuthApi(second)
    await first.goto('/')
    await first.locator('input').nth(0).fill('demo-owner')
    await first.locator('input[type="password"]').fill('demo1234')
    await first.getByRole('button', { name: 'Log in' }).click()
    await expect(first.getByText('안녕하세요, Demo Owner님')).toBeVisible()
    await second.goto('/')
    await expect(second.getByText('안녕하세요, Demo Owner님')).toBeVisible()

    let refreshStarted = () => {}
    const started = new Promise<void>((resolve) => { refreshStarted = resolve })
    const release = new Promise<void>((resolve) => { releaseRefresh = resolve })
    let refreshFinished = false
    let logoutBeforeRefresh = false
    await first.unroute('**/api/auth/refresh')
    await first.route('**/api/auth/refresh', async (route) => {
      refreshStarted()
      await release
      await route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ success: true, data: { accessToken, refreshToken: null }, message: null }) })
      refreshFinished = true
    })
    await second.unroute('**/api/auth/logout')
    await second.route('**/api/auth/logout', async (route) => {
      logoutBeforeRefresh = !refreshFinished
      await route.fulfill({ status: 200,
        headers: { 'content-type': 'application/json', 'set-cookie': 'flowmat_rt=; Max-Age=0; HttpOnly; Path=/api/auth' },
        body: JSON.stringify({ success: true, data: null, message: null }) })
    })

    await first.goto('/projects/prj-e2e/workflows/wf-e2e', { waitUntil: 'commit' })
    await started
    await second.getByRole('button', { name: 'Logout' }).click()
    await expect(second.getByRole('button', { name: 'Logout' })).toBeDisabled()
    expect(logoutBeforeRefresh).toBe(false)
    releaseRefresh()
    await expect(second.locator('input').nth(0)).toBeVisible()
    await expect.poll(() => first.evaluate(() => localStorage.getItem('flowmat_refresh_cookie'))).toBeNull()
  } finally {
    releaseRefresh()
    await context.close()
  }
})

test('an expired refresh session returns the user to login', async ({ page }) => {
  await mockAuthApi(page)
  await page.unroute('**/api/auth/refresh')
  await page.route('**/api/auth/refresh', async (route) => {
    await route.fulfill({ status: 401, body: JSON.stringify({ success: false, data: null }) })
  })
  await page.goto('/')
  await page.evaluate(() => localStorage.setItem('flowmat_refresh_cookie', '1'))
  await page.goto('/projects/prj-e2e/workflows/wf-e2e')
  await expect(page.locator('input').nth(0)).toBeVisible()
  await expect.poll(() => page.evaluate(() => localStorage.getItem('flowmat_refresh_cookie'))).toBeNull()
})

test('a mocked OAuth provider callback completes login without storing a token', async ({ page }) => {
  await mockAuthApi(page)
  await page.route('**/api/auth/oauth2/exchange', async (route) => {
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        success: true,
        data: {
          resultType: 'login',
          accessToken,
          signupToken: null,
          provider: 'google',
          deviceId: 'device-1',
          additionalInfoRequired: false,
        },
        message: null,
      }),
    })
  })

  await page.goto('/oauth2/success?code=mock-provider-code')
  await expect(page.getByText('안녕하세요, Demo Owner님')).toBeVisible()
  expect(await page.evaluate(() => localStorage.getItem('access_token'))).toBeNull()
})

test('authenticated user can open workspace, select a node, and switch canvas tools', async ({ page }) => {
  const unmockedApiPaths: string[] = []
  await page.route((url) => url.pathname.startsWith('/api/'), async (route) => {
    unmockedApiPaths.push(new URL(route.request().url()).pathname)
    await route.fulfill({ status: 401, contentType: 'application/json',
      body: JSON.stringify({ success: false, data: null, message: 'Unmocked API request' }) })
  })
  await mockAuthApi(page)
  await mockWorkspaceApi(page)
  await page.route('**/api/auth/login', async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 250))
    await route.fallback()
  })
  await page.goto('/')
  await page.locator('input').nth(0).fill('demo-owner')
  await page.locator('input[type="password"]').fill('demo1234')
  await page.getByRole('button', { name: 'Log in' }).click()
  await expect(page.getByText('안녕하세요, Demo Owner님')).toBeVisible()

  await page.goto('/projects/prj-e2e/workflows/wf-e2e')
  await expect(page.getByText('E2E Workflow')).toBeVisible()
  await expect(page.getByText('Input Node')).toBeVisible()
  await expect(page.getByText('1 nodes | 0 connections')).toBeVisible()

  await Promise.all([
    ...[
      '/api/processes/process-e2e/execution-policy',
      '/api/project-members',
      '/api/processes/process-e2e/execution-binding',
      '/api/projects/prj-e2e/http-profiles',
    ].map(async (path) => {
      const response = await page.waitForResponse((response) =>
        new URL(response.url()).pathname === path && response.request().method() === 'GET')
      expect(response.status(), `${path} must use its workspace API fixture`).toBe(200)
    }),
    page.getByText('Input Node').click(),
  ])
  await expect(page.locator('.inspector__title')).toHaveText('Input Node')

  await page.getByRole('tab', { name: 'Annotate' }).click()
  await expect(page.getByRole('button', { name: 'Save Editor' })).toBeVisible()
  await page.getByRole('tab', { name: 'View' }).click()
  await expect(page.locator('button.ribbon-button', { hasText: 'Fit View' })).toBeVisible()
  expect(unmockedApiPaths, 'The selected node inspector must stay inside the workspace API fixtures').toEqual([])
})
