import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * Organization management against a mocked API (docs/domain/organization.md OR2-OR4): create a team, add a member,
 * hand over ownership and leave; the last owner cannot leave, and the project list is metadata only.
 */
test('an owner creates a team, adds a member, hands over ownership and leaves', async ({ page }) => {
  const organizations = [{ organizationId: 'org-personal', organizationName: 'demo-owner의 작업 공간', organizationType: 'personal',
    ownerUserId: 'demo-owner', myRole: 'owner' }]
  const members: Record<string, Record<string, unknown>[]> = {
    'org-personal': [{ organizationMemberId: 'm-own', userId: 'demo-owner', orgRole: 'owner', memberStatus: 'active',
      joinedAt: '2030-01-01T00:00:00Z', leftAt: null }],
  }
  const writes: string[] = []
  const error = (status: number, message: string) => ({ status, contentType: 'application/json',
    body: JSON.stringify({ success: false, data: null, message }) })

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    const method = request.method()
    if (pathname === '/api/organizations' && method === 'POST') {
      writes.push('create')
      const body = request.postDataJSON() as { organizationName: string }
      organizations.push({ organizationId: 'org-team', organizationName: body.organizationName, organizationType: 'team',
        ownerUserId: 'demo-owner', myRole: 'owner' })
      members['org-team'] = [{ organizationMemberId: 'm-1', userId: 'demo-owner', orgRole: 'owner', memberStatus: 'active',
        joinedAt: '2030-01-02T00:00:00Z', leftAt: null }]
      return ok(route, organizations[1])
    }
    if (pathname === '/api/organizations/org-team/members' && method === 'POST') {
      const body = request.postDataJSON() as { userId: string; orgRole: string }
      writes.push(`add ${body.userId} ${body.orgRole}`)
      if (body.userId === 'ghost') return route.fulfill(error(400, 'User ghost does not exist.'))
      const added = { organizationMemberId: 'm-2', userId: body.userId, orgRole: body.orgRole, memberStatus: 'active',
        joinedAt: '2030-01-03T00:00:00Z', leftAt: null }
      members['org-team'].push(added)
      return ok(route, added)
    }
    if (pathname === '/api/organizations/org-team/members/m-2' && method === 'PUT') {
      const body = request.postDataJSON() as { orgRole: string }
      writes.push(`role m-2 ${body.orgRole}`)
      members['org-team'][1].orgRole = body.orgRole
      return ok(route, members['org-team'][1])
    }
    if (pathname === '/api/organizations/org-team/members/m-1/leave' && method === 'POST') {
      writes.push('leave m-1')
      members['org-team'] = members['org-team'].filter((member) => member.organizationMemberId !== 'm-1')
      organizations.splice(1, 1)
      return ok(route, { organizationMemberId: 'm-1', userId: 'demo-owner', orgRole: 'owner', memberStatus: 'left',
        joinedAt: '2030-01-02T00:00:00Z', leftAt: '2030-01-04T00:00:00Z' })
    }
    if (method !== 'GET') throw new Error(`Unexpected write ${method} ${pathname}`)
    if (pathname === '/api/organizations') return ok(route, organizations)
    const membersPath = pathname.match(/^\/api\/organizations\/([^/]+)\/members$/)
    if (membersPath) return ok(route, members[membersPath[1]] ?? [])
    if (pathname === '/api/organizations/org-personal/projects') {
      return ok(route, [{ projectId: 'p1', projectName: 'Bakery line', projectStatus: 'active', ownerId: 'demo-owner' }])
    }
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.getByRole('link', { name: 'Organizations' }).click()
  await expect(page).toHaveURL(/\/organizations$/)
  const personal = page.getByRole('region', { name: 'Organization demo-owner의 작업 공간' })
  await expect(personal.getByText('Your personal workspace.', { exact: false })).toBeVisible()
  await expect(personal.getByRole('region', { name: 'Organization projects' })).toContainText('Bakery line')
  // The only owner cannot leave.
  await expect(personal.getByRole('button', { name: 'Leave' })).toBeDisabled()
  await expect(personal).toContainText('The last owner cannot leave')

  const form = page.getByRole('form', { name: 'New team organization' })
  await form.getByRole('button', { name: 'Create organization' }).click()
  await expect(form.getByRole('alert')).toHaveText('Give the organization a name.')
  await form.getByLabel('Organization name').fill('  Plant team ')
  await form.getByRole('button', { name: 'Create organization' }).click()
  await expect(page).toHaveURL(/\/organizations\/org-team$/)
  const team = page.getByRole('region', { name: 'Organization Plant team' })

  const add = team.getByRole('form', { name: 'Add organization member' })
  await add.getByLabel('User ID').fill('ghost')
  await add.getByRole('button', { name: 'Add member' }).click()
  await expect(add.getByRole('alert')).toHaveText('User ghost does not exist.')
  await add.getByLabel('User ID').fill('kim')
  await add.getByLabel('New member role').selectOption('admin')
  await add.getByRole('button', { name: 'Add member' }).click()
  await expect(add.getByRole('status')).toHaveText('Added kim as admin.')
  await expect(team.getByRole('table', { name: 'Organization members' })).toContainText('kim')

  // Demoting the only owner is blocked before it is sent.
  await team.getByLabel('Role of demo-owner').selectOption('member')
  await expect(team.getByRole('button', { name: 'Save role' })).toBeDisabled()
  await team.getByLabel('Role of demo-owner').selectOption('owner')
  await team.getByLabel('Role of kim').selectOption('owner')
  await team.getByRole('button', { name: 'Save role' }).click()
  await expect(team.getByText('kim is now owner.')).toBeVisible()

  page.once('dialog', (dialog) => void dialog.accept())
  await team.getByRole('button', { name: 'Leave' }).click()
  await expect(page).toHaveURL(/\/organizations$/)
  await expect(page.getByRole('navigation', { name: 'Your organizations' })).not.toContainText('Plant team')
  expect(writes).toEqual(['create', 'add ghost member', 'add kim admin', 'role m-2 owner', 'leave m-1'])
})

