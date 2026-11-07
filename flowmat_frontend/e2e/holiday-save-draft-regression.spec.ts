import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

for (const changed of [false, true]) {
  test(`holiday save preserves newer input: ${changed}`, async ({ page }) => {
    const projectId = 'prj-holiday-draft'
    const holidays: Record<string, unknown>[] = []
    const commands: Record<string, unknown>[] = []
    let release!: () => void
    const gate = new Promise<void>((resolve) => { release = resolve })
    await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
      const request = route.request()
      const { pathname } = new URL(request.url())
      if (await answerAuth(route, pathname)) return
      if (pathname === '/api/holidays' && request.method() === 'POST') {
        const body = request.postDataJSON() as Record<string, unknown>
        commands.push(body)
        await gate
        holidays.push({ ...body, holidayId: 'saved-holiday' })
        return ok(route, holidays)
      }
      if (request.method() !== 'GET') return route.fulfill({ status: 400, body: 'Unexpected write' })
      if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Holidays', ownerId: 'demo-owner' }])
      if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Holidays', ownerId: 'demo-owner' })
      if (pathname === '/api/holidays') return ok(route, holidays)
      return ok(route, [])
    })
    await mockedLogin(page)
    await page.goto(`/projects/${projectId}/inventory?tab=equipment`)
    const panel = page.locator('details[aria-label="Holidays"]')
    await panel.locator(':scope > summary').click()
    const date = panel.getByLabel('Date', { exact: true })
    const name = panel.getByLabel('Name', { exact: true })
    await date.fill('2032-03-03')
    await name.fill('Saved holiday')
    await panel.getByRole('button', { name: 'Add holiday' }).click()
    try {
      await expect.poll(() => commands.length).toBe(1)
      if (changed) {
        await date.fill('2032-04-04')
        await name.fill('Next holiday')
      }
    } finally { release() }
    await expect(panel.getByRole('cell', { name: 'Saved holiday', exact: true })).toBeVisible()
    await expect(date).toHaveValue(changed ? '2032-04-04' : '')
    await expect(name).toHaveValue(changed ? 'Next holiday' : '')
    expect(commands).toHaveLength(1)
    expect(commands[0]).toMatchObject({ date: '2032-03-03', name: 'Saved holiday' })
  })
}
