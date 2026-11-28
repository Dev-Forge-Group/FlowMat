import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * The home page is drawn dark whatever the theme. Its greeting and section headings keep the page's light text in the
 * light theme too, instead of the theme's near-black heading color on the dark page (unreadable before).
 */
for (const colorScheme of ['light', 'dark'] as const) {
  test(`home headings are readable in the ${colorScheme} theme`, async ({ browser }) => {
    const context = await browser.newContext({ colorScheme })
    const page = await context.newPage()
    await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
      const { pathname } = new URL(route.request().url())
      if (await answerAuth(route, pathname)) return
      return ok(route, [])
    })
    await mockedLogin(page)
    await page.goto('/')
    const greeting = page.getByRole('heading', { level: 1 })
    await expect(greeting).toContainText('안녕하세요')
    const colors = await greeting.evaluate((heading) => {
      const page = heading.closest('.home-page') as HTMLElement
      return { heading: getComputedStyle(heading).color, page: getComputedStyle(page).color }
    })
    expect(colors.heading).toBe(colors.page)
    await context.close()
  })
}

/** A project card shows its name and status, not the internal id a first-time user has no use for (it stays in the tooltip). */
test('home project cards do not show the internal id', async ({ page }) => {
  const projects = [{ projectId: 'c12340d9-030c-4a7b-972f-432bb1829155', projectName: '우유 시험', projectStatus: 'active' }]
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const { pathname } = new URL(route.request().url())
    if (await answerAuth(route, pathname)) return
    if (pathname === '/api/projects') return ok(route, projects)
    return ok(route, [])
  })
  await mockedLogin(page)
  const card = page.getByRole('button').filter({ hasText: '우유 시험' })
  await expect(card).toContainText('active', { timeout: 15_000 })
  await expect(card).not.toContainText('c12340d9')
  await expect(card).toHaveAttribute('title', 'c12340d9-030c-4a7b-972f-432bb1829155')
})
