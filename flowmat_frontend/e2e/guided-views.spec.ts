import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * archify's guided views on the workspace canvas (docs/architecture/adr/ADR-037-guided-views.md) against a mocked API
 * that keeps the chapters: reading a chapter, stepping through them, playing, opening a link to a moment, and making,
 * renaming, moving and deleting a chapter from the processes selected on the canvas.
 * Mix → Bake → Cool, Bake → Pack, and Wash on its own.
 */
const workflow = { workflowId: 'wf-guide', projectId: 'prj-e2e', workflowName: 'Bakery line', workflowDesc: null,
  workflowType: 'standard', workflowStatus: 'draft' }
const node = (processId: string, processName: string, posX: number, posY: number) => ({ processId, projectId: 'prj-e2e',
  workflowId: 'wf-guide', processName, processType: 'process', nodeType: 'process', processStatus: 'ready', colorScheme: 'slate',
  posX, posY, width: 150, height: 72, processDesc: null, version: 1, versionNonce: 1 })
const connection = (connectionId: string, fromProcessId: string, toProcessId: string) => ({ connectionId, projectId: 'prj-e2e',
  workflowId: 'wf-guide', fromProcessId, toProcessId, fromIoId: null, toIoId: null, sourceHandle: 'out-default',
  targetHandle: 'in-default', connectionType: 'material_flow', connectionLabel: null, flowRate: null, unit: null, delayTimeSec: 0,
  lossRate: 0, priority: 1, conditionExpr: null, capacity: null, failurePolicy: 'stop', defaultRoute: false, splitKind: null,
  splitValue: null, version: 1, versionNonce: 1 })

interface Chapter { guidedViewId: string; workflowId: string; sortOrder: number; label: string; note: string | null;
  focusProcessIds: string[]; version: number }
interface Sent { method: string; path: string; body: unknown }

test.use({ viewport: { width: 1440, height: 1000 } })

