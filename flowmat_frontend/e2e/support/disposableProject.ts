import { expect, test, type Page } from '@playwright/test'

export async function disposableProject(page: Page, name: string) {
  if (process.env.REAL_API_E2E !== '1' || process.env.FLOWMAT_FEATURE_E2E_DISPOSABLE !== '1') {
    throw new Error('An explicitly disposable backend is required')
  }
  await page.goto('/')
  await page.locator('input').nth(0).fill('demo-owner')
  await page.locator('input[type="password"]').fill('demo1234')
  const login = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/auth/login')
  await page.getByRole('button', { name: 'Log in', exact: true }).click()
  const response = await login
  expect(response.ok()).toBe(true)
  const token = (await response.json()).data.accessToken as string
  await expect(page.getByRole('button', { name: 'Logout', exact: true })).toBeVisible()
  const request = (method: string, path: string, body?: unknown) => page.request.fetch(`/api${path}`, {
    method, headers: { Authorization: `Bearer ${token}` }, data: body,
  })
  const api = async <T = unknown>(method: string, path: string, body?: unknown): Promise<T> => {
    const result = await request(method, path, body)
    expect(result.ok(), `${method} ${path}: ${result.status()}`).toBe(true)
    const envelope = await result.json()
    expect(envelope.success).toBe(true)
    return envelope.data as T
  }
  const projectId = (await api<{ projectId: string }>('POST', '/projects', {
    projectName: `${name}-${Date.now()}`, ownerId: 'demo-owner',
  })).projectId
  return {
    projectId, api, request,
    async dispose(successful: boolean) {
      try {
        const result = await request('DELETE', `/projects/${projectId}`)
        if (successful) expect(result.ok(), `Project cleanup returned ${result.status()}`).toBe(true)
        else if (!result.ok()) test.info().annotations.push({ type: 'cleanup', description: `Project cleanup returned ${result.status()}` })
      } catch (error) {
        if (successful) throw error
        test.info().annotations.push({ type: 'cleanup', description: 'Project cleanup failed after the primary failure' })
      }
    },
  }
}
