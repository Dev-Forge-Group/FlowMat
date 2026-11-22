import { expect, test } from '@playwright/test'
import { disposableProject } from './support/disposableProject'

test.skip(process.env.REAL_API_E2E !== '1' || process.env.FLOWMAT_FEATURE_E2E_DISPOSABLE !== '1',
  'Requires an explicitly disposable backend database')
test.use({ viewport: { width: 1920, height: 1080 }, trace: 'off' })

/**
 * The month's journey (docs/status/ROADMAP-2026-11-23.md §8, ADR-043): draw raw milk in → mix → fill → pack with
 * materials, equipment and time; ask for 1,000 bottles; see what is needed against stock, what can be made now, the
 * bottleneck and the time; confirm into a draft work order and a draft purchase order for the shortage.
 *
 * Written first (week 1), it passed once the plan (week 2), the editor's Plan panel (week 3) and confirmation (week 4) were
 * in, and runs in browser-e2e.yml since. The numbers are ADR-043's rules worked by hand:
 * Pack 1,000 / 24 = 41.6667 batches; 1% breakage before Pack, so Fill makes 1,010.1011; Fill takes 0.2 kg of mix a
 * bottle and 2% is lost before it, so Mix sends 206.1431 kg in 1.8406 batches of 112 kg; each batch takes 100 kg of milk.
 * 150 kg of milk makes 814.968 bottles; counted units round down to whole ones, as "buildable" does for BOMs today.
 */
