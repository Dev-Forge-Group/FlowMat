import { expect, test, type Page, type Route } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * Week 2 of docs/status/MONTH-PLAN-2026-11-16.md, item 4, against a mocked API: what is typed into the work order and
 * inspection forms while a slow save waits stays as the next draft when the save ends, as on the purchase, warehouse and
 * run screens. A form left as it was sent is cleared or closed as before.
 */
const projectId = 'prj-forms-pending'
const salt = { itemId: 'salt', projectId, itemCode: 'SALT', itemName: 'salt', itemType: 'material', resourceCategory: 'material',
  unitId: 'unit_kg', itemStatus: 'active', lotManageYn: 'N', unitCost: null }

/** Mocks the project; {@code write} answers the one write path under test and every other write is refused. */
async function mockProject(page: Page, write: { path: string; answer: (route: Route, body: Record<string, unknown>) => Promise<unknown> }) {
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === write.path) {
      return write.answer(route, request.postDataJSON() as Record<string, unknown>)
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/project-members') {
      return ok(route, [{ projectMemberId: 'pm', projectId, userId: 'demo-owner', projectRole: 'owner', memberStatus: 'active' }])
    }
    if (pathname === `/api/projects/${projectId}/time-zone`) return ok(route, { projectId, timeZone: 'Asia/Seoul', version: 0 })
    if (pathname === '/api/items') return ok(route, [salt])
    if (pathname === '/api/quality/summary') {
      return ok(route, { inspections: 0, passed: 0, failed: 0, passRate: null, openDefects: 0, resolvedDefects: 0,
        defectsByType: [], failuresByCheck: [], byItem: [] })
    }
    return ok(route, [])
  })
  await mockedLogin(page)
}

/** A reply held until {@code release} is called. */
function held() {
  let release!: () => void
  const gate = new Promise<void>((resolve) => { release = resolve })
  return { gate, release }
}

test('a work order typed while the previous one saves stays as the next draft', async ({ page }) => {
  const sent: Record<string, unknown>[] = []
  const first = held()
  await mockProject(page, {
    path: '/api/work-orders',
    answer: async (route, body) => {
      sent.push(body)
      if (sent.length === 1) await first.gate
      return ok(route, { ...body, workOrderId: `wo-${sent.length}`, workOrderNumber: `WO-${sent.length}`, workOrderStatus: 'draft',
        projectId, targetQuantity: body.targetQuantity ?? null, producedQuantity: 0, runCount: 0, priority: 'normal',
        bomId: null, plannedStartAt: null, plannedEndAt: null, actualStartAt: null, actualEndAt: null, instruction: null,
        instructionUrl: null, assignedTo: null, optionalItemIds: [] })
    },
  })
  await page.goto(`/projects/${projectId}/runs?view=work-orders`)
  const title = page.getByLabel('Title *', { exact: true })
  await title.fill('First order')
  await page.getByRole('button', { name: 'Create draft', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Saving...', exact: true })).toBeDisabled()
  await title.fill('Second order')
  await page.getByLabel('Target item', { exact: true }).selectOption('salt')
  first.release()

  await expect(page.getByRole('button', { name: 'Create draft', exact: true })).toBeEnabled()
  await expect(title).toHaveValue('Second order')
  await expect(page.getByLabel('Target item', { exact: true })).toHaveValue('salt')
  expect(sent).toHaveLength(1)
  expect(sent[0]).toMatchObject({ workOrderTitle: 'First order' })

  // Saved as it was sent, the form is cleared as before.
  await page.getByRole('button', { name: 'Create draft', exact: true }).click()
  await expect.poll(() => sent.length).toBe(2)
  expect(sent[1]).toMatchObject({ workOrderTitle: 'Second order', targetItemId: 'salt' })
  await expect(title).toHaveValue('')
})

test('an inspection typed while the previous one saves stays open as the next one', async ({ page }) => {
  const sent: Record<string, unknown>[] = []
  const first = held()
  await mockProject(page, {
    path: '/api/quality-inspections',
    answer: async (route, body) => {
      sent.push(body)
      if (sent.length === 1) await first.gate
      return ok(route, { ...body, inspectionId: `qi-${sent.length}`, resultStatus: body.result ?? 'pass', inspectedBy: 'demo-owner',
        inspectedAt: '2026-11-14T00:00:00Z' })
    },
  })
  await page.goto(`/projects/${projectId}/inventory?tab=quality`)
  await page.getByRole('button', { name: 'Record inspection', exact: true }).click()
  const form = page.getByRole('form', { name: 'Record inspection' })
  const check = form.locator('label', { hasText: /^Check \*/ }).locator('input')
  const result = form.locator('label', { hasText: /^Result \*/ }).locator('select')
  await check.fill('Visual')
  await result.selectOption('pass')
  await form.getByRole('button', { name: 'Save inspection', exact: true }).click()
  await expect(form.getByRole('button', { name: 'Saving...', exact: true })).toBeDisabled()
  await check.fill('Weight')
  await result.selectOption('fail')
  first.release()

  await expect(form.getByRole('status')).toHaveText('Saved. What you typed while it saved is kept here as the next inspection.')
  await expect(check).toHaveValue('Weight')
  await expect(result).toHaveValue('fail')
  expect(sent).toHaveLength(1)
  expect(sent[0]).toMatchObject({ inspectionType: 'Visual', result: 'pass', itemId: 'salt' })

  // Saved as it was sent, the form closes as before.
  await form.getByRole('button', { name: 'Save inspection', exact: true }).click()
  await expect.poll(() => sent.length).toBe(2)
  expect(sent[1]).toMatchObject({ inspectionType: 'Weight', result: 'fail' })
  await expect(form).toHaveCount(0)
})
