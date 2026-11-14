import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { uniqueSuffix } from './support/uniqueSuffix'

test.skip(!process.env.REAL_API_E2E, 'Needs the real backend and demo seed')
// Shifts are read in the planning zone (Asia/Seoul by default) and the dates are typed as local time.
test.use({ timezoneId: 'Asia/Seoul' })

/**
 * Planned dates suggested from the equipment's calendar (docs/domain/equipment-schedule.md "계획 기간 제안"): a draft on
 * equipment with a weekday day shift takes the earliest start and end that hold what it has to make, and readiness then
 * finds exactly that time in the planned window. The order is cancelled and the equipment deleted at the end.
 */
const PROJECT = 'prj_demo_main'
const suffix = uniqueSuffix()
const CODE = `EQ-PLAN-${suffix}`
const TITLE = `Plan dates ${suffix}`

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

test('a draft takes the earliest dates its equipment has time for', async ({ page, request }) => {
  const call = await api(request)
  const equipment = await call('POST', '/equipments', {
    projectId: PROJECT, equipmentCode: CODE, equipmentName: `plan e2e ${suffix}`, equipmentType: 'oven', details: { capacityPerHour: 10 },
  })
  await call('PUT', `/equipments/${equipment.equipmentId}/calendar`, {
    shifts: [{ shiftStart: '09:00', shiftEnd: '17:00', workDays: [1, 2, 3, 4, 5] }],
  })
  // 100 at 10 an hour needs 10 h: Monday's 8 h shift and two hours on Tuesday. The draft plans only one hour.
  const order = await call('POST', '/work-orders', {
    projectId: PROJECT, workOrderTitle: TITLE, targetQuantity: 100,
    plannedStartAt: '2030-01-07T09:00:00+09:00', plannedEndAt: '2030-01-07T10:00:00+09:00',
  })
  await call('PUT', `/work-orders/${order.workOrderId}/equipment`, { equipmentId: equipment.equipmentId })
  try {
    await login(page)
    await page.goto(`/projects/${PROJECT}/runs?view=work-orders`)
    await page.getByRole('row', { name: new RegExp(TITLE) }).getByRole('button', { name: 'Readiness' }).click()
    const readiness = page.locator('[aria-label="Readiness"]')
    await expect(readiness).toContainText('needs 10 h for 100 but has only 1 h available in the planned window')

    const plan = page.locator('[aria-label="Plan dates"]')
    await expect(plan.getByLabel('Plan from')).toHaveValue('2030-01-07T09:00')
    await plan.getByLabel('Plan from').fill('2030-01-07T00:00')
    await plan.getByRole('button', { name: 'Suggest dates' }).click()
    await expect(plan.getByRole('status')).toHaveText('Mon 2030-01-07 09:00 → Tue 2030-01-08 11:00 · needs 10 h')
    await plan.getByRole('button', { name: 'Use these dates' }).click()
    await expect(readiness).toContainText('needs 10 h of the 10 h available in the planned window')
  } finally {
    await call('PUT', `/work-orders/${order.workOrderId}/equipment`, { equipmentId: null })
    await call('POST', `/work-orders/${order.workOrderId}/cancel`, {})
    await call('DELETE', `/equipments/${equipment.equipmentId}`)
  }
})
