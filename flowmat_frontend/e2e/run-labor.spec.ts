import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * Worked time of an open run (docs/domain/worker-calendar.md LB1-LB7) against a mocked API: the form starts at the crew
 * member with time left and their suggestion, another person brings their own, and a record or a cancel shows the run's labor
 * as the server answers.
 */
test('worked time starts from the crew and their shifts, and a mistake is cancelled with a reason', async ({ page }) => {
  const run = { productionRunId: 'run-lb', projectId: 'prj-e2e', workflowId: null, workflowRevisionId: null, runNumber: 'RUN-LB',
    runType: 'production', runStatus: 'running', targetItemId: null, plannedOutputQty: 1, actualOutputQty: null, workOrderId: 'wo-1',
    bomId: null, bomVersion: null }
  const line = (id: string, userId: string, minutes: number, cancelReason: string | null = null) => ({ runLaborId: id, userId,
    workedMinutes: minutes, note: null, recordedBy: 'demo-owner', recordedAt: '2026-10-18T00:00:00Z', cancelled: cancelReason != null,
    cancelledBy: cancelReason ? 'demo-owner' : null, cancelledAt: cancelReason ? '2026-10-18T01:00:00Z' : null, cancelReason })
  const person = (userId: string, sharePercent: number, workedMinutes: number, suggestedMinutes: number | null, suggestedFrom: string) =>
    ({ userId, sharePercent, workedMinutes, suggestedMinutes, suggestedFrom })
  const worker = (userId: string, owner = false) => ({ projectId: 'prj-e2e', userId, owner, calendarSet: false, weeklyHours: 0,
    shifts: [], daysOff: [] })
  let labor: Record<string, unknown> = { productionRunId: 'run-lb', workedMinutes: 0, lines: [],
    people: [person('kim', 50, 0, 60, 'shifts'), person('lee', 100, 0, 120, 'run')] }
  const recorded: Record<string, unknown>[] = []
  const cancelled: unknown[] = []

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/production-runs/run-lb/labor') {
      recorded.push(request.postDataJSON() as Record<string, unknown>)
      labor = { productionRunId: 'run-lb', workedMinutes: 120, lines: [line('lb-1', 'lee', 120)],
        people: [person('kim', 50, 0, 60, 'shifts'), person('lee', 100, 120, null, 'run')] }
      return ok(route, labor)
    }
    if (request.method() === 'POST' && pathname === '/api/production-runs/run-lb/labor/lb-1/cancel') {
      cancelled.push(request.postDataJSON())
      labor = { productionRunId: 'run-lb', workedMinutes: 0, lines: [line('lb-1', 'lee', 120, 'Wrong person')],
        people: [person('kim', 50, 0, 60, 'shifts'), person('lee', 100, 0, 120, 'run')] }
      return ok(route, labor)
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/production-runs/run-lb') return ok(route, run)
    if (pathname === '/api/production-runs/run-lb/labor') return ok(route, labor)
    if (pathname === '/api/projects/prj-e2e/workers') {
      return ok(route, [worker('demo-owner', true), worker('kim'), worker('lee'), worker('park')])
    }
    if (pathname === '/api/production-runs/run-lb/equipment-time') {
      return ok(route, { productionRunId: 'run-lb', defaultEquipmentId: null, suggestedMinutes: null, runningMinutes: 0,
        runningCost: 0, costComplete: true, lines: [] })
    }
    if (pathname === '/api/production-runs/run-lb/setups') {
      return ok(route, { productionRunId: 'run-lb', defaultEquipmentId: null, setupMinutes: 0, setupCost: 0, costComplete: true,
        lines: [] })
    }
    if (pathname === '/api/production-runs/run-lb/cost') {
      return ok(route, { productionRunId: 'run-lb', materialCost: 0, costComplete: true, outputQuantity: 0, costPerUnit: null,
        costBasis: 'CURRENT', costBasisAt: '2026-10-18T00:00:00Z', estimated: false, lines: [] })
    }
    if (pathname === '/api/production-runs/run-lb/by-product-value') {
      return ok(route, { productionRunId: 'run-lb', byProductValue: 0, valueComplete: true, costBasis: 'CURRENT',
        costBasisAt: '2026-10-18T00:00:00Z', estimated: false, lines: [] })
    }
    if (pathname === '/api/production-runs/run-lb/material-usage') return ok(route, { bomId: null, lines: [] })
    if (pathname === '/api/production-runs/run-lb/instruction') {
      return ok(route, { productionRunId: 'run-lb', open: true, instruction: null, checks: [], requiredSteps: 0, requiredDone: 0,
        complete: true, undone: [] })
    }
    if (pathname === '/api/production-runs/run-lb/quality-checklist') {
      return ok(route, { productionRunId: 'run-lb', required: 0, requiredPassed: 0, requiredMissing: 0, failed: 0, lines: [] })
    }
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/runs/run-lb')
  const panel = page.getByRole('region', { name: 'Labor', exact: true })
  await expect(panel).toContainText('No worked time yet', { timeout: 15_000 })
  const workerField = panel.getByLabel('Worker', { exact: true })
  const minutesField = panel.getByLabel('Worked minutes')
  // The crew comes first with their share, then the project's other workers; kim has time left, so the form starts there.
  await expect(workerField.locator('option')).toHaveText(['Choose who worked', 'kim · crew 50%', 'lee · crew 100%', 'demo-owner', 'park'])
  await expect(workerField).toHaveValue('kim')
  await expect(minutesField).toHaveValue('60')
  await expect(panel).toContainText("Suggested from kim's shifts during the run at 50%, less what is recorded.")

  await workerField.selectOption('lee')
  await expect(minutesField).toHaveValue('120')
  await expect(panel).toContainText("Suggested from the run's time, less what is recorded (lee has no shifts).")
  await panel.getByRole('button', { name: 'Record worked time' }).click()
  await expect(panel).toContainText('120 min: lee 120')
  expect(recorded).toHaveLength(1)
  expect(recorded[0]).toMatchObject({ userId: 'lee', workedMinutes: 120 })
  expect(typeof recorded[0].requestId).toBe('string')

  page.once('dialog', (dialog) => void dialog.accept('Wrong person'))
  await panel.getByRole('button', { name: 'Cancel worked time' }).click()
  await expect(panel).toContainText('cancelled: Wrong person')
  await expect(panel).toContainText('No worked time yet')
  expect(cancelled).toEqual([{ reason: 'Wrong person' }])
})

