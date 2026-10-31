import { expect, test, type Page, type Route } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

const projectId = 'prj-revision-review'
const workflowId = 'wf-revision-review'
const otherWorkflowId = 'wf-revision-other'
const beforeExact = '{"amount":9007199254740993.123456789,"nullable":null}'
const afterExact = '{"amount":9007199254740993.123456790,"nullable":null}'

type Change = {
  section: string; entityId: string; path: string; kind: 'added' | 'removed' | 'modified'
  before: unknown; after: unknown; beforeJson?: string; afterJson?: string
}
type Revision = { revisionId: string; revisionNo: number; schemaVersion: number; status: string }
type Comparison = {
  workflowId: string; fromRevision: Revision; toRevision: Revision
  totalChanges: number; changes: Change[]; nextOffset: number | null
}
type Reply = Comparison | { status: number; message: string }

function revision(id: string, no: number, status = 'published'): Revision {
  return { revisionId: id, revisionNo: no, schemaVersion: 1, status }
}
function change(path: string, beforeJson = beforeExact, afterJson = afterExact): Change {
  return { section: 'nodeBindings', entityId: 'http-process', path, kind: 'modified',
    before: JSON.parse(beforeJson), after: JSON.parse(afterJson), beforeJson, afterJson }
}
function comparison(url: URL, changes = [change('/requestMapping/body')], nextOffset: number | null = null): Comparison {
  const requestedWorkflow = url.pathname.split('/')[3]
  const id = (key: string) => url.searchParams.get(key) ?? ''
  const meta = (value: string) => revision(value, Number(value.slice(-1)), value.endsWith('1') ? 'retired' : 'published')
  return { workflowId: requestedWorkflow, fromRevision: meta(id('fromRevisionId')), toRevision: meta(id('toRevisionId')),
    totalChanges: nextOffset === null ? changes.length : nextOffset + 1, changes, nextOffset }
}

