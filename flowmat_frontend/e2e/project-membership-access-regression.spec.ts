import { expect, test, type Page, type Route } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

const projectId = 'prj-settings-membership-access'
const memberId = 'membership-other'
const invitedEmail = 'private-invite@example.invalid'

async function fixture(page: Page, role: 'owner' | 'editor' | 'viewer') {
  await page.clock.install()
  const state = { memberStatus: 200, inviteStatus: 200, inviteReads: 0, memberReads: 0,
    writes: [] as { method: string; path: string; body: unknown }[] }
  let members = [
    { projectMemberId: 'membership-me', projectId, userId: 'demo-owner', projectRole: role, memberStatus: 'active', joinedAt: null },
    { projectMemberId: memberId, projectId, userId: 'private-member', projectRole: 'viewer', memberStatus: 'active', joinedAt: null },
  ]
  let invites = [{ inviteId: 'invite-private', projectId, invitedEmail, projectRole: 'viewer', inviteStatus: 'pending', expiredAt: null }]
  async function denied(route: Route, status: number) {
    return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({
      success: false, data: null, message: status === 404 ? 'Project no longer exists.' : 'Project membership is required.',
    }) })
  }
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request(), path = new URL(request.url()).pathname
    if (await answerAuth(route, path)) return
    if (request.method() !== 'GET') {
      const body = request.postData() ? request.postDataJSON() : null
      state.writes.push({ method: request.method(), path, body })
      if (path === `/api/project-members/${memberId}/role`) {
        members = members.map((member) => member.projectMemberId === memberId ? { ...member, projectRole: body.projectRole } : member)
        return ok(route, members.find((member) => member.projectMemberId === memberId))
      }
      if (path === `/api/project-members/${memberId}` && request.method() === 'DELETE') {
        members = members.filter((member) => member.projectMemberId !== memberId)
        return ok(route, null)
      }
      if (path === '/api/project-invites' && request.method() === 'POST') {
        const invite = { inviteId: 'invite-new', projectId, invitedEmail: body.invitedEmail, projectRole: body.projectRole,
          inviteStatus: 'pending', expiredAt: null }
        invites = [...invites, invite]
        return ok(route, invite)
      }
      if (path === '/api/project-invites/invite-private' && request.method() === 'DELETE') {
        invites = invites.filter((invite) => invite.inviteId !== 'invite-private')
        return ok(route, null)
      }
      throw new Error(`Unexpected write ${request.method()} ${path}`)
    }
    if (path === '/api/project-members') {
      state.memberReads++
      return state.memberStatus === 200 ? ok(route, members) : denied(route, state.memberStatus)
    }
    if (path === '/api/project-invites') {
      state.inviteReads++
      if (role !== 'owner') return denied(route, 403)
      return state.inviteStatus === 200 ? ok(route, invites) : denied(route, state.inviteStatus)
    }
    if (path === '/api/projects') return ok(route, [{ projectId, projectName: 'Membership fixture', projectStatus: 'active' }])
    if (path.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/settings`)
  return state
}

function membersPanel(page: Page) {
  return page.locator('div').filter({ has: page.getByRole('heading', { name: 'Members', exact: true }) }).last()
}

function invitesPanel(page: Page) {
  return page.locator('div').filter({ has: page.getByRole('heading', { name: 'Invitations', exact: true }) }).last()
}

async function revisitStaleSettings(page: Page) {
  await page.clock.fastForward(31_000)
  await page.getByRole('link', { name: 'Home', exact: false }).click()
  await expect(page).toHaveURL(/\/$/)
  // The URL changes before React commits the Home transition. Going back before that keeps the settings page mounted,
  // so nothing remounts and nothing refetches; wait until the page is really gone.
  await expect(page.getByRole('heading', { name: 'Project Settings', exact: true })).toHaveCount(0)
  await page.goBack()
  await expect(page).toHaveURL(`/projects/${projectId}/settings`)
}

for (const role of ['viewer', 'editor'] as const) {
  test(`${role} reads members without owner controls or invitation requests`, async ({ page }) => {
    const state = await fixture(page, role)
    const members = membersPanel(page)
    await expect(members.getByText('private-member', { exact: true })).toBeVisible()
    await expect(members.getByRole('combobox')).toHaveCount(0)
    await expect(members.getByRole('button', { name: /^(Save|Remove)$/ })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Send Invite', exact: true })).toHaveCount(0)
    await expect(page.getByRole('heading', { name: 'Invitations', exact: true })).toHaveCount(0)
    expect(state.inviteReads).toBe(0)
    expect(state.writes).toEqual([])
  })
}

test('the owner still saves roles, removes members, creates and cancels invitations', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => { if (message.type() === 'error') errors.push(message.text()) })
  const state = await fixture(page, 'owner')
  const members = membersPanel(page), invites = invitesPanel(page)
  const member = members.getByRole('row').filter({ hasText: 'private-member' })
  await member.getByRole('combobox').selectOption('editor')
  await member.getByRole('button', { name: 'Save', exact: true }).click()
  await expect.poll(() => state.writes.length).toBe(1)
  expect(state.writes[0]).toEqual({ method: 'PUT', path: `/api/project-members/${memberId}/role`, body: { projectRole: 'editor' } })
  await expect(member.getByRole('button', { name: 'Save', exact: true })).toBeEnabled()
  page.once('dialog', (dialog) => void dialog.accept())
  await member.getByRole('button', { name: 'Remove', exact: true }).click()
  await expect(member).toHaveCount(0)
  await invites.getByRole('textbox').fill('new-member@example.invalid')
  await invites.getByRole('button', { name: 'Send Invite', exact: true }).click()
  await expect(invites.getByText('new-member@example.invalid', { exact: true })).toBeVisible()
  await invites.getByRole('row').filter({ hasText: invitedEmail }).getByRole('button', { name: 'Cancel', exact: true }).click()
  await expect(invites.getByText(invitedEmail, { exact: true })).toHaveCount(0)
  expect(state.writes).toEqual([
    { method: 'PUT', path: `/api/project-members/${memberId}/role`, body: { projectRole: 'editor' } },
    { method: 'DELETE', path: `/api/project-members/${memberId}`, body: null },
    { method: 'POST', path: '/api/project-invites', body: { projectId, invitedEmail: 'new-member@example.invalid', projectRole: 'viewer' } },
    { method: 'DELETE', path: '/api/project-invites/invite-private', body: null },
  ])
  expect(errors).toEqual([])
})

test('a denied member refresh hides cached member rows after membership removal', async ({ page }) => {
  const state = await fixture(page, 'editor')
  await expect(membersPanel(page).getByText('private-member', { exact: true })).toBeVisible()
  const before = state.memberReads
  state.memberStatus = 403
  await revisitStaleSettings(page)
  await expect.poll(() => state.memberReads).toBeGreaterThan(before)
  const members = membersPanel(page)
  await expect(members.getByRole('alert')).toContainText('Project membership is required.')
  await expect(members.getByText('private-member', { exact: true })).toHaveCount(0)
  await expect(members.getByRole('button', { name: /^(Save|Remove)$/ })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Send Invite', exact: true })).toHaveCount(0)
  await expect(page.getByText(invitedEmail, { exact: true })).toHaveCount(0)
  await expect(members.getByText('No members found.', { exact: true })).toHaveCount(0)
  expect(state.writes).toEqual([])
})

test('a missing project response hides cached invitations and the invitation form', async ({ page }) => {
  const state = await fixture(page, 'owner')
  await expect(invitesPanel(page).getByText(invitedEmail, { exact: true })).toBeVisible()
  const before = state.inviteReads
  state.inviteStatus = 404
  await revisitStaleSettings(page)
  await expect.poll(() => state.inviteReads).toBeGreaterThan(before)
  const invites = invitesPanel(page)
  await expect(invites.getByRole('alert')).toContainText('Project no longer exists.')
  await expect(invites.getByText(invitedEmail, { exact: true })).toHaveCount(0)
  await expect(invites.getByRole('textbox')).toHaveCount(0)
  await expect(invites.getByRole('button', { name: /^(Send Invite|Cancel)$/ })).toHaveCount(0)
  await expect(invites.getByText('No pending invitations.', { exact: true })).toHaveCount(0)
  expect(state.writes).toEqual([])
})

test('an initial invitation refusal is an error rather than an empty list', async ({ page }) => {
  const state = await fixture(page, 'owner')
  await expect(invitesPanel(page).getByText(invitedEmail, { exact: true })).toBeVisible()
  state.inviteStatus = 403
  await page.reload()
  const invites = invitesPanel(page)
  await expect(invites.getByRole('alert')).toContainText('Project membership is required.')
  await expect(invites.getByRole('textbox')).toHaveCount(0)
  await expect(invites.getByText('No pending invitations.', { exact: true })).toHaveCount(0)
  expect(state.writes).toEqual([])
})
