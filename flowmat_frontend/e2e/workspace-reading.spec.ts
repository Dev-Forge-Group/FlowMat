import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * Reading the process canvas against a mocked API: a process's upstream and downstream, the route between two processes,
 * finding a process by name and the reading guide. Reading is view only and lives in the address hash, so a reload or a
 * copied link opens the same view. Mix → Bake → Cool, Bake → Pack, and Wash on its own.
 */
const workflow = { workflowId: 'wf-read', projectId: 'prj-e2e', workflowName: 'Bakery line', workflowDesc: null,
  workflowType: 'standard', workflowStatus: 'draft' }
const node = (processId: string, processName: string, posX: number, posY: number) => ({ processId, projectId: 'prj-e2e',
  workflowId: 'wf-read', processName, processType: 'process', nodeType: 'process', processStatus: 'ready', colorScheme: 'slate',
  posX, posY, width: 150, height: 72, processDesc: null, version: 1, versionNonce: 1 })
const connection = (connectionId: string, fromProcessId: string, toProcessId: string) => ({ connectionId, projectId: 'prj-e2e',
  workflowId: 'wf-read', fromProcessId, toProcessId, fromIoId: null, toIoId: null, sourceHandle: 'out-default',
  targetHandle: 'in-default', connectionType: 'material_flow', connectionLabel: null, flowRate: null, unit: null, delayTimeSec: 0,
  lossRate: 0, priority: 1, conditionExpr: null, capacity: null, failurePolicy: 'stop', defaultRoute: false, splitKind: null,
  splitValue: null, version: 1, versionNonce: 1 })

