import { expect, test, type APIResponse } from '@playwright/test'

test.skip(process.env.REAL_API_E2E !== '1' || process.env.FLOWMAT_ROTA_E2E_DISPOSABLE !== '1',
  'Requires an explicitly disposable backend database')
test.use({ timezoneId: 'Asia/Seoul', trace: 'off' })

type Calendar = { rota: { weeks: number; startsOn: string; currentWeek: number } | null; weeklyHours: number;
  shifts: { shiftStart: string; shiftEnd: string; workDays: number[]; rotaWeek: number }[] }

async function data<T>(response: APIResponse): Promise<T> {
  expect(response.ok(), `API returned ${response.status()}`).toBe(true)
  const envelope = await response.json()
  expect(envelope.success).toBe(true)
  return envelope.data as T
}

test('real rotating shifts survive reload and drive overnight availability and date overrides', async ({ page }) => {
  test.setTimeout(120_000)
  let projectId = ''
  let token = ''
  let completed = false
  const headers = () => ({ Authorization: `Bearer ${token}` })
  const get = async <T>(path: string) => data<T>(await page.request.get(`/api${path}`, { headers: headers() }))
  try {
    await page.goto('/')
    await page.locator('input').nth(0).fill('demo-owner')
    await page.locator('input[type="password"]').fill('demo1234')
    const login = page.waitForResponse((response) => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/auth/login')
    await page.getByRole('button', { name: 'Log in', exact: true }).click()
    const loggedIn = await login
    expect(loggedIn.ok()).toBe(true)
    token = (await loggedIn.json()).data.accessToken
    await expect(page.getByRole('button', { name: 'Logout', exact: true })).toBeVisible()
    projectId = (await data<{ projectId: string }>(await page.request.post('/api/projects', {
      headers: headers(), data: { projectName: `Rota-E2E-${Date.now()}`, ownerId: 'demo-owner' },
    }))).projectId
    const worker = `/projects/${projectId}/workers/demo-owner`
    const panel = page.getByRole('region', { name: 'Workers', exact: true })
    const calendar = panel.getByRole('group', { name: 'Worker demo-owner', exact: true })
    const rota = calendar.getByRole('group', { name: 'Rota', exact: true })
    const openCalendar = async () => {
      await page.goto(`/projects/${projectId}/settings`)
      await calendar.locator(':scope > summary').click()
      await expect(calendar.getByRole('button', { name: 'Add shift', exact: true })).toBeVisible()
    }
    const hours = async (monday: string) => {
      const start = new Date(`${monday}T00:00:00+09:00`)
      const end = new Date(start.getTime() + 7 * 86_400_000)
      const parameters = new URLSearchParams({ from: start.toISOString(), to: end.toISOString() })
      return (await get<{ workingHours: number }>(`${worker}/availability?${parameters}`)).workingHours
    }
    await test.step('the real editor saves early shifts, night shifts and a Sunday crossing the rota boundary', async () => {
      await openCalendar()
      await rota.getByLabel('Rota weeks', { exact: true }).selectOption('2')
      await rota.getByLabel('Rota starts', { exact: true }).fill('2041-09-04')
      await calendar.getByRole('button', { name: 'Add shift', exact: true }).click()
      await calendar.getByLabel('Shift 1 start', { exact: true }).fill('06:00')
      await calendar.getByLabel('Shift 1 end', { exact: true }).fill('14:00')
      await calendar.getByRole('button', { name: 'Add shift', exact: true }).click()
      await calendar.getByLabel('Shift 2 week', { exact: true }).selectOption('2')
      await calendar.getByLabel('Shift 2 start', { exact: true }).fill('22:00')
      await calendar.getByLabel('Shift 2 end', { exact: true }).fill('06:00')
      await calendar.getByLabel('Shift 2 Fri', { exact: true }).uncheck()
      await calendar.getByRole('button', { name: 'Add shift', exact: true }).click()
      await calendar.getByLabel('Shift 3 week', { exact: true }).selectOption('2')
      await calendar.getByLabel('Shift 3 start', { exact: true }).fill('22:00')
      await calendar.getByLabel('Shift 3 end', { exact: true }).fill('06:00')
      for (const day of ['Mon', 'Tue', 'Wed', 'Thu', 'Fri']) await calendar.getByLabel(`Shift 3 ${day}`, { exact: true }).uncheck()
      await calendar.getByLabel('Shift 3 Sun', { exact: true }).check()
      const saved = page.waitForResponse((response) => response.request().method() === 'PUT'
        && new URL(response.url()).pathname === `/api${worker}/shifts`)
      await calendar.getByRole('button', { name: 'Save shifts', exact: true }).click()
      expect((await saved).status()).toBe(200)
      await expect(calendar.getByRole('status')).toHaveText('Shifts saved: 40 h a week on average.')
      const stored = await get<Calendar>(worker)
      expect(stored.rota).toMatchObject({ weeks: 2, startsOn: '2041-09-02' })
      expect([1, 2]).toContain(stored.rota!.currentWeek)
      expect(stored.weeklyHours).toBe(40)
      expect(stored.shifts.map((shift) => shift.rotaWeek)).toEqual([1, 2, 2])
      expect(await hours('2041-09-02')).toBe(46)
      expect(await hours('2041-09-09')).toBe(34)
      expect(await hours('2041-09-16')).toBe(46)
      expect(await hours('2041-08-26')).toBe(34)
    })
    await test.step('reload uses the normalized start week and stored per-shift weeks', async () => {
      await openCalendar()
      await expect(rota.getByLabel('Rota weeks', { exact: true })).toHaveValue('2')
      await expect(rota.getByLabel('Rota starts', { exact: true })).toHaveValue('2041-09-02')
      await expect(calendar.getByLabel('Shift 1 week', { exact: true })).toHaveValue('1')
      await expect(calendar.getByLabel('Shift 2 week', { exact: true })).toHaveValue('2')
      await expect(calendar.getByLabel('Shift 3 week', { exact: true })).toHaveValue('2')
      await expect(rota).toContainText('2-week rota from 2041-09-02')
    })
    await test.step('a date override wins over a rotating night shift, and removing it restores the rota', async () => {
      await data(await page.request.put(`/api${worker}/days/2041-09-09`, { headers: headers(), data: { shifts: [] } }))
      expect(await hours('2041-09-09')).toBe(26)
      await openCalendar()
      const changes = calendar.getByRole('group', { name: 'Day changes', exact: true })
      await expect(changes).toContainText('2041-09-09')
      await expect(changes).toContainText('Off')
      await data(await page.request.delete(`/api${worker}/days/2041-09-09`, { headers: headers() }))
      expect(await hours('2041-09-09')).toBe(34)
    })
    await test.step('fractional weeks are rejected without changing the saved rota', async () => {
      const before = await get<Calendar>(worker)
      for (const body of [
        { shifts: before.shifts, rota: { weeks: 2.5, startsOn: '2041-09-02' } },
        { shifts: [{ ...before.shifts[0], rotaWeek: 1.5 }], rota: { weeks: 2, startsOn: '2041-09-02' } },
      ]) {
        const response = await page.request.put(`/api${worker}/shifts`, { headers: headers(), data: body })
        expect(response.status()).toBe(400)
      }
      expect(await get<Calendar>(worker)).toEqual(before)
      expect(await hours('2041-09-09')).toBe(34)
    })
    await test.step('the editor refuses a week outside the chosen rota before sending it', async () => {
      await openCalendar()
      await rota.getByLabel('Rota weeks', { exact: true }).selectOption('3')
      await calendar.getByLabel('Shift 3 week', { exact: true }).selectOption('3')
      await rota.getByLabel('Rota weeks', { exact: true }).selectOption('2')
      let updates = 0
      const observe = (request: import('@playwright/test').Request) => {
        if (request.method() === 'PUT' && new URL(request.url()).pathname === `/api${worker}/shifts`) updates++
      }
      page.on('request', observe)
      try {
        await calendar.getByRole('button', { name: 'Save shifts', exact: true }).click()
        await expect(calendar.getByRole('alert')).toHaveText('Shift 3 is in week 3, but the rota has 2 weeks.')
        expect(updates).toBe(0)
      } finally { page.off('request', observe) }
      expect((await get<Calendar>(worker)).rota?.weeks).toBe(2)
    })
    await test.step('returning to weekly shifts removes the rota and keeps each shift', async () => {
      await openCalendar()
      await rota.getByLabel('Rota weeks', { exact: true }).selectOption('1')
      await expect(calendar.getByLabel('Shift 1 week', { exact: true })).toHaveCount(0)
      await calendar.getByRole('button', { name: 'Save shifts', exact: true }).click()
      await expect(calendar.getByRole('status')).toHaveText('Shifts saved: 80 h a week.')
      const weekly = await get<Calendar>(worker)
      expect(weekly.rota).toBeNull()
      expect(weekly.shifts).toHaveLength(3)
      expect(weekly.shifts.map((shift) => shift.rotaWeek)).toEqual([1, 1, 1])
      expect(await hours('2041-09-09')).toBe(80)
      expect(await get<unknown[]>(`/boms?projectId=${projectId}`)).toEqual([])
      expect(await get<unknown[]>(`/work-orders?projectId=${projectId}`)).toEqual([])
    })
    completed = true
  } finally {
    if (projectId && token) {
      try {
        const removed = await page.request.delete(`/api/projects/${projectId}`, { headers: headers() })
        if (completed) expect(removed.ok(), `Project cleanup returned ${removed.status()}`).toBe(true)
        else if (!removed.ok()) test.info().annotations.push({ type: 'cleanup', description: `Project cleanup returned ${removed.status()}` })
      } catch (error) {
        // eslint-disable-next-line no-unsafe-finally -- The primary test succeeded before completed became true.
        if (completed) throw error
        test.info().annotations.push({ type: 'cleanup', description: 'Project cleanup request failed after the primary failure' })
      }
    }
  }
})