test('a new project goes to the chosen team organization, or the personal one by default', async ({ page }) => {
  const created: Record<string, unknown>[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (pathname === '/api/projects' && request.method() === 'POST') {
      const body = request.postDataJSON() as Record<string, unknown>
      created.push(body)
      return ok(route, { projectId: `prj-${created.length}`, projectName: body.projectName, projectDesc: null, projectStatus: 'active',
        visibility: 'private', currentWorkflowId: null, organizationId: body.organizationId ?? 'org-personal' })
    }
    if (request.method() !== 'GET') throw new Error(`Unexpected write ${request.method()} ${pathname}`)
    if (pathname === '/api/organizations') {
      return ok(route, [
        { organizationId: 'org-personal', organizationName: 'demo-owner의 작업 공간', organizationType: 'personal', ownerUserId: 'demo-owner', myRole: 'owner' },
        { organizationId: 'org-team', organizationName: 'Plant team', organizationType: 'team', ownerUserId: 'lee', myRole: 'member' },
      ])
    }
    return ok(route, [])
  })

  await mockedLogin(page)
  const form = page.locator('form').filter({ has: page.getByPlaceholder('프로젝트 이름') })
  const organization = form.getByLabel('조직')
  await expect(organization).toHaveValue('')
  await form.getByPlaceholder('프로젝트 이름').fill('Mine')
  await form.getByRole('button', { name: '만들기', exact: true }).click()
  await expect.poll(() => created.length).toBe(1)
  // The form clears the name once the project is made; typing before that would be cleared with it.
  await expect(form.getByRole('button', { name: '만들기', exact: true })).toBeEnabled()
  await expect(form.getByPlaceholder('프로젝트 이름')).toHaveValue('')
  await organization.selectOption('org-team')
  await form.getByPlaceholder('프로젝트 이름').fill('Shared line')
  await form.getByRole('button', { name: '만들기', exact: true }).click()
  await expect.poll(() => created.length).toBe(2)
  expect(created[0].organizationId).toBeUndefined()
  expect(created[1]).toMatchObject({ projectName: 'Shared line', organizationId: 'org-team' })
})

test('a member sees members without management or the project list', async ({ page }) => {
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') throw new Error(`Unexpected write ${request.method()} ${pathname}`)
    if (pathname === '/api/organizations') {
      return ok(route, [{ organizationId: 'org-x', organizationName: 'Partner team', organizationType: 'team', ownerUserId: 'lee', myRole: 'member' }])
    }
    if (pathname === '/api/organizations/org-x/members') {
      return ok(route, [
        { organizationMemberId: 'm-a', userId: 'lee', orgRole: 'owner', memberStatus: 'active', joinedAt: null, leftAt: null },
        { organizationMemberId: 'm-b', userId: 'demo-owner', orgRole: 'member', memberStatus: 'active', joinedAt: null, leftAt: null },
      ])
    }
    if (pathname === '/api/organizations/org-x/projects') throw new Error('A member must not ask for the project list')
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/organizations/org-x')
  const team = page.getByRole('region', { name: 'Organization Partner team' })
  await expect(team.getByRole('table', { name: 'Organization members' })).toContainText('lee')
  await expect(team.getByRole('button', { name: 'Remove' })).toHaveCount(0)
  await expect(team.getByRole('form', { name: 'Add organization member' })).toHaveCount(0)
  await expect(team.getByRole('combobox')).toHaveCount(0)
  await expect(team.getByRole('button', { name: 'Leave' })).toBeEnabled()
  await expect(team).toContainText('Only owners and admins see the organization')
})