async function openBakery(page: Page, hash = '') {
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/workflows/wf-read/canvas') {
      return ok(route, { workflow, graphSeq: 0, processes: [node('proc-mix', 'Mix', 80, 120), node('proc-bake', 'Bake', 380, 120),
        node('proc-cool', 'Cool', 680, 40), node('proc-pack', 'Pack', 680, 240), node('proc-wash', 'Wash', 80, 360)], processIos: [],
        connections: [connection('conn-mb', 'proc-mix', 'proc-bake'), connection('conn-bc', 'proc-bake', 'proc-cool'),
          connection('conn-bp', 'proc-bake', 'proc-pack')], annotations: [], currentUserRole: 'owner' })
    }
    if (pathname === '/api/workflows/wf-read/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    if (pathname === '/api/workflows') return ok(route, [workflow])
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/prj-e2e/workflows/wf-read${hash}`)
  await expect(page.locator('.react-flow__edge')).toHaveCount(3, { timeout: 15_000 })
}

const processNode = (page: Page, id: string) => page.locator(`.react-flow__node[data-id="${id}"]`)
const connectionEdge = (page: Page, id: string) => page.locator(`.react-flow__edge[data-id="${id}"]`)
const hashOf = (page: Page) => new URL(page.url()).hash

test('a process reads downstream, keeps it over a reload and lets Esc stop reading', async ({ page }) => {
  await openBakery(page)
  const chip = page.getByRole('region', { name: 'Canvas reading' })
  await expect(chip).toContainText('Select a process to follow its connections.')

  await processNode(page, 'proc-bake').click({ position: { x: 24, y: 18 } })
  await expect(chip.getByRole('button', { name: 'Upstream 1' })).toBeEnabled()
  await chip.getByRole('button', { name: 'Downstream 2' }).click()
  await expect(chip.getByRole('status')).toHaveText('Downstream of Bake: 2 processes, 2 connections, 1 step')
  await expect(chip.getByRole('button', { name: 'Downstream 2' })).toHaveAttribute('aria-pressed', 'true')
  expect(hashOf(page)).toBe('#focus=proc-bake&reach=downstream')
  await expect(processNode(page, 'proc-bake')).toHaveClass(/reading-origin/)
  await expect(processNode(page, 'proc-cool')).toHaveClass(/reading-downstream/)
  await expect(processNode(page, 'proc-pack')).toHaveClass(/reading-downstream/)
  await expect(processNode(page, 'proc-mix')).toHaveClass(/reading-dim/)
  await expect(processNode(page, 'proc-wash')).toHaveClass(/reading-dim/)
  await expect(connectionEdge(page, 'conn-bc')).toHaveClass(/reading-on/)
  await expect(connectionEdge(page, 'conn-mb')).toHaveClass(/reading-dim/)

  // The link is the reading: a reload shows the same view.
  await page.reload()
  await expect(chip.getByRole('status')).toHaveText('Downstream of Bake: 2 processes, 2 connections, 1 step', { timeout: 15_000 })
  await expect(processNode(page, 'proc-mix')).toHaveClass(/reading-dim/)

  // Esc first leaves the tool the page opens with (adding processes), as before; the next Esc stops reading.
  await page.keyboard.press('Escape')
  await expect(chip.getByRole('status')).toHaveText('Downstream of Bake: 2 processes, 2 connections, 1 step')
  await page.keyboard.press('Escape')
  await expect(chip.getByRole('status')).toHaveCount(0)
  expect(hashOf(page)).toBe('')
  await expect(page.locator('.reading-dim')).toHaveCount(0)
})

test('a route runs along the connections and says when there is none', async ({ page }) => {
  await openBakery(page)
  const chip = page.getByRole('region', { name: 'Canvas reading' })

  await processNode(page, 'proc-mix').click({ position: { x: 24, y: 18 } })
  await chip.getByRole('button', { name: 'Route from here' }).click()
  await expect(chip.getByRole('status')).toHaveText('Route from Mix: click the process to end at.')
  await processNode(page, 'proc-cool').click({ position: { x: 24, y: 18 } })
  await expect(chip.getByRole('status')).toHaveText('Route Mix → Bake → Cool: 2 connections')
  expect(hashOf(page)).toBe('#route=proc-mix~proc-cool')
  await expect(connectionEdge(page, 'conn-mb')).toHaveClass(/reading-route/)
  await expect(connectionEdge(page, 'conn-bc')).toHaveClass(/reading-route/)
  await expect(connectionEdge(page, 'conn-bp')).toHaveClass(/reading-dim/)
  await expect(processNode(page, 'proc-pack')).toHaveClass(/reading-dim/)

  // Against the direction of the connections there is no way back.
  await chip.getByRole('button', { name: 'Route from here' }).click()
  await processNode(page, 'proc-mix').click({ position: { x: 24, y: 18 } })
  await expect(chip.getByRole('status')).toHaveText('No route from Cool to Mix along the connections.')
  await expect(page.locator('.reading-dim')).toHaveCount(0)
})

test('"/" finds a process and "?" opens the guide', async ({ page }) => {
  await openBakery(page)
  const chip = page.getByRole('region', { name: 'Canvas reading' })

  await page.keyboard.press('/')
  const finder = page.getByRole('dialog', { name: 'Find a process' })
  await finder.getByRole('combobox', { name: 'Process name, type or id' }).fill('was')
  await expect(finder.getByRole('option')).toHaveText([/^Wash/])
  await page.keyboard.press('Enter')
  await expect(finder).toHaveCount(0)
  expect(hashOf(page)).toBe('#focus=proc-wash')
  await expect(chip.getByRole('status')).toHaveText('Wash: 0 connected processes, 0 connections')
  await expect(processNode(page, 'proc-wash')).toHaveClass(/reading-origin/)
  await expect(processNode(page, 'proc-mix')).toHaveClass(/reading-dim/)

  // Esc in the finder only closes it.
  await page.keyboard.press('/')
  await expect(finder).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(finder).toHaveCount(0)
  expect(hashOf(page)).toBe('#focus=proc-wash')

  await page.keyboard.press('?')
  const guide = page.getByRole('dialog', { name: 'Reading guide' })
  await expect(guide.getByRole('button', { name: 'Close' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(guide).toHaveCount(0)
  expect(hashOf(page)).toBe('#focus=proc-wash')
  await chip.getByRole('button', { name: 'Reading guide' }).click()
  await guide.getByRole('button', { name: 'Close' }).click()
  await expect(guide).toHaveCount(0)
})
