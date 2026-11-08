import { expect, test, type Locator, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * archify's view tools on the workspace canvas against a mocked API: the Semantic Radar opened from MAP, the Live and
 * Still motion control, the presentation stage, moving the Semantic Passport, and the reading panels sitting over the
 * canvas. Mix → Bake → Cool, Bake → Pack, and Wash on its own; Bake is equipment, Cool storage, Pack an output.
 */
const workflow = { workflowId: 'wf-view', projectId: 'prj-e2e', workflowName: 'Bakery line', workflowDesc: null,
  workflowType: 'standard', workflowStatus: 'draft' }
const node = (processId: string, processName: string, posX: number, posY: number, nodeType = 'process') => ({ processId,
  projectId: 'prj-e2e', workflowId: 'wf-view', processName, processType: 'process', nodeType, processStatus: 'ready',
  colorScheme: 'slate', posX, posY, width: 150, height: 72, processDesc: null, version: 1, versionNonce: 1 })
const connection = (connectionId: string, fromProcessId: string, toProcessId: string) => ({ connectionId, projectId: 'prj-e2e',
  workflowId: 'wf-view', fromProcessId, toProcessId, fromIoId: null, toIoId: null, sourceHandle: 'out-default',
  targetHandle: 'in-default', connectionType: 'material_flow', connectionLabel: null, flowRate: null, unit: null, delayTimeSec: 0,
  lossRate: 0, priority: 1, conditionExpr: null, capacity: null, failurePolicy: 'stop', defaultRoute: false, splitKind: null,
  splitValue: null, version: 1, versionNonce: 1 })

// Room for the passport, which lists every connection, to move inside the canvas below the ribbon.
test.use({ viewport: { width: 1440, height: 1000 } })

async function openBakery(page: Page, address = '') {
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/workflows/wf-view/canvas') {
      return ok(route, { workflow, graphSeq: 0, processes: [node('proc-mix', 'Mix', 80, 120),
        node('proc-bake', 'Bake', 380, 120, 'equipment'), node('proc-cool', 'Cool', 680, 40, 'storage'),
        node('proc-pack', 'Pack', 680, 240, 'output'), node('proc-wash', 'Wash', 80, 360)], processIos: [],
        connections: [connection('conn-mb', 'proc-mix', 'proc-bake'), connection('conn-bc', 'proc-bake', 'proc-cool'),
          connection('conn-bp', 'proc-bake', 'proc-pack')], annotations: [], currentUserRole: 'owner' })
    }
    if (pathname === '/api/workflows/wf-view/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    if (pathname === '/api/workflows') return ok(route, [workflow])
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/prj-e2e/workflows/wf-view${address}`)
  await expect(page.locator('.react-flow__edge')).toHaveCount(3, { timeout: 15_000 })
  // The page opens with the Process tool; the Pointer keeps clicks on the canvas from adding processes.
  await page.keyboard.press('v')
}

const processNode = (page: Page, id: string) => page.locator(`.react-flow__node[data-id="${id}"]`)
const hashOf = (page: Page) => new URL(page.url()).hash
const searchOf = (page: Page) => new URL(page.url()).search
const viewControls = (page: Page) => page.getByRole('toolbar', { name: 'Diagram view controls' })
const passport = (page: Page, name: string) => page.getByRole('region', { name, exact: true })
const radar = (page: Page) => page.getByRole('region', { name: 'Semantic radar' })
const cameraOf = (page: Page) => page.locator('.react-flow__viewport').evaluate((element) => getComputedStyle(element).transform)

async function boxOf(locator: Locator) {
  const box = await locator.boundingBox()
  if (!box) throw new Error('not laid out')
  return box
}

async function dragBy(page: Page, handle: Locator, dx: number, dy: number, at?: { x: number; y: number }) {
  const box = await boxOf(handle)
  const x = box.x + (at?.x ?? box.width / 2)
  const y = box.y + (at?.y ?? box.height / 2)
  await page.mouse.move(x, y)
  await page.mouse.down()
  await page.mouse.move(x + dx, y + dy, { steps: 6 })
  await page.mouse.up()
}

test('MAP opens a semantic radar that reads a process, steers the camera and moves aside', async ({ page }) => {
  await openBakery(page)
  const map = viewControls(page).getByRole('button', { name: 'Open semantic radar' })
  await map.click()
  await expect(map).toHaveAttribute('aria-expanded', 'true')
  const status = radar(page).locator('.overview-map-copy small')
  await expect(status).toHaveText('5 processes · full map')
  await expect(radar(page).getByRole('button', { name: /from Semantic Radar$/ })).toHaveCount(5)
  await expect(radar(page).locator('[data-radar-node-id="proc-bake"]')).toHaveAttribute('data-kind', 'cloud')
  await expect(radar(page).locator('[data-radar-node-id="proc-pack"]')).toHaveAttribute('data-kind', 'success')

  // A process picked on the radar is read, and marked there.
  await radar(page).getByRole('button', { name: 'Focus Bake from Semantic Radar' }).click()
  await expect(passport(page, 'Bake')).toBeVisible()
  await expect.poll(() => hashOf(page)).toBe('#focus=proc-bake')
  await expect(radar(page).locator('[data-radar-node-id="proc-bake"]')).toHaveAttribute('data-radar-active', 'true')

  // A click on the radar moves the camera there; so do the arrow keys.
  const surface = radar(page).getByRole('group', { name: /^Diagram overview/ })
  let camera = await cameraOf(page)
  const area = await boxOf(surface)
  await page.mouse.click(area.x + 6, area.y + 6)
  await expect.poll(() => cameraOf(page)).not.toBe(camera)
  await expect(status).toHaveText(/^5 processes · \d+% viewport$/)
  camera = await cameraOf(page)
  await surface.focus()
  await page.keyboard.press('ArrowRight')
  await expect.poll(() => cameraOf(page)).not.toBe(camera)

  // Its head moves it somewhere else on the canvas.
  const before = await boxOf(radar(page))
  await dragBy(page, radar(page).locator('.overview-map-head'), -220, -160, { x: 24, y: 12 })
  await expect(radar(page)).toHaveAttribute('data-manual-placement', 'true')
  const after = await boxOf(radar(page))
  expect(after.x).toBeLessThan(before.x - 150)
  expect(after.y).toBeLessThan(before.y - 100)

  // Esc on the radar closes it and gives MAP the focus back; the reading stays.
  await surface.focus()
  await page.keyboard.press('Escape')
  await expect(radar(page)).toHaveCount(0)
  await expect(map).toBeFocused()
  await expect(passport(page, 'Bake')).toBeVisible()

  // The radar and the lens take turns, and M opens the radar where it was left.
  await page.keyboard.press('m')
  await expect(radar(page)).toHaveAttribute('data-manual-placement', 'true')
  await viewControls(page).getByRole('button', { name: 'Open semantic lens' }).click()
  await expect(page.getByRole('dialog', { name: 'Compare process kinds' })).toBeVisible()
  await expect(radar(page)).toHaveCount(0)
  await page.keyboard.press('m')
  await expect(radar(page)).toBeVisible()
  await expect(page.getByRole('dialog', { name: 'Compare process kinds' })).toHaveCount(0)
})

test('Still stops the canvas animating, is remembered, and pauses a playing route journey', async ({ page }) => {
  await openBakery(page, '#route=proc-mix~proc-pack')
  const live = viewControls(page).getByRole('button', { name: 'Pause motion' })
  await expect(live).toHaveAttribute('aria-pressed', 'true')
  await expect(live).toHaveText('LIVE')
  await expect(page.locator('html')).toHaveAttribute('data-motion', 'live')
  await expect.poll(() => processNode(page, 'proc-mix').evaluate((element) => getComputedStyle(element).transitionDuration))
    .not.toBe('0s')

  const journey = page.getByRole('group', { name: 'Route journey controls' })
  await journey.getByRole('button', { name: 'Play route journey' }).click()
  await expect(journey.getByRole('button', { name: 'Pause route journey' })).toHaveAttribute('aria-pressed', 'true')

  await live.click()
  const still = viewControls(page).getByRole('button', { name: 'Resume motion' })
  await expect(still).toHaveAttribute('aria-pressed', 'false')
  await expect(still).toHaveText('STILL')
  await expect(page.locator('html')).toHaveAttribute('data-motion', 'still')
  await expect(journey.getByRole('button', { name: /^(Play|Replay) route journey$/ })).toHaveAttribute('aria-pressed', 'false')
  await expect(processNode(page, 'proc-mix')).toHaveCSS('transition-duration', '0s')

  // The choice is this browser's: a reload keeps it.
  await page.reload()
  await expect(viewControls(page).getByRole('button', { name: 'Resume motion' })).toHaveText('STILL', { timeout: 15_000 })
  await viewControls(page).getByRole('button', { name: 'Resume motion' }).click()
  await expect(viewControls(page).getByRole('button', { name: 'Pause motion' })).toHaveText('LIVE')
})

test('the system\'s reduced-motion preference keeps the canvas still', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await openBakery(page)
  const control = viewControls(page).getByRole('button', { name: 'Motion paused by reduced-motion preference' })
  await expect(control).toBeDisabled()
  await expect(control).toHaveText('STILL')
  await expect(page.locator('html')).toHaveAttribute('data-motion', 'still')
})

test('the presentation stage gives the canvas the window, stays in the link and ends with F or Esc', async ({ page }) => {
  await openBakery(page, '#focus=proc-bake')
  const canvasBefore = await boxOf(page.locator('.workspace-canvas'))
  await viewControls(page).getByRole('button', { name: 'Enter presentation stage' }).click()
  await expect.poll(() => searchOf(page)).toBe('?present=1')
  expect(hashOf(page)).toBe('#focus=proc-bake')
  await expect(page.locator('.ribbon')).toBeHidden()
  await expect(page.locator('.workspace-panel--left')).toBeHidden()
  await expect(page.locator('.workspace-panel--right')).toBeHidden()
  await expect(passport(page, 'Bake')).toBeVisible()
  const exit = viewControls(page).getByRole('button', { name: 'Exit presentation stage' })
  await expect(exit).toHaveAttribute('aria-pressed', 'true')
  const viewport = page.viewportSize()!
  await expect.poll(async () => (await boxOf(page.locator('.workspace-canvas'))).width).toBe(viewport.width)
  expect((await boxOf(page.locator('.workspace-canvas'))).height).toBeGreaterThan(canvasBefore.height)

  // A link opens on the stage; F leaves it and comes back.
  await page.reload()
  await expect(exit).toBeVisible({ timeout: 15_000 })
  await expect(page.locator('.ribbon')).toBeHidden()
  await page.keyboard.press('v')
  await page.keyboard.press('f')
  await expect.poll(() => searchOf(page)).toBe('')
  await expect(page.locator('.ribbon')).toBeVisible()
  await page.keyboard.press('f')
  await expect.poll(() => searchOf(page)).toBe('?present=1')
  await expect(page.locator('.ribbon')).toBeHidden()

  // Esc stops reading first, as in archify, and only then ends the stage.
  await page.keyboard.press('Escape')
  await expect(passport(page, 'Bake')).toHaveCount(0)
  expect(searchOf(page)).toBe('?present=1')
  await page.keyboard.press('Escape')
  await expect.poll(() => searchOf(page)).toBe('')
  await expect(page.locator('.ribbon')).toBeVisible()
})

test('the passport moves by its handle, stays put from one process to the next and goes back with Home', async ({ page }) => {
  await openBakery(page)
  await processNode(page, 'proc-bake').click({ position: { x: 24, y: 18 } })
  const bake = passport(page, 'Bake')
  const handle = bake.getByRole('button', { name: /^Move semantic passport/ })
  const home = await boxOf(bake)

  await dragBy(page, handle, 180, 60)
  await expect(bake).toHaveAttribute('data-manual-placement', 'true')
  const moved = await boxOf(bake)
  expect(Math.round(moved.x - home.x)).toBe(180)
  expect(Math.round(moved.y - home.y)).toBe(60)

  // Following a connection opens the next process's passport where this one was.
  await bake.getByRole('button', { name: /Cool/ }).first().click()
  const cool = passport(page, 'Cool')
  await expect(cool).toBeVisible()
  expect(Math.round((await boxOf(cool)).x)).toBe(Math.round(moved.x))

  // The arrow keys nudge it (Shift for small steps), Esc during a drag puts it back, and Home resets it.
  const coolHandle = cool.getByRole('button', { name: /^Move semantic passport/ })
  await coolHandle.focus()
  await page.keyboard.press('ArrowLeft')
  await expect.poll(async () => Math.round((await boxOf(cool)).x)).toBe(Math.round(moved.x) - 16)
  await page.keyboard.press('Shift+ArrowUp')
  await expect.poll(async () => Math.round((await boxOf(cool)).y)).toBe(Math.round(moved.y) - 4)
  const kept = await boxOf(cool)
  const grip = await boxOf(coolHandle)
  await page.mouse.move(grip.x + grip.width / 2, grip.y + grip.height / 2)
  await page.mouse.down()
  await page.mouse.move(grip.x + 120, grip.y + 90, { steps: 4 })
  await page.keyboard.press('Escape')
  await page.mouse.up()
  await expect.poll(async () => Math.round((await boxOf(cool)).x)).toBe(Math.round(kept.x))
  await expect(cool).toBeVisible()
  await coolHandle.focus()
  await page.keyboard.press('Home')
  await expect(cool).not.toHaveAttribute('data-manual-placement', 'true')
  expect(Math.round((await boxOf(cool)).x)).toBe(Math.round(home.x))
})

test('the lens, the finder and the guide open over the canvas', async ({ page }) => {
  await openBakery(page)
  const canvas = await boxOf(page.locator('.workspace-canvas'))
  const inside = async (panel: Locator) => {
    await expect(panel).toBeVisible()
    expect(await panel.evaluate((element) => getComputedStyle(element).position)).toBe('absolute')
    const box = await boxOf(panel)
    expect(box.x).toBeGreaterThanOrEqual(canvas.x)
    expect(box.y).toBeGreaterThanOrEqual(canvas.y)
    expect(box.x + box.width).toBeLessThanOrEqual(canvas.x + canvas.width + 1)
    expect(box.y + box.height).toBeLessThanOrEqual(canvas.y + canvas.height + 1)
  }

  await viewControls(page).getByRole('button', { name: 'Open semantic lens' }).click()
  await inside(page.getByRole('dialog', { name: 'Compare process kinds' }))
  await page.keyboard.press('Escape')
  await page.keyboard.press('/')
  await inside(page.getByRole('dialog', { name: 'Find a process' }))
  await page.keyboard.press('Escape')
  await page.keyboard.press('?')
  const guide = page.getByRole('dialog', { name: 'Explore this workflow' })
  await inside(guide)
  // The guide's last action is the stage.
  await guide.getByRole('button', { name: 'Enter Presentation Stage' }).click()
  await expect(guide).toHaveCount(0)
  await expect.poll(() => searchOf(page)).toBe('?present=1')
})