/**
 * Worked time of a finished run (LB9-LB11): the record form is gone, and a correction cancels a record and adds the time of
 * another person, starting from the crew member with time left and their suggestion.
 */
test('a finished run asks for a worked time correction instead of changing its time', async ({ page }) => {
  const run = { productionRunId: 'run-lbc', projectId: 'prj-e2e', workflowId: null, workflowRevisionId: null, runNumber: 'RUN-LBC',
    runType: 'production', runStatus: 'finished', targetItemId: null, plannedOutputQty: 1, actualOutputQty: 1, workOrderId: 'wo-1',
    bomId: null, bomVersion: null }
  const labor = { productionRunId: 'run-lbc', workedMinutes: 45,
    lines: [{ runLaborId: 'lb-1', userId: 'kim', workedMinutes: 45, note: null, recordedBy: 'demo-owner',
      recordedAt: '2026-10-19T00:00:00Z', cancelled: false, cancelledBy: null, cancelledAt: null, cancelReason: null }],
    people: [{ userId: 'kim', sharePercent: 50, workedMinutes: 45, suggestedMinutes: 15, suggestedFrom: 'shifts' },
      { userId: 'lee', sharePercent: 100, workedMinutes: 0, suggestedMinutes: 120, suggestedFrom: 'run' }],
    steps: [{ stepId: 'st-mix', nodeName: 'Mix', sequenceNo: 1, status: 'completed', startedAt: '2026-10-19T08:00:00Z',
      endedAt: '2026-10-19T09:30:00Z', workedMinutes: 0 }] }
  const requested: { reason: string; lines: Record<string, unknown>[] }[] = []

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/production-runs/run-lbc/corrections') {
      const body = request.postDataJSON() as { reason: string; lines: Record<string, unknown>[] }
      requested.push(body)
      return ok(route, { productionRunCorrectionId: 'c1', productionRunId: 'run-lbc', correctionNo: 1, status: 'pending_approval',
        reason: body.reason, requestedBy: 'demo-owner', requestedAt: '2026-10-19T01:00:00Z', decidedBy: null, decidedAt: null,
        decisionNote: null, appliedAt: null, lines: [] })
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/production-runs/run-lbc') return ok(route, run)
    if (pathname === '/api/production-runs/run-lbc/labor') return ok(route, labor)
    if (pathname === '/api/production-runs/run-lbc/equipment-time') {
      return ok(route, { productionRunId: 'run-lbc', defaultEquipmentId: null, suggestedMinutes: null, runningMinutes: 0,
        runningCost: 0, costComplete: true, lines: [] })
    }
    if (pathname === '/api/production-runs/run-lbc/setups') {
      return ok(route, { productionRunId: 'run-lbc', defaultEquipmentId: null, setupMinutes: 0, setupCost: 0, costComplete: true,
        lines: [] })
    }
    if (pathname === '/api/production-runs/run-lbc/cost') {
      return ok(route, { productionRunId: 'run-lbc', materialCost: 0, costComplete: true, outputQuantity: 1, costPerUnit: 0,
        costBasis: 'HISTORICAL', costBasisAt: '2026-10-19T00:00:00Z', estimated: false, lines: [] })
    }
    if (pathname === '/api/production-runs/run-lbc/by-product-value') {
      return ok(route, { productionRunId: 'run-lbc', byProductValue: 0, valueComplete: true, costBasis: 'HISTORICAL',
        costBasisAt: '2026-10-19T00:00:00Z', estimated: false, lines: [] })
    }
    if (pathname === '/api/production-runs/run-lbc/material-usage') return ok(route, { bomId: null, lines: [] })
    if (pathname === '/api/production-runs/run-lbc/instruction') {
      return ok(route, { productionRunId: 'run-lbc', open: false, instruction: null, checks: [], requiredSteps: 0, requiredDone: 0,
        complete: true, undone: [] })
    }
    if (pathname === '/api/production-runs/run-lbc/quality-checklist') {
      return ok(route, { productionRunId: 'run-lbc', required: 0, requiredPassed: 0, requiredMissing: 0, failed: 0, lines: [] })
    }
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/runs/run-lbc')
  const panel = page.getByRole('region', { name: 'Labor', exact: true })
  await expect(panel).toContainText('45 min: kim 45', { timeout: 15_000 })
  await expect(panel.getByRole('button', { name: 'Record worked time' })).toHaveCount(0)
  await expect(panel.getByRole('button', { name: 'Cancel worked time' })).toHaveCount(0)

  await panel.getByText('Request a worked time correction').click()
  await panel.getByLabel('Cancel kim 45 min').check()
  // kim still has time left, so the added line starts there; lee brings their own suggestion.
  await expect(panel.getByLabel('Correction worker')).toHaveValue('kim')
  await expect(panel.getByLabel('Correction worked minutes')).toHaveValue('15')
  await panel.getByLabel('Correction worker').selectOption('lee')
  await expect(panel.getByLabel('Correction worked minutes')).toHaveValue('120')
  // On Mix, lee's time starts from the step's hour and a half (LB17).
  await panel.getByLabel('Correction step').selectOption('st-mix')
  await expect(panel.getByLabel('Correction worked minutes')).toHaveValue('90')
  await panel.getByLabel('Worked time correction reason').fill('Lee did the shift, not kim')
  await panel.getByRole('button', { name: 'Request correction' }).click()
  await expect(panel.getByRole('status')).toContainText('Requested correction #1')
  expect(requested).toEqual([{ reason: 'Lee did the shift, not kim', lines: [
    { kind: 'cancel_labor', targetRunLaborId: 'lb-1' },
    { kind: 'add_labor', userId: 'lee', workedMinutes: 90, stepId: 'st-mix' },
  ] }])
})

