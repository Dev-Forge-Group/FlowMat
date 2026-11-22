import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * A node's execution requirements in the inspector (docs/architecture/adr/ADR-043-plan-backwards-from-a-target.md decision
 * 4) against a mocked API: the list in words, an amount checked before sending, and the whole list saved at once.
 */
test('the node inspector edits what a run takes', async ({ page }) => {
  const workflow = { workflowId: 'wf-req', projectId: 'prj-e2e', workflowName: 'Melon milk', workflowDesc: null, workflowType: 'standard',
    workflowStatus: 'draft' }
  const process = { processId: 'proc-mix', projectId: 'prj-e2e', workflowId: 'wf-req', processName: 'Mix', processType: 'process',
    nodeType: 'process', processStatus: 'ready', colorScheme: 'slate', posX: 200, posY: 160, width: 180, height: 80, processDesc: null,
    version: 1, versionNonce: 1 }
  let requirements: unknown[] = []
  const sent: unknown[] = []

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'PUT' && pathname === '/api/processes/proc-mix/requirements') {
      const body = request.postDataJSON() as { requirements: Record<string, unknown>[] }
      sent.push(body)
      requirements = body.requirements.map((one, index) => ({ ...one, processRequirementId: `r${index}`, processId: 'proc-mix' }))
      return ok(route, requirements)
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/workflows/wf-req/canvas') {
      return ok(route, { workflow, graphSeq: 0, processes: [process], processIos: [], connections: [], annotations: [],
        currentUserRole: 'owner' })
    }
    if (pathname === '/api/workflows/wf-req/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    if (pathname === '/api/processes/proc-mix/requirements') return ok(route, requirements)
    if (pathname === '/api/equipments') {
      return ok(route, [{ equipmentId: 'eq-mixer', projectId: 'prj-e2e', equipmentCode: 'MIXER-1', equipmentName: 'mixer',
        equipmentType: 'machine', equipmentStatus: 'active' }])
    }
    if (pathname === '/api/workflows') return ok(route, [workflow])
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/workflows/wf-req')
  await page.locator('.react-flow__node').filter({ hasText: 'Mix' }).click({ timeout: 15_000 })
  const section = page.getByRole('region', { name: 'Requirements' })
  await expect(section).toContainText('Nothing yet')

  await section.getByRole('button', { name: 'Edit requirements' }).click()
  const form = section.getByRole('form', { name: 'Edit requirements' })
  await form.getByRole('button', { name: 'Add requirement' }).click()
  await form.getByRole('button', { name: 'Add requirement' }).click()
  const first = form.getByRole('group', { name: 'Requirement 1' })
  await first.getByLabel('Kind').selectOption('equipment')
  await first.getByLabel('Equipment').selectOption('eq-mixer')
  await first.getByLabel('Amount').fill('1')
  const second = form.getByRole('group', { name: 'Requirement 2' })
  await form.getByRole('button', { name: 'Save requirements' }).click()
  await expect(section.getByRole('alert')).toHaveText('Each requirement needs an amount above 0.')
  expect(sent).toHaveLength(0)

  await second.getByLabel('Amount').fill('30')
  await form.getByRole('button', { name: 'Save requirements' }).click()
  await expect(section.getByRole('listitem')).toHaveText(['Equipment MIXER-1: 1 per batch', 'Time: 30 min per batch'])
  await expect(section).toContainText('Plans use them once a revision is published.')
  expect(sent[0]).toEqual({ requirements: [
    { kind: 'equipment', ref: 'eq-mixer', amount: 1, unit: null, per: 'batch' },
    { kind: 'time', ref: null, amount: 30, unit: 'min', per: 'batch' },
  ] })
})
