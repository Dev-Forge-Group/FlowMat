import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * A material drawn from stock has no connection in, so a required input blocks publishing (REQUIRED_INPUT_UNCONNECTED).
 * The check offers to take it from stock in one click: the port becomes not required and the check comes back clean.
 */
test('a required input with nothing connected is taken from stock in one click', async ({ page }) => {
  const workflow = { workflowId: 'wf-stock', projectId: 'prj-e2e', workflowName: 'Melon milk', workflowDesc: null,
    workflowType: 'standard', workflowStatus: 'draft' }
  const mix = { processId: 'mix', projectId: 'prj-e2e', workflowId: 'wf-stock', processName: 'Mix', processType: 'process',
    nodeType: 'process', processStatus: 'ready', colorScheme: 'slate', posX: 120, posY: 160, width: 180, height: 80,
    processDesc: null, version: 1, versionNonce: 1 }
  let milk = { processIoId: 'mix-milk', processId: 'mix', itemId: 'milk', ioName: 'Raw milk', direction: 'input', ioType: 'material',
    quantity: 100, unit: 'kg', formula: null, colorScheme: 'sky', requiredYn: 'Y', allowShortageYn: 'N', resourceType: 'material' }
  const out = { ...milk, processIoId: 'mix-out', itemId: null, ioName: 'Mix', direction: 'output', quantity: 112, colorScheme: 'emerald',
    requiredYn: 'N' }
  const updates: unknown[] = []

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'PUT' && pathname === '/api/process-ios/mix-milk') {
      updates.push(request.postDataJSON())
      milk = { ...milk, requiredYn: 'N' }
      return ok(route, milk)
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/workflows/wf-stock/canvas') {
      return ok(route, { workflow, graphSeq: 0, processes: [mix], processIos: [milk, out], connections: [], annotations: [],
        currentUserRole: 'owner' })
    }
    if (pathname === '/api/workflows/wf-stock/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    if (pathname === '/api/workflows/wf-stock/validation') {
      return ok(route, milk.requiredYn === 'Y'
        ? { errors: 1, warnings: 0, issues: [{ severity: 'error', code: 'REQUIRED_INPUT_UNCONNECTED', processId: 'mix',
          ioId: 'mix-milk', connectionId: null, message: 'Required input port mix-milk has no connection.',
          supportedFixes: ['connect an output to input Mix / Raw milk', 'make input Mix / Raw milk optional'] }] }
        : { errors: 0, warnings: 0, issues: [] })
    }
    if (pathname === '/api/workflows') return ok(route, [workflow])
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/workflows/wf-stock')
  const check = page.getByRole('region', { name: 'Workflow validation' })
  await check.getByRole('button', { name: 'Check workflow' }).click({ timeout: 15_000 })
  await expect(check).toContainText('1 errors, 0 warnings')

  await check.getByRole('button', { name: 'Take it from stock (not required)' }).click()
  await expect(check).toContainText('No issues found.')
  expect(updates).toEqual([{ requiredYn: 'N' }])
  await expect(check.getByRole('button', { name: 'Take it from stock (not required)' })).toHaveCount(0)
})
