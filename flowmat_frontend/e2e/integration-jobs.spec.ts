import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

const projectId = 'prj-integration'
const profile = { profileId: 'profile-supplier', projectId, name: 'Supplier', enabled: true, version: 1, currentRevision: 2,
  profileVersionId: 'supplier-v2', baseOrigin: 'https://api.example.com', credentialAlias: null, createdAt: '', updatedAt: '',
  operations: [
    { operationId: 'list', method: 'GET', relativePath: '/items', successStatuses: [200] },
    { operationId: 'update', method: 'POST', relativePath: '/stock', successStatuses: [200] },
  ] }

type Job = Record<string, unknown> & { jobId: string; version: number }

async function setup(page: Page, role = 'owner', initial: Job[] = []) {
  const jobs: Job[] = initial.map((job) => ({ ...job }))
  const runs: Record<string, unknown>[] = []
  const writes: { method: string; path: string; body: unknown }[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    const base = `/api/projects/${projectId}/integration-jobs`
    if (request.method() !== 'GET') {
      const body = request.postData() ? request.postDataJSON() : null
      writes.push({ method: request.method(), path: pathname, body })
      if (request.method() === 'POST' && pathname === base) {
        const job = { ...body, jobId: 'job-1', projectId, version: 1, scheduleCron: null, scheduleEnabled: false, nextRunAt: null,
          runAs: null, pausedReason: null, failuresInRow: 0, lastRun: null }
        jobs.push(job); return ok(route, job)
      }
      if (request.method() === 'POST' && pathname.endsWith('/preview')) return ok(route, { runId: null, jobId: 'job-1',
        trigger: 'preview', scheduledFor: null, status: 'partial', runBy: 'demo-owner', startedAt: null, finishedAt: null,
        rowsTotal: 2, rowsApplied: 1, rowsLeftOut: 1, message: '1 of 2 records were left out.', items: [
          { no: 1, key: 'OATS', result: 'create', message: null },
          { no: 2, key: 'RYE', result: 'left_out', message: 'Row 2: itemName is required' }] })
      if (request.method() === 'POST' && pathname.endsWith('/runs')) {
        const run = { runId: `run-${runs.length + 1}`, jobId: 'job-1', trigger: 'manual', scheduledFor: null, status: 'succeeded',
          runBy: 'demo-owner', startedAt: '2026-11-20T09:00:00Z', finishedAt: '2026-11-20T09:00:01Z', rowsTotal: 1, rowsApplied: 1,
          rowsLeftOut: 0, message: null, items: [{ no: 1, key: 'OATS', result: 'create', message: null }] }
        runs.unshift(run)
        jobs[0] = { ...jobs[0], lastRun: { ...run, items: [] } }
        return ok(route, run)
      }
      if (request.method() === 'PUT' && pathname.endsWith('/schedule')) {
        const input = body as { cron: string | null; enabled: boolean; version: number }
        if (input.version !== jobs[0].version) return route.fulfill({ status: 409, contentType: 'application/json',
          body: JSON.stringify({ success: false, data: null, message: 'Someone changed this job since it was read.' }) })
        jobs[0] = { ...jobs[0], version: jobs[0].version + 1, scheduleEnabled: input.enabled,
          scheduleCron: input.cron ?? jobs[0].scheduleCron, runAs: input.enabled ? 'demo-owner' : jobs[0].runAs,
          nextRunAt: input.enabled ? '2026-11-23T06:00:00+09:00' : null, pausedReason: null, failuresInRow: 0 }
        return ok(route, jobs[0])
      }
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: 'Unexpected write' }) })
    }
    if (pathname === '/api/project-members') return ok(route, [{ projectMemberId: 'member', projectId, userId: 'demo-owner',
      projectRole: role, memberStatus: 'active' }])
    if (pathname === `/api/projects/${projectId}/http-profiles`) return ok(route, [profile])
    if (pathname === base) return ok(route, jobs)
    if (pathname === `${base}/job-1/runs`) return ok(route, runs)
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/settings`)
  return { panel: page.getByRole('region', { name: 'Integration jobs' }), writes }
}

test('an owner makes a pull job, previews it, runs it and reads its runs', async ({ page }) => {
  const { panel, writes } = await setup(page)
  await expect(panel).toContainText('No integration jobs yet.')
  await panel.getByRole('button', { name: 'Add integration job' }).click()
  const form = panel.getByRole('form', { name: 'Edit integration job' })
  await form.getByLabel('Job name').fill('Supplier catalogue')
  await expect(form.getByLabel('HTTP profile')).toHaveValue(profile.profileVersionId)
  await expect(form.getByLabel('Operation that reads')).toHaveValue('list')
  await form.getByRole('button', { name: 'Save job' }).click()
  await expect(form).toBeHidden()
  const created = writes.find((write) => write.method === 'POST')!.body as Record<string, unknown>
  expect(created).toMatchObject({ name: 'Supplier catalogue', kind: 'pull_items', profileVersionId: 'supplier-v2', operationId: 'list',
    verifyOperationId: null, note: null, mapping: { rowsAt: '/items' } })

  const job = panel.getByRole('article', { name: 'Integration job Supplier catalogue' })
  await expect(job).toContainText('Pull items · Supplier · list')
  await expect(job).toContainText('Runs by hand')
  await job.getByRole('button', { name: 'Preview Supplier catalogue' }).click()
  const preview = job.getByRole('status', { name: 'Preview' })
  await expect(preview).toContainText('Preview (nothing saved): Partly done · 1 of 2 taken · 1 left out')
  await expect(preview.getByRole('row').nth(2)).toContainText('RYELeft outRow 2: itemName is required')

  await job.getByRole('button', { name: 'Run Supplier catalogue now' }).click()
  await expect(job.getByRole('status', { name: 'Run result' })).toContainText('Run: Succeeded · 1 of 1 taken')
  await expect(job).toContainText('Last run: Succeeded · 1 of 1 taken')
  await job.getByRole('button', { name: 'View runs of Supplier catalogue' }).click()
  const history = job.getByLabel('Runs of Supplier catalogue')
  await history.getByRole('button', { name: /by hand · demo-owner · Succeeded/ }).click()
  await expect(history.getByRole('row').nth(1)).toContainText('OATSNew')
})

test('an owner switches a schedule on and off, and a push asks for the operation that reads the target', async ({ page }) => {
  const job = { jobId: 'job-1', projectId, name: 'Levels', kind: 'pull_items', profileVersionId: 'supplier-v2', operationId: 'list',
    verifyOperationId: null, mapping: { rowsAt: '/items', row: { object: { itemCode: { from: '/sku' } } } }, note: null,
    scheduleCron: null, scheduleEnabled: false, nextRunAt: null, runAs: null,
    pausedReason: 'Switched off after 3 failed runs in a row. The last: The source answered HTTP 503.', failuresInRow: 3,
    version: 4, lastRun: null }
  const { panel, writes } = await setup(page, 'owner', [job])
  const card = panel.getByRole('article', { name: 'Integration job Levels' })
  await expect(card.getByRole('status')).toHaveText(job.pausedReason)
  const schedule = card.getByRole('form', { name: 'Schedule of Levels' })
  await schedule.getByLabel(/Schedule/).fill('0 7 * * *')
  await schedule.getByRole('button', { name: 'Switch schedule on' }).click()
  await expect(card).toContainText('Scheduled 0 7 * * * (project time) as demo-owner')
  await expect(card.getByRole('status')).toHaveCount(0)
  expect(writes.at(-1)).toMatchObject({ method: 'PUT', body: { cron: '0 7 * * *', enabled: true, version: 4 } })
  await card.getByRole('form', { name: 'Schedule of Levels' }).getByRole('button', { name: 'Switch schedule off' }).click()
  await expect(card).toContainText('Schedule off (0 7 * * *)')
  expect(writes.at(-1)).toMatchObject({ body: { enabled: false, version: 5 } })

  await panel.getByRole('button', { name: 'Add integration job' }).click()
  const form = panel.getByRole('form', { name: 'Edit integration job' })
  await form.getByLabel('Kind').selectOption('push_stock')
  await expect(form.getByLabel('Operation that sends')).toHaveValue('update')
  await expect(form.getByLabel('Operation that reads the target')).toHaveValue('list')
  await expect(form.getByLabel('Mapping (JSON)')).toHaveValue(/"rowsAt": "\/stock"/)
  await form.getByLabel('Kind').selectOption('pull_stock')
  await expect(form.getByLabel('Receipt note')).toBeVisible()
  await expect(form.getByLabel('Operation that reads the target')).toHaveCount(0)
})

test('a member who is not the owner sees the jobs but cannot run or change them', async ({ page }) => {
  const job = { jobId: 'job-1', projectId, name: 'Levels', kind: 'pull_stock', profileVersionId: 'supplier-v2', operationId: 'list',
    verifyOperationId: null, mapping: {}, note: 'nightly', scheduleCron: null, scheduleEnabled: false, nextRunAt: null, runAs: null,
    pausedReason: null, failuresInRow: 0, version: 1, lastRun: null }
  const { panel } = await setup(page, 'editor', [job])
  await expect(panel).toContainText('Only the project owner can create, run or schedule integration jobs.')
  const card = panel.getByRole('article', { name: 'Integration job Levels' })
  await expect(card).toContainText('Pull stock · Supplier · list')
  await expect(card.getByRole('button', { name: /Run Levels now|Preview Levels|Edit Levels/ })).toHaveCount(0)
  await expect(card.getByRole('form', { name: 'Schedule of Levels' })).toHaveCount(0)
})