async function fixture(page: Page, respond: (url: URL) => Reply | Promise<Reply>, view = 'flow-runs') {
  const state = { reads: [] as URL[], writes: [] as string[] }
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route: Route) => {
    const request = route.request()
    const url = new URL(request.url())
    const { pathname } = url
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') {
      state.writes.push(`${request.method()} ${pathname}`)
      return route.fulfill({ status: 409, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: 'Revision comparison cannot write data.' }) })
    }
    if (/\/workflows\/[^/]+\/revisions\/compare$/.test(pathname)) {
      state.reads.push(url)
      const result = await respond(url)
      if ('status' in result) return route.fulfill({ status: result.status, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: result.message }) })
      return ok(route, result)
    }
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Revision review', projectStatus: 'active' }])
    if (pathname === '/api/project-members') return ok(route, [{ projectMemberId: 'member-revision-review',
      projectId, userId: 'demo-owner', projectRole: 'viewer', memberStatus: 'active' }])
    if (pathname === '/api/workflows') return ok(route, [
      { workflowId, projectId, workflowName: 'HTTP workflow', workflowType: 'standard', workflowStatus: 'published' },
      { workflowId: otherWorkflowId, projectId, workflowName: 'Other workflow', workflowType: 'standard', workflowStatus: 'published' },
    ])
    if (/\/workflows\/[^/]+\/revisions$/.test(pathname)) {
      const prefix = pathname.includes(otherWorkflowId) ? 'other' : 'revision'
      return ok(route, [3, 1, 2].map((no) => ({ workflowRevisionId: `${prefix}-${no}`,
        workflowId: pathname.split('/')[3], revisionNo: no, status: no === 1 ? 'retired' : 'published',
        schemaVersion: 1, publishedBy: 'demo-owner', publishedAt: '2026-10-28T00:00:00Z', retiredBy: null, retiredAt: null })))
    }
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/runs?view=${view}`)
  return { state, panel: page.getByRole('region', { name: 'Workflow revision comparison', exact: true }) }
}

test('a viewer compares pinned HTTP binding JSON exactly without writing and within a narrow viewport', async ({ page }) => {
  const { state, panel } = await fixture(page, (url) => comparison(url))
  await page.getByRole('button', { name: 'Compare revisions', exact: true }).click()
  await expect(panel).toContainText(beforeExact)
  await expect(panel).toContainText(afterExact)
  expect(state.reads).toHaveLength(1)
  expect(state.reads[0].searchParams.get('fromRevisionId')).toBe('revision-2')
  expect(state.reads[0].searchParams.get('toRevisionId')).toBe('revision-3')
  await expect(panel).toContainText('nodeBindings')
  await expect(panel).toContainText('/requestMapping/body')
  await page.setViewportSize({ width: 375, height: 812 })
  await expect.poll(async () => {
    const bounds = await panel.boundingBox()
    return bounds === null ? Number.POSITIVE_INFINITY : bounds.x + bounds.width
  }).toBeLessThanOrEqual(375)
  const bounds = await panel.boundingBox()
  expect(bounds!.x).toBeGreaterThanOrEqual(0)
  expect(await panel.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)
  expect(state.writes).toEqual([])
})

test('retired revisions can be selected, swapped and reviewed over pages with an explicit refresh', async ({ page }) => {
  let fresh = false
  const firstPage = Array.from({ length: 100 }, (_, index) => change(`/requestMapping/page-${index}`))
  const { state, panel } = await fixture(page, (url) => {
    if (fresh) return { ...comparison(url, [change('/fresh-current-page')]), totalChanges: 101 }
    if (Number(url.searchParams.get('offset')) > 0) return { ...comparison(url, [change('/last-page')]), totalChanges: 101 }
    return comparison(url, firstPage, 100)
  })
  expect(state.reads).toEqual([])
  await page.getByRole('button', { name: 'Compare revisions', exact: true }).click()
  await expect(panel).toContainText('/requestMapping/page-0')
  await expect(panel.getByRole('button', { name: 'Previous changes', exact: true })).toBeDisabled()
  expect(state.reads[0].searchParams.get('limit')).toBe('100')
  expect(state.reads[0].searchParams.get('offset')).toBe('0')
  await expect(panel.getByRole('combobox', { name: 'From revision', exact: true }).getByRole('option', { name: /v1.*retired/ })).toHaveCount(1)
  await panel.getByRole('combobox', { name: 'From revision', exact: true }).selectOption('revision-1')
  await expect.poll(() => state.reads[state.reads.length - 1].searchParams.get('fromRevisionId')).toBe('revision-1')
  await panel.getByRole('button', { name: 'Swap revisions', exact: true }).click()
  await expect.poll(() => state.reads[state.reads.length - 1].searchParams.get('fromRevisionId')).toBe('revision-3')
  expect(state.reads[state.reads.length - 1].searchParams.get('toRevisionId')).toBe('revision-1')
  await expect(panel).toContainText('/requestMapping/page-0')
  await panel.getByRole('button', { name: 'Next changes', exact: true }).click()
  await expect(panel).toContainText('/last-page')
  await expect(panel).not.toContainText('/requestMapping/page-0')
  expect(state.reads[state.reads.length - 1].searchParams.get('offset')).toBe('100')
  await expect(panel.getByRole('button', { name: 'Next changes', exact: true })).toBeDisabled()
  await panel.getByRole('button', { name: 'Previous changes', exact: true }).click()
  await expect(panel).toContainText('/requestMapping/page-0')
  await panel.getByRole('button', { name: 'Next changes', exact: true }).click()
  await expect(panel).toContainText('/last-page')
  fresh = true
  await panel.getByRole('button', { name: 'Refresh comparison', exact: true }).click()
  await expect(panel).toContainText('/fresh-current-page')
  await expect(panel).not.toContainText('/last-page')
  expect(state.reads[state.reads.length - 1].searchParams.get('offset')).toBe('100')
  expect(state.writes).toEqual([])
})

test('added and removed bindings distinguish absence from an explicitly stored null', async ({ page }) => {
  const added = { ...change('/nodeBindings/http-added', 'null', '{"operationId":"create"}'),
    entityId: 'http-added', kind: 'added' as const }
  const removed = { ...change('/nodeBindings/http-removed', '{"operationId":"delete"}', 'null'),
    entityId: 'http-removed', kind: 'removed' as const }
  const explicitNull = change('/responseMapping/nullable', 'null', '"mapped"')
  const { state, panel } = await fixture(page, (url) => comparison(url, [added, removed, explicitNull]))
  await page.getByRole('button', { name: 'Compare revisions', exact: true }).click()
  const addedRow = panel.getByRole('article', { name: /added nodeBindings http-added/ })
  const removedRow = panel.getByRole('article', { name: /removed nodeBindings http-removed/ })
  const nullRow = panel.getByRole('article', { name: /modified nodeBindings http-process.*nullable/ })
  await expect(addedRow).toContainText('Absent')
  await expect(addedRow).toContainText('{"operationId":"create"}')
  await expect(removedRow).toContainText('Absent')
  await expect(removedRow).toContainText('{"operationId":"delete"}')
  await expect(nullRow.locator('pre').first()).toHaveText('null')
  await expect(nullRow).not.toContainText('Absent')
  expect(state.writes).toEqual([])
})

test('a late response for an earlier revision pair cannot replace the selected comparison', async ({ page }) => {
  let release: ((value: Reply) => void) | undefined
  let lateAnswered = false
  const oldResponse = new Promise<Reply>((resolve) => { release = resolve })
  const { state, panel } = await fixture(page, async (url) => {
    if (url.searchParams.get('fromRevisionId') === 'revision-2') {
      const result = await oldResponse
      lateAnswered = true
      return result
    }
    return comparison(url, [change('/current-revision-pair')])
  })
  await page.getByRole('button', { name: 'Compare revisions', exact: true }).click()
  await expect.poll(() => state.reads.length).toBe(1)
  await panel.getByRole('combobox', { name: 'From revision', exact: true }).selectOption('revision-1')
  await expect(panel).toContainText('/current-revision-pair')
  release!(comparison(state.reads[0], [change('/stale-revision-pair')]))
  await expect.poll(() => lateAnswered).toBe(true)
  await expect(panel).toContainText('/current-revision-pair')
  await expect(panel).not.toContainText('/stale-revision-pair')
  expect(state.writes).toEqual([])
})

test('changing workflows isolates a pending comparison and requires an explicit open for the new workflow', async ({ page }) => {
  let release: ((value: Reply) => void) | undefined
  let lateAnswered = false
  const oldResponse = new Promise<Reply>((resolve) => { release = resolve })
  const { state, panel } = await fixture(page, async (url) => {
    if (url.pathname.includes(`/workflows/${workflowId}/`)) {
      const result = await oldResponse
      lateAnswered = true
      return result
    }
    return comparison(url, [change('/other-workflow-only')])
  })
  await page.getByRole('button', { name: 'Compare revisions', exact: true }).click()
  await expect.poll(() => state.reads.length).toBe(1)
  await page.getByRole('combobox', { name: 'Workflow', exact: true }).selectOption(otherWorkflowId)
  await expect(page.getByRole('button', { name: 'Compare revisions', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Compare revisions', exact: true }).click()
  await expect(panel).toContainText('/other-workflow-only')
  release!(comparison(state.reads[0], [change('/previous-workflow-private')]))
  await expect.poll(() => lateAnswered).toBe(true)
  await expect(panel).not.toContainText('/previous-workflow-private')
  expect(state.reads[state.reads.length - 1].pathname).toContain(`/workflows/${otherWorkflowId}/`)
  expect(state.writes).toEqual([])
})

test('a denied refresh removes cached comparison evidence until an explicit recovery read', async ({ page }) => {
  let denied = false
  let recovered = false
  const { state, panel } = await fixture(page, (url) => denied
    ? { status: 403, message: 'Revision access was removed.' }
    : comparison(url, [change(recovered ? '/fresh-authorized-comparison' : '/old-private-comparison')]))
  await page.getByRole('button', { name: 'Compare revisions', exact: true }).click()
  await expect(panel).toContainText('/old-private-comparison')
  denied = true
  await panel.getByRole('button', { name: 'Refresh comparison', exact: true }).click()
  await expect(panel.getByRole('alert')).toContainText('Revision access was removed.')
  await expect(panel).not.toContainText('/old-private-comparison')
  await expect(panel.getByRole('article')).toHaveCount(0)
  const deniedReadCount = state.reads.length
  const from = panel.getByRole('combobox', { name: 'From revision', exact: true })
  if (await from.isEnabled()) await from.selectOption('revision-1')
  else await expect(from).toBeDisabled()
  await expect(panel).not.toContainText('/old-private-comparison')
  expect(state.reads).toHaveLength(deniedReadCount)
  denied = false
  recovered = true
  await panel.getByRole('button', { name: 'Refresh comparison', exact: true }).click()
  await expect(panel).toContainText('/fresh-authorized-comparison')
  await expect(panel).not.toContainText('/old-private-comparison')
  expect(state.writes).toEqual([])
})

test('mismatched identities and malformed pages are rejected without exposing returned evidence', async ({ page }) => {
  let variant = 0
  const { state, panel } = await fixture(page, (url) => {
    const result = comparison(url, [change('/wrong-response-private')])
    if (variant === 0) return { ...result, workflowId: otherWorkflowId }
    if (variant === 1) return { ...result, fromRevision: { ...result.fromRevision, revisionId: 'unrequested-revision' } }
    return { ...result, changes: null } as unknown as Comparison
  })
  await page.getByRole('button', { name: 'Compare revisions', exact: true }).click()
  for (variant = 0; variant < 3; variant += 1) {
    if (variant > 0) await panel.getByRole('button', { name: 'Refresh comparison', exact: true }).click()
    await expect(panel.getByRole('alert')).toBeVisible()
    await expect(panel).not.toContainText('/wrong-response-private')
    await expect(panel.getByRole('article')).toHaveCount(0)
  }
  expect(state.writes).toEqual([])
})

test('an older response without exact JSON never falls back to rounded JavaScript numbers', async ({ page }) => {
  const withoutExact: Change = { section: 'nodeBindings', entityId: 'large-amount-process',
    path: '/requestMapping/amount', kind: 'modified', before: Number('9007199254740993'), after: Number('9007199254740995') }
  const { state, panel } = await fixture(page, (url) => comparison(url, [withoutExact]))
  await page.getByRole('button', { name: 'Compare revisions', exact: true }).click()
  await expect(panel).toContainText('Exact JSON is unavailable for this change.')
  await expect(panel).not.toContainText('9007199254740992')
  await expect(panel).not.toContainText('9007199254740996')
  expect(state.writes).toEqual([])
})

test('the production runs view offers the same lazy read-only comparison outside its start form', async ({ page }) => {
  const { state, panel } = await fixture(page, (url) => comparison(url, []), 'runs')
  await expect(page.getByRole('button', { name: 'Compare revisions', exact: true })).toBeVisible()
  expect(state.reads).toEqual([])
  await page.getByRole('button', { name: 'Compare revisions', exact: true }).click()
  await expect(panel).toContainText('0 changes')
  await expect(panel.getByRole('article')).toHaveCount(0)
  await expect(panel.getByRole('button', { name: 'Next changes', exact: true })).toBeDisabled()
  expect(state.writes).toEqual([])
})


test('an older successful refresh cannot clear a newer access refusal or reopen cached evidence', async ({ page }) => {
  let pairAReads = 0
  let release: ((value: Reply) => void) | undefined
  let lateAnswered = false
  const oldRefresh = new Promise<Reply>((resolve) => { release = resolve })
  const { state, panel } = await fixture(page, async (url) => {
    const from = url.searchParams.get('fromRevisionId')
    if (from === 'revision-2') {
      pairAReads += 1
      if (pairAReads === 2) {
        const result = await oldRefresh
        lateAnswered = true
        return result
      }
      return comparison(url, [change('/original-readable-pair')])
    }
    if (from === 'revision-1' && url.searchParams.get('toRevisionId') === 'revision-3') {
      return { status: 403, message: 'Access was removed while the earlier refresh was pending.' }
    }
    return comparison(url, [change('/requires-explicit-recovery')])
  })
  await page.getByRole('button', { name: 'Compare revisions', exact: true }).click()
  await expect(panel).toContainText('/original-readable-pair')
  await panel.getByRole('button', { name: 'Refresh comparison', exact: true }).click()
  await expect.poll(() => pairAReads).toBe(2)
  await panel.getByRole('combobox', { name: 'From revision', exact: true }).selectOption('revision-1')
  await expect(panel.getByRole('alert')).toContainText('Access was removed while the earlier refresh was pending.')
  const readsAtRefusal = state.reads.length
  const pendingRefreshUrl = state.reads.find((url, index) => index > 0 && url.searchParams.get('fromRevisionId') === 'revision-2')!
  release!(comparison(pendingRefreshUrl, [change('/late-previous-refresh')]))
  await expect.poll(() => lateAnswered).toBe(true)
  await expect(panel.getByRole('button', { name: 'Refresh comparison', exact: true })).toBeEnabled()
  await panel.getByRole('combobox', { name: 'To revision', exact: true }).selectOption('revision-2')
  await expect(panel.getByRole('alert')).toContainText('Refresh comparison to check access again.')
  await expect(panel.getByRole('article')).toHaveCount(0)
  await expect(panel).not.toContainText('/late-previous-refresh')
  await expect(panel).not.toContainText('/requires-explicit-recovery')
  expect(state.reads).toHaveLength(readsAtRefusal)
  await panel.getByRole('button', { name: 'Close comparison', exact: true }).click()
  await panel.getByRole('button', { name: 'Compare revisions', exact: true }).click()
  await expect(panel.getByRole('alert')).toContainText('Refresh comparison to check access again.')
  expect(state.reads).toHaveLength(readsAtRefusal)
  await panel.getByRole('button', { name: 'Refresh comparison', exact: true }).click()
  await expect(panel).toContainText('/requires-explicit-recovery')
  expect(state.writes).toEqual([])
})
