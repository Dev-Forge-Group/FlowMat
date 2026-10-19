import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

function deferred() {
  let resolve!: () => void
  const promise = new Promise<void>((done) => { resolve = done })
  return { promise, resolve }
}

async function fixture(page: Page, mode: 'delay' | 'lost-reply') {
  const gate = deferred()
  const recorded: Record<string, unknown>[] = []
  const person = (userId: string, workedMinutes: number, suggestedMinutes: number) =>
    ({ userId, sharePercent: 100, workedMinutes, suggestedMinutes, suggestedFrom: 'run' })
  const labor = (saved: boolean) => ({ productionRunId: 'run-labor-race', workedMinutes: saved ? 20 : 0,
    people: [person('kim', saved ? 20 : 0, saved ? 40 : 60), person('lee', 0, 120)],
    lines: saved ? [{ runLaborId: 'saved-line', userId: 'kim', workedMinutes: 20, note: 'First pass',
      recordedBy: 'demo-owner', recordedAt: '2026-10-18T00:00:00Z', cancelled: false,
      cancelledBy: null, cancelledAt: null, cancelReason: null }] : [] })
  let saved = false
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/production-runs/run-labor-race/labor') {
      recorded.push(request.postDataJSON() as Record<string, unknown>)
      if (mode === 'delay') await gate.promise
      saved = true
      if (mode === 'lost-reply' && recorded.length === 1) {
        return route.fulfill({ status: 500, contentType: 'application/json',
          body: JSON.stringify({ success: false, data: null, message: 'Reply was lost' }) })
      }
      return ok(route, labor(true))
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/production-runs/run-labor-race') return ok(route, {
      productionRunId: 'run-labor-race', projectId: 'prj-e2e', workflowId: null, workflowRevisionId: null,
      runNumber: 'RUN-LABOR-RACE', runType: 'simulation', runStatus: 'running', targetItemId: null,
      plannedOutputQty: 1, actualOutputQty: null, workOrderId: null, bomId: null, bomVersion: null,
    })
    if (pathname === '/api/production-runs/run-labor-race/labor') return ok(route, labor(saved))
    if (pathname === '/api/projects/prj-e2e/workers') return ok(route, ['kim', 'lee'].map((userId) =>
      ({ projectId: 'prj-e2e', userId, owner: false, calendarSet: false, weeklyHours: 0, shifts: [], daysOff: [] })))
    if (pathname === '/api/production-runs/run-labor-race/equipment-time') return ok(route, {
      productionRunId: 'run-labor-race', defaultEquipmentId: null, suggestedMinutes: null, runningMinutes: 0,
      runningCost: 0, costComplete: true, lines: [],
    })
    if (pathname === '/api/production-runs/run-labor-race/setups') return ok(route, {
      productionRunId: 'run-labor-race', defaultEquipmentId: null, setupMinutes: 0, setupCost: 0, costComplete: true, lines: [],
    })
    if (pathname === '/api/production-runs/run-labor-race/cost') return ok(route, {
      productionRunId: 'run-labor-race', materialCost: 0, costComplete: true, outputQuantity: 0, costPerUnit: null,
      costBasis: 'CURRENT', costBasisAt: '2026-10-18T00:00:00Z', estimated: false, lines: [],
    })
    if (pathname === '/api/production-runs/run-labor-race/by-product-value') return ok(route, {
      productionRunId: 'run-labor-race', byProductValue: 0, valueComplete: true, costBasis: 'CURRENT',
      costBasisAt: '2026-10-18T00:00:00Z', estimated: false, lines: [],
    })
    if (pathname === '/api/production-runs/run-labor-race/material-usage') return ok(route, { bomId: null, lines: [] })
    if (pathname === '/api/production-runs/run-labor-race/instruction') return ok(route, {
      productionRunId: 'run-labor-race', open: true, instruction: null, checks: [], requiredSteps: 0, requiredDone: 0,
      complete: true, undone: [],
    })
    if (pathname === '/api/production-runs/run-labor-race/quality-checklist') return ok(route, {
      productionRunId: 'run-labor-race', required: 0, requiredPassed: 0, requiredMissing: 0, failed: 0, lines: [],
    })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/runs/run-labor-race')
  const panel = page.getByRole('region', { name: 'Labor', exact: true })
  await expect(panel.getByLabel('Worker', { exact: true })).toHaveValue('kim')
  await panel.getByLabel('Worked minutes').fill('20')
  await panel.getByLabel('Labor note').fill('First pass')
  return { panel, gate, recorded }
}

for (const nextWorker of ['kim', 'lee']) {
  test(`a labor reply preserves the next draft for ${nextWorker}`, async ({ page }) => {
    const { panel, gate, recorded } = await fixture(page, 'delay')
    try {
      await panel.getByRole('button', { name: 'Record worked time', exact: true }).click()
      await expect.poll(() => recorded.length).toBe(1)
      await panel.getByLabel('Worker', { exact: true }).selectOption(nextWorker)
      await panel.getByLabel('Worked minutes').fill('35')
      await panel.getByLabel('Labor note').fill('Next pass')
      gate.resolve()
      await expect(panel).toContainText('20 min: kim 20')
      await expect.soft(panel.getByLabel('Worker', { exact: true })).toHaveValue(nextWorker)
      await expect.soft(panel.getByLabel('Worked minutes')).toHaveValue('35')
      await expect.soft(panel.getByLabel('Labor note')).toHaveValue('Next pass')
      expect(recorded).toHaveLength(1)
      expect(recorded[0]).toMatchObject({ userId: 'kim', workedMinutes: 20, note: 'First pass' })
    } finally {
      gate.resolve()
    }
  })
}

test('retrying labor after a lost reply keeps the original request key', async ({ page }) => {
  const { panel, recorded } = await fixture(page, 'lost-reply')
  await panel.getByRole('button', { name: 'Record worked time', exact: true }).click()
  await expect(panel.getByRole('alert')).toContainText('Reply was lost')
  await panel.getByRole('button', { name: 'Retry worked time', exact: true }).click()
  await expect(panel).toContainText('20 min: kim 20')
  expect(recorded).toHaveLength(2)
  expect(recorded[1]).toEqual(recorded[0])
})
