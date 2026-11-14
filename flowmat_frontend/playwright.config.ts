import { defineConfig, devices } from '@playwright/test'

// CI serves the built app (E2E_WEB_SERVER=preview after `npm run build`). A cold dev server transforms modules on first
// request, and a lazily loaded screen that fails to load mid-test swaps the page for the router's error screen.
// `vite preview` keeps the same /api proxy (preview.proxy defaults to server.proxy).
// E2E_PORT moves the server Playwright starts, so two copies of the repository can run their checks at the same time
// (docs/local-development.md). The backend it proxies to is FLOWMAT_API_TARGET in vite.config.js.
const port = process.env.E2E_PORT ?? '4173'
const origin = `http://127.0.0.1:${port}`
const webServerCommand = process.env.E2E_WEB_SERVER === 'preview'
  ? `npm run preview -- --host 127.0.0.1 --port ${port} --strictPort`
  : `npm run dev -- --host 127.0.0.1 --port ${port}`

export default defineConfig({
  testDir: './e2e',
  testIgnore: [
    ...(process.env.STAGING_OAUTH_CALLBACK_URL ? [] : ['**/staging-oauth.spec.ts']),
    ...(process.env.REAL_API_E2E ? [] : ['**/backend-contract.spec.ts', '**/bom-lot-flow.spec.ts', '**/lot-genealogy.spec.ts', '**/inventory-reports.spec.ts']),
  ],
  fullyParallel: true,
  // Real-API specs all log in as demo-owner against one backend; concurrent logins of the same user currently knock
  // each other out (401), so run them one at a time.
  ...(process.env.REAL_API_E2E ? { workers: 1 } : {}),
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? 'line' : 'list',
  use: {
    baseURL: process.env.BASE_URL ?? origin,
    trace: 'retain-on-failure',
  },
  ...(process.env.BASE_URL
    ? {}
    : {
        webServer: {
          command: webServerCommand,
          url: origin,
          reuseExistingServer: !process.env.CI,
        },
      }),
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
})
