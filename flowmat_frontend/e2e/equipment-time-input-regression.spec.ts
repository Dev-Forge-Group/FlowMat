import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

type Kind = 'running' | 'setup'
const controls = {
  running: { region: 'Equipment running cost', equipment: 'Running equipment', minutes: 'Running minutes',
    note: 'Running note', button: 'Record running time', path: 'equipment-time', field: 'runningMinutes' },
  setup: { region: 'Setup cost', equipment: 'Setup equipment', minutes: 'Setup minutes',
    note: 'Setup note', button: 'Record setup', path: 'setups', field: 'setupMinutes' },
} as const

async function fixture(page: Page, kind: Kind, finished = false) {
  let release!: () => void
  const gate = new Promise<void>((done) => { release = done })
  const recorded: Record<string, unknown>[] = []
  const saved = { running: finished, setup: finished }
  let corrections: unknown[] = []
  const payload = (mode: Kind) => ({ productionRunId: 'run-equipment-race', defaultEquipmentId: 'press-a',
    suggestedMinutes: saved[mode] ? 40 : 60, runningMinutes: saved[mode] ? 20 : 0,
    setupMinutes: saved[mode] ? 20 : 0, runningCost: saved[mode] ? 10 : 0,
    setupCost: saved[mode] ? 10 : 0, costComplete: true,
    lines: saved[mode] ? [{ runEquipmentTimeId: 'time-1', runSetupId: 'setup-1', equipmentId: 'press-a',
      equipmentLabel: 'PRESS-A', runningMinutes: 20, setupMinutes: 20, runningCost: 10, setupCost: 10,
      hourlyCost: 30, hourlyCostVersion: 1, note: 'First pass', recordedBy: 'demo-owner',
      recordedAt: '2026-10-19T00:00:00Z', cancelled: false, cancelledBy: null, cancelledAt: null, cancelReason: null }] : [] })
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === `/api/production-runs/run-equipment-race/${controls[kind].path}`) {
      recorded.push(request.postDataJSON() as Record<string, unknown>)
      await gate
      saved[kind] = true
      return ok(route, payload(kind))
    }
    if (request.method() === 'POST' && pathname === '/api/production-runs/run-equipment-race/corrections') {
      const body = request.postDataJSON() as { reason: string; lines: Record<string, unknown>[] }
      recorded.push(body)
      await gate
      const correction = { productionRunCorrectionId: 'correction-1', productionRunId: 'run-equipment-race',
        correctionNo: 1, status: 'pending_approval', reason: body.reason, requestedBy: 'demo-owner',
        requestedAt: '2026-10-19T01:00:00Z', decidedBy: null, decidedAt: null, decisionNote: null, appliedAt: null,
        lines: body.lines.map((line, index) => ({ ...line, lineNo: index + 1 })) }
      corrections = [correction]
      return ok(route, correction)
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/production-runs/run-equipment-race/corrections') return ok(route, corrections)
    if (pathname === '/api/production-runs/run-equipment-race') return ok(route, {
      productionRunId: 'run-equipment-race', projectId: 'prj-e2e', workflowId: null, workflowRevisionId: null,
      runNumber: 'RUN-EQUIPMENT-RACE', runType: 'simulation', runStatus: finished ? 'finished' : 'running', targetItemId: null,
      plannedOutputQty: 1, actualOutputQty: finished ? 1 : null, workOrderId: null, bomId: null, bomVersion: null,
    })
    if (pathname === '/api/equipments') return ok(route, ['a', 'b'].map((id) => ({ equipmentId: `press-${id}`,
      projectId: 'prj-e2e', equipmentCode: `PRESS-${id.toUpperCase()}`, equipmentName: `Press ${id}`,
      equipmentType: 'machine', equipmentStatus: 'active', details: {} })))
    if (pathname === '/api/production-runs/run-equipment-race/equipment-time') return ok(route, payload('running'))
    if (pathname === '/api/production-runs/run-equipment-race/setups') return ok(route, payload('setup'))
    if (pathname === '/api/production-runs/run-equipment-race/labor') return ok(route, {
      productionRunId: 'run-equipment-race', workedMinutes: 0, people: [], lines: [], steps: [],
    })
    if (pathname === '/api/production-runs/run-equipment-race/cost') return ok(route, {
      productionRunId: 'run-equipment-race', materialCost: 0, costComplete: true, outputQuantity: 0, costPerUnit: null,
      costBasis: 'CURRENT', costBasisAt: null, estimated: false, lines: [],
    })
    if (pathname === '/api/production-runs/run-equipment-race/by-product-value') return ok(route, {
      productionRunId: 'run-equipment-race', byProductValue: 0, valueComplete: true, costBasis: 'CURRENT',
      costBasisAt: null, estimated: false, lines: [],
    })
    if (pathname === '/api/production-runs/run-equipment-race/material-usage') return ok(route, { bomId: null, lines: [] })
    if (pathname === '/api/production-runs/run-equipment-race/instruction') return ok(route, {
      productionRunId: 'run-equipment-race', open: !finished, instruction: null, checks: [], requiredSteps: 0, requiredDone: 0,
      complete: true, undone: [],
    })
    if (pathname === '/api/production-runs/run-equipment-race/quality-checklist') return ok(route, {
      productionRunId: 'run-equipment-race', required: 0, requiredPassed: 0, requiredMissing: 0, failed: 0, lines: [],
    })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/runs/run-equipment-race')
  const config = finished ? { ...controls[kind],
    equipment: `Correction ${kind} equipment`, minutes: `Correction ${kind} minutes`,
    note: kind === 'running' ? 'Running time correction reason' : 'Setup correction reason',
    button: 'Request correction' } : controls[kind]
  const panel = page.getByRole('region', { name: config.region, exact: true })
  if (finished) {
    await panel.getByText(kind === 'running' ? 'Request a running time correction' : 'Request a setup correction').click()
    await panel.getByLabel(config.equipment).selectOption('press-a')
    await panel.getByRole('checkbox', { name: 'Cancel PRESS-A 20 min' }).check()
  }
  await expect(panel.getByLabel(config.equipment)).toHaveValue('press-a')
  await panel.getByLabel(config.minutes).fill('20')
  await panel.getByLabel(config.note).fill('First pass')
  return { panel, config, recorded, release }
}

