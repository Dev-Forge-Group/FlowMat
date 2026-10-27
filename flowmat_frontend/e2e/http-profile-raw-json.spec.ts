import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

const projectId = 'prj-profile-raw'
const path = `/api/projects/${projectId}/http-profiles`
const profile = { profileId: 'profile-raw', projectId, name: 'Dispatch', baseOrigin: 'https://api.example.com',
  credentialAlias: null, enabled: true, version: 2, currentRevision: 3, profileVersionId: 'profile-v3',
  operations: [{ operationId: 'send', method: 'POST', relativePath: '/jobs', successStatuses: [200] }], createdAt: '', updatedAt: '' }
const cases = [
  { label: 'decimal status', operations: '[{"operationId":"send","method":"POST","relativePath":"/jobs","successStatuses":[200.0]}]', invalid: true },
  { label: 'rounded status', operations: '[{"operationId":"send","method":"POST","relativePath":"/jobs","successStatuses":[199.99999999999999]}]', invalid: true },
  { label: 'duplicate method', operations: '[{"operationId":"send","method":"GET","method":"POST","relativePath":"/jobs","successStatuses":[200]}]', invalid: true },
  { label: 'whole statuses', operations: '[{"operationId":"send","method":"POST","relativePath":"/jobs","successStatuses":[200,202]}]', invalid: false },
]

async function setup(page: Page, editing: boolean, operations: string, invalid: boolean) {
  const sent: { method: string; body: string }[] = []
  let rows = editing ? [profile] : []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname
    if (await answerAuth(route, pathname)) return
    if (pathname === path || pathname === `${path}/${profile.profileId}`) {
      if (request.method() === 'GET') return ok(route, rows)
      const body = request.postData() ?? ''
      sent.push({ method: request.method(), body })
      // Mirrors the server's existing strict numeric-token and duplicate-key rejection.
      if (invalid && body.includes(operations)) return route.fulfill({ status: 400, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: 'Operations JSON was rejected without coercion.' }) })
      const saved = { ...profile, ...request.postDataJSON(), version: editing ? 3 : 0 }
      rows = [saved]
      return ok(route, saved)
    }
    if (request.method() !== 'GET') throw new Error(`Unexpected mutation: ${pathname}`)
    if (pathname === '/api/project-members') return ok(route, [{ projectMemberId: 'owner', projectId,
      userId: 'demo-owner', projectRole: 'owner', memberStatus: 'active' }])
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/settings`)
  const panel = page.getByRole('region', { name: 'HTTP profiles' })
  await panel.getByRole('button', { name: editing ? 'Edit Dispatch' : 'Add HTTP profile', exact: true }).click()
  const form = panel.getByRole('form', { name: 'Edit HTTP profile' })
  await form.getByLabel('Profile name').fill(' Dispatch changed ')
  await form.getByLabel('Base origin').fill('https://api.example.com')
  await form.getByLabel('Approved operations (JSON)').fill(operations)
  await form.getByRole('button', { name: 'Save profile', exact: true }).click()
  return { sent, panel, form }
}

for (const editing of [false, true]) {
  for (const entry of cases) {
    test(`${editing ? 'update' : 'create'} preserves ${entry.label} operation source for server validation`, async ({ page }) => {
      const { sent, panel, form } = await setup(page, editing, entry.operations, entry.invalid)
      await expect.poll(() => sent.length).toBe(1)
      expect(sent[0].method).toBe(editing ? 'PUT' : 'POST')
      expect(sent[0].body).toContain(entry.operations)
      const parsed = JSON.parse(sent[0].body)
      expect(parsed).toMatchObject({ name: 'Dispatch changed', baseOrigin: 'https://api.example.com', credentialAlias: null, enabled: true })
      if (editing) expect(parsed.expectedVersion).toBe(2)
      else expect(parsed).not.toHaveProperty('expectedVersion')
      if (entry.invalid) {
        await expect(panel.getByRole('alert')).toContainText('Operations JSON was rejected without coercion.')
        await expect(form.getByLabel('Approved operations (JSON)')).toHaveValue(entry.operations)
        await expect(form.getByRole('button', { name: 'Save profile', exact: true })).toBeEnabled()
      } else await expect(form).toHaveCount(0)
    })
  }
}
