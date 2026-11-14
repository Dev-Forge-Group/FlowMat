import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { uniqueSuffix } from './support/uniqueSuffix'

test.skip(!process.env.REAL_API_E2E, 'Needs the real backend and demo seed')

/**
 * Retiring a workflow revision from the Runs screen (docs/domain/workflow-revision.md V4·V5): a revision published here
 * can be retired, after which it is no longer offered for new runs. A project of its own is made and deleted.
 */
const suffix = uniqueSuffix()

async function api(request: APIRequestContext) {
  const login = await request.post('/api/auth/login', { data: { userIdOrEmail: 'demo-owner', password: 'demo1234' } })
  expect(login.ok(), await login.text()).toBeTruthy()
  const headers = { Authorization: `Bearer ${(await login.json()).data.accessToken}` }
  return async (method: string, path: string, data?: unknown) => {
    const response = await request.fetch('/api' + path, { method, headers, data })
    const body = await response.json()
    expect(response.ok(), `${method} ${path}: ${body.message}`).toBeTruthy()
    return body.data
  }
}

async function login(page: Page) {
  await page.goto('/')
  await page.getByRole('textbox', { name: 'demo-owner' }).fill('demo-owner')
  await page.getByRole('textbox', { name: '••••••••' }).fill('demo1234')
  await page.getByRole('button', { name: 'Log in' }).click()
  await expect(page.getByText('안녕하세요, Demo Owner님')).toBeVisible({ timeout: 15_000 })
  await page.waitForLoadState('networkidle')
}

test('a published revision is retired and no longer offered for new runs', async ({ page, request }) => {
  const call = await api(request)
  const project = (await call('POST', '/projects', { projectName: `Revisions E2E ${suffix}`, ownerId: 'demo-owner' })).projectId
  try {
    const workflow = (await call('POST', '/workflows', { projectId: project, workflowName: `Retire ${suffix}` })).workflowId
    await call('POST', '/processes', { workflowId: workflow, processName: 'Only step' })

    await login(page)
    await page.goto(`/projects/${project}/runs?workflowId=${workflow}`)
    await page.getByRole('button', { name: 'Publish current workflow' }).click()
    const revisionSelect = page.getByLabel('Workflow revision *')
    await expect(revisionSelect.locator('option')).toHaveText([/^v1 · /])

    await page.getByText(/^Revisions \(/).click()
    const list = page.getByRole('list', { name: 'Workflow revisions' })
    await expect(list.getByRole('listitem', { name: 'v1 published' })).toContainText('published by demo-owner')
    page.once('dialog', (dialog) => void dialog.accept())
    await list.getByRole('listitem', { name: 'v1 published' }).getByRole('button', { name: 'Retire' }).click()
    await expect(list.getByRole('listitem', { name: 'v1 retired' })).toContainText('retired by demo-owner')
    await expect(revisionSelect).toBeDisabled()
    await expect(revisionSelect.locator('option')).toHaveText(['No published revision'])
    await expect(page.getByText('Publish this workflow before starting a versioned run.')).toBeVisible()
  } finally {
    await call('DELETE', `/projects/${project}`).catch(() => undefined)
  }
})
