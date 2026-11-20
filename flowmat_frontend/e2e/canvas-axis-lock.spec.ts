import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * settingTool's Shift lock while dragging (docs/status/SETTINGTOOL-FEATURE-MAP.md), against a mocked API: with Shift
 * held a process slides only along the axis it moved further on, and that is the position saved; without Shift it goes
 * where it is dropped.
 */
const node = (processId: string, processName: string, posX: number, posY: number) => ({ processId, projectId: 'prj-e2e',
  workflowId: 'wf-lock', processName, processType: 'process', nodeType: 'process', processStatus: 'ready', colorScheme: 'slate',
  posX, posY, width: 150, height: 72, processDesc: null, version: 1, versionNonce: 1 })

async function openLine(page: Page) {
  const saved: { processId: string; posX: number; posY: number }[] = []
  const processes = [node('proc-mix', 'Mix', 80, 120), node('proc-bake', 'Bake', 480, 400)]
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    const position = pathname.match(/^\/api\/processes\/([^/]+)\/position$/)
    if (position && request.method() === 'PATCH') {
      const body = request.postDataJSON() as { posX: number; posY: number }
      saved.push({ processId: position[1], ...body })
      return ok(route, { ...processes.find((one) => one.processId === position[1]), ...body })
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404, body: 'Unexpected write' })
    if (pathname === '/api/workflows/wf-lock/canvas') {
      return ok(route, { workflow: { workflowId: 'wf-lock', projectId: 'prj-e2e', workflowName: 'Line', workflowDesc: null,
        workflowType: 'standard', workflowStatus: 'draft', currentUserRole: 'owner' }, graphSeq: 0, processes, processIos: [],
        connections: [], annotations: [], currentUserRole: 'owner' })
    }
    if (pathname === '/api/workflows/wf-lock/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/workflows/wf-lock')
  await expect(page.locator('.react-flow__node')).toHaveCount(2, { timeout: 15_000 })
  await page.keyboard.press('v')
  return saved
}

async function drag(page: Page, processId: string, dx: number, dy: number, shift: boolean) {
  const box = (await page.locator(`.react-flow__node[data-id="${processId}"]`).boundingBox())!
  await page.mouse.move(box.x + 30, box.y + 20)
  await page.mouse.down()
  if (shift) await page.keyboard.down('Shift')
  await page.mouse.move(box.x + 30 + dx, box.y + 20 + dy, { steps: 12 })
  await page.mouse.up()
  if (shift) await page.keyboard.up('Shift')
}

test('Shift keeps a dragged process on the axis it moved further along', async ({ page }) => {
  const saved = await openLine(page)
  const zoom = await page.locator('.react-flow__viewport').evaluate((element) =>
    new DOMMatrixReadOnly(getComputedStyle(element).transform).a)

  // Without Shift a process goes where it is dropped.
  await drag(page, 'proc-mix', 80 * zoom, 80 * zoom, false)
  await expect.poll(() => saved.length).toBe(1)
  expect(saved[0]).toMatchObject({ processId: 'proc-mix' })
  expect(saved[0].posX).toBeGreaterThan(120)
  expect(saved[0].posY).toBeGreaterThan(160)

  // With Shift, mostly across: it stays at the height it was picked up at.
  await drag(page, 'proc-mix', 160 * zoom, 40 * zoom, true)
  await expect.poll(() => saved.length).toBe(2)
  expect(saved[1].posY).toBe(saved[0].posY)
  expect(saved[1].posX).toBeGreaterThan(saved[0].posX + 100)

  // With Shift, mostly down: it stays in its column.
  await drag(page, 'proc-bake', -30 * zoom, 120 * zoom, true)
  await expect.poll(() => saved.length).toBe(3)
  expect(saved[2]).toMatchObject({ processId: 'proc-bake', posX: 480 })
  expect(saved[2].posY).toBeGreaterThan(480)
})