/**
 * Worked time on a process step (LB13-LB16): picking a started step starts the minutes from its time at the person's share,
 * and the record names the step.
 */
test('worked time can name a started process step and starts from its time', async ({ page }) => {
  const run = { productionRunId: 'run-lbs', projectId: 'prj-e2e', workflowId: null, workflowRevisionId: null, runNumber: 'RUN-LBS',
    runType: 'production', runStatus: 'running', targetItemId: null, plannedOutputQty: 1, actualOutputQty: null, workOrderId: 'wo-1',
    bomId: null, bomVersion: null }
  const steps = [
    { stepId: 'st-mix', nodeName: 'Mix', sequenceNo: 1, status: 'completed', startedAt: '2026-10-19T08:00:00Z',
      endedAt: '2026-10-19T09:30:00Z', workedMinutes: 0 },
    { stepId: 'st-bake', nodeName: 'Bake', sequenceNo: 2, status: 'planned', startedAt: null, endedAt: null, workedMinutes: 0 },
  ]
  let labor: Record<string, unknown> = { productionRunId: 'run-lbs', workedMinutes: 0, lines: [], steps,
    people: [{ userId: 'kim', sharePercent: 50, workedMinutes: 0, suggestedMinutes: 60, suggestedFrom: 'shifts' }] }
  const recorded: Record<string, unknown>[] = []

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/production-runs/run-lbs/labor') {
      recorded.push(request.postDataJSON() as Record<string, unknown>)
      labor = { productionRunId: 'run-lbs', workedMinutes: 45, steps: [{ ...steps[0], workedMinutes: 45 }, steps[1]],
        lines: [{ runLaborId: 'lb-1', userId: 'kim', workedMinutes: 45, note: null, recordedBy: 'demo-owner',
          recordedAt: '2026-10-19T10:00:00Z', cancelled: false, cancelledBy: null, cancelledAt: null, cancelReason: null,
          stepId: 'st-mix', stepName: 'Mix' }],
        people: [{ userId: 'kim', sharePercent: 50, workedMinutes: 45, suggestedMinutes: 15, suggestedFrom: 'shifts' }] }
      return ok(route, labor)
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/production-runs/run-lbs') return ok(route, run)
    if (pathname === '/api/production-runs/run-lbs/labor') return ok(route, labor)
    if (pathname === '/api/production-runs/run-lbs/equipment-time') {
      return ok(route, { productionRunId: 'run-lbs', defaultEquipmentId: null, suggestedMinutes: null, runningMinutes: 0,
        runningCost: 0, costComplete: true, lines: [] })
    }
    if (pathname === '/api/production-runs/run-lbs/setups') {
      return ok(route, { productionRunId: 'run-lbs', defaultEquipmentId: null, setupMinutes: 0, setupCost: 0, costComplete: true,
        lines: [] })
    }
    if (pathname === '/api/production-runs/run-lbs/cost') {
      return ok(route, { productionRunId: 'run-lbs', materialCost: 0, costComplete: true, outputQuantity: 0, costPerUnit: null,
        costBasis: 'CURRENT', costBasisAt: '2026-10-19T00:00:00Z', estimated: false, lines: [] })
    }
    if (pathname === '/api/production-runs/run-lbs/by-product-value') {
      return ok(route, { productionRunId: 'run-lbs', byProductValue: 0, valueComplete: true, costBasis: 'CURRENT',
        costBasisAt: '2026-10-19T00:00:00Z', estimated: false, lines: [] })
    }
    if (pathname === '/api/production-runs/run-lbs/material-usage') return ok(route, { bomId: null, lines: [] })
    if (pathname === '/api/production-runs/run-lbs/instruction') {
      return ok(route, { productionRunId: 'run-lbs', open: true, instruction: null, checks: [], requiredSteps: 0, requiredDone: 0,
        complete: true, undone: [] })
    }
    if (pathname === '/api/production-runs/run-lbs/quality-checklist') {
      return ok(route, { productionRunId: 'run-lbs', required: 0, requiredPassed: 0, requiredMissing: 0, failed: 0, lines: [] })
    }
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/runs/run-lbs')
  const panel = page.getByRole('region', { name: 'Labor', exact: true })
  const stepField = panel.getByLabel('Labor step')
  // Only the started step is offered; the whole run is the default with kim's shift suggestion.
  await expect(stepField.locator('option')).toHaveText(['Whole run', '#1 Mix'], { timeout: 15_000 })
  await expect(panel.getByLabel('Worked minutes')).toHaveValue('60')
  await stepField.selectOption('st-mix')
  await expect(panel.getByLabel('Worked minutes')).toHaveValue('45')
  await expect(panel).toContainText("Suggested from Mix's time at 50%, less what is recorded.")
  await panel.getByRole('button', { name: 'Record worked time' }).click()
  await expect(panel).toContainText('kim · Mix')
  await expect(panel).toContainText('By step: Mix 45')
  expect(recorded[0]).toMatchObject({ userId: 'kim', workedMinutes: 45, stepId: 'st-mix' })
})
