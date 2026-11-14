import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { uniqueSuffix } from './support/uniqueSuffix'

test.skip(!process.env.REAL_API_E2E, 'Needs the real backend and demo seed')
// Downtime is typed as local time and shifts are read in the planning zone (Asia/Seoul by default).
test.use({ timezoneId: 'Asia/Seoul' })

/**
 * Equipment calendar and downtime through the real UI (docs/domain/equipment-schedule.md): a shift and a day of downtime
 * are set on the equipment tab, and a work order put on that equipment shows in its readiness whether the equipment has
 * the hours the order needs. The equipment is deleted and the order cancelled at the end.
 */
const PROJECT = 'prj_demo_main'
const suffix = uniqueSuffix()
const CODE = `EQ-E2E-${suffix}`
const NAME = `schedule e2e ${suffix}`
const TITLE = `Plan ${suffix}`

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

test('a shift and downtime decide whether the equipment has the hours a work order needs', async ({ page, request }) => {
  const call = await api(request)
  const equipment = await call('POST', '/equipments', {
    projectId: PROJECT, equipmentCode: CODE, equipmentName: NAME, equipmentType: 'oven', details: { capacityPerHour: 10 },
  })
  // 100 at 10 an hour needs 10 h; Monday 09:00 to Tuesday 17:00 holds two 8 h shifts.
  const order = await call('POST', '/work-orders', {
    projectId: PROJECT, workOrderTitle: TITLE, targetQuantity: 100,
    plannedStartAt: '2030-01-07T09:00:00+09:00', plannedEndAt: '2030-01-08T17:00:00+09:00',
  })
  try {
    await login(page)
    await page.goto(`/projects/${PROJECT}/inventory?tab=equipment`)
    await page.getByRole('row', { name: new RegExp(CODE) }).getByRole('button', { name: 'Schedule' }).click()
    const schedule = page.getByRole('region', { name: `Schedule of ${CODE}` })
    await expect(schedule.getByRole('status')).toContainText('No calendar: available around the clock.')

    await schedule.getByLabel('Shift start').fill('09:00')
    await schedule.getByLabel('Shift end').fill('17:00')
    await schedule.getByRole('button', { name: 'Save calendar' }).click()
    await expect(schedule.getByRole('status')).toContainText('09:00–17:00 · Mon–Fri · 8 h a shift')

    await schedule.getByLabel('Type').selectOption('breakdown')
    await schedule.getByLabel('Starts').fill('2030-01-08T00:00')
    await schedule.getByLabel('Ends').fill('2030-01-09T00:00')
    await schedule.getByLabel('Reason').fill('Burner fault')
    await schedule.getByRole('button', { name: 'Add downtime' }).click()
    const downtimes = schedule.getByRole('table', { name: 'Downtime list' })
    await expect(downtimes).toContainText('breakdown')
    await expect(downtimes).toContainText('24 h')

    await page.goto(`/projects/${PROJECT}/runs?view=work-orders`)
    const orderRow = page.getByRole('row', { name: new RegExp(TITLE) })
    await orderRow.getByRole('button', { name: 'Readiness' }).click()
    await page.getByRole('combobox', { name: 'Equipment' }).selectOption({ label: `${CODE} · ${NAME}` })
    await expect(orderRow).toContainText(`on ${CODE}`)
    const readiness = page.locator('[aria-label="Readiness"]')
    await expect(readiness).toContainText('needs 10 h for 100 but has only 8 h available in the planned window (8 h down)')

    // Without the downtime both shifts are free.
    const saved = await call('GET', `/equipments/${equipment.equipmentId}/schedule`)
    await call('DELETE', `/equipments/${equipment.equipmentId}/downtimes/${saved.downtimes[0].downtimeId}`)
    await readiness.getByRole('button', { name: 'Check again' }).click()
    await expect(readiness).toContainText('needs 10 h of the 16 h available in the planned window')

    // A second, evening shift (docs/domain/equipment-schedule.md "교대"): overlaps are refused, touching shifts are fine.
    await page.goto(`/projects/${PROJECT}/inventory?tab=equipment`)
    await page.getByRole('row', { name: new RegExp(CODE) }).getByRole('button', { name: 'Schedule' }).click()
    const shifts = page.getByRole('region', { name: `Schedule of ${CODE}` })
    await shifts.getByRole('button', { name: 'Add shift' }).click()
    await expect(shifts.getByLabel('Shift 2 start')).toHaveValue('17:00')
    await shifts.getByLabel('Shift 2 start').fill('16:00')
    await shifts.getByRole('button', { name: 'Save calendar' }).click()
    await expect(shifts.getByRole('alert')).toContainText('Shifts 1 and 2 overlap on Mon.')
    await shifts.getByLabel('Shift 2 start').fill('17:00')
    await shifts.getByRole('button', { name: 'Save calendar' }).click()
    await expect(shifts.getByRole('status'))
      .toContainText('09:00–17:00 · Mon–Fri · 8 h a shift; 17:00–01:00 (ends next day) · Mon–Fri · 8 h a shift · 80 h a week')

    // Monday 09:00 to Tuesday 17:00 now holds Monday's two shifts and Tuesday's day shift.
    await page.goto(`/projects/${PROJECT}/runs?view=work-orders`)
    await page.getByRole('row', { name: new RegExp(TITLE) }).getByRole('button', { name: 'Readiness' }).click()
    await expect(page.locator('[aria-label="Readiness"]')).toContainText('needs 10 h of the 24 h available in the planned window')

    // Status history (equipment.md): a change to maintenance with a note is kept and shown when editing.
    await page.goto(`/projects/${PROJECT}/inventory?tab=equipment`)
    await page.getByRole('row', { name: new RegExp(CODE) }).getByRole('button', { name: 'Edit' }).click()
    await page.getByLabel('Status', { exact: true }).selectOption('maintenance')
    await page.getByLabel('Status note').fill('Bearing replaced')
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(page.getByRole('row', { name: new RegExp(CODE) })).toContainText('maintenance')
    await page.getByRole('row', { name: new RegExp(CODE) }).getByRole('button', { name: 'Edit' }).click()
    await expect(page.getByLabel('Status history')).toContainText('active → maintenance · Bearing replaced')
    await expect(page.getByLabel('Status history')).toContainText('added as active')
  } finally {
    await call('PUT', `/work-orders/${order.workOrderId}/equipment`, { equipmentId: null })
    await call('POST', `/work-orders/${order.workOrderId}/cancel`, {})
    await call('DELETE', `/equipments/${equipment.equipmentId}`)
  }
})
