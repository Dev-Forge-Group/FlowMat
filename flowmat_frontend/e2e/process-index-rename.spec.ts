import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * Renaming one process after another from the process index (settingTool's object list,
 * docs/status/SETTINGTOOL-FEATURE-MAP.md), against a mocked API: Enter saves and goes on to the next process, an
 * unchanged or blank name saves nothing, Esc stops, and a viewer is not offered it.
 */
const node = (processId: string, processName: string, posX: number, posY: number) => ({ processId, projectId: 'prj-e2e',
  workflowId: 'wf-names', processName, processType: 'process', nodeType: 'process', processStatus: 'ready', colorScheme: 'slate',
  posX, posY, width: 150, height: 72, processDesc: null, version: 1, versionNonce: 1 })

async function openLine(page: Page, role = 'owner') {
  const renamed: { processId: string; processName: unknown }[] = []
  const processes = [node('p-1', 'Step 1', 80, 120), node('p-2', 'Step 2', 380, 120), node('p-3', 'Step 3', 680, 120)]
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    const update = pathname.match(/^\/api\/processes\/([^/]+)$/)
    if (update && request.method() === 'PUT') {
      const body = request.postDataJSON() as { processName?: string }
      renamed.push({ processId: update[1], processName: body.processName })
      return ok(route, { ...processes.find((one) => one.processId === update[1]), ...body, version: 2 })
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404, body: 'Unexpected write' })
    if (pathname === '/api/workflows/wf-names/canvas') {
      return ok(route, { workflow: { workflowId: 'wf-names', projectId: 'prj-e2e', workflowName: 'Names', workflowDesc: null,
        workflowType: 'standard', workflowStatus: 'draft', currentUserRole: role }, graphSeq: 0, processes, processIos: [],
        connections: [], annotations: [], currentUserRole: role })
    }
    if (pathname === '/api/workflows/wf-names/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/workflows/wf-names')
  await expect(page.locator('.react-flow__node')).toHaveCount(3, { timeout: 15_000 })
  await page.keyboard.press('v')
  await page.getByRole('toolbar', { name: 'Diagram view controls' }).getByRole('button', { name: 'Open process index' }).click()
  return { renamed, index: page.getByRole('region', { name: 'Process index' }) }
}

test('the index renames one process after another', async ({ page }) => {
  const { renamed, index } = await openLine(page)
  await index.getByRole('button', { name: 'Rename in order' }).click()

  // Enter saves and goes on; the next name is ready to type over.
  const first = index.getByLabel('New name for Step 1')
  await expect(first).toBeFocused()
  await first.fill('Weigh')
  await first.press('Enter')
  const second = index.getByLabel('New name for Step 2')
  await expect(second).toBeFocused()
  await expect.poll(() => renamed).toEqual([{ processId: 'p-1', processName: 'Weigh' }])
  await expect(page.locator('.react-flow__node[data-id="p-1"]')).toContainText('Weigh')

  // An unchanged or blank name saves nothing and still goes on.
  await second.press('Enter')
  const third = index.getByLabel('New name for Step 3')
  await third.fill('   ')
  await third.press('Enter')
  await expect(index.getByRole('textbox')).toHaveCount(0)
  expect(renamed).toHaveLength(1)

  // F2 on an entry renames that one; Esc stops without saving.
  await index.getByRole('button', { name: /Step 3/ }).focus()
  await page.keyboard.press('F2')
  await index.getByLabel('New name for Step 3').fill('Pack')
  await page.keyboard.press('Escape')
  await expect(index.getByRole('textbox')).toHaveCount(0)
  await expect(index).toBeVisible()
  expect(renamed).toHaveLength(1)

  // A double-click renames the one clicked.
  await index.getByRole('button', { name: /Step 2/ }).dblclick()
  await index.getByLabel('New name for Step 2').fill('Mix')
  await page.keyboard.press('Enter')
  await expect.poll(() => renamed.at(-1)).toEqual({ processId: 'p-2', processName: 'Mix' })
})

test('a viewer is not offered to rename from the index', async ({ page }) => {
  const { index } = await openLine(page, 'viewer')
  await expect(index.getByRole('button', { name: 'Rename in order' })).toHaveCount(0)
  await index.getByRole('button', { name: /Step 1/ }).focus()
  await page.keyboard.press('F2')
  await expect(index.getByRole('textbox')).toHaveCount(0)
})
