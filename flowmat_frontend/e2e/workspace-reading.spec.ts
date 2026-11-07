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

test('a route marks where it can go, keeps picking after a wrong pick and lists its steps', async ({ page }) => {
  await openBakery(page)
  const chip = page.getByRole('region', { name: 'Canvas reading' })

  await processNode(page, 'proc-mix').click({ position: { x: 24, y: 18 } })
  await chip.getByRole('button', { name: 'Route from here' }).click()
  await expect(chip.getByRole('status'))
    .toHaveText('Route from Mix: 3 processes reachable. Click a marked one to end at, or press / to find it.')
  await expect(processNode(page, 'proc-mix')).toHaveClass(/reading-origin/)
  await expect(processNode(page, 'proc-cool')).toHaveClass(/reading-candidate/)
  await expect(processNode(page, 'proc-wash')).toHaveClass(/reading-dim/)

  // Wash cannot be reached from Mix: the pick is refused and the picking goes on.
  await processNode(page, 'proc-wash').click({ position: { x: 24, y: 18 } })
  await expect(chip.getByRole('alert')).toHaveText('Wash cannot be reached from Mix along the connections; pick a marked process.')
  await expect(processNode(page, 'proc-cool')).toHaveClass(/reading-candidate/)
  expect(hashOf(page)).toBe('')

  await processNode(page, 'proc-cool').click({ position: { x: 24, y: 18 } })
  await expect(chip.getByRole('status')).toHaveText('Route from Mix to Cool: 2 steps')
  await expect(chip.getByRole('list', { name: 'Route steps' }).getByRole('button')).toHaveText(['Mix', 'Bake', 'Cool'])
  expect(hashOf(page)).toBe('#route=proc-mix~proc-cool')
  await expect(connectionEdge(page, 'conn-mb')).toHaveClass(/reading-route/)
  await expect(connectionEdge(page, 'conn-bc')).toHaveClass(/reading-route/)
  await expect(connectionEdge(page, 'conn-bp')).toHaveClass(/reading-dim/)
  await expect(processNode(page, 'proc-pack')).toHaveClass(/reading-dim/)
  // Nothing is downstream of Cool, so no route starts there.
  await expect(chip.getByRole('button', { name: 'Route from here' })).toBeDisabled()

  // A link to a way against the connections says there is none.
  await page.goto('/projects/prj-e2e/workflows/wf-read#route=proc-cool~proc-mix')
  await expect(chip.getByRole('status')).toHaveText('No route from Cool to Mix along the connections.', { timeout: 15_000 })
  await expect(page.locator('.reading-dim')).toHaveCount(0)
})

