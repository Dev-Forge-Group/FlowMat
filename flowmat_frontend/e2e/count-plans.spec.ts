import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

for (const scenario of ['blind-retry', 'changed', 'viewer', 'owner-baseline', 'record-retry', 'submit-retry'] as const) {
  test(`count plans: ${scenario}`, async ({ page }) => {
    const projectId = 'prj-count-plan'
    const stocks = ['first', 'second'].map((id) => ({ inventoryId: id, projectId, itemId: id, quantity: 10,
      location: `BIN-${id}`, lotId: null, inventoryStatus: 'available', deletedYn: 'N' }))
    const lines = stocks.map((stock) => ({ lineId: `line-${stock.inventoryId}`, inventoryId: stock.inventoryId,
      itemId: stock.itemId, lotId: null, location: stock.location, baselineQuantity: scenario === 'viewer' ? null : 10,
      checkpointQuantity: scenario === 'viewer' ? null : 10, countedQuantity: null as number | null,
      requiresRecount: false, countedBy: null as string | null, countedAt: null as string | null, entryVersion: 0 }))
    const plan = { planId: 'plan-1', projectId, blind: scenario !== 'owner-baseline', status: 'open', note: 'Cycle count',
      createdBy: 'demo-owner', createdAt: '2026-10-05T12:00:00Z', submittedBy: null as string | null,
      submittedAt: null as string | null, countId: null as string | null, lines }
    const plans = scenario === 'viewer' || scenario === 'owner-baseline' ? [plan] : []
    const writes: { pathname: string; body: Record<string, unknown> }[] = []
    let release!: () => void
    const gate = new Promise<void>((resolve) => { release = resolve })
    let submits = 0
    let adjustments = 0
    await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
      const request = route.request(); const { pathname } = new URL(request.url())
      if (await answerAuth(route, pathname)) return
      if (pathname === '/api/items') return ok(route, stocks.map((stock) => ({ itemId: stock.itemId,
        itemCode: stock.itemId.toUpperCase(), itemName: `Material ${stock.itemId}`, itemStatus: 'active', unitId: 'kg' })))
      if (pathname === '/api/project-members') return ok(route, [{ projectMemberId: 'pm-1', projectId, userId: 'demo-owner',
        projectRole: scenario === 'viewer' ? 'viewer' : 'owner', memberStatus: 'active' }])
      if (pathname === '/api/inventories') return ok(route, stocks)
      if (pathname === '/api/inventory-count-plans' && request.method() === 'GET') return ok(route, plans)
      if (pathname.startsWith('/api/inventory-count-plans') && request.method() !== 'GET') {
        const body = request.postDataJSON() as Record<string, unknown>; writes.push({ pathname, body })
        if (pathname === '/api/inventory-count-plans') {
          expect(body.inventoryIds).toEqual(['first', 'second']); expect(body.blind).toBe(true)
          expect(typeof body.requestId).toBe('string')
          if (!plans.length) plans.push(plan)
          if (scenario === 'blind-retry' && writes.length === 1) { await gate; return route.abort('failed') }
          return ok(route, plan)
        }
        if (pathname.endsWith('/submit')) {
          submits++
          if (scenario === 'changed' && submits === 1) {
            plan.status = 'recount_required'; lines[0].requiresRecount = true; lines[0].entryVersion++
            return route.fulfill({ status: 409, contentType: 'application/json',
              body: JSON.stringify({ success: false, data: null, message: 'Stock changed in this plan. Recount the flagged rows.' }) })
          }
          if (plan.status !== 'submitted') { adjustments++; plan.status = 'submitted'; plan.countId = 'count-1'; plan.submittedBy = 'demo-owner' }
          if (scenario === 'submit-retry' && submits === 1) { await gate; return route.abort('failed') }
          return ok(route, plan)
        }
        const line = lines.find((entry) => pathname.includes(entry.lineId))!
        if (pathname.endsWith('/recount')) {
          line.requiresRecount = false; line.checkpointQuantity = 11; line.countedQuantity = null; line.entryVersion++
          plan.status = 'open'
        } else {
          if (Number(body.expectedEntryVersion) === line.entryVersion) {
            line.countedQuantity = Number(body.countedQuantity); line.countedBy = 'demo-owner'; line.entryVersion++
          } else expect(body.countedQuantity).toBe(line.countedQuantity)
          if (scenario === 'record-retry' && line.lineId === 'line-first' && writes.filter((write) => write.pathname === pathname).length === 1) { await gate; return route.abort('failed') }
        }
        return ok(route, plan)
      }
      if (request.method() !== 'GET') throw new Error(`Unexpected write: ${pathname}`)
      return ok(route, [])
    })
    await mockedLogin(page)
    await page.goto(`/projects/${projectId}/inventory?tab=count-plans`)
    const panel = page.getByRole('region', { name: 'Count plans', exact: true })
    if (scenario === 'viewer' || scenario === 'owner-baseline') {
      await panel.getByLabel('Open count plan').selectOption('plan-1')
      if (scenario === 'viewer') {
        await expect(panel.getByRole('button', { name: /^Start plan/ })).toHaveCount(0)
        await expect(panel.getByLabel('Counted quantity').first()).toBeDisabled()
        await expect(panel).not.toContainText('Original baseline:')
      } else await expect(panel).toContainText('Original baseline: 10')
      expect(writes).toHaveLength(0); return
    }
    await panel.getByLabel(/FIRST.*BIN-first/).check()
    await panel.getByLabel(/SECOND.*BIN-second/).check()
    await panel.getByLabel('Plan note').fill('Cycle count')
    await panel.getByRole('button', { name: 'Start plan (2)' }).click()
    if (scenario === 'blind-retry') {
      await expect(panel.getByLabel('Plan note')).toBeDisabled(); release()
      await expect(panel).toContainText('The result is unconfirmed.')
      await panel.getByRole('button', { name: 'Retry count plan request' }).click()
      await expect.poll(() => writes[1]).toEqual(writes[0])
    }
    const first = panel.getByRole('form', { name: 'Count FIRST · Material first' })
    const second = panel.getByRole('form', { name: 'Count SECOND · Material second' })
    await expect(panel).not.toContainText('Original baseline:')
    await panel.getByLabel('Review baseline quantities (owner)').check()
    await expect(panel).toContainText('Original baseline: 10')
    await panel.getByLabel('Review baseline quantities (owner)').uncheck()
    await first.getByLabel('Counted quantity').fill('11')
    await first.getByRole('button', { name: 'Save measurement' }).click()
    if (scenario === 'record-retry') {
      await expect(first.getByLabel('Counted quantity')).toBeDisabled(); release()
      await expect(panel).toContainText('The result is unconfirmed.')
      await panel.getByRole('button', { name: 'Retry count plan request' }).click()
      const records = writes.filter((write) => write.pathname.endsWith('/line-first'))
      expect(records).toHaveLength(2); expect(records[0]).toEqual(records[1])
    }
    await expect(first).toContainText('Saved: 11')
    await second.getByLabel('Counted quantity').fill('8')
    await second.getByRole('button', { name: 'Save measurement' }).click()
    await expect(second).toContainText('Saved: 8')
    await first.getByLabel('Counted quantity').fill('12')
    await expect(panel.getByRole('button', { name: 'Submit count plan' })).toBeDisabled()
    await first.getByLabel('Counted quantity').fill('11')
    await panel.getByRole('button', { name: 'Submit count plan' }).click()
    if (scenario === 'submit-retry') {
      await expect(first.getByLabel('Counted quantity')).toBeDisabled(); release()
      await expect(panel).toContainText('The result is unconfirmed.')
      await panel.getByRole('button', { name: 'Retry count plan request' }).click()
      await expect.poll(() => submits).toBe(2)
    }
    if (scenario === 'changed') {
      await expect(first).toContainText('Recount required.')
      await expect(second).toContainText('Saved: 8')
      await first.getByRole('button', { name: 'Start recount' }).click()
      await expect(first.getByLabel('Counted quantity')).toHaveValue('')
      await first.getByLabel('Counted quantity').fill('11')
      await first.getByRole('button', { name: 'Save measurement' }).click()
      await panel.getByRole('button', { name: 'Submit count plan' }).click()
    }
    await expect(panel).toContainText('Stock was updated once.')
    await expect(first.getByLabel('Counted quantity')).toBeDisabled()
    expect(adjustments).toBe(1)
  })
}
