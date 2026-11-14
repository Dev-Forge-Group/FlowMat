import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * Recording on the run screen while the server is slow or its reply is lost (docs/domain/production-run-recording.md),
 * against a mocked API: a resend carries the same request key, and what is typed during a save is kept as the next draft.
 */
interface Sent { itemId: string; direction: string; plannedQty: number; actualQty?: number; unit: string; requestId: string }

const salt = { itemId: 'salt', projectId: 'prj-e2e', itemCode: 'SALT', itemName: 'salt', itemType: 'material',
  resourceCategory: 'material', unitId: 'unit_kg', itemStatus: 'active', lotManageYn: 'N', unitCost: null }

function recorded(body: Sent, id: string) {
  return { productionRunItemId: id, productionRunId: 'run-rp', processId: null, processIoId: null, inventoryId: null,
    itemId: body.itemId, direction: body.direction, plannedQty: body.plannedQty, actualQty: body.actualQty ?? null, unit: body.unit,
    quantitySource: 'manual', conversionRate: null, lotId: null, cancelled: false, cancelledBy: null, cancelledAt: null,
    cancelReason: null, productionRunCorrectionId: null, cancelledByCorrectionId: null, viaItemId: null }
}

/** Mocks an open run; {@code answer} decides each recording's reply from the bodies sent so far. */
async function mockRun(page: Page, answer: (sent: Sent[]) => Promise<'ok' | 'lost'>) {
  const run = { productionRunId: 'run-rp', projectId: 'prj-e2e', workflowId: null, workflowRevisionId: null, runNumber: 'RUN-RP',
    runType: 'actual', runStatus: 'running', targetItemId: null, plannedOutputQty: 1, actualOutputQty: null, workOrderId: null,
    bomId: null, bomVersion: null }
  const sent: Sent[] = []
  const items: ReturnType<typeof recorded>[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/production-runs/run-rp/items') {
      const body = request.postDataJSON() as Sent
      sent.push(body)
      const reply = await answer(sent)
      // The server keeps one recording per request key, whatever happened to its reply.
      let item = items.find((one) => (one as { requestId?: string }).requestId === body.requestId)
      if (!item) {
        item = { ...recorded(body, `ri-${items.length + 1}`), requestId: body.requestId } as ReturnType<typeof recorded>
        items.push(item)
      }
      if (reply === 'lost') return route.abort('failed')
      return ok(route, item)
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/production-runs/run-rp') return ok(route, run)
    if (pathname === '/api/production-runs/run-rp/items') return ok(route, items)
    if (pathname === '/api/items') return ok(route, [salt])
    if (pathname === '/api/production-runs/run-rp/labor') return ok(route, { productionRunId: 'run-rp', workedMinutes: 0, lines: [], people: [] })
    if (pathname === '/api/production-runs/run-rp/equipment-time') {
      return ok(route, { productionRunId: 'run-rp', defaultEquipmentId: null, suggestedMinutes: null, runningMinutes: 0,
        runningCost: 0, costComplete: true, lines: [] })
    }
    if (pathname === '/api/production-runs/run-rp/setups') {
      return ok(route, { productionRunId: 'run-rp', defaultEquipmentId: null, setupMinutes: 0, setupCost: 0, costComplete: true, lines: [] })
    }
    if (pathname === '/api/production-runs/run-rp/cost') {
      return ok(route, { productionRunId: 'run-rp', materialCost: 0, costComplete: true, outputQuantity: 0, costPerUnit: null,
        costBasis: 'CURRENT', costBasisAt: '2026-11-14T00:00:00Z', estimated: false, lines: [] })
    }
    if (pathname === '/api/production-runs/run-rp/by-product-value') {
      return ok(route, { productionRunId: 'run-rp', byProductValue: 0, valueComplete: true, costBasis: 'CURRENT',
        costBasisAt: '2026-11-14T00:00:00Z', estimated: false, lines: [] })
    }
    if (pathname === '/api/production-runs/run-rp/material-usage') return ok(route, { bomId: null, lines: [] })
    if (pathname === '/api/production-runs/run-rp/instruction') {
      return ok(route, { productionRunId: 'run-rp', open: true, instruction: null, checks: [], requiredSteps: 0, requiredDone: 0,
        complete: true, undone: [] })
    }
    if (pathname === '/api/production-runs/run-rp/quality-checklist') {
      return ok(route, { productionRunId: 'run-rp', required: 0, requiredPassed: 0, requiredMissing: 0, failed: 0, lines: [] })
    }
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/runs/run-rp')
  const form = page.locator('#record-run-item')
  await expect(form).toBeVisible({ timeout: 15_000 })
  return { form, sent, items }
}

function field(form: ReturnType<Page['locator']>, label: RegExp) {
  return form.locator('label', { hasText: label }).locator('input, select').first()
}

test('a recording whose reply was lost is sent again under the same key and recorded once', async ({ page }) => {
  const f = await mockRun(page, async (sent) => (sent.length === 1 ? 'lost' : 'ok'))
  await field(f.form, /^Item \*/).selectOption('salt')
  await field(f.form, /^Planned \*$/).fill('3')
  await field(f.form, /^Unit \*/).fill('kg')
  await f.form.getByRole('button', { name: 'Record', exact: true }).click()
  await expect(f.form.getByText('The result is unconfirmed.')).toBeVisible()
  await expect(field(f.form, /^Planned \*$/)).toHaveValue('3')

  await f.form.getByRole('button', { name: 'Record', exact: true }).click()
  await expect(f.form.getByText('The result is unconfirmed.')).toHaveCount(0)
  await expect(field(f.form, /^Planned \*$/)).toHaveValue('')
  expect(f.sent).toHaveLength(2)
  expect(f.sent[0].requestId).toBeTruthy()
  expect(f.sent[1]).toEqual(f.sent[0])
  expect(f.items).toHaveLength(1)

  // Once answered, the same values again are a new recording with a new key.
  await field(f.form, /^Item \*/).selectOption('salt')
  await field(f.form, /^Planned \*$/).fill('3')
  await field(f.form, /^Unit \*/).fill('kg')
  await f.form.getByRole('button', { name: 'Record', exact: true }).click()
  await expect.poll(() => f.sent.length).toBe(3)
  expect(f.sent[2].requestId).not.toBe(f.sent[0].requestId)
})

test('what is typed while a recording saves stays as the next draft', async ({ page }) => {
  let release!: () => void
  const slow = new Promise<void>((resolve) => { release = resolve })
  const f = await mockRun(page, async (sent) => {
    if (sent.length === 1) await slow
    return 'ok'
  })
  await field(f.form, /^Item \*/).selectOption('salt')
  await field(f.form, /^Planned \*$/).fill('3')
  await field(f.form, /^Unit \*/).fill('kg')
  await f.form.getByRole('button', { name: 'Record', exact: true }).click()
  await expect(f.form.getByRole('button', { name: 'Recording...', exact: true })).toBeDisabled()
  await field(f.form, /^Planned \*$/).fill('7')
  await field(f.form, /^Actual$/).fill('6.5')
  release()

  await expect(f.form.getByRole('button', { name: 'Record', exact: true })).toBeEnabled()
  await expect(field(f.form, /^Planned \*$/)).toHaveValue('7')
  await expect(field(f.form, /^Actual$/)).toHaveValue('6.5')
  await expect(field(f.form, /^Item \*/)).toHaveValue('salt')
  expect(f.sent).toHaveLength(1)
  expect(f.sent[0]).toMatchObject({ itemId: 'salt', plannedQty: 3, unit: 'kg' })

  await f.form.getByRole('button', { name: 'Record', exact: true }).click()
  await expect.poll(() => f.sent.length).toBe(2)
  expect(f.sent[1]).toMatchObject({ itemId: 'salt', plannedQty: 7, actualQty: 6.5, unit: 'kg' })
  expect(f.sent[1].requestId).not.toBe(f.sent[0].requestId)
  await expect(field(f.form, /^Planned \*$/)).toHaveValue('')
})
