import { expect, test, type Page, type Response } from '@playwright/test'

test.skip(!process.env.REAL_API_E2E || process.env.FLOWMAT_ORG_E2E_DISPOSABLE !== '1',
  'Requires explicit disposable DB opt-in: FLOWMAT_ORG_E2E_DISPOSABLE=1')
test.use({ trace: 'off' })

function response(page: Page, method: string, path: string) {
  return page.waitForResponse((reply) => reply.request().method() === method
    && new URL(reply.url()).pathname === `/api${path}`)
}

async function payload(reply: Response) {
  expect(reply.ok(), `Request returned ${reply.status()}`).toBe(true)
  const envelope = await reply.json()
  expect(envelope.success).toBe(true)
  return envelope.data as Record<string, string>
}

async function login(page: Page, userId = 'demo-owner') {
  await page.goto('/')
  await page.locator('input').nth(0).fill(userId)
  await page.locator('input[type="password"]').fill('demo1234')
  const signedIn = response(page, 'POST', '/auth/login')
  await page.getByRole('button', { name: 'Log in', exact: true }).click()
  const data = await payload(await signedIn)
  await expect(page.getByRole('link', { name: 'Organizations', exact: true })).toBeVisible()
  return data.accessToken
}

async function createTeam(page: Page, name: string) {
  await page.getByRole('link', { name: 'Organizations', exact: true }).click()
  const form = page.getByRole('form', { name: 'New team organization' })
  await form.getByLabel('Organization name').fill(name)
  const created = response(page, 'POST', '/organizations')
  await form.getByRole('button', { name: 'Create organization' }).click()
  const data = await payload(await created)
  await expect(page).toHaveURL(new RegExp(`/organizations/${data.organizationId}$`))
  return data.organizationId
}

test('real organization UI validates membership and chooses personal or team projects', async ({ page }) => {
  test.setTimeout(90_000)
  const token = await login(page)
  const projects: string[] = []
  try {
    const name = `Org-API-${Date.now()}`
    const id = await createTeam(page, name)
    const team = page.getByRole('region', { name: `Organization ${name}`, exact: true })
    await expect(team.getByRole('button', { name: 'Leave', exact: true })).toBeDisabled()
    await team.getByLabel('Role of demo-owner').selectOption('member')
    await expect(team.getByRole('button', { name: 'Save role' })).toBeDisabled()
    await team.getByLabel('Role of demo-owner').selectOption('owner')

    const add = team.getByRole('form', { name: 'Add organization member' })
    const missing = `missing-${Date.now()}`
    await add.getByLabel('User ID').fill(missing)
    const rejected = response(page, 'POST', `/organizations/${id}/members`)
    await add.getByRole('button', { name: 'Add member' }).click()
    expect((await rejected).status()).toBe(400)
    await expect(add.getByRole('alert')).toHaveText(`User ${missing} does not exist.`)
    await add.getByLabel('User ID').fill('demo-owner')
    const duplicate = response(page, 'POST', `/organizations/${id}/members`)
    await add.getByRole('button', { name: 'Add member' }).click()
    expect((await duplicate).status()).toBe(409)
    await expect(add.getByRole('alert')).toContainText('already a member')

    await page.goto('/')
    const form = page.locator('form').filter({ has: page.getByPlaceholder('프로젝트 이름', { exact: true }) })
    await expect(form.getByLabel('조직')).toHaveValue('')
    for (const organizationId of ['', id]) {
      const projectName = `${name}-${organizationId ? 'team' : 'personal'}`
      await form.getByLabel('조직').selectOption(organizationId)
      await form.getByPlaceholder('프로젝트 이름', { exact: true }).fill(projectName)
      const created = response(page, 'POST', '/projects')
      const firstWorkflow = response(page, 'POST', '/workflows')
      await form.getByRole('button', { name: '만들기', exact: true }).click()
      const reply = await created
      const data = await payload(reply)
      projects.push(data.projectId)
      // A new project opens in the editor with its first workflow; come back to make the next one.
      const workflow = await payload(await firstWorkflow)
      await expect(page).toHaveURL(new RegExp(`/projects/${data.projectId}/workflows/${workflow.workflowId}$`))
      await page.goBack()
      const body = reply.request().postDataJSON() as Record<string, unknown>
      if (organizationId) {
        expect(body.organizationId).toBe(id)
        expect(data.organizationId).toBe(id)
      } else {
        expect(body.organizationId).toBeUndefined()
        expect(data.organizationId).toBeTruthy()
        expect(data.organizationId).not.toBe(id)
      }
      await expect(form.getByPlaceholder('프로젝트 이름', { exact: true })).toHaveValue('')
    }
    await page.goto(`/organizations/${id}`)
    const metadata = team.getByRole('region', { name: 'Organization projects' })
    await expect(metadata).toContainText(`${name}-team`)
    await expect(metadata).not.toContainText(`${name}-personal`)
    await expect(metadata.getByRole('link')).toHaveCount(0)
  } finally {
    for (const id of projects) {
      const deleted = await page.request.delete(`/api/projects/${id}`, { headers: { Authorization: `Bearer ${token}` } })
      expect(deleted.ok(), 'Test project cleanup failed').toBe(true)
    }
  }
})

