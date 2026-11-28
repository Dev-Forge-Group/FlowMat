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
