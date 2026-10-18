import { expect, test } from '@playwright/test'
import { disposableProject } from './support/disposableProject'

test.skip(process.env.REAL_API_E2E !== '1' || process.env.FLOWMAT_FEATURE_E2E_DISPOSABLE !== '1',
  'Requires an explicitly disposable backend database')
test.use({ timezoneId: 'Asia/Seoul', trace: 'off' })

type Schedule = { scheduleId: string; effectiveFrom: string | null; current: boolean }
type Calendar = { weeklyHours: number; shifts: { shiftStart: string; shiftEnd: string }[]; schedules: Schedule[] }

test('real schedule versions preserve earlier hours when future shifts are saved, edited and deleted', async ({ page }) => {
  test.setTimeout(150_000)
  const fixture = await disposableProject(page, 'Schedule-history')
  const { projectId, api, request } = fixture
  const base = `/projects/${projectId}/workers/demo-owner`
  const calendar = page.getByRole('region', { name: 'Workers', exact: true })
    .getByRole('group', { name: 'Worker demo-owner', exact: true })
  const versions = calendar.getByRole('group', { name: 'Shift versions', exact: true })
  const load = () => api<Calendar>('GET', base)
  const open = async () => {
    await page.goto(`/projects/${projectId}/settings`)
    await calendar.locator('summary').click()
    await expect(calendar.getByRole('button', { name: 'Save shifts', exact: true })).toBeVisible()
  }
  const save = async () => {
    const response = page.waitForResponse((one) => one.request().method() === 'PUT'
      && new URL(one.url()).pathname === `/api${base}/shifts`)
    await calendar.getByRole('button', { name: 'Save shifts', exact: true }).click()
    expect((await response).status()).toBe(200)
  }
  const hours = async (monday: string) => {
    const from = new Date(`${monday}T00:00:00+09:00`)
    const to = new Date(from.getTime() + 7 * 86_400_000)
    return (await api<{ workingHours: number }>('GET', `${base}/availability?${new URLSearchParams({
      from: from.toISOString(), to: to.toISOString(),
    })}`)).workingHours
  }
  let completed = false
  try {
    const zone = await api<{ version: number; timeZone: string }>('GET', `/projects/${projectId}/time-zone`)
    if (zone.timeZone !== 'Asia/Seoul') {
      await api('PUT', `/projects/${projectId}/time-zone`, { timeZone: 'Asia/Seoul', expectedVersion: zone.version })
    }
    await open()
    await calendar.getByRole('button', { name: 'Add shift', exact: true }).click()
    await calendar.getByLabel('Shift 1 start', { exact: true }).fill('09:00')
    await calendar.getByLabel('Shift 1 end', { exact: true }).fill('17:00')
    await save()
    await expect(calendar.getByRole('status')).toHaveText('Shifts saved: 40 h a week.')
    const initial = await load()
    expect(initial.schedules).toHaveLength(1)
    expect(initial.schedules[0].current).toBe(true)
    const originalShifts = initial.shifts

    await test.step('a future version leaves the preceding week and current shifts alone', async () => {
      await calendar.getByLabel('Shifts start on', { exact: true }).fill('2041-09-09')
      await calendar.getByLabel('Shift 1 start', { exact: true }).fill('14:00')
      await calendar.getByLabel('Shift 1 end', { exact: true }).fill('20:00')
      await save()
      await expect(versions.getByRole('listitem')).toHaveCount(2)
      await expect(calendar.getByRole('status')).toHaveText('Shifts saved from 2041-09-09; until then the current shifts stay.')
      expect((await load()).shifts).toEqual(originalShifts)
      expect(await hours('2041-09-02')).toBe(40)
      expect(await hours('2041-09-09')).toBe(30)
    })

    const firstFuture = (await load()).schedules.find((line) => line.effectiveFrom === '2041-09-09')!
    await test.step('editing after reload replaces the same dated version', async () => {
      await open()
      await expect(calendar.getByLabel('Shift 1 start', { exact: true })).toHaveValue('09:00')
      await versions.getByRole('button', { name: 'Edit version from 2041-09-09', exact: true }).click()
      await expect(calendar.getByLabel('Shift 1 start', { exact: true })).toHaveValue('14:00')
      await expect(calendar.getByLabel('Shifts start on', { exact: true })).toHaveValue('2041-09-09')
      await calendar.getByLabel('Shift 1 end', { exact: true }).fill('21:00')
      await save()
      const stored = await load()
      expect(stored.schedules).toHaveLength(2)
      expect(stored.schedules.find((line) => line.effectiveFrom === '2041-09-09')?.scheduleId).toBe(firstFuture.scheduleId)
      expect(await hours('2041-09-02')).toBe(40)
      expect(await hours('2041-09-09')).toBe(35)
    })

    await test.step('removing one of two future versions preserves the other', async () => {
      await calendar.getByLabel('Shifts start on', { exact: true }).fill('2041-09-16')
      await calendar.getByLabel('Shift 1 end', { exact: true }).fill('19:00')
      await save()
      await expect(versions.getByRole('listitem')).toHaveCount(3)
      expect(await hours('2041-09-16')).toBe(25)
      const secondFuture = (await load()).schedules.find((line) => line.effectiveFrom === '2041-09-16')!
      await open()
      const remove = page.waitForResponse((one) => one.request().method() === 'DELETE'
        && new URL(one.url()).pathname === `/api${base}/schedules/${firstFuture.scheduleId}`)
      await versions.getByRole('button', { name: 'Delete version from 2041-09-09', exact: true }).click()
      expect((await remove).status()).toBe(200)
      await expect(versions.getByRole('listitem')).toHaveCount(2)
      expect((await load()).schedules.map((line) => line.scheduleId)).toEqual([initial.schedules[0].scheduleId, secondFuture.scheduleId])
      expect(await hours('2041-09-09')).toBe(40)
      expect(await hours('2041-09-16')).toBe(25)
      expect((await request('GET', `${base}/schedules/${firstFuture.scheduleId}`)).status()).toBe(404)
      await versions.getByRole('button', { name: 'Delete version from 2041-09-16', exact: true }).click()
      await expect(versions).toHaveCount(0)
      expect(await hours('2041-09-16')).toBe(40)
    })

    const beforeInvalid = await load()
    expect((await request('DELETE', `${base}/schedules/${initial.schedules[0].scheduleId}`)).status()).toBe(409)
    expect((await request('PUT', `${base}/shifts`, { shifts: [], effectiveFrom: '1900-01-01' })).status()).toBe(400)
    expect(await load()).toEqual(beforeInvalid)
    expect(await api('GET', `/boms?projectId=${projectId}`)).toEqual([])
    expect(await api('GET', `/work-orders?projectId=${projectId}`)).toEqual([])
    completed = true
  } finally {
    await request('PUT', `${base}/shifts`, { shifts: [] }).catch(() => undefined)
    await fixture.dispose(completed)
  }
})