test('real member management updates owner, admin and member screens without granting project access', async ({ page, browser }) => {
  test.skip(!process.env.FLOWMAT_ORG_E2E_MEMBER_ID, 'Requires a second disposable user with the demo password; never seed the development DB')
  test.setTimeout(120_000)
  const memberId = process.env.FLOWMAT_ORG_E2E_MEMBER_ID!
  await login(page)
  const name = `Org-Roles-${Date.now()}`
  const id = await createTeam(page, name)
  const team = page.getByRole('region', { name: `Organization ${name}`, exact: true })
  const add = team.getByRole('form', { name: 'Add organization member' })
  await add.getByLabel('User ID').fill(memberId)
  const joined = response(page, 'POST', `/organizations/${id}/members`)
  await add.getByRole('button', { name: 'Add member' }).click()
  const membership = await payload(await joined)
  await expect(team.getByRole('table', { name: 'Organization members' })).toContainText(memberId)

  // Each account has its own browser context; no mocked auth or organization requests.
  const context = await browser.newContext({ baseURL: test.info().project.use.baseURL })
  try {
    const member = await context.newPage()
    const token = await login(member, memberId)
    let metadataReads = 0
    member.on('request', (request) => {
      if (new URL(request.url()).pathname === `/api/organizations/${id}/projects`) metadataReads++
    })
    await member.goto(`/organizations/${id}`)
    const detail = member.getByRole('region', { name: `Organization ${name}`, exact: true })
    await expect(detail.getByRole('table', { name: 'Organization members' })).toContainText(memberId)
    await expect(detail.getByRole('form', { name: 'Add organization member' })).toHaveCount(0)
    await expect(detail.getByRole('combobox')).toHaveCount(0)
    await expect(detail).toContainText('Only owners and admins see')
    expect(metadataReads).toBe(0)
    const denied = await member.request.get('/api/projects/prj_demo_main', { headers: { Authorization: `Bearer ${token}` } })
    expect(denied.status()).toBe(403)

    await team.getByLabel(`Role of ${memberId}`).selectOption('admin')
    const changed = response(page, 'PUT', `/organizations/${id}/members/${membership.organizationMemberId}`)
    await team.getByRole('button', { name: 'Save role' }).click()
    await payload(await changed)
    await member.reload()
    await expect(detail.getByRole('region', { name: 'Organization projects' })).toBeVisible()
    await expect(detail.getByLabel('New member role').getByRole('option')).toHaveText(['admin', 'member'])
    await expect(detail.getByLabel(`Role of ${memberId}`)).toHaveCount(0)
    const stillDenied = await member.request.get('/api/projects/prj_demo_main', { headers: { Authorization: `Bearer ${token}` } })
    expect(stillDenied.status()).toBe(403)

    page.once('dialog', (dialog) => void dialog.accept())
    const removed = response(page, 'POST', `/organizations/${id}/members/${membership.organizationMemberId}/remove`)
    await team.getByRole('button', { name: 'Remove', exact: true }).click()
    await payload(await removed)
    await member.reload()
    await expect(member.getByRole('status')).toContainText('This organization is not in your list')

    await add.getByLabel('User ID').fill(memberId)
    await add.getByLabel('New member role').selectOption('owner')
    const rejoined = response(page, 'POST', `/organizations/${id}/members`)
    await add.getByRole('button', { name: 'Add member' }).click()
    await payload(await rejoined)
    await team.getByLabel('Role of demo-owner').selectOption('member')
    const demoted = page.waitForResponse((reply) => reply.request().method() === 'PUT'
      && new URL(reply.url()).pathname.startsWith(`/api/organizations/${id}/members/`))
    await team.getByRole('button', { name: 'Save role' }).click()
    await payload(await demoted)
    await expect(team.getByRole('form', { name: 'Add organization member' })).toHaveCount(0)
    page.once('dialog', (dialog) => void dialog.accept())
    const left = page.waitForResponse((reply) => reply.request().method() === 'POST'
      && new URL(reply.url()).pathname.startsWith(`/api/organizations/${id}/members/`)
      && new URL(reply.url()).pathname.endsWith('/leave'))
    await team.getByRole('button', { name: 'Leave', exact: true }).click()
    await payload(await left)
    await expect(page).toHaveURL(/\/organizations$/)
    await expect(page.getByRole('navigation', { name: 'Your organizations' })).not.toContainText(name)
    await member.reload()
    await expect(detail.getByRole('button', { name: 'Leave', exact: true })).toBeDisabled()
  } finally {
    await context.close()
  }
})
