import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/** Worked time with its clock times (docs/domain/worker-calendar.md LB31-LB32) against a mocked API. */
test('worked time can carry its clock times, and its minutes start from them', async ({ page }) => {
  const run = { productionRunId: 'run-lt', projectId: 'prj-e2e', workflowId: null, workflowRevisionId: null, runNumber: 'RUN-LT',
    runType: 'production', runStatus: 'running', targetItemId: null, plannedOutputQty: 1, actualOutputQty: null, workOrderId: 'wo-1',
    bomId: null, bomVersion: null }
  const person = (workedMinutes: number, suggestedMinutes: number | null) =>
    ({ userId: 'kim', sharePercent: 100, workedMinutes, suggestedMinutes, suggestedFrom: 'shifts' })
  let labor: Record<string, unknown> = { productionRunId: 'run-lt', workedMinutes: 0, lines: [], people: [person(0, 60)] }
  const recorded: Record<string, unknown>[] = []

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/production-runs/run-lt/labor') {
      const body = request.postDataJSON() as Record<string, unknown>
      recorded.push(body)
      labor = { productionRunId: 'run-lt', workedMinutes: 120, people: [person(120, null)],
        lines: [{ runLaborId: 'lt-1', userId: 'kim', workedMinutes: 120, note: null, recordedBy: 'demo-owner',
          recordedAt: '2041-09-01T03:00:00Z', cancelled: false, cancelledBy: null, cancelledAt: null, cancelReason: null,
          workedFrom: body.workedFrom, workedTo: body.workedTo }] }
      return ok(route, labor)
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/production-runs/run-lt') return ok(route, run)
    if (pathname === '/api/production-runs/run-lt/labor') return ok(route, labor)
    if (pathname === '/api/projects/prj-e2e/workers') {
      return ok(route, [{ projectId: 'prj-e2e', userId: 'kim', owner: false, calendarSet: true, weeklyHours: 40, shifts: [], daysOff: [] }])
    }
    if (pathname === '/api/production-runs/run-lt/equipment-time') {
      return ok(route, { productionRunId: 'run-lt', defaultEquipmentId: null, suggestedMinutes: null, runningMinutes: 0,
        runningCost: 0, costComplete: true, lines: [] })
    }
    if (pathname === '/api/production-runs/run-lt/setups') {
      return ok(route, { productionRunId: 'run-lt', defaultEquipmentId: null, setupMinutes: 0, setupCost: 0, costComplete: true, lines: [] })
    }
    if (pathname === '/api/production-runs/run-lt/cost') {
      return ok(route, { productionRunId: 'run-lt', materialCost: 0, costComplete: true, outputQuantity: 0, costPerUnit: null,
        costBasis: 'CURRENT', costBasisAt: '2041-09-01T00:00:00Z', estimated: false, lines: [] })
    }
    if (pathname === '/api/production-runs/run-lt/by-product-value') {
      return ok(route, { productionRunId: 'run-lt', byProductValue: 0, valueComplete: true, costBasis: 'CURRENT',
        costBasisAt: '2041-09-01T00:00:00Z', estimated: false, lines: [] })
    }
    if (pathname === '/api/production-runs/run-lt/material-usage') return ok(route, { bomId: null, lines: [] })
    if (pathname === '/api/production-runs/run-lt/instruction') {
      return ok(route, { productionRunId: 'run-lt', open: true, instruction: null, checks: [], requiredSteps: 0, requiredDone: 0,
        complete: true, undone: [] })
    }
    if (pathname === '/api/production-runs/run-lt/quality-checklist') {
      return ok(route, { productionRunId: 'run-lt', required: 0, requiredPassed: 0, requiredMissing: 0, failed: 0, lines: [] })
    }
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/runs/run-lt')
  const panel = page.getByRole('region', { name: 'Labor', exact: true })
  await expect(panel).toContainText('No worked time yet', { timeout: 15_000 })
  const minutesField = panel.getByLabel('Worked minutes')
  await expect(minutesField).toHaveValue('60')

  // Both or neither, before anything is sent.
  await panel.getByLabel('Worked from').fill('2041-09-01T09:00')
  await panel.getByRole('button', { name: 'Record worked time' }).click()
  await expect(panel.getByRole('alert')).toHaveText('Give both Worked from and Worked to, or neither.')
  expect(recorded).toHaveLength(0)

  // With both, the minutes start from the time between.
  await panel.getByLabel('Worked to').fill('2041-09-01T11:00')
  await expect(minutesField).toHaveValue('120')
  await expect(panel).toContainText('From the clock times; fewer minutes leave out breaks.')
  await panel.getByRole('button', { name: 'Record worked time' }).click()
  await expect(panel).toContainText('kim · 2041-09-01 09:00–11:00')
  const sent = await page.evaluate(() => ({ from: new Date('2041-09-01T09:00').toISOString(), to: new Date('2041-09-01T11:00').toISOString() }))
  expect(recorded).toHaveLength(1)
  expect(recorded[0]).toMatchObject({ userId: 'kim', workedMinutes: 120, workedFrom: sent.from, workedTo: sent.to })
  await expect(panel.getByLabel('Worked from')).toHaveValue('')
  await expect(panel.getByLabel('Worked to')).toHaveValue('')
})
