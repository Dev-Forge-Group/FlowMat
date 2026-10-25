import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * Cancelling an input of a work order's run asks whether its stock goes back to the order
 * (docs/domain/stock-allocation.md R2, R4), against a mocked API.
 */
const recording = (overrides: Record<string, unknown> = {}) => ({ productionRunItemId: 'ri-1', productionRunId: 'run-ra', processId: null,
  processIoId: null, inventoryId: 'inv-flour', itemId: 'flour', direction: 'input', plannedQty: 4, actualQty: 4, unit: 'kg',
  quantitySource: 'manual', conversionRate: null, lotId: null, cancelled: false, cancelledBy: null, cancelledAt: null, cancelReason: null,
  productionRunCorrectionId: null, cancelledByCorrectionId: null, viaItemId: null, ...overrides })

async function mockRun(page: Page, workOrderId: string | null, sent: unknown[]) {
  const run = { productionRunId: 'run-ra', projectId: 'prj-e2e', workflowId: null, workflowRevisionId: null, runNumber: 'RUN-RA',
    runType: 'production', runStatus: 'running', targetItemId: null, plannedOutputQty: 1, actualOutputQty: null, workOrderId,
    bomId: null, bomVersion: null }
  let items = [recording()]
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/production-runs/run-ra/items/ri-1/cancel') {
      const body = request.postDataJSON() as { reason: string }
      sent.push(body)
      items = [recording({ cancelled: true, cancelledBy: 'demo-owner', cancelledAt: '2026-10-24T01:00:00Z', cancelReason: body.reason })]
      return ok(route, items[0])
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/production-runs/run-ra') return ok(route, run)
    if (pathname === '/api/production-runs/run-ra/items') return ok(route, items)
    if (pathname === '/api/production-runs/run-ra/labor') return ok(route, { productionRunId: 'run-ra', workedMinutes: 0, lines: [], people: [] })
    if (pathname === '/api/production-runs/run-ra/equipment-time') {
      return ok(route, { productionRunId: 'run-ra', defaultEquipmentId: null, suggestedMinutes: null, runningMinutes: 0,
        runningCost: 0, costComplete: true, lines: [] })
    }
    if (pathname === '/api/production-runs/run-ra/setups') {
      return ok(route, { productionRunId: 'run-ra', defaultEquipmentId: null, setupMinutes: 0, setupCost: 0, costComplete: true, lines: [] })
    }
    if (pathname === '/api/production-runs/run-ra/cost') {
      return ok(route, { productionRunId: 'run-ra', materialCost: 0, costComplete: true, outputQuantity: 0, costPerUnit: null,
        costBasis: 'CURRENT', costBasisAt: '2026-10-24T00:00:00Z', estimated: false, lines: [] })
    }
    if (pathname === '/api/production-runs/run-ra/by-product-value') {
      return ok(route, { productionRunId: 'run-ra', byProductValue: 0, valueComplete: true, costBasis: 'CURRENT',
        costBasisAt: '2026-10-24T00:00:00Z', estimated: false, lines: [] })
    }
    if (pathname === '/api/production-runs/run-ra/material-usage') return ok(route, { bomId: null, lines: [] })
    if (pathname === '/api/production-runs/run-ra/instruction') {
      return ok(route, { productionRunId: 'run-ra', open: true, instruction: null, checks: [], requiredSteps: 0, requiredDone: 0,
        complete: true, undone: [] })
    }
    if (pathname === '/api/production-runs/run-ra/quality-checklist') {
      return ok(route, { productionRunId: 'run-ra', required: 0, requiredPassed: 0, requiredMissing: 0, failed: 0, lines: [] })
    }
    return ok(route, [])
  })
}

/** Answers the reason prompt, then the question about the work order as {@code allocateAgain} says; returns the dialogs seen. */
function answerDialogs(page: Page, allocateAgain: boolean): string[] {
  const seen: string[] = []
  page.on('dialog', (dialog) => {
    seen.push(dialog.type())
    void (dialog.type() === 'prompt' ? dialog.accept('Wrong LOT') : allocateAgain ? dialog.accept() : dialog.dismiss())
  })
  return seen
}

async function cancelTheInput(page: Page) {
  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/runs/run-ra')
  const row = page.getByRole('row').filter({ hasText: 'inv-flour' })
  await row.getByRole('button', { name: 'Cancel', exact: true }).click({ timeout: 15_000 })
  await expect(row).toContainText('cancelled')
}

test('an input of a work order\'s run goes back to the order when asked', async ({ page }) => {
  const sent: unknown[] = []
  await mockRun(page, 'wo-1', sent)
  const seen = answerDialogs(page, true)
  await cancelTheInput(page)
  await expect(page.getByRole('status').filter({ hasText: 'allocated to the work order again' }))
    .toHaveText('Cancelled. The returned stock is allocated to the work order again.')
  expect(seen).toEqual(['prompt', 'confirm'])
  expect(sent).toEqual([{ reason: 'Wrong LOT', reallocate: true }])
})

test('declining keeps the returned stock free', async ({ page }) => {
  const sent: unknown[] = []
  await mockRun(page, 'wo-1', sent)
  const seen = answerDialogs(page, false)
  await cancelTheInput(page)
  expect(seen).toEqual(['prompt', 'confirm'])
  expect(sent).toEqual([{ reason: 'Wrong LOT' }])
  await expect(page.getByText('allocated to the work order again')).toHaveCount(0)
})

test('a run without a work order only asks why', async ({ page }) => {
  const sent: unknown[] = []
  await mockRun(page, null, sent)
  const seen = answerDialogs(page, true)
  await cancelTheInput(page)
  expect(seen).toEqual(['prompt'])
  expect(sent).toEqual([{ reason: 'Wrong LOT' }])
})
