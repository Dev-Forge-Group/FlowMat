import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { uniqueSuffix } from './support/uniqueSuffix'

test.skip(!process.env.REAL_API_E2E, 'Needs the real backend and demo seed')
// Weeks start at local Monday midnight; the orders below are in the planning zone (Asia/Seoul).
test.use({ timezoneId: 'Asia/Seoul' })

/**
 * The equipment load board through the real UI (docs/domain/equipment-load.md): a week's available hours against what the
 * equipment's orders need, drafts apart, and the overload once another order is approved. Orders are cancelled and the
 * equipment deleted at the end.
 */
const PROJECT = 'prj_demo_main'
const suffix = uniqueSuffix()
const CODE = `EQ-LD-${suffix}`

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

test('the load board sets a week of orders against the equipment calendar', async ({ page, request }) => {
  const call = await api(request)
  const equipment = await call('POST', '/equipments', {
    projectId: PROJECT, equipmentCode: CODE, equipmentName: `load e2e ${suffix}`, equipmentType: 'press', details: { capacityPerHour: 10 },
  })
  await call('PUT', `/equipments/${equipment.equipmentId}/calendar`, { shiftStart: '09:00', shiftEnd: '17:00', workDays: [1, 2, 3, 4, 5] })
  const orders: string[] = []
  const order = async (title: string, quantity: number, start: string, end: string, approve: boolean) => {
    const created = await call('POST', '/work-orders', {
      projectId: PROJECT, workOrderTitle: title, targetQuantity: quantity, plannedStartAt: start, plannedEndAt: end,
    })
    orders.push(created.workOrderId)
    await call('PUT', `/work-orders/${created.workOrderId}/equipment`, { equipmentId: equipment.equipmentId })
    if (approve) await call('POST', `/work-orders/${created.workOrderId}/approve`, {})
    return created
  }
  // 100 at 10 an hour on Monday-Tuesday (10 h) and a 50 draft on Wednesday (5 h) in a 40 h week.
  const first = await order(`Load ${suffix}`, 100, '2030-01-07T09:00:00+09:00', '2030-01-08T17:00:00+09:00', true)
  const draft = await order(`Draft ${suffix}`, 50, '2030-01-09T09:00:00+09:00', '2030-01-09T17:00:00+09:00', false)
  try {
    await login(page)
    await page.goto(`/projects/${PROJECT}/inventory?tab=equipment`)
    await page.getByText('Load by week').click()
    const board = page.getByRole('region', { name: 'Equipment load' })
    await board.getByLabel('Week of').fill('2030-01-09')
    const row = board.getByRole('table', { name: 'Load by equipment' }).getByRole('row', { name: new RegExp(CODE) })
    await expect(row).toContainText('40 h')
    await expect(row).toContainText('25%')
    await expect(row).toContainText(`${first.workOrderNumber} · 10 h`)
    await expect(row).toContainText('draft · 5 h')

    // Another 400 over the whole week needs 40 h more: 50 of 40 h.
    await order(`More ${suffix}`, 400, '2030-01-07T09:00:00+09:00', '2030-01-11T17:00:00+09:00', true)
    await board.getByRole('button', { name: 'Refresh' }).click()
    await expect(row).toContainText('125% · Overloaded')

    // The week as a timeline: the two approved orders overlap and clash; the draft never does.
    const timeline = board.getByRole('list', { name: `Timeline of ${CODE}` })
    await expect(timeline.getByRole('listitem')).toHaveCount(3)
    await expect(timeline.getByRole('listitem', { name: `${first.workOrderNumber} · clashes`, exact: true })).toBeVisible()
    await expect(timeline.getByRole('listitem', { name: `${draft.workOrderNumber} · draft`, exact: true })).toBeVisible()

    await board.getByRole('button', { name: 'Next week' }).click()
    await expect(row).not.toContainText(first.workOrderNumber)
    await expect(row).toContainText('0%')
  } finally {
    for (const workOrderId of orders) await call('POST', `/work-orders/${workOrderId}/cancel`, {})
    await call('DELETE', `/equipments/${equipment.equipmentId}`)
  }
})
