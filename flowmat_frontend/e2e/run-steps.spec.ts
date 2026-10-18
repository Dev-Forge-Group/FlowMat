import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * A production run's process steps (docs/architecture/adr/ADR-011-production-steps.md decision 9) against a mocked API: each
 * step says what it made or gets and how many of its instruction steps are confirmed, Start sends the step, a step past its
 * time limit is marked late but can still complete (docs/domain/flow-run.md F12), and a refused Complete shows why.
 */
test('a run goes through its process steps', async ({ page }) => {
  const run = { productionRunId: 'run-st', projectId: 'prj-e2e', workflowId: 'wf-line', workflowRevisionId: 'rev-line', runNumber: 'RUN-ST',
    runType: 'simulation', runStatus: 'running', targetItemId: 'bread', plannedOutputQty: 100, actualOutputQty: null, workOrderId: null,
    bomId: null, bomVersion: null }
  const step = (stepId: string, nodeName: string, sequenceNo: number, status: string, extra: Record<string, unknown> = {}) => ({
    stepId, nodeId: `n-${stepId}`, nodeName, sequenceNo, status, gateway: false, sourceStepId: null, sourceConnectionId: null,
    scheduledAt: null, startedAt: null, endedAt: null, errorCode: null, errorMessage: null, inputSnapshot: {}, outputSnapshot: null,
    joinWaiting: null, instructionRequired: 0, instructionDone: 0, ...extra })
  let bakeStatus = 'planned'
  const steps = () => [
    step('mix', 'Mix', 1, 'completed', { outputSnapshot: { quantity: 98, unit: 'kg' } }),
    step('bake', 'Bake', 2, bakeStatus, { inputSnapshot: { quantity: 98, unit: 'kg' }, instructionRequired: 1,
      ...(bakeStatus === 'running' ? { timeoutAt: '2026-10-17T09:00:00Z', overdueAt: '2026-10-17T09:00:05Z' } : {}) }),
  ]
  const sent: string[] = []

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    const acting = pathname.match(/^\/api\/production-runs\/run-st\/steps\/([^/]+)\/(start|complete)$/)
    if (request.method() === 'POST' && acting) {
      sent.push(`${acting[2]} ${acting[1]}`)
      if (acting[2] === 'complete') {
        return route.fulfill({ status: 409, contentType: 'application/json',
          body: JSON.stringify({ success: false, data: null, message: 'Confirm the required work instruction step 1 for Bake first.' }) })
      }
      bakeStatus = 'running'
      return ok(route, steps()[1])
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/production-runs/run-st') return ok(route, run)
    if (pathname === '/api/production-runs/run-st/steps') return ok(route, steps())
    if (pathname === '/api/production-runs/run-st/instruction') {
      return ok(route, { productionRunId: 'run-st', open: true, instruction: null, checks: [], requiredSteps: 0, requiredDone: 0,
        complete: true, undone: [] })
    }
    if (pathname === '/api/production-runs/run-st/cost') {
      return ok(route, { productionRunId: 'run-st', materialCost: 0, costComplete: true, outputQuantity: null, costPerUnit: null,
        costBasis: 'CURRENT', costBasisAt: null, estimated: false, lines: [] })
    }
    if (pathname === '/api/production-runs/run-st/by-product-value') {
      return ok(route, { productionRunId: 'run-st', byProductValue: 0, valueComplete: true, costBasis: 'CURRENT', costBasisAt: null,
        estimated: false, lines: [] })
    }
    if (pathname === '/api/production-runs/run-st/material-usage') return ok(route, { bomId: null, lines: [] })
    if (pathname === '/api/production-runs/run-st/quality-checklist') {
      return ok(route, { productionRunId: 'run-st', required: 0, requiredPassed: 0, requiredMissing: 0, failed: 0, lines: [] })
    }
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/runs/run-st')
  const panel = page.getByRole('region', { name: 'Process steps' })
  await expect(panel.getByRole('listitem').filter({ hasText: 'Mix' })).toContainText('completed · made 98 kg', { timeout: 15_000 })
  const bake = panel.getByRole('listitem').filter({ hasText: 'Bake' })
  await expect(bake).toContainText('planned · gets 98 kg · 0 of 1 instruction steps confirmed')
  await expect(panel.getByRole('button', { name: 'Start Mix' })).toHaveCount(0)
  await panel.getByRole('button', { name: 'Start Bake' }).click()
  await expect(bake).toContainText('running')
  await expect(bake).toContainText('late since')
  await panel.getByRole('button', { name: 'Complete Bake' }).click()
  await expect(panel.getByRole('alert')).toHaveText('Confirm the required work instruction step 1 for Bake first.')
  expect(sent).toEqual(['start bake', 'complete bake'])
})