for (const finished of [false, true]) {
  for (const kind of ['running', 'setup'] as const) {
    for (const nextEquipment of ['press-a', 'press-b']) {
      test(`${kind} ${finished ? 'correction' : 'record'} reply preserves the next draft for ${nextEquipment}`, async ({ page }) => {
        const { panel, config, recorded, release } = await fixture(page, kind, finished)
        try {
          await panel.getByRole('button', { name: config.button, exact: true }).click()
          await expect.poll(() => recorded.length).toBe(1)
          await panel.getByLabel(config.equipment).selectOption(nextEquipment)
          await panel.getByLabel(config.minutes).fill('35')
          await panel.getByLabel(config.note).fill('Next pass')
          if (finished) {
            const checkbox = panel.getByRole('checkbox', { name: 'Cancel PRESS-A 20 min' })
            await checkbox.uncheck()
            await checkbox.check()
          }
          release()
          if (finished) await expect(panel.getByRole('status')).toContainText('Requested correction #1')
          else await expect(panel.getByRole('row', { name: /PRESS-A/ })).toContainText('First pass')
          await expect.soft(panel.getByLabel(config.equipment)).toHaveValue(nextEquipment)
          await expect.soft(panel.getByLabel(config.minutes)).toHaveValue('35')
          await expect.soft(panel.getByLabel(config.note)).toHaveValue('Next pass')
          expect(recorded).toHaveLength(1)
          if (finished) {
            await expect.soft(panel.getByRole('checkbox', { name: 'Cancel PRESS-A 20 min' })).toBeChecked()
            expect(recorded[0]).toEqual({ reason: 'First pass', lines: [
              kind === 'running' ? { kind: 'cancel_running_time', targetRunEquipmentTimeId: 'time-1' }
                : { kind: 'cancel_setup', targetRunSetupId: 'setup-1' },
              { kind: kind === 'running' ? 'add_running_time' : 'add_setup', equipmentId: 'press-a', [config.field]: 20 },
            ] })
          } else expect(recorded[0]).toMatchObject({ equipmentId: 'press-a', [config.field]: 20, note: 'First pass' })
        } finally {
          release()
        }
      })
    }
  }
}
