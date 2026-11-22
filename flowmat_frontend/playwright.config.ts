import { readFileSync } from 'node:fs'
import { defineConfig, devices, type ReporterDescription } from '@playwright/test'

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

// CI leaves out spec files that already passed against the same code (E2E_SKIP_FILE, from
// e2e/support/already-passed/select.mjs plan) and records this run for the next one (E2E_PASSED_LOG).
// docs/operations/ci-selection.md says what counts as the same code.
function alreadyPassed(): string[] {
  const file = process.env.E2E_SKIP_FILE
  if (!file) return []
  try {
    return (JSON.parse(readFileSync(file, 'utf8')).skip as string[]).map((spec) => `**/${spec}`)
  } catch {
    return []
  }
}
const recorder: ReporterDescription[] = process.env.E2E_PASSED_LOG ? [['./e2e/support/already-passed/reporter.ts']] : []

export default defineConfig({
  testDir: './e2e',
  testIgnore: [
    ...(process.env.STAGING_OAUTH_CALLBACK_URL ? [] : ['**/staging-oauth.spec.ts']),
    ...(process.env.REAL_API_E2E ? [] : ['**/backend-contract.spec.ts', '**/bom-lot-flow.spec.ts', '**/lot-genealogy.spec.ts', '**/inventory-reports.spec.ts']),
    ...alreadyPassed(),
  ],
  // When CI records which spec files passed (E2E_FILE_SHARDS), a shard takes whole files, so it sees all of a file's tests.
  fullyParallel: !process.env.E2E_FILE_SHARDS,
  // Real-API specs all log in as demo-owner against one backend; concurrent logins of the same user currently knock
  // each other out (401), so run them one at a time.
  ...(process.env.REAL_API_E2E ? { workers: 1 } : {}),
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? [['line'], ...recorder] : [['list'], ...recorder],
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
