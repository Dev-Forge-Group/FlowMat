import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * Start from a description (docs/architecture/adr/ADR-047-process-draft-from-a-description.md) against a mocked API: the
 * draft is shown before anything is made, then built in a new project like a sample, with a connection wherever a step
 * takes what an earlier step made. A server without drafting shows no card at all, only the samples.
 */
const DRAFT = {
  title: 'Melon milk', summary: 'Mix milk and syrup, then fill bottles.',
  items: [{ code: 'MILK', name: 'Raw milk', type: 'material', unit: 'kg' }, { code: 'SYRUP', name: 'Melon syrup', type: 'material', unit: 'kg' },
    { code: 'MIX', name: 'Mix', type: 'semi', unit: 'kg' }, { code: 'BOTTLE', name: 'Bottle', type: 'material', unit: 'ea' },
    { code: 'MELON-MILK', name: 'Melon milk', type: 'product', unit: 'ea' }],
  steps: [{ name: 'Mix', inputs: [{ item: 'MILK', quantity: 100 }, { item: 'SYRUP', quantity: 10 }], outputs: [{ item: 'MIX', quantity: 110 }], minutes: 30 },
    { name: 'Fill', inputs: [{ item: 'MIX', quantity: 0.2 }, { item: 'BOTTLE', quantity: 1 }], outputs: [{ item: 'MELON-MILK', quantity: 1 }], minutes: 0 }],
  target: { item: 'MELON-MILK', quantity: 1000 },
}

test('a description becomes a draft, then a process in a new project', async ({ page }) => {
  const writes: { path: string; body: Record<string, unknown> }[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (pathname === '/api/process-drafts/availability') return ok(route, { available: true })
    if (request.method() === 'GET') return ok(route, [])
    const body = (request.postDataJSON() ?? {}) as Record<string, unknown>
    if (pathname === '/api/process-drafts') return ok(route, DRAFT)
    writes.push({ path: pathname, body })
    if (pathname === '/api/projects') return ok(route, { projectId: 'prj-draft' })
    if (pathname === '/api/items') return ok(route, { itemId: `itm-${body.itemCode as string}` })
    if (pathname === '/api/workflows') return ok(route, { workflowId: 'wf-draft' })
    if (pathname === '/api/processes') return ok(route, { processId: `p-${body.processName as string}` })
    if (pathname === '/api/process-ios') return ok(route, { processIoId: `io-${body.processId as string}-${body.direction as string}-${body.itemId as string}` })
    return ok(route, { id: 'x' })
  })
  await mockedLogin(page)
  const card = page.getByRole('region', { name: '설명으로 시작' })
  await card.getByLabel('만드는 것').fill('We mix raw milk with melon syrup and fill bottles.')
  await card.getByRole('button', { name: '초안 만들기' }).click()
  const draft = card.getByRole('group', { name: '초안' })
  await expect(draft).toContainText('Mix: Raw milk 100 kg + Melon syrup 10 kg → Mix 110 kg (30분)')
  expect(writes).toHaveLength(0)

  await draft.getByRole('button', { name: '이 초안으로 만들기' }).click()
  await expect(page).toHaveURL(/\/projects\/prj-draft\/workflows\/wf-draft$/, { timeout: 15_000 })
  expect(writes[0]).toMatchObject({ path: '/api/projects', body: { projectName: 'Melon milk' } })
  expect(writes.find((one) => one.path === '/api/process-connections')?.body).toMatchObject({
    fromProcessId: 'p-Mix', toProcessId: 'p-Fill', fromIoId: 'io-p-Mix-output-itm-MIX', toIoId: 'io-p-Fill-input-itm-MIX',
  })
  expect(writes.find((one) => one.path === '/api/processes/p-Mix/requirements')?.body)
    .toEqual({ requirements: [{ kind: 'time', amount: 30, unit: 'min' }] })
})

test('a server without drafting shows only the samples', async ({ page }) => {
  const drafts: string[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const { pathname } = new URL(route.request().url())
    if (await answerAuth(route, pathname)) return
    if (pathname === '/api/process-drafts/availability') return ok(route, { available: false })
    if (pathname === '/api/process-drafts') drafts.push(pathname)
    return ok(route, [])
  })
  await mockedLogin(page)
  await expect(page.getByRole('region', { name: '견본으로 시작' })).toBeVisible({ timeout: 15_000 })
  await expect(page.getByRole('region', { name: '설명으로 시작' })).toHaveCount(0)
  expect(drafts).toHaveLength(0)
})
