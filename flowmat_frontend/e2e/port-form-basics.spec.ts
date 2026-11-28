import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * A new port starts with every field, because a port without an item is described by its resource type and schema. Once an
 * item is chosen, the port needs only direction, item, name, quantity, unit and whether it must be connected: the rest fold
 * under "More settings", which still opens on demand.
 */
test('choosing an item folds the less common port fields away', async ({ page }) => {
  const workflow = { workflowId: 'wf-ports', projectId: 'prj-e2e', workflowName: 'Melon milk', workflowDesc: null,
    workflowType: 'standard', workflowStatus: 'draft' }
  const mix = { processId: 'mix', projectId: 'prj-e2e', workflowId: 'wf-ports', processName: 'Mix', processType: 'process',
    nodeType: 'process', processStatus: 'ready', colorScheme: 'slate', posX: 120, posY: 160, width: 180, height: 80,
    processDesc: null, version: 1, versionNonce: 1 }
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') throw new Error(`Unexpected write ${request.method()} ${pathname}`)
    if (pathname === '/api/workflows/wf-ports/canvas') {
      return ok(route, { workflow, graphSeq: 0, processes: [mix], processIos: [], connections: [], annotations: [],
        currentUserRole: 'owner' })
    }
    if (pathname === '/api/workflows/wf-ports/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    if (pathname === '/api/items') {
      return ok(route, [{ itemId: 'milk', projectId: 'prj-e2e', itemCode: 'MILK', itemName: 'Raw milk', itemType: 'raw',
        unitId: 'unit_kg', itemStatus: 'active' }])
    }
    if (pathname === '/api/units') return ok(route, [{ unitId: 'unit_kg', unitCode: 'kg', unitName: 'Kilogram', activeYn: 'Y' }])
    if (pathname === '/api/workflows') return ok(route, [workflow])
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/workflows/wf-ports')
  await page.locator('.react-flow__node[data-id="mix"]').click({ position: { x: 24, y: 18 }, timeout: 15_000 })
  // The inspector's buttons are drawn as buttons (the base layer strips their chrome).
  await expect(page.getByRole('button', { name: 'Add Input' })).toHaveCSS('border-top-width', '1px')
  await page.getByRole('button', { name: 'Add Input' }).click()
  const rule = page.getByRole('textbox', { name: 'Validation rule' })
  await expect(rule).toBeVisible()

  const field = (label: string) => page.locator('label').filter({ has: page.locator('span', { hasText: new RegExp(`^${label}$`) }) })
  await field('Item').locator('select').selectOption('milk')
  await expect(field('Port Name').locator('input')).toHaveValue('Raw milk')
  await expect(field('Unit').locator('input')).toHaveValue('kg')
  await expect(field('Required').locator('select')).toBeVisible()
  await expect(rule).toBeHidden()
  await expect(page.getByRole('button', { name: 'Create Port' })).toHaveCSS('border-top-width', '1px')

  await page.getByText('More settings', { exact: true }).click()
  await expect(rule).toBeVisible()
  await expect(field('Allow Shortage').locator('select')).toBeVisible()
})
