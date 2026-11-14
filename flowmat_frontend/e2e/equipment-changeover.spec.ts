import { test, expect, type APIRequestContext, type Locator, type Page } from '@playwright/test'
import { uniqueSuffix } from './support/uniqueSuffix'

test.skip(!process.env.REAL_API_E2E, 'Needs the real backend and demo seed')

/**
 * Changeover times through the real UI (docs/domain/equipment-changeover.md): a rule set on the equipment's schedule is
 * added to the time a work order needs when the order planned before it on the same equipment makes another item. The
 * orders are cancelled and the equipment deleted at the end.
 */
const PROJECT = 'prj_demo_main'
const suffix = uniqueSuffix()
const FIRST_ITEM = `CO-A-${suffix}`
const NEXT_ITEM = `CO-B-${suffix}`
const CODE = `EQ-CO-${suffix}`
// A select's label text includes all of its options, so getByLabel('To') also matched From once the suffix had "TO" in
// it. The role name leaves the select's own options out.
const side = (scope: Locator, name: 'From' | 'To') => scope.getByRole('combobox', { name, exact: true })

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

test('a changeover from the previous order on the equipment is added to the time an order needs', async ({ page, request }) => {
  const call = await api(request)
  const item = async (code: string) => call('POST', '/items', {
    projectId: PROJECT, itemCode: code, itemName: `changeover e2e ${code}`, itemType: 'product', unitId: 'unit_ea',
  })
  const first = await item(FIRST_ITEM)
  const next = await item(NEXT_ITEM)
  const equipment = await call('POST', '/equipments', {
    projectId: PROJECT, equipmentCode: CODE, equipmentName: `changeover e2e ${suffix}`, equipmentType: 'line',
    details: { capacityPerHour: 10 },
  })
  await call('PUT', `/equipments/${equipment.equipmentId}/calendar`, { shiftStart: '09:00', shiftEnd: '17:00', workDays: [1, 2, 3, 4, 5] })
  const order = async (title: string, itemId: string, start: string, end: string) => {
    const created = await call('POST', '/work-orders', {
      projectId: PROJECT, workOrderTitle: title, targetItemId: itemId, targetQuantity: 100, plannedStartAt: start, plannedEndAt: end,
    })
    await call('PUT', `/work-orders/${created.workOrderId}/equipment`, { equipmentId: equipment.equipmentId })
    return created
  }
  // First item on Monday (approved), the next item Tuesday to Wednesday: 10 h of work in two 8 h shifts.
  const earlier = await order(`Earlier ${suffix}`, first.itemId, '2030-01-07T09:00:00+09:00', '2030-01-07T17:00:00+09:00')
  await call('POST', `/work-orders/${earlier.workOrderId}/approve`, {})
  const later = await order(`Later ${suffix}`, next.itemId, '2030-01-08T09:00:00+09:00', '2030-01-09T17:00:00+09:00')
  let third: { workOrderId: string; workOrderNumber: string } | null = null
  try {
    await login(page)
    await page.goto(`/projects/${PROJECT}/inventory?tab=equipment`)
    await page.getByRole('row', { name: new RegExp(CODE) }).getByRole('button', { name: 'Schedule' }).click()
    const changeovers = page.getByRole('group', { name: 'Changeovers' })
    await expect(changeovers).toContainText('No changeover times set')

    await side(changeovers, 'From').selectOption({ label: `${FIRST_ITEM} · changeover e2e ${FIRST_ITEM}` })
    await side(changeovers, 'To').selectOption({ label: `${NEXT_ITEM} · changeover e2e ${NEXT_ITEM}` })
    await changeovers.getByLabel('Minutes').fill('90')
    await changeovers.getByLabel('Note').fill('Line flush')
    await changeovers.getByRole('button', { name: 'Add changeover' }).click()
    const rule = changeovers.getByRole('table', { name: 'Changeover list' }).getByRole('row', { name: new RegExp(NEXT_ITEM) })
    await expect(rule).toContainText('1 h 30 min')

    // The same pair again is refused before it is sent; the time is changed on the row instead.
    await side(changeovers, 'From').selectOption({ label: `${FIRST_ITEM} · changeover e2e ${FIRST_ITEM}` })
    await side(changeovers, 'To').selectOption({ label: `${NEXT_ITEM} · changeover e2e ${NEXT_ITEM}` })
    await changeovers.getByLabel('Minutes').fill('30')
    await changeovers.getByRole('button', { name: 'Add changeover' }).click()
    await expect(changeovers.getByRole('alert')).toContainText('This pair already has a changeover')
    await rule.getByRole('button', { name: 'Change time' }).click()
    await rule.getByLabel('New minutes').fill('120')
    await rule.getByRole('button', { name: 'Save' }).click()
    await expect(rule).toContainText('2 h')

    await page.goto(`/projects/${PROJECT}/runs?view=work-orders`)
    await page.getByRole('row', { name: new RegExp(`Later ${suffix}`) }).getByRole('button', { name: 'Readiness' }).click()
    const readiness = page.locator('[aria-label="Readiness"]')
    await expect(readiness).toContainText(`${earlier.workOrderNumber}`)
    await expect(readiness).toContainText(`120 min changeover from ${FIRST_ITEM} to ${NEXT_ITEM}`)
    await expect(readiness).toContainText('needs 12 h (with 2 h changeover) of the 16 h available in the planned window')

    // The load board adds up the week's changeovers and names an order with fewer (equipment-load.md "전환 순서 제안"):
    // first, next, first takes 2 h + 1 h; next first, then both of the first item, 1 h.
    await call('POST', `/equipments/${equipment.equipmentId}/changeovers`, { fromItemId: next.itemId, toItemId: first.itemId, minutes: 60 })
    const added = await order(`Third ${suffix}`, first.itemId, '2030-01-10T09:00:00+09:00', '2030-01-10T17:00:00+09:00')
    third = added
    await page.goto(`/projects/${PROJECT}/inventory?tab=equipment`)
    await page.getByText('Load by week').click()
    const board = page.getByRole('region', { name: 'Equipment load' })
    await board.getByLabel('Week of').fill('2030-01-07')
    await expect(board.getByRole('table', { name: 'Load by equipment' }).getByRole('row', { name: new RegExp(CODE) }))
      .toContainText(`Changeovers 3 h as planned · 1 h as ${later.workOrderNumber}, ${earlier.workOrderNumber}, ${added.workOrderNumber}`)
  } finally {
    if (third) await call('POST', `/work-orders/${third.workOrderId}/cancel`, {})
    await call('POST', `/work-orders/${later.workOrderId}/cancel`, {})
    await call('POST', `/work-orders/${earlier.workOrderId}/cancel`, {})
    await call('DELETE', `/equipments/${equipment.equipmentId}`)
  }
})
