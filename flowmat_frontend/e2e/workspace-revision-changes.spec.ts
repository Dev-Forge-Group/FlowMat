import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * The draft compared with a published revision over the canvas, against a mocked API (archify's delta): added processes
 * and connections marked +, changed ones Δ, what is gone named in the list, all by stable id; moving a process is not a
 * change. View only, and kept in the address beside any reading.
 * v2 was Mix → Bake → Wash. The draft moved Mix, renamed Bake to Oven, removed Wash and added Pack after Oven.
 */
const workflow = { workflowId: 'wf-chg', projectId: 'prj-e2e', workflowName: 'Changed line', workflowDesc: null,
  workflowType: 'standard', workflowStatus: 'draft' }
const node = (processId: string, processName: string, posX: number, nodeType = 'process') => ({ processId,
  projectId: 'prj-e2e', workflowId: 'wf-chg', processName, processType: 'process', nodeType, processStatus: 'ready',
  colorScheme: 'slate', posX, posY: 120, width: 150, height: 72, processDesc: null, version: 1, versionNonce: 1 })
const connection = (connectionId: string, fromProcessId: string, toProcessId: string) => ({ connectionId, projectId: 'prj-e2e',
  workflowId: 'wf-chg', fromProcessId, toProcessId, fromIoId: null, toIoId: null, sourceHandle: 'out-default',
  targetHandle: 'in-default', connectionType: 'material_flow', connectionLabel: null, flowRate: null, unit: null, delayTimeSec: 0,
  lossRate: 0, priority: 1, conditionExpr: null, capacity: null, failurePolicy: 'stop', defaultRoute: false, splitKind: null,
  splitValue: null, version: 1, versionNonce: 1 })
const revision = (workflowRevisionId: string, revisionNo: number, status: string) => ({ workflowRevisionId, workflowId: 'wf-chg',
  revisionNo, status, schemaVersion: 3, publishedBy: 'demo-owner', publishedAt: '2030-01-01T00:00:00Z', retiredBy: null,
  retiredAt: null })

async function openChangedLine(page: Page, hash = '') {
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/workflows/wf-chg/canvas') {
      return ok(route, { workflow, graphSeq: 0, processes: [{ ...node('proc-mix', 'Mix', 80), posY: 300, version: 5 },
        node('proc-bake', 'Oven', 380, 'equipment'), node('proc-pack', 'Pack', 680, 'output')], processIos: [],
      connections: [connection('conn-mb', 'proc-mix', 'proc-bake'), connection('conn-bp', 'proc-bake', 'proc-pack')],
      annotations: [], currentUserRole: 'owner' })
    }
    if (pathname === '/api/workflows/wf-chg/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    if (pathname === '/api/workflows/wf-chg/revisions') return ok(route, [revision('rev-1', 1, 'retired'), revision('rev-2', 2, 'published')])
    if (pathname === '/api/workflows/wf-chg/revisions/rev-2') {
      return ok(route, { ...revision('rev-2', 2, 'published'), snapshot: { schemaVersion: 3,
        processes: [node('proc-mix', 'Mix', 80), node('proc-bake', 'Bake', 380, 'equipment'), node('proc-wash', 'Wash', 680)],
        processIos: [], connections: [connection('conn-mb', 'proc-mix', 'proc-bake'), connection('conn-bw', 'proc-bake', 'proc-wash')] } })
    }
    if (pathname === '/api/workflows') return ok(route, [workflow])
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/prj-e2e/workflows/wf-chg${hash}`)
  await expect(page.locator('.react-flow__edge')).toHaveCount(2, { timeout: 15_000 })
}

const processNode = (page: Page, id: string) => page.locator(`.react-flow__node[data-id="${id}"]`)
const connectionEdge = (page: Page, id: string) => page.locator(`.react-flow__edge[data-id="${id}"]`)

test('the draft is compared with a published revision over the canvas and the comparison survives a reload', async ({ page }) => {
  await openChangedLine(page)
  // CHANGES in the view controls opens the picker; the comparison's receipt stays over the canvas.
  const changes = page.getByRole('toolbar', { name: 'Diagram view controls' }).getByRole('button', { name: 'Compare with a revision' })
  const receipts = page.getByRole('region', { name: 'Reading receipts' })
  await changes.click()
  const picker = page.getByRole('dialog', { name: 'Compare with a revision' })
  // Newest first.
  await expect(picker.getByRole('group', { name: 'Revisions' }).getByRole('button')).toHaveText([/^v2\s+published$/, /^v1\s+retired$/])
  await picker.getByRole('button', { name: /^v2/ }).click()

  const summary = 'Since v2: processes 1 added, 1 changed, 1 removed; connections 1 added, 1 removed.'
  await expect(receipts).toContainText(summary)
  await expect(changes).toHaveAttribute('aria-pressed', 'true')
  expect(new URL(page.url()).hash).toBe('#changes=rev-2')
  await expect(processNode(page, 'proc-pack')).toHaveClass(/change-added/)
  await expect(processNode(page, 'proc-bake')).toHaveClass(/change-changed/)
  // Mix only moved and was saved again: not a change.
  await expect(processNode(page, 'proc-mix')).not.toHaveClass(/change-mark/)
  await expect(connectionEdge(page, 'conn-bp')).toHaveClass(/change-added/)
  await expect(connectionEdge(page, 'conn-mb')).not.toHaveClass(/change-/)

  // What is gone is named; what is there is a way to it.
  await expect(picker.getByRole('list', { name: 'Changed processes' }).getByRole('button')).toHaveText([/^Δ Oven\s*name$/, /^\+ Pack\s*added$/])
  await expect(picker.getByRole('list', { name: 'Removed' }).getByRole('listitem')).toHaveText(['− Wash', '− Bake → Wash'])
  await picker.getByRole('button', { name: /Oven/ }).click()
  await picker.getByRole('button', { name: 'Close' }).click()
  await expect(picker).toHaveCount(0)

  // The comparison is in the address, beside any reading: a reload opens it again.
  await processNode(page, 'proc-bake').click({ position: { x: 24, y: 18 } })
  // Bake is named Oven in the draft.
  await page.getByRole('region', { name: 'Oven', exact: true }).getByRole('button', { name: 'Trace 1 downstream process' }).click()
  expect(new URL(page.url()).hash).toBe('#focus=proc-bake&reach=downstream&changes=rev-2')
  await page.reload()
  await expect(receipts).toContainText(summary, { timeout: 15_000 })
  await expect(processNode(page, 'proc-pack')).toHaveClass(/change-added/)
  await expect(processNode(page, 'proc-pack')).toHaveClass(/reading-downstream/)

  await receipts.getByRole('button', { name: 'Hide changes' }).click()
  expect(new URL(page.url()).hash).toBe('#focus=proc-bake&reach=downstream')
  await expect(processNode(page, 'proc-pack')).not.toHaveClass(/change-mark/)
  await expect(changes).toHaveAttribute('aria-pressed', 'false')
})

test('a link to a revision the workflow does not have says so', async ({ page }) => {
  await openChangedLine(page, '#changes=rev-9')
  await expect(page.getByRole('region', { name: 'Reading receipts' }))
    .toContainText("The linked revision is not one of this workflow's revisions.")
  await expect(page.locator('.react-flow__node.change-mark')).toHaveCount(0)
})