async function openBakery(page: Page, options: { role?: string; address?: string; conflictOnce?: boolean } = {}) {
  let chapters: Chapter[] = [
    { guidedViewId: 'ch-make', workflowId: 'wf-guide', sortOrder: 0, label: 'Make', note: 'Mix the dough, then bake it',
      focusProcessIds: ['proc-mix', 'proc-bake'], version: 1 },
    { guidedViewId: 'ch-finish', workflowId: 'wf-guide', sortOrder: 1, label: 'Finish', note: null,
      focusProcessIds: ['proc-cool', 'proc-pack'], version: 1 },
  ]
  const sent: Sent[] = []
  let conflict = options.conflictOnce ?? false
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    const views = '/api/workflows/wf-guide/guided-views'
    if (pathname.startsWith(views) && request.method() !== 'GET') {
      const body = request.postData() ? request.postDataJSON() : null
      sent.push({ method: request.method(), path: pathname.slice(views.length), body })
      if (request.method() === 'POST') {
        const made = { guidedViewId: `ch-${chapters.length + 1}`, workflowId: 'wf-guide', sortOrder: chapters.length,
          label: body.label, note: body.note, focusProcessIds: body.focusProcessIds, version: 1 }
        chapters = [...chapters, made]
        return ok(route, made)
      }
      if (pathname === `${views}/order`) {
        chapters = (body.guidedViewIds as string[]).map((id, index) => ({ ...chapters.find((one) => one.guidedViewId === id)!, sortOrder: index }))
        return ok(route, chapters)
      }
      const id = decodeURIComponent(pathname.slice(views.length + 1))
      if (request.method() === 'DELETE') {
        chapters = chapters.filter((one) => one.guidedViewId !== id)
        return ok(route, null)
      }
      if (conflict) {
        conflict = false
        chapters = chapters.map((one) => (one.guidedViewId === id ? { ...one, version: one.version + 1 } : one))
        return route.fulfill({ status: 409, contentType: 'application/json',
          body: JSON.stringify({ success: false, data: null, message: 'Someone changed this chapter since it was read.' }) })
      }
      chapters = chapters.map((one) => (one.guidedViewId === id
        ? { ...one, label: body.label, note: body.note, focusProcessIds: body.focusProcessIds, version: one.version + 1 } : one))
      return ok(route, chapters.find((one) => one.guidedViewId === id))
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === views) return ok(route, chapters)
    if (pathname === '/api/workflows/wf-guide/canvas') {
      return ok(route, { workflow: { ...workflow, currentUserRole: options.role ?? 'owner' }, graphSeq: 0,
        processes: [node('proc-mix', 'Mix', 80, 120), node('proc-bake', 'Bake', 380, 120), node('proc-cool', 'Cool', 680, 40),
          node('proc-pack', 'Pack', 680, 240), node('proc-wash', 'Wash', 80, 360)], processIos: [],
        connections: [connection('conn-mb', 'proc-mix', 'proc-bake'), connection('conn-bc', 'proc-bake', 'proc-cool'),
          connection('conn-bp', 'proc-bake', 'proc-pack')], annotations: [], currentUserRole: options.role ?? 'owner' })
    }
    if (pathname === '/api/workflows/wf-guide/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    if (pathname === '/api/workflows') return ok(route, [workflow])
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/prj-e2e/workflows/wf-guide${options.address ?? ''}`)
  await expect(page.locator('.react-flow__edge')).toHaveCount(3, { timeout: 15_000 })
  await page.keyboard.press('v')
  return { sent }
}

const processNode = (page: Page, id: string) => page.locator(`.react-flow__node[data-id="${id}"]`)
const hashOf = (page: Page) => new URL(page.url()).hash
const panelOf = (page: Page) => page.getByRole('region', { name: 'Guided views' })
const openViews = (page: Page) => page.getByRole('toolbar', { name: 'Diagram view controls' })
  .getByRole('button', { name: 'Open guided views' }).click()

test('a chapter shows its processes, the chapters step and play, and a link opens the same moment', async ({ page }) => {
  await openBakery(page)
  await openViews(page)
  const panel = panelOf(page)
  await expect(panel).toContainText('2 chapters')
  const list = panel.getByRole('list', { name: 'Chapters' })
  await expect(list.getByRole('button')).toHaveText([/1\s*Make\s*2 processes/, /2\s*Finish\s*2 processes/])

  // Choosing a chapter shows its processes strongly and the rest receded, and puts it in the address.
  await list.getByRole('button', { name: /Make/ }).click()
  expect(hashOf(page)).toBe('#view=ch-make')
  await expect(processNode(page, 'proc-mix')).toHaveClass(/reading-on/)
  await expect(processNode(page, 'proc-bake')).toHaveClass(/reading-on/)
  await expect(processNode(page, 'proc-cool')).toHaveClass(/reading-dim/)
  const caption = panel.getByLabel('Chapter being read')
  await expect(caption).toContainText('1 / 2 · Make')
  await expect(caption).toContainText('Mix the dough, then bake it')

  await panel.getByRole('button', { name: 'Next', exact: true }).click()
  expect(hashOf(page)).toBe('#view=ch-finish')
  await expect(caption).toContainText('2 / 2 · Finish')
  await expect(panel.getByRole('button', { name: 'Next', exact: true })).toBeDisabled()
  await panel.getByRole('button', { name: 'Previous', exact: true }).click()
  expect(hashOf(page)).toBe('#view=ch-make')

  // Playing walks each chapter's processes, one beat each, into the next chapter, and stops after the last.
  await panel.getByRole('button', { name: 'Play', exact: true }).click()
  await expect.poll(() => hashOf(page)).toBe('#view=ch-make&beat=proc-mix')
  await expect(processNode(page, 'proc-mix')).toHaveClass(/reading-origin/)
  await expect(caption).toContainText('At Mix')
  await expect.poll(() => hashOf(page), { timeout: 5_000 }).toBe('#view=ch-make&beat=proc-bake')
  await expect.poll(() => hashOf(page), { timeout: 5_000 }).toBe('#view=ch-finish&beat=proc-cool')
  await expect.poll(() => hashOf(page), { timeout: 5_000 }).toBe('#view=ch-finish&beat=proc-pack')
  await expect(panel.getByRole('button', { name: 'Play', exact: true })).toHaveAttribute('aria-pressed', 'false', { timeout: 5_000 })

  // A link to that moment opens it: the chapter shown and the process reached.
  await page.goto('/projects/prj-e2e/workflows/wf-guide#view=ch-finish&beat=proc-pack')
  await page.reload()
  await expect(processNode(page, 'proc-pack')).toHaveClass(/reading-origin/, { timeout: 15_000 })
  await expect(processNode(page, 'proc-mix')).toHaveClass(/reading-dim/)
  await openViews(page)
  await expect(panelOf(page).getByLabel('Chapter being read')).toContainText('At Pack')
})

test('a chapter is made from the selection, renamed, moved and deleted', async ({ page }) => {
  const { sent } = await openBakery(page, { conflictOnce: true })
  await openViews(page)
  const panel = panelOf(page)
  const make = panel.getByRole('button', { name: /^New chapter from/ })
  await expect(make).toBeDisabled()
  await expect(make).toHaveText('New chapter from 0 selected processes')

  // Mix, then Wash with Control: the chapter takes them top to bottom, as a reader would.
  await processNode(page, 'proc-mix').click()
  await expect(make).toHaveText('New chapter from 1 selected process')
  await processNode(page, 'proc-wash').click({ modifiers: ['Control'] })
  await expect(make).toHaveText('New chapter from 2 selected processes')
  await make.click()
  const form = panel.getByRole('form', { name: 'New chapter' })
  await expect(form.getByLabel('Name')).toHaveValue('Chapter 3')
  await expect(form).toContainText('Shows Mix, Wash, in that order.')
  await form.getByLabel('Name').fill('Wash up')
  await form.getByLabel('Note').fill('Clean the bowls after mixing')
  await form.getByRole('button', { name: 'Add chapter' }).click()
  await expect(panel.getByRole('status')).toHaveText('Added chapter Wash up')
  expect(sent[0]).toEqual({ method: 'POST', path: '',
    body: { label: 'Wash up', note: 'Clean the bowls after mixing', focusProcessIds: ['proc-mix', 'proc-wash'] } })
  await expect.poll(() => hashOf(page)).toBe('#view=ch-3')
  await expect(panel.getByLabel('Chapter being read')).toContainText('3 / 3 · Wash up')

  // Someone else changed it meanwhile: the change is refused, the chapter reloaded, and the second try goes through.
  await panel.getByRole('button', { name: 'Edit', exact: true }).click()
  const edit = panel.getByRole('form', { name: 'Edit chapter' })
  await edit.getByLabel('Name').fill('Wash')
  await edit.getByRole('button', { name: 'Save' }).click()
  await expect(panel.getByRole('alert')).toContainText('Someone changed this chapter meanwhile')
  expect(sent[1]).toMatchObject({ method: 'PUT', path: '/ch-3', body: { label: 'Wash', version: 1 } })
  await edit.getByRole('button', { name: 'Save' }).click()
  await expect(panel.getByRole('status')).toHaveText('Saved chapter Wash')
  expect(sent[2]).toMatchObject({ method: 'PUT', path: '/ch-3', body: { label: 'Wash', version: 2 } })

  await panel.getByRole('button', { name: 'Move chapter up' }).click()
  await expect(panel.getByRole('status')).toHaveText('Moved Wash up')
  expect(sent[3]).toEqual({ method: 'PUT', path: '/order', body: { guidedViewIds: ['ch-make', 'ch-3', 'ch-finish'] } })
  await expect(panel.getByLabel('Chapter being read')).toContainText('2 / 3 · Wash')

  await panel.getByRole('button', { name: 'Delete', exact: true }).click()
  await panel.getByRole('button', { name: 'Delete Wash' }).click()
  await expect(panel.getByRole('status')).toHaveText('Deleted chapter Wash')
  expect(sent[4]).toEqual({ method: 'DELETE', path: '/ch-3', body: null })
  await expect.poll(() => hashOf(page)).toBe('')
  await expect(panel).toContainText('2 chapters')
})

test('a viewer reads the chapters and is not offered to change them', async ({ page }) => {
  await openBakery(page, { role: 'viewer', address: '#view=ch-make' })
  await expect(processNode(page, 'proc-bake')).toHaveClass(/reading-on/, { timeout: 15_000 })
  await openViews(page)
  const panel = panelOf(page)
  await expect(panel.getByLabel('Chapter being read')).toContainText('1 / 2 · Make')
  await expect(panel.getByRole('button', { name: /^New chapter from/ })).toHaveCount(0)
  await expect(panel.getByRole('button', { name: 'Edit', exact: true })).toHaveCount(0)
})