test('"/" while picking a route finds only where it can start or end, and Esc stops the picking first', async ({ page }) => {
  await openBakery(page)
  const chip = page.getByRole('region', { name: 'Canvas reading' })
  const combobox = (dialog: Locator) => dialog.getByRole('combobox', { name: 'Process name, type or id' })

  await chip.getByRole('button', { name: 'Pick a route' }).click()
  await expect(chip.getByRole('status')).toHaveText('Route: click a marked process to start from, or press / to find it.')
  await processNode(page, 'proc-cool').click({ position: { x: 24, y: 18 } })
  await expect(chip.getByRole('alert')).toHaveText('Nothing is downstream of Cool; pick a marked process to start from.')

  await page.keyboard.press('/')
  const starts = page.getByRole('dialog', { name: 'Find where the route starts' })
  await expect(starts.getByRole('option')).toHaveText([/^Mix\s+1 connection out$/, /^Bake\s+2 connections out$/])
  await page.keyboard.press('Enter')
  await expect(chip.getByRole('status'))
    .toHaveText('Route from Mix: 3 processes reachable. Click a marked one to end at, or press / to find it.')

  await page.keyboard.press('/')
  const ends = page.getByRole('dialog', { name: 'Find where the route ends' })
  await expect(ends.getByRole('option')).toHaveText([/^Bake\s+1 step$/, /^Cool\s+2 steps$/, /^Pack\s+2 steps$/])
  await combobox(ends).fill('wa')
  await expect(ends.getByText('No process matches.')).toBeVisible()
  await combobox(ends).fill('pa')
  await expect(ends.getByRole('option')).toHaveText([/^Pack/])
  await page.keyboard.press('Enter')
  await expect(ends).toHaveCount(0)
  await expect(chip.getByRole('status')).toHaveText('Route from Mix to Pack: 2 steps')
  expect(hashOf(page)).toBe('#route=proc-mix~proc-pack')

  // Esc leaves the tool the page opens with, then stops picking (the route stays), then stops reading.
  await processNode(page, 'proc-bake').click({ position: { x: 24, y: 18 } })
  await chip.getByRole('button', { name: 'Route from here' }).click()
  await expect(processNode(page, 'proc-pack')).toHaveClass(/reading-candidate/)
  await page.keyboard.press('Escape')
  await page.keyboard.press('Escape')
  await expect(chip.getByRole('status')).toHaveText('Route from Mix to Pack: 2 steps')
  await expect(processNode(page, 'proc-pack')).toHaveClass(/reading-route/)
  await page.keyboard.press('Escape')
  await expect(chip.getByRole('status')).toHaveCount(0)
  expect(hashOf(page)).toBe('')
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

test('the inspector lists a process\'s connections, previews one on the canvas and follows it', async ({ page }) => {
  await openBakery(page)
  await processNode(page, 'proc-bake').click({ position: { x: 24, y: 18 } })
  const connections = page.getByRole('region', { name: 'Connections' })
  await expect(connections.getByRole('heading', { level: 5 })).toHaveText(['Out 2', 'In 1'])
  const rows = connections.getByRole('button')
  await expect(rows).toHaveText([/Cool\s+material flow/, /Pack\s+material flow/, /Mix\s+material flow/])

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
  await expect(connections.getByRole('heading', { level: 5 })).toHaveText(['Out 1'])
  await expect(page.locator('.reading-dim')).toHaveCount(0)
})

test('Runs shows a production run over the canvas, keeps it beside a reading in the link and hides it', async ({ page }) => {
  await openBakery(page)
  const chip = page.getByRole('region', { name: 'Canvas reading' })

  await chip.getByRole('button', { name: 'Runs' }).click()
  const picker = page.getByRole('dialog', { name: 'Show a run' })
  await expect(picker.getByRole('group', { name: 'Runs' }).getByRole('button'))
    .toHaveText([/^R-0001\s+running$/, /^R-0002\s+finished$/])
  await picker.getByRole('button', { name: /R-0001/ }).click()
  await expect(chip).toContainText('Run R-0001 (running): 1 completed, 1 running, 1 waiting; 1 process no longer on the canvas')
  expect(hashOf(page)).toBe('#run=run-1')
  // Each process takes its latest step: Bake failed once and now runs again.
  await expect(processNode(page, 'proc-mix')).toHaveClass(/run-completed/)
  await expect(processNode(page, 'proc-bake')).toHaveClass(/run-running/)
  await expect(processNode(page, 'proc-cool')).toHaveClass(/run-waiting/)
  await expect(processNode(page, 'proc-wash')).not.toHaveClass(/run-mark/)
  await page.keyboard.press('Escape')
  await expect(picker).toHaveCount(0)

  // A reading keeps the run, and the link opens both.
  await processNode(page, 'proc-bake').click({ position: { x: 24, y: 18 } })
  await chip.getByRole('button', { name: 'Downstream 2' }).click()
  await expect(chip.getByRole('status')).toHaveText('Downstream of Bake: 2 processes, 2 connections, 1 step')
  expect(hashOf(page)).toBe('#focus=proc-bake&reach=downstream&run=run-1')
  await expect(processNode(page, 'proc-mix')).toHaveClass(/reading-dim/)
  await expect(processNode(page, 'proc-mix')).toHaveClass(/run-completed/)
  await page.reload()
  await expect(chip).toContainText('Run R-0001 (running)', { timeout: 15_000 })
  await expect(processNode(page, 'proc-bake')).toHaveClass(/run-running/)

  // Stopping the reading leaves the run; Hide run takes it away.
  await page.keyboard.press('Escape')
  await page.keyboard.press('Escape')
  await expect(chip.getByRole('status')).toHaveCount(0)
  expect(hashOf(page)).toBe('#run=run-1')
  await chip.getByRole('button', { name: 'Hide run' }).click()
  await expect(page.locator('.run-mark')).toHaveCount(0)
  expect(hashOf(page)).toBe('')
})

test('Kinds reads one or two kinds of process, refuses a third and keeps the lens in the link', async ({ page }) => {
  await openBakery(page)
  const chip = page.getByRole('region', { name: 'Canvas reading' })

  await chip.getByRole('button', { name: 'Kinds' }).click()
  const lens = page.getByRole('dialog', { name: 'Read by kind' })
  const kinds = lens.getByRole('group', { name: 'Kinds' }).getByRole('button')
  await expect(kinds).toHaveText(['Process 2', 'Equipment 1', 'Storage 1', 'Output 1'])
  await expect(kinds.first()).toBeFocused()

  // One kind: its processes, every connection touching them, and the other ends halfway.
  await lens.getByRole('button', { name: 'Storage 1' }).click()
  await expect(chip.getByRole('status')).toHaveText('Storage: 1 process, 1 connection, 1 nearby')
  expect(hashOf(page)).toBe('#lens=storage')
  await expect(lens.getByRole('button', { name: 'Storage 1' })).toHaveAttribute('aria-pressed', 'true')
  await expect(processNode(page, 'proc-cool')).toHaveClass(/reading-lens/)
  await expect(processNode(page, 'proc-bake')).toHaveClass(/reading-near/)
  await expect(processNode(page, 'proc-mix')).toHaveClass(/reading-dim/)
  await expect(connectionEdge(page, 'conn-bc')).toHaveClass(/reading-on/)
  await expect(connectionEdge(page, 'conn-mb')).toHaveClass(/reading-dim/)

  // Two kinds: only the connections between them.
  await lens.getByRole('button', { name: 'Equipment 1' }).click()
  await expect(chip.getByRole('status')).toHaveText('Storage and Equipment: 2 processes, 1 connection between them')
  expect(hashOf(page)).toBe('#lens=storage~equipment')
  await expect(processNode(page, 'proc-bake')).toHaveClass(/reading-lens/)
  await expect(connectionEdge(page, 'conn-bp')).toHaveClass(/reading-dim/)

  // A third kind is refused; turning one off reads the other alone.
  await lens.getByRole('button', { name: 'Output 1' }).click()
  await expect(lens.getByRole('alert')).toHaveText('A lens shows one or two kinds; turn one off first.')
  expect(hashOf(page)).toBe('#lens=storage~equipment')
  await lens.getByRole('button', { name: 'Storage 1' }).click()
  await expect(chip.getByRole('status')).toHaveText('Equipment: 1 process, 3 connections, 3 nearby')

  // Esc closes the picker only; the lens stays over a reload, and the next Esc (after leaving the tool) stops it.
  await page.keyboard.press('Escape')
  await expect(lens).toHaveCount(0)
  await expect(chip.getByRole('button', { name: 'Kinds' })).toBeFocused()
  await page.reload()
  await expect(chip.getByRole('status')).toHaveText('Equipment: 1 process, 3 connections, 3 nearby', { timeout: 15_000 })
  await expect(chip.getByRole('button', { name: 'Kinds' })).toHaveAttribute('aria-pressed', 'true')
  await page.keyboard.press('Escape')
  await page.keyboard.press('Escape')
  await expect(chip.getByRole('status')).toHaveCount(0)
  expect(hashOf(page)).toBe('')
})
