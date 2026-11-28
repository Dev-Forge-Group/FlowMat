import { expect, type Page, type Route } from '@playwright/test'

/**
 * For specs that answer the whole API themselves, so nothing is written to a real database (BOMs, for one, are kept out of
 * the dev database). They run in CI's plain browser-e2e step, without REAL_API_E2E.
 */

/** A successful answer in the backend's envelope. */
export async function ok(route: Route, data: unknown) {
  await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data, message: null }) })
}

/**
 * Answers the sign-in calls (CSRF, login, refresh, current user) as the backend does, and the home screen's question
 * whether this server drafts from a description (off, as on every test server; ADR-047); true when it did.
 */
export async function answerAuth(route: Route, pathname: string): Promise<boolean> {
  if (pathname === '/api/auth/csrf') {
    await route.fulfill({
      status: 200, headers: { 'content-type': 'application/json', 'set-cookie': 'XSRF-TOKEN=test-csrf; Path=/' },
      body: JSON.stringify({ success: true, data: 'test-csrf', message: null }),
    })
    return true
  }
  if (pathname === '/api/auth/login') {
    await route.fulfill({
      status: 200, headers: { 'content-type': 'application/json', 'set-cookie': 'flowmat_rt=test-refresh; HttpOnly; Path=/api/auth' },
      body: JSON.stringify({ success: true, data: {
        accessToken: 'eyJ.fake.access', refreshToken: null, deviceId: 'device-1', additionalInfoRequired: false,
      }, message: null }),
    })
    return true
  }
  if (pathname === '/api/auth/refresh') {
    await ok(route, { accessToken: 'eyJ.fake.access', refreshToken: null })
    return true
  }
  if (pathname === '/api/users/me') {
    await ok(route, { userId: 'demo-owner', userName: 'Demo Owner' })
    return true
  }
  if (pathname === '/api/users/me/permissions') {
    await ok(route, { canManageUsers: false })
    return true
  }
  if (pathname === '/api/process-drafts/availability') {
    await ok(route, { available: false })
    return true
  }
  return false
}

/** Logs in through the login form; the mocked API answers it. */
export async function mockedLogin(page: Page) {
  await page.goto('/')
  await page.locator('input').nth(0).fill('demo-owner')
  await page.locator('input[type="password"]').fill('demo1234')
  await page.getByRole('button', { name: 'Log in' }).click()
  await expect(page.locator('input[type="password"]')).toHaveCount(0, { timeout: 15_000 })
}
