import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * Start from a sample (docs/status/ROADMAP-2026-11-23.md §6 first run) against a mocked API: the Home button builds the
 * melon milk sample through the public API in order, publishes it and opens its workflow; a refusal on the way says where.
 * The stool sample uses the same builder with other data.
 */
async function answer(page: Page, failOn?: string) {
  const writes: { method: string; path: string; body: Record<string, unknown> }[] = []
  let seq = 0
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'GET') return ok(route, [])
    const body = (request.postDataJSON() ?? {}) as Record<string, unknown>
    writes.push({ method: request.method(), path: pathname, body })
    if (pathname === failOn) {
      return route.fulfill({ status: 400, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: 'Location RAW is closed' }) })
    }
    seq += 1
    const id = `id-${seq}`
    if (pathname === '/api/projects') return ok(route, { projectId: 'prj-sample' })
    if (pathname === '/api/items') return ok(route, { itemId: `itm-${body.itemCode as string}` })
    if (pathname === '/api/equipments') return ok(route, { equipmentId: `eq-${body.equipmentCode as string}` })
    if (pathname === '/api/workflows') return ok(route, { workflowId: 'wf-sample' })
    if (pathname === '/api/processes') return ok(route, { processId: `p-${body.processName as string}` })
    if (pathname === '/api/process-ios') {
      return ok(route, { processIoId: `io-${body.processId as string}-${body.direction as string}-${body.itemId as string}` })
    }
    return ok(route, { id })
  })
  return writes
}

test('the melon milk sample is built in order and opened', async ({ page }) => {
  const writes = await answer(page)
  await mockedLogin(page)
  const card = page.getByRole('region', { name: '견본으로 시작' })
  await card.getByRole('button', { name: /메론우유 견본 만들기/ }).click()
  await expect(page).toHaveURL(/\/projects\/prj-sample\/workflows\/wf-sample$/, { timeout: 15_000 })

  expect(writes[0]).toMatchObject({ path: '/api/projects', body: { ownerId: 'demo-owner', projectName: 'Melon milk sample' } })
  // Stock goes through the inventory command; raw milk is the short one.
  expect(writes.filter((one) => one.path === '/api/inventories').map((one) => one.body))
    .toContainEqual({ projectId: 'prj-sample', itemId: 'itm-MM-MILK', location: 'RAW', quantity: 150 })
  // Materials drawn from stock are not required inputs, or publishing would refuse them.
  expect(writes.filter((one) => one.path === '/api/process-ios').every((one) => one.body.requiredYn === 'N')).toBe(true)
  expect(writes.find((one) => one.path === '/api/processes/p-Mix/requirements')?.body)
    .toEqual({ requirements: [{ kind: 'equipment', ref: 'eq-MIXER-1', amount: 1 }, { kind: 'time', amount: 30, unit: 'min' }] })
  expect(writes.at(-1)).toMatchObject({ method: 'POST', path: '/api/workflows/wf-sample/revisions' })
  // Each connection joins the output and input ports of the item it carries.
  expect(writes.find((one) => one.path === '/api/process-connections' && one.body.toProcessId === 'p-Fill')?.body).toMatchObject({
    fromProcessId: 'p-Mix', lossRate: 0.02, fromIoId: 'io-p-Mix-output-itm-MM-MIX', toIoId: 'io-p-Fill-input-itm-MM-MIX',
  })
})

test('a refusal stops the sample and says at which step', async ({ page }) => {
  const writes = await answer(page, '/api/inventories')
  await mockedLogin(page)
  await page.getByRole('button', { name: /메론우유 견본 만들기/ }).click()
  await expect(page.getByRole('alert')).toHaveText('견본이 재고 단계에서 멈췄습니다: Location RAW is closed', { timeout: 15_000 })
  await expect(page).toHaveURL(/\/$/)
  expect(writes.some((one) => one.path === '/api/workflows')).toBe(false)
})