test('melon milk: from a target of 1,000 bottles to a confirmed draft plan', async ({ page }) => {
  test.setTimeout(180_000)
  const fixture = await disposableProject(page, 'Melon-milk')
  const { projectId, api, request } = fixture
  let completed = false
  try {
    const tag = Date.now().toString(36).toUpperCase()
    for (const locationCode of ['RAW', 'FG']) {
      await api('POST', '/storage-locations', { projectId, locationCode, locationType: 'location' })
    }
    const item = async (code: string, itemName: string, itemType: string, unitId: string) =>
      (await api<{ itemId: string }>('POST', '/items', { projectId, itemCode: `MM-${code}-${tag}`, itemName, itemType, unitId })).itemId
    const milk = await item('MILK', 'Raw milk', 'material', 'unit_kg')
    const syrup = await item('SYRUP', 'Melon syrup', 'material', 'unit_kg')
    const sugar = await item('SUGAR', 'Sugar', 'material', 'unit_kg')
    const bottle = await item('BOTTLE', 'Bottle', 'material', 'unit_ea')
    const box = await item('BOX', 'Box of 24', 'material', 'unit_ea')
    const mixed = await item('MIX', 'Melon milk mix', 'semi', 'unit_kg')
    const filled = await item('FILLED', 'Filled bottle', 'semi', 'unit_ea')
    const melonMilk = await item('PRODUCT', 'Melon milk', 'product', 'unit_ea')
    // Raw milk is short: 150 kg of the 184.0564 kg needed.
    const stock: Record<string, number> = { [milk]: 150, [syrup]: 20, [sugar]: 50, [bottle]: 2000, [box]: 50 }
    for (const [itemId, quantity] of Object.entries(stock)) {
      await api('POST', '/inventories', { projectId, itemId, location: 'RAW', quantity })
    }
    const equipment = async (code: string) => (await api<{ equipmentId: string }>('POST', '/equipments', {
      projectId, equipmentCode: `${code}-${tag}`, equipmentName: code.toLowerCase(), equipmentType: 'machine',
    })).equipmentId
    const mixer = await equipment('MIXER')
    const filler = await equipment('FILLER')

    const { workflowId } = await api<{ workflowId: string }>('POST', '/workflows', { projectId, workflowName: `Melon milk ${tag}` })
    const node = async (processName: string, posX: number) => (await api<{ processId: string }>('POST', '/processes', {
      workflowId, processName, nodeType: 'process', posX, posY: 240,
    })).processId
    const port = async (processId: string, direction: 'input' | 'output', itemId: string, quantity: number, unit: string,
      resourceType = direction === 'output' && itemId === melonMilk ? 'product' : 'material') =>
      (await api<{ processIoId: string }>('POST', '/process-ios', {
        // Materials drawn from stock have no connection in, so they are not required inputs.
        processId, itemId, direction, quantity, unit, resourceType, requiredYn: 'N',
      })).processIoId
    const receive = await node('Receive milk', 80)
    const mix = await node('Mix', 420)
    const fill = await node('Fill', 760)
    const pack = await node('Pack', 1100)
    const receiveIn = await port(receive, 'input', milk, 1, 'kg')
    const receiveOut = await port(receive, 'output', milk, 1, 'kg')
    const mixMilk = await port(mix, 'input', milk, 100, 'kg')
    await port(mix, 'input', syrup, 10, 'kg')
    await port(mix, 'input', sugar, 5, 'kg')
    const mixOut = await port(mix, 'output', mixed, 112, 'kg')
    const fillMix = await port(fill, 'input', mixed, 0.2, 'kg')
    await port(fill, 'input', bottle, 1, 'ea')
    const fillOut = await port(fill, 'output', filled, 1, 'ea')
    const packFilled = await port(pack, 'input', filled, 24, 'ea')
    await port(pack, 'input', box, 1, 'ea')
    const packOut = await port(pack, 'output', melonMilk, 24, 'ea')
    const connect = (fromProcessId: string, fromIoId: string, toProcessId: string, toIoId: string, lossRate?: number) =>
      api('POST', '/process-connections', { workflowId, fromProcessId, toProcessId, fromIoId, toIoId, ...(lossRate ? { lossRate } : {}) })
    await connect(receive, receiveOut, mix, mixMilk)
    await connect(mix, mixOut, fill, fillMix, 0.02)
    await connect(fill, fillOut, pack, packFilled, 0.01)
    // Execution requirements (ADR-043 decision 4): a mix takes the mixer 30 minutes, a bottle the filler 3 seconds, a box
    // two people a minute.
    await api('PUT', `/processes/${mix}/requirements`, { requirements: [
      { kind: 'equipment', ref: mixer, amount: 1, per: 'batch' },
      { kind: 'time', amount: 30, unit: 'min', per: 'batch' }] })
    await api('PUT', `/processes/${fill}/requirements`, { requirements: [
      { kind: 'equipment', ref: filler, amount: 1, per: 'batch' },
      { kind: 'time', amount: 0.05, unit: 'min', per: 'output_unit' }] })
    await api('PUT', `/processes/${pack}/requirements`, { requirements: [
      { kind: 'crew', amount: 2, per: 'batch' },
      { kind: 'time', amount: 1, unit: 'min', per: 'batch' }] })
    const revision = await api<{ workflowRevisionId: string }>('POST', `/workflows/${workflowId}/revisions`)

    const inventories = async () => (await api<{ itemId: string; quantity: number }[]>('GET', `/inventories?projectId=${projectId}`))
      .map(({ itemId, quantity }) => `${itemId} ${quantity}`).sort()
    const before = await inventories()

    // Week 2: the plan, read only.
    const target = { processIoId: packOut, quantity: 1000, unit: 'ea' }
    const plan = await api<any>('POST', `/workflows/${workflowId}/plan-previews`, { target })
    expect(plan).toMatchObject({ revisionId: revision.workflowRevisionId, feasible: true, buildable: 814 })
    const needs = Object.fromEntries((plan.requirements as any[]).map((row) =>
      [row.itemId, [row.needed, row.available, row.shortage, row.unit]]))
    expect(needs).toEqual({
      [milk]: [184.0564, 150, 34.0564, 'kg'],
      [syrup]: [18.4057, 20, 0, 'kg'],
      [sugar]: [9.2029, 50, 0, 'kg'],
      [bottle]: [1010.1011, 2000, 0, 'ea'],
      [box]: [41.6667, 50, 0, 'ea'],
    })
    // The milk's reason: from the target back through Pack, the breakage, Fill, the loss, Mix and Receive.
    const milkRow = (plan.requirements as any[]).find((row) => row.itemId === milk)
    expect((milkRow.trace as { processId: string }[]).map((step) => step.processId))
      .toEqual(expect.arrayContaining([pack, fill, mix, receive]))
    expect(plan.nodes).toEqual(expect.arrayContaining([
      expect.objectContaining({ processId: mix, batches: 1.8406, minutes: 55.2169 }),
      expect.objectContaining({ processId: fill, batches: 1010.1011, minutes: 50.5051 }),
      expect.objectContaining({ processId: pack, batches: 41.6667, minutes: 41.6667 }),
    ]))
    // Stock limits first, then the longest equipment step: Mix.
    expect((plan.limits as { kind: string; at: string }[]).map(({ kind, at }) => `${kind} ${at}`))
      .toEqual([`stock ${receiveIn}`, `equipment-time ${mix}`])
    expect(await inventories()).toEqual(before)

    // Week 3: the editor's Plan panel shows the same and lights the milk's path.
    await page.goto(`/projects/${projectId}/workflows/${workflowId}`)
    await page.getByRole('button', { name: 'Plan', exact: true }).click()
    const panel = page.getByRole('region', { name: 'Plan the flow' })
    await panel.getByLabel('Target').fill('1000')
    await panel.getByRole('button', { name: 'Plan', exact: true }).click()
    await expect(panel.getByRole('row', { name: /Raw milk/ })).toContainText('184.0564 kg')
    await expect(panel.getByRole('row', { name: /Raw milk/ })).toContainText('34.0564 kg short')
    await expect(panel.getByText('Can make 814 now')).toBeVisible()
    await expect(panel.getByText('Bottleneck: Mix')).toBeVisible()
    await panel.getByRole('row', { name: /Raw milk/ }).click()
    await expect(page.locator('.react-flow__node.is-traced')).toHaveCount(4)

    // Week 4: confirming makes a draft work order and a draft purchase order for the milk, once, and moves no stock.
    const requestId = `melon-${tag}`
    const confirmation = { target, basis: plan.basis, requestId, supplierName: 'Dairy Co' }
    const confirmed = await api<{ workOrderId: string; purchaseOrderIds: string[] }>('POST',
      `/workflows/${workflowId}/plan-confirmations`, confirmation)
    expect(confirmed.purchaseOrderIds).toHaveLength(1)
    expect(await api('POST', `/workflows/${workflowId}/plan-confirmations`, confirmation)).toEqual(confirmed)
    expect(await api('GET', `/work-orders/${confirmed.workOrderId}`)).toMatchObject({
      workflowId, workflowRevisionId: revision.workflowRevisionId, targetItemId: melonMilk, targetQuantity: 1000,
      workOrderStatus: 'draft',
    })
    expect(await api('GET', `/purchase-orders/${confirmed.purchaseOrderIds[0]}`)).toMatchObject({
      status: 'draft', lines: [expect.objectContaining({ itemId: milk, quantity: 34.0564 })],
    })
    expect(await inventories()).toEqual(before)

    // A new revision changes the basis: confirming the old plan is refused and makes nothing.
    await api('POST', `/workflows/${workflowId}/revisions`)
    const stale = await request('POST', `/workflows/${workflowId}/plan-confirmations`,
      { ...confirmation, requestId: `${requestId}-stale` })
    expect(stale.status()).toBe(409)
    completed = true
  } finally {
    await fixture.dispose(completed)
  }
})
