import { expect, test, type Locator, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * Reading the process canvas against a mocked API: a process's upstream and downstream, the route between two processes,
 * finding a process by name and the reading guide. Reading is view only and lives in the address hash, so a reload or a
 * copied link opens the same view. Mix → Bake → Cool, Bake → Pack, and Wash on its own; Bake is equipment, Cool storage,
 * Pack an output, Mix and Wash plain processes.
 */
const workflow = { workflowId: 'wf-read', projectId: 'prj-e2e', workflowName: 'Bakery line', workflowDesc: null,
  workflowType: 'standard', workflowStatus: 'draft' }
const node = (processId: string, processName: string, posX: number, posY: number, nodeType = 'process') => ({ processId,
  projectId: 'prj-e2e', workflowId: 'wf-read', processName, processType: 'process', nodeType, processStatus: 'ready',
  colorScheme: 'slate', posX, posY, width: 150, height: 72, processDesc: null, version: 1, versionNonce: 1 })
const connection = (connectionId: string, fromProcessId: string, toProcessId: string) => ({ connectionId, projectId: 'prj-e2e',
  workflowId: 'wf-read', fromProcessId, toProcessId, fromIoId: null, toIoId: null, sourceHandle: 'out-default',
  targetHandle: 'in-default', connectionType: 'material_flow', connectionLabel: null, flowRate: null, unit: null, delayTimeSec: 0,
  lossRate: 0, priority: 1, conditionExpr: null, capacity: null, failurePolicy: 'stop', defaultRoute: false, splitKind: null,
  splitValue: null, version: 1, versionNonce: 1 })

const productionRun = (productionRunId: string, runNumber: string, runStatus: string) => ({ productionRunId, projectId: 'prj-e2e',
  workflowId: 'wf-read', workflowRevisionId: 'rev-1', runNumber, runType: 'actual', runStatus, targetItemId: null,
  plannedOutputQty: 10, actualOutputQty: null, workOrderId: null, bomId: null, bomVersion: null })
const runStep = (nodeId: string, status: string, sequenceNo: number) => ({ stepId: `step-${sequenceNo}`, nodeId, nodeName: null,
  sequenceNo, status, gateway: false, sourceStepId: null, sourceConnectionId: null, scheduledAt: null, startedAt: null,
  endedAt: null, errorCode: null, errorMessage: null, inputSnapshot: null, outputSnapshot: null, joinWaiting: null,
  instructionRequired: 0, instructionDone: 0 })

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
      return ok(route, { workflow, graphSeq: 0, processes: [node('proc-mix', 'Mix', 80, 120),
        node('proc-bake', 'Bake', 380, 120, 'equipment'), node('proc-cool', 'Cool', 680, 40, 'storage'),
        node('proc-pack', 'Pack', 680, 240, 'output'), node('proc-wash', 'Wash', 80, 360)], processIos: [],
        connections: [connection('conn-mb', 'proc-mix', 'proc-bake'), connection('conn-bc', 'proc-bake', 'proc-cool'),
          connection('conn-bp', 'proc-bake', 'proc-pack')], annotations: [], currentUserRole: 'owner' })
    }
    if (pathname === '/api/workflows/wf-read/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    if (pathname === '/api/workflows') return ok(route, [workflow])
    // R-0001 is running: Mix done, Bake retried after a failure, Cool waiting, and a step for a process removed since.
    if (pathname === '/api/production-runs') return ok(route, [productionRun('run-1', 'R-0001', 'running'),
      productionRun('run-2', 'R-0002', 'finished')])
    if (pathname === '/api/production-runs/run-1/steps') {
      return ok(route, [runStep('proc-mix', 'completed', 1), runStep('proc-bake', 'failed', 2), runStep('proc-bake', 'running', 3),
        runStep('proc-cool', 'planned', 4), runStep('proc-old', 'completed', 5)])
    }
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/prj-e2e/workflows/wf-read${hash}`)
  await expect(page.locator('.react-flow__edge')).toHaveCount(3, { timeout: 15_000 })
}

const processNode = (page: Page, id: string) => page.locator(`.react-flow__node[data-id="${id}"]`)
const connectionEdge = (page: Page, id: string) => page.locator(`.react-flow__edge[data-id="${id}"]`)
const hashOf = (page: Page) => new URL(page.url()).hash

// The reading surfaces: the Semantic Passport of the process in hand, the route panel, the receipts of what is shown
// while its panel is closed, and the dock of view controls at the canvas's bottom right.
const passport = (page: Page, name: string) => page.getByRole('region', { name, exact: true })
const routePanel = (page: Page) => page.locator('.route-probe')
const receipts = (page: Page) => page.getByRole('region', { name: 'Reading receipts' })
const viewControls = (page: Page) => page.getByRole('toolbar', { name: 'Diagram view controls' })
const searchbox = (dialog: Locator) => dialog.getByRole('searchbox', { name: 'Search processes' })

test('a process reads downstream, keeps it over a reload and lets Esc stop reading', async ({ page }) => {
  await openBakery(page)
  const bake = passport(page, 'Bake')
  await expect(page.locator('.relationship-lens')).toHaveCount(0)

  await processNode(page, 'proc-bake').click({ position: { x: 24, y: 18 } })
  await expect(bake.getByRole('button', { name: 'Trace 1 upstream process' })).toBeEnabled()
  await bake.getByRole('button', { name: 'Trace 2 downstream processes' }).click()
  await expect(bake.getByRole('status')).toHaveText('Downstream · 2 processes · 2 connections · max 1 hop')
  await expect(bake.getByRole('button', { name: 'Trace 2 downstream processes' })).toHaveAttribute('aria-pressed', 'true')
  expect(hashOf(page)).toBe('#focus=proc-bake&reach=downstream')
  await expect(processNode(page, 'proc-bake')).toHaveClass(/reading-origin/)
  await expect(processNode(page, 'proc-cool')).toHaveClass(/reading-downstream/)
  await expect(processNode(page, 'proc-pack')).toHaveClass(/reading-downstream/)
  await expect(processNode(page, 'proc-mix')).toHaveClass(/reading-dim/)
  await expect(processNode(page, 'proc-wash')).toHaveClass(/reading-dim/)
  await expect(connectionEdge(page, 'conn-bc')).toHaveClass(/reading-on/)
  await expect(connectionEdge(page, 'conn-mb')).toHaveClass(/reading-dim/)

  // The link is the reading: a reload shows the same view, and the passport follows the process being read.
  await page.reload()
  await expect(bake.getByRole('status')).toHaveText('Downstream · 2 processes · 2 connections · max 1 hop', { timeout: 15_000 })
  await expect(processNode(page, 'proc-mix')).toHaveClass(/reading-dim/)

  // Esc first leaves the tool the page opens with (adding processes), as before; the next Esc stops reading.
  await page.keyboard.press('Escape')
  await expect(bake.getByRole('status')).toHaveText('Downstream · 2 processes · 2 connections · max 1 hop')
  await page.keyboard.press('Escape')
  await expect(bake).toHaveCount(0)
  expect(hashOf(page)).toBe('')
  await expect(page.locator('.reading-dim')).toHaveCount(0)
})

test('a route marks where it can go, keeps picking after a wrong pick and lists its steps', async ({ page }) => {
  await openBakery(page)
  const route = routePanel(page)

  // The process in the passport is where the route starts.
  await processNode(page, 'proc-mix').click({ position: { x: 24, y: 18 } })
  await viewControls(page).getByRole('button', { name: 'Trace a directed route' }).click()
  await expect(passport(page, 'Mix')).toHaveCount(0)
  await expect(route.locator('#route-probe-title')).toHaveText('Where does the path from Mix end?')
  await expect(route.getByRole('status')).toHaveText('3 reachable processes are highlighted. Click one.')
  await expect(processNode(page, 'proc-mix')).toHaveClass(/reading-origin/)
  await expect(processNode(page, 'proc-cool')).toHaveClass(/reading-candidate/)
  await expect(processNode(page, 'proc-wash')).toHaveClass(/reading-dim/)

  // Wash cannot be reached from Mix: the pick is refused and the picking goes on.
  await processNode(page, 'proc-wash').click({ position: { x: 24, y: 18 } })
  await expect(route.getByRole('alert')).toHaveText('Wash cannot be reached from Mix along the arrows. Pick a highlighted process.')
  await expect(processNode(page, 'proc-cool')).toHaveClass(/reading-candidate/)
  expect(hashOf(page)).toBe('')

  await processNode(page, 'proc-cool').click({ position: { x: 24, y: 18 } })
  await expect(route.locator('#route-probe-title')).toHaveText('Mix to Cool')
  await expect(route.getByRole('status')).toHaveText('3 processes · 2 steps · shortest path')
  await expect(route.getByRole('button', { name: /^Route position \d of 3:/ })).toHaveText(['Mix', 'Bake', 'Cool'])
  expect(hashOf(page)).toBe('#route=proc-mix~proc-cool')
  await expect(connectionEdge(page, 'conn-mb')).toHaveClass(/reading-route/)
  await expect(connectionEdge(page, 'conn-bc')).toHaveClass(/reading-route/)
  await expect(connectionEdge(page, 'conn-bp')).toHaveClass(/reading-dim/)
  await expect(processNode(page, 'proc-pack')).toHaveClass(/reading-dim/)

  // Nothing is downstream of Cool, so a route started there has nowhere to go.
  await route.getByRole('button', { name: 'Clear route probe' }).click()
  await expect(route).toHaveCount(0)
  await processNode(page, 'proc-cool').click({ position: { x: 24, y: 18 } })
  await viewControls(page).getByRole('button', { name: 'Trace a directed route' }).click()
  await expect(route.getByRole('status')).toHaveText('No arrows lead out of Cool. Clear it and pick another start.')

  // A link to a way against the connections says there is none; opened in this tab, it ends the picking.
  await page.goto('/projects/prj-e2e/workflows/wf-read#route=proc-cool~proc-mix')
  await expect(route.getByRole('status')).toHaveText('Mix cannot be reached from Cool along the arrows.', { timeout: 15_000 })
  await expect(page.locator('.reading-dim')).toHaveCount(0)
})

test('"/" while picking a route finds only where it can start or end, and Esc stops the picking first', async ({ page }) => {
  await openBakery(page)
  const route = routePanel(page)

  // With no process in hand the route starts from a pick.
  await viewControls(page).getByRole('button', { name: 'Trace a directed route' }).click()
  await expect(route.locator('#route-probe-title')).toHaveText('Click where the path starts')
  await expect(route.getByRole('status')).toHaveText('Click a start, then an end. Paths follow the arrows.')
  await processNode(page, 'proc-cool').click({ position: { x: 24, y: 18 } })
  await expect(route.getByRole('alert')).toHaveText('No arrows lead out of Cool. Pick a highlighted process.')

  await page.keyboard.press('/')
  const starts = page.getByRole('dialog', { name: 'Choose route start' })
  await expect(starts.getByRole('group', { name: 'Processes that can start a route' }).getByRole('button'))
    .toHaveText([/^Mix\s*start/, /^Bake\s*start/])
  await page.keyboard.press('Enter')
  await expect(starts).toHaveCount(0)
  await expect(route.getByRole('status')).toHaveText('3 reachable processes are highlighted. Click one.')

  await page.keyboard.press('/')
  const ends = page.getByRole('dialog', { name: 'Destination from Mix' })
  const destinations = ends.getByRole('group', { name: 'Reachable route destinations' }).getByRole('button')
  await expect(destinations).toHaveText([/^Bake\s*1 hop/, /^Cool\s*2 hops/, /^Pack\s*2 hops/])
  await searchbox(ends).fill('wa')
  await expect(ends.getByText('No matching reachable destinations')).toBeVisible()
  await searchbox(ends).fill('pa')
  await expect(destinations).toHaveText([/^Pack/])
  await page.keyboard.press('Enter')
  await expect(ends).toHaveCount(0)
  await expect(route.locator('#route-probe-title')).toHaveText('Mix to Pack')
  await expect(route.getByRole('status')).toHaveText('3 processes · 2 steps · shortest path')
  expect(hashOf(page)).toBe('#route=proc-mix~proc-pack')

  // Picking again from the guide keeps the route until a pick is made. Esc leaves the tool the page opens with, then
  // stops picking (the route stays), then stops reading.
  await page.keyboard.press('?')
  await page.getByRole('dialog', { name: 'Explore this workflow' }).getByRole('button', { name: 'Trace a directed route' }).click()
  await expect(route.getByRole('status')).toHaveText('Click a start, then an end. Paths follow the arrows.')
  await expect(processNode(page, 'proc-bake')).toHaveClass(/reading-candidate/)
  await page.keyboard.press('Escape')
  await page.keyboard.press('Escape')
  await expect(route.getByRole('status')).toHaveText('3 processes · 2 steps · shortest path')
  await expect(processNode(page, 'proc-pack')).toHaveClass(/reading-route/)
  await page.keyboard.press('Escape')
  await expect(route).toHaveCount(0)
  expect(hashOf(page)).toBe('')
})

test('"/" finds a process and "?" opens the guide', async ({ page }) => {
  await openBakery(page)

  await page.keyboard.press('/')
  const finder = page.getByRole('dialog', { name: 'Find a process' })
  await searchbox(finder).fill('was')
  await expect(finder.getByRole('group', { name: 'Processes' }).getByRole('button')).toHaveText([/^Wash/])
  await page.keyboard.press('Enter')
  await expect(finder).toHaveCount(0)
  expect(hashOf(page)).toBe('#focus=proc-wash')
  await expect(passport(page, 'Wash')).toContainText('0 outgoing · 0 incoming')
  await expect(passport(page, 'Wash')).toContainText('No connected relationships')
  await expect(processNode(page, 'proc-wash')).toHaveClass(/reading-origin/)
  await expect(processNode(page, 'proc-mix')).toHaveClass(/reading-dim/)

  // Esc in the finder only closes it.
  await page.keyboard.press('/')
  await expect(finder).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(finder).toHaveCount(0)
  expect(hashOf(page)).toBe('#focus=proc-wash')

  await page.keyboard.press('?')
  const guide = page.getByRole('dialog', { name: 'Explore this workflow' })
  await expect(guide).toContainText('5 processes · 3 connections')
  await expect(guide.getByRole('button', { name: 'Find any process' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(guide).toHaveCount(0)
  expect(hashOf(page)).toBe('#focus=proc-wash')
  await viewControls(page).getByRole('button', { name: 'Open diagram guide' }).click()
  await guide.getByRole('button', { name: 'Close diagram guide' }).click()
  await expect(guide).toHaveCount(0)
})

test('the passport lists a process\'s connections, previews one on the canvas and follows it', async ({ page }) => {
  await openBakery(page)
  await processNode(page, 'proc-bake').click({ position: { x: 24, y: 18 } })
  const relationships = passport(page, 'Bake').getByRole('group', { name: 'Connected relationships' })
  await expect(relationships.locator('.relationship-lens-group-title')).toHaveText(['Outgoing', 'Incoming'])
  const rows = relationships.getByRole('button')
  await expect(rows).toHaveText([/Cool\s*connects to · material flow/, /Pack\s*connects to · material flow/,
    /Mix\s*connects from · material flow/])

  // Pointing at a row shows just that connection; leaving it puts back what was shown.
  await rows.nth(1).hover()
  await expect(connectionEdge(page, 'conn-bp')).toHaveClass(/reading-on/)
  await expect(connectionEdge(page, 'conn-bc')).toHaveClass(/reading-dim/)
  await expect(processNode(page, 'proc-cool')).toHaveClass(/reading-dim/)
  await page.mouse.move(4, 4)
  await expect(page.locator('.reading-dim')).toHaveCount(0)

  // The arrows move between rows, each previewing its connection.
  await rows.first().focus()
  await expect(connectionEdge(page, 'conn-bc')).toHaveClass(/reading-on/)
  await page.keyboard.press('ArrowDown')
  await expect(rows.nth(1)).toBeFocused()
  await expect(connectionEdge(page, 'conn-bp')).toHaveClass(/reading-on/)

  // Choosing a row goes to the process at the other end.
  await rows.nth(2).click()
  await expect(page.locator('.inspector__title')).toHaveText('Mix')
  await expect(passport(page, 'Mix').locator('.relationship-lens-group-title')).toHaveText(['Outgoing'])
  await expect(page.locator('.reading-dim')).toHaveCount(0)
})

test('Runs shows a production run over the canvas, keeps it beside a reading in the link and hides it', async ({ page }) => {
  await openBakery(page)

  await viewControls(page).getByRole('button', { name: 'Show a production run' }).click()
  const picker = page.getByRole('dialog', { name: 'Show a run' })
  await expect(picker.getByRole('group', { name: 'Runs' }).getByRole('button'))
    .toHaveText([/^R-0001\s+running$/, /^R-0002\s+finished$/])
  await picker.getByRole('button', { name: /R-0001/ }).click()
  await expect(receipts(page)).toContainText('Run R-0001 (running): 1 completed, 1 running, 1 waiting; 1 process no longer on the canvas')
  expect(hashOf(page)).toBe('#run=run-1')
  // Each process takes its latest step: Bake failed once and now runs again.
  await expect(processNode(page, 'proc-mix')).toHaveClass(/run-completed/)
  await expect(processNode(page, 'proc-bake')).toHaveClass(/run-running/)
  await expect(processNode(page, 'proc-cool')).toHaveClass(/run-waiting/)
  await expect(processNode(page, 'proc-wash')).not.toHaveClass(/run-mark/)
  await page.keyboard.press('Escape')
  await expect(picker).toHaveCount(0)

  // A reading keeps the run, and the link opens both.
  const bake = passport(page, 'Bake')
  await processNode(page, 'proc-bake').click({ position: { x: 24, y: 18 } })
  await bake.getByRole('button', { name: 'Trace 2 downstream processes' }).click()
  await expect(bake.getByRole('status')).toHaveText('Downstream · 2 processes · 2 connections · max 1 hop')
  expect(hashOf(page)).toBe('#focus=proc-bake&reach=downstream&run=run-1')
  await expect(processNode(page, 'proc-mix')).toHaveClass(/reading-dim/)
  await expect(processNode(page, 'proc-mix')).toHaveClass(/run-completed/)
  await page.reload()
  await expect(receipts(page)).toContainText('Run R-0001 (running)', { timeout: 15_000 })
  await expect(processNode(page, 'proc-bake')).toHaveClass(/run-running/)

  // Stopping the reading leaves the run; Hide run takes it away.
  await page.keyboard.press('Escape')
  await page.keyboard.press('Escape')
  await expect(bake).toHaveCount(0)
  expect(hashOf(page)).toBe('#run=run-1')
  await receipts(page).getByRole('button', { name: 'Hide run' }).click()
  await expect(page.locator('.run-mark')).toHaveCount(0)
  await expect(receipts(page)).toHaveCount(0)
  expect(hashOf(page)).toBe('')
})

test('the lens reads one or two kinds of process, waits on a third and keeps the lens in the link', async ({ page }) => {
  await openBakery(page)

  await viewControls(page).getByRole('button', { name: 'Open semantic lens' }).click()
  const lens = page.getByRole('dialog', { name: 'Compare process kinds' })
  const kinds = lens.getByRole('group', { name: 'Process kinds' }).getByRole('button')
  await expect(kinds).toHaveText([/^Process\s*2$/, /^Equipment\s*1$/, /^Storage\s*1$/, /^Output\s*1$/])
  await expect(kinds.first()).toBeFocused()

  // One kind: its processes, every connection touching them, and the other ends halfway.
  await lens.getByRole('button', { name: 'Storage, 1 process' }).click()
  await expect(lens.getByRole('status'))
    .toHaveText('1 Storage process · 1 touching connection · connected peers remain visible')
  expect(hashOf(page)).toBe('#lens=storage')
  await expect(lens.getByRole('button', { name: 'Storage, 1 process' })).toHaveAttribute('aria-pressed', 'true')
  await expect(processNode(page, 'proc-cool')).toHaveClass(/reading-lens/)
  await expect(processNode(page, 'proc-bake')).toHaveClass(/reading-near/)
  await expect(processNode(page, 'proc-mix')).toHaveClass(/reading-dim/)
  await expect(connectionEdge(page, 'conn-bc')).toHaveClass(/reading-on/)
  await expect(connectionEdge(page, 'conn-mb')).toHaveClass(/reading-dim/)

  // Two kinds: only the connections between them, each way.
  await lens.getByRole('button', { name: 'Equipment, 1 process' }).click()
  await expect(lens.getByRole('status')).toHaveText('Storage → Equipment: 0 · Equipment → Storage: 1 · 1 direct connection')
  expect(hashOf(page)).toBe('#lens=storage~equipment')
  await expect(processNode(page, 'proc-bake')).toHaveClass(/reading-lens/)
  await expect(connectionEdge(page, 'conn-bp')).toHaveClass(/reading-dim/)

  // A third kind waits until one is turned off; turning one off reads the other alone.
  await expect(lens.getByRole('button', { name: 'Output, 1 process' })).toBeDisabled()
  await lens.getByRole('button', { name: 'Storage, 1 process' }).click()
  await expect(lens.getByRole('status'))
    .toHaveText('1 Equipment process · 3 touching connections · connected peers remain visible')
  await expect(lens.getByRole('button', { name: 'Output, 1 process' })).toBeEnabled()

  // Esc closes the lens panel only, back to where it was opened; its receipt stays over a reload, and the next Esc
  // (after leaving the tool the page opens with) stops it.
  await page.keyboard.press('Escape')
  await expect(lens).toHaveCount(0)
  await expect(viewControls(page).getByRole('button', { name: 'Open active semantic lens' })).toBeFocused()
  await expect(receipts(page).getByRole('status'))
    .toHaveText('1 Equipment process · 3 touching connections · connected peers remain visible')
  await page.reload()
  await expect(receipts(page).getByRole('status'))
    .toHaveText('1 Equipment process · 3 touching connections · connected peers remain visible', { timeout: 15_000 })
  await expect(viewControls(page).getByRole('button', { name: 'Open active semantic lens' })).toHaveAttribute('aria-pressed', 'true')
  await page.keyboard.press('Escape')
  await page.keyboard.press('Escape')
  await expect(receipts(page)).toHaveCount(0)
  expect(hashOf(page)).toBe('')
})

test('keys typed into a dropdown stay there instead of opening the map or the finder', async ({ page }) => {
  await openBakery(page)
  await processNode(page, 'proc-bake').click({ position: { x: 24, y: 18 } })
  await page.getByRole('combobox', { name: 'Node Type' }).focus()
  await page.keyboard.press('m')
  await page.keyboard.press('/')
  await expect(viewControls(page).getByRole('button', { name: 'Open semantic radar' })).toHaveAttribute('aria-expanded', 'false')
  await expect(page.getByRole('dialog', { name: 'Find a process' })).toHaveCount(0)
  // Away from the dropdown the same keys work.
  await page.mouse.click(4, 4)
  await page.keyboard.press('m')
  await expect(viewControls(page).getByRole('button', { name: 'Open semantic radar' })).toHaveAttribute('aria-expanded', 'true')
})
