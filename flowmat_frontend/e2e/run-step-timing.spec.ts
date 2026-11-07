import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * A production run's step timing against a mocked API (archify's waterfall): on request, the steps and worked time with clock
 * times are bars on one time axis, each step under the step it came from; the running step is open (its length a lower
 * bound), the failed one red, the waiting one without a bar, and worked time recorded as minutes only is counted, not drawn.
 */
// The browser is in New York and the project in Seoul: the axis follows the project.
test.use({ timezoneId: 'America/New_York' })

test('a run shows its steps and worked time on one time axis on request', async ({ page }) => {
  const run = { productionRunId: 'run-tm', projectId: 'prj-e2e', workflowId: 'wf-line', workflowRevisionId: 'rev-line',
    runNumber: 'RUN-TM', runType: 'actual', runStatus: 'running', targetItemId: 'bread', plannedOutputQty: 100,
    actualOutputQty: null, workOrderId: null, bomId: null, bomVersion: null }
  const at = (time: string) => `2026-11-01T${time}:00Z`
  const step = (stepId: string, nodeName: string, sequenceNo: number, status: string, startedAt: string | null,
    endedAt: string | null, sourceStepId: string | null = null) => ({ stepId, nodeId: `n-${stepId}`, nodeName, sequenceNo, status,
    gateway: false, sourceStepId, sourceConnectionId: null, scheduledAt: null, startedAt, endedAt, errorCode: null,
    errorMessage: null, inputSnapshot: {}, outputSnapshot: null, joinWaiting: null, instructionRequired: 0, instructionDone: 0 })
  const worked = (runLaborId: string, userId: string, workedMinutes: number, workedFrom: string | null, workedTo: string | null) => ({
    runLaborId, userId, workedMinutes, note: null, recordedBy: 'owner', recordedAt: at('10:00'), cancelled: false,
    cancelledBy: null, cancelledAt: null, cancelReason: null, stepId: 'mix', stepName: 'Mix', workedFrom, workedTo })

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/production-runs/run-tm') return ok(route, run)
    if (pathname === '/api/production-runs/run-tm/steps') {
      return ok(route, [step('mix', 'Mix', 1, 'completed', at('09:00'), at('09:30')),
        step('bake', 'Bake', 2, 'running', at('09:30'), null, 'mix'), step('cool', 'Cool', 3, 'planned', null, null, 'bake'),
        step('wash', 'Wash', 4, 'failed', at('09:10'), at('09:15'))])
    }
    if (pathname === '/api/production-runs/run-tm/labor') {
      return ok(route, { productionRunId: 'run-tm', workedMinutes: 30, people: [],
        lines: [worked('l1', 'kim', 20, at('09:05'), at('09:25')), worked('l2', 'park', 10, null, null)], steps: [] })
    }
    if (pathname === '/api/production-runs/run-tm/instruction') {
      return ok(route, { productionRunId: 'run-tm', open: true, instruction: null, checks: [], requiredSteps: 0, requiredDone: 0,
        complete: true, undone: [] })
    }
    if (pathname === '/api/production-runs/run-tm/cost') {
      return ok(route, { productionRunId: 'run-tm', materialCost: 0, costComplete: true, outputQuantity: null, costPerUnit: null,
        costBasis: 'CURRENT', costBasisAt: null, estimated: false, lines: [] })
    }
    if (pathname === '/api/production-runs/run-tm/by-product-value') {
      return ok(route, { productionRunId: 'run-tm', byProductValue: 0, valueComplete: true, costBasis: 'CURRENT', costBasisAt: null,
        estimated: false, lines: [] })
    }
    if (pathname === '/api/production-runs/run-tm/material-usage') return ok(route, { bomId: null, lines: [] })
    if (pathname === '/api/projects/prj-e2e/time-zone') return ok(route, { projectId: 'prj-e2e', timeZone: 'Asia/Seoul', version: 0 })
    if (pathname === '/api/production-runs/run-tm/quality-checklist') {
      return ok(route, { productionRunId: 'run-tm', required: 0, requiredPassed: 0, requiredMissing: 0, failed: 0, lines: [] })
    }
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/runs/run-tm')
  const timing = page.getByRole('region', { name: 'Step timing' })
  await timing.getByRole('button', { name: 'Show step timing' }).click({ timeout: 15_000 })

  // Depth first: Bake came from Mix, Cool from Bake; kim's worked time sits under Mix.
  await expect(timing.getByRole('rowheader')).toHaveText([/^Mix\s+completed$/, /^kim\s+worked$/, /^Bake\s+running$/,
    /^Cool\s+planned$/, /^Wash\s+failed$/])
  await expect(timing.getByRole('row').filter({ hasText: 'Mix' })).toContainText('30 min')
  await expect(timing.getByRole('row').filter({ hasText: 'kim' })).toContainText('20 min')
  await expect(timing.getByRole('row').filter({ hasText: 'Bake' })).toContainText('≥ ')
  await expect(timing.getByRole('row').filter({ hasText: 'Cool' })).toContainText('not started')
  await expect(timing.locator('.run-timing__bar')).toHaveCount(4)
  await expect(timing.locator('.run-timing__bar--open')).toHaveCount(1)
  await expect(timing.locator('.run-timing__bar--failed')).toHaveCount(1)
  await expect(timing.getByText('1 worked-time record has minutes only and is not drawn.')).toBeVisible()

  // The axis starts at Mix's 09:00 UTC, written in the project's zone rather than the browser's.
  const [seoul, newYork] = await page.evaluate(() => ['Asia/Seoul', undefined].map((timeZone) => new Date(Date.UTC(2026, 10, 1, 9))
    .toLocaleString(undefined, { dateStyle: 'short', timeStyle: 'short', timeZone })))
  expect(seoul).not.toBe(newYork)
  await expect(timing.locator('.run-timing__axis > span').first()).toHaveText(seoul)
  await expect(timing).toContainText('Times in Asia/Seoul.')

  await timing.getByRole('button', { name: 'Hide step timing' }).click()
  await expect(timing.getByRole('table')).toHaveCount(0)
})
