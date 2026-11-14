import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { uniqueSuffix } from './support/uniqueSuffix'

test.skip(!process.env.REAL_API_E2E, 'Needs the real backend and demo seed')
// Days and shifts are read in the planning zone (Asia/Seoul by default).
test.use({ timezoneId: 'Asia/Seoul' })

/**
 * A date's own shifts through the real UI (docs/domain/equipment-schedule.md "날짜별 교대"): a closed Wednesday and a short
 * Tuesday change what a work order on the equipment has in its planned window. The order is cancelled and the
 * equipment deleted at the end.
 */
const PROJECT = 'prj_demo_main'
const suffix = uniqueSuffix()
const CODE = `EQ-DAY-${suffix}`
const TITLE = `Days ${suffix}`
const OTHER = `EQ-DAY2-${suffix}`

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

test('a closed day and a short day change the time a work order has', async ({ page, request }) => {
  const call = await api(request)
  const equipment = await call('POST', '/equipments', {
    projectId: PROJECT, equipmentCode: CODE, equipmentName: `days e2e ${suffix}`, equipmentType: 'oven', details: { capacityPerHour: 10 },
  })
  await call('PUT', `/equipments/${equipment.equipmentId}/calendar`, {
    shifts: [{ shiftStart: '09:00', shiftEnd: '17:00', workDays: [1, 2, 3, 4, 5] }],
  })
  const other = await call('POST', '/equipments', {
    projectId: PROJECT, equipmentCode: OTHER, equipmentName: `days e2e other ${suffix}`, equipmentType: 'oven', details: { capacityPerHour: 10 },
  })
  await call('PUT', `/equipments/${other.equipmentId}/calendar`, {
    shifts: [{ shiftStart: '09:00', shiftEnd: '17:00', workDays: [1, 2, 3, 4, 5] }],
  })
  // Monday 09:00 to Wednesday 17:00 holds three 8 h shifts; 100 at 10 an hour needs 10 h.
  const order = await call('POST', '/work-orders', {
    projectId: PROJECT, workOrderTitle: TITLE, targetQuantity: 100,
    plannedStartAt: '2030-01-07T09:00:00+09:00', plannedEndAt: '2030-01-09T17:00:00+09:00',
  })
  await call('PUT', `/work-orders/${order.workOrderId}/equipment`, { equipmentId: equipment.equipmentId })
  try {
    await login(page)
    await page.goto(`/projects/${PROJECT}/inventory?tab=equipment`)
    await page.getByRole('row', { name: new RegExp(CODE) }).getByRole('button', { name: 'Schedule' }).click()
    const schedule = page.getByRole('region', { name: `Schedule of ${CODE}` })
    const change = schedule.getByRole('form', { name: 'Change a day' })
    const days = schedule.getByRole('table', { name: 'Day changes' })

    await change.getByLabel('Day', { exact: true }).fill('2030-01-09')
    await change.getByLabel('Closed all day').check()
    await change.getByLabel('Note').fill('Stock take')
    await change.getByRole('button', { name: 'Save day' }).click()
    await expect(days.getByRole('row', { name: /Wed 2030-01-09/ })).toContainText('Closed')
    await expect(days.getByRole('row', { name: /Wed 2030-01-09/ })).toContainText('Stock take')

    await change.getByLabel('Day', { exact: true }).fill('2030-01-08')
    await change.getByLabel('From', { exact: true }).fill('09:00')
    await change.getByLabel('Until', { exact: true }).fill('12:00')
    await change.getByRole('button', { name: 'Save day' }).click()
    await expect(days.getByRole('row', { name: /Tue 2030-01-08/ })).toContainText('09:00–12:00 · 3 h')

    // The next Monday to Wednesday closed in one change (D6), then all five copied to the other equipment (D7).
    await change.getByLabel('Day', { exact: true }).fill('2030-01-14')
    await change.getByLabel('Through').fill('2030-01-17')
    // Thursday left out: Monday to Wednesday only.
    await change.getByRole('group', { name: 'Days of the week' }).getByLabel('Thu').uncheck()
    await change.getByLabel('Closed all day').check()
    await change.getByLabel('Note').fill('Line move')
    await change.getByRole('button', { name: 'Save 3 days' }).click()
    for (const day of [/Mon 2030-01-14/, /Tue 2030-01-15/, /Wed 2030-01-16/]) {
      await expect(days.getByRole('row', { name: day })).toContainText('Line move')
    }
    const copy = schedule.getByRole('form', { name: 'Copy day changes' })
    await copy.getByLabel('Copy to').selectOption(other.equipmentId)
    await copy.getByRole('button', { name: 'Copy 5 day changes' }).click()
    await expect(copy.getByRole('status')).toHaveText(`Copied 5 day changes to ${OTHER}.`)
    const copied = await call('GET', `/equipments/${other.equipmentId}/schedule`)
    expect(copied.days.map((day: { date: string }) => day.date))
      .toEqual(['2030-01-08', '2030-01-09', '2030-01-14', '2030-01-15', '2030-01-16'])

    // Back to the calendar for a range at once (D6): Monday to Wednesday of the next week here; the 9th stays closed.
    const clear = schedule.getByRole('form', { name: 'Clear days' })
    await clear.getByLabel('Back to the calendar from').fill('2030-01-14')
    await clear.getByLabel('through', { exact: true }).fill('2030-01-16')
    await clear.getByRole('button', { name: 'Clear these days' }).click()
    await expect(days.getByRole('row', { name: /2030-01-1[456]/ })).toHaveCount(0)
    await expect(days.getByRole('row', { name: /Wed 2030-01-09/ })).toContainText('Closed')

    // Monday 8 h, Tuesday 3 h, Wednesday closed: 11 h for the 10 h needed.
    await page.goto(`/projects/${PROJECT}/runs?view=work-orders`)
    await page.getByRole('row', { name: new RegExp(TITLE) }).getByRole('button', { name: 'Readiness' }).click()
    const readiness = page.locator('[aria-label="Readiness"]')
    await expect(readiness).toContainText('needs 10 h of the 11 h available in the planned window')
  } finally {
    await call('PUT', `/work-orders/${order.workOrderId}/equipment`, { equipmentId: null })
    await call('POST', `/work-orders/${order.workOrderId}/cancel`, {})
    await call('DELETE', `/equipments/${equipment.equipmentId}`)
    await call('DELETE', `/equipments/${other.equipmentId}`)
  }
})
