import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * Leaving lines out of an item import (docs/domain/item-import.md "줄을 골라 적용"), against a mocked API: a file with a
 * wrong line can be saved without it, the file is checked again whenever the choice changes, and what is saved is
 * exactly what the last check showed.
 */
interface Input { dryRun: boolean; rows: { itemCode: string; itemName?: string }[]; skipRows?: number[] }

test('lines with problems are left out in one press and the rest is saved', async ({ page }) => {
  const projectId = 'prj-item-import-rows'
  const sent: Input[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/items/import') {
      const body = request.postDataJSON() as Input
      sent.push(body)
      const skip = new Set(body.skipRows ?? [])
      // Like the server: a left-out row is not checked; BAD rows are wrong; the rest are new items.
      const rows = body.rows.map((row, index) => skip.has(index + 1)
        ? { row: index + 1, itemCode: row.itemCode, action: 'skipped', message: null }
        : row.itemCode.startsWith('BAD')
          ? { row: index + 1, itemCode: row.itemCode, action: 'error', message: 'Unit parsec is not an active unit' }
          : { row: index + 1, itemCode: row.itemCode, action: 'create', message: null })
      const errors = rows.filter((row) => row.action === 'error').length
      return ok(route, { dryRun: body.dryRun, applied: !body.dryRun && errors === 0,
        created: rows.filter((row) => row.action === 'create').length, updated: 0, unchanged: 0, errors, skipped: skip.size, rows })
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 400, body: 'Unexpected write' })
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Import rows', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Import rows', ownerId: 'demo-owner' })
    if (pathname === '/api/material-requirements') return ok(route, { projectId, orders: 0, lines: [], problems: [] })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=items`)
  const panel = page.getByRole('region', { name: 'Items spreadsheet', exact: true })
  await panel.getByLabel('Import items file').setInputFiles({ name: 'items.csv', mimeType: 'text/csv',
    buffer: Buffer.from('item_code,item_name\nOATS,Oats\nBAD-1,Cane\nRYE,Rye\nBAD-2,Dust\n') })
  const check = panel.getByLabel('Import check', { exact: true })

  // The whole file: two lines are wrong, so nothing can be saved yet.
  await expect(check).toContainText('2 to add, 0 to update, 0 unchanged, 2 with problems')
  await expect(check.getByRole('button', { name: 'Save 2 changes', exact: true })).toBeDisabled()
  expect(sent).toHaveLength(1)
  expect(sent[0].skipRows).toBeUndefined()

  // One press leaves out both wrong lines; the file is checked again without them.
  await check.getByRole('button', { name: 'Leave out the 2 lines with problems', exact: true }).click()
  await expect(check).toContainText('2 to add, 0 to update, 0 unchanged, 2 left out')
  expect(sent[1]).toMatchObject({ dryRun: true, skipRows: [2, 4] })
  expect(sent[1].rows).toHaveLength(4)
  // Line numbers are the file's: the header is line 1.
  await expect(check.getByRole('row', { name: /line 3/ })).toContainText('left out')
  await expect(check.getByLabel('Import line 3', { exact: true })).not.toBeChecked()
  await expect(check.getByLabel('Import line 2', { exact: true })).toBeChecked()
  await expect(check.getByRole('button', { name: /Leave out the/ })).toHaveCount(0)

  // A good line can be left out as well, and a left-out line put back.
  await check.getByLabel('Import line 4', { exact: true }).uncheck()
  await expect(check).toContainText('1 to add, 0 to update, 0 unchanged, 3 left out')
  expect(sent[2]).toMatchObject({ dryRun: true, skipRows: [2, 3, 4] })
  // The last line that is still in cannot be taken out: the server refuses an import of nothing.
  await expect(check.getByLabel('Import line 2', { exact: true })).toBeDisabled()
  await check.getByLabel('Import line 4', { exact: true }).check()
  await expect(check).toContainText('2 to add, 0 to update, 0 unchanged, 2 left out')
  expect(sent[3]).toMatchObject({ dryRun: true, skipRows: [2, 4] })

  // Saving sends the same file with the same lines left out.
  await check.getByRole('button', { name: 'Save 2 changes', exact: true }).click()
  await expect(panel.getByRole('status')).toHaveText('Saved: 2 added, 0 updated, 0 unchanged, 2 left out.')
  expect(sent).toHaveLength(5)
  expect(sent[4]).toMatchObject({ dryRun: false, skipRows: [2, 4] })
  expect(sent[4].rows.map((row) => row.itemCode)).toEqual(['OATS', 'BAD-1', 'RYE', 'BAD-2'])
  await expect(check).toHaveCount(0)
})
