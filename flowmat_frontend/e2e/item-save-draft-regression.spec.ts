import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

for (const mode of ['create', 'edit'] as const) for (const changed of [false, true]) {
  test(`item ${mode} response preserves newer draft: ${changed}`, async ({ page }) => {
    const projectId = 'prj-item-save-draft'
    const items: Record<string, unknown>[] = []
    if (mode === 'edit') items.push({ itemId: 'saved-item', projectId, itemCode: 'BEFORE',
      itemName: 'Existing item', itemType: 'generic', resourceCategory: 'material',
      itemStatus: 'active', lotManageYn: 'N' })
    const commands: Record<string, unknown>[] = []
    let release!: () => void
    const gate = new Promise<void>((resolve) => { release = resolve })
    await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
      const request = route.request()
      const { pathname } = new URL(request.url())
      if (await answerAuth(route, pathname)) return
      if ((pathname === '/api/items' && request.method() === 'POST') ||
          (pathname === '/api/items/saved-item' && request.method() === 'PUT')) {
        const body = request.postDataJSON() as Record<string, unknown>
        commands.push(body)
        await gate
        const item = { ...body, itemId: 'saved-item', projectId, activeYn: 'Y' }
        items.splice(0, items.length, item)
        return ok(route, item)
      }
      if (request.method() !== 'GET') return route.fulfill({ status: 400, body: 'Unexpected write' })
      if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Draft preservation', ownerId: 'demo-owner' }])
      if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Draft preservation', ownerId: 'demo-owner' })
      if (pathname === '/api/items') return ok(route, items)
      return ok(route, [])
    })
    await mockedLogin(page)
    await page.goto(`/projects/${projectId}/inventory?tab=items`)
    if (mode === 'edit') await page.getByRole('button', { name: 'Edit', exact: true }).click()
    const code = page.getByLabel('Code *', { exact: true })
    const name = page.getByLabel('Name *', { exact: true })
    await code.fill('SAVED')
    await name.fill('Saved item')
    await page.getByRole('button', { name: mode === 'edit' ? 'Save' : 'Add', exact: true }).click()
    try {
      await expect.poll(() => commands.length).toBe(1)
      if (changed) {
        await code.fill('NEXT')
        await name.fill('Next draft')
      }
    } finally { release() }
    await expect(page.getByRole('cell', { name: 'Saved item', exact: true })).toBeVisible()
    await expect(code).toHaveValue(changed ? 'NEXT' : '')
    await expect(name).toHaveValue(changed ? 'Next draft' : '')
    await expect(page.getByRole('heading', { name: mode === 'edit' && changed ? 'Edit Item' : 'Add Item', exact: true })).toBeVisible()
    expect(commands).toHaveLength(1)
    expect(commands[0]).toMatchObject({ itemCode: 'SAVED', itemName: 'Saved item' })
  })
}
