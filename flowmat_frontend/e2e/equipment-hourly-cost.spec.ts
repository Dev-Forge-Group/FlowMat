import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

for (const mode of ['normal', 'lost-reply', 'lost-clear', 'gateway-error'] as const) {
  test(`equipment planning rate and separate setup estimate with ${mode}`, async ({ page }) => {
    if (mode === 'lost-clear') await page.clock.install()
    const projectId = 'setup-fixture'
    const equipment = { equipmentId: 'press', projectId, equipmentCode: 'PRESS', equipmentName: 'Press', equipmentType: 'machine',
      equipmentStatus: 'active', details: { manufacturer: null, modelName: null, serialNo: null, capacityPerHour: 10,
        powerKwh: null, waterLiter: null, location: null } }
    let rate = { equipmentId: 'press', hourlyCost: null as number | null, version: 0, updatedBy: null as string | null, updatedAt: null }
    let changeoverMinutes = 1
    let loadReads = 0
    let reads = 0
    const commands: { hourlyCost: number | null; expectedVersion: number }[] = []
    let release: (() => void) | undefined
    const hold = new Promise<void>((resolve) => { release = resolve })
    await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
      const request = route.request(); const url = new URL(request.url()); const { pathname } = url
      if (await answerAuth(route, pathname)) return
      if (pathname === '/api/equipments/press/hourly-cost') {
        if (request.method() === 'GET') { reads++; return ok(route, rate) }
        const command = request.postDataJSON(); commands.push(command)
        if (command.expectedVersion === rate.version) rate = { ...rate, hourlyCost: command.hourlyCost, version: rate.version + 1, updatedBy: 'demo-owner' }
        else if (command.expectedVersion !== rate.version - 1 || command.hourlyCost !== rate.hourlyCost)
          return route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ success: false, data: null, message: 'expectedVersion changed; reload the current hourlyCost before saving a different rate.' }) })
        if (mode !== 'normal' && commands.length === 1) {
          await hold
          if (mode !== 'gateway-error') return route.abort('failed')
          return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ success: false, data: null, message: 'Gateway unavailable' }) })
        }
        return ok(route, rate)
      }
      if (pathname === '/api/equipments/press/changeovers' || pathname === '/api/equipments/press/changeovers/default') {
        if (request.method() === 'PUT') changeoverMinutes = request.postDataJSON().minutes
        else if (request.method() !== 'GET') throw new Error(`Unexpected write ${pathname}`)
        return ok(route, [{ changeoverId: 'default', equipmentId: 'press', fromItemId: null, toItemId: null,
          fromItemCode: null, fromItemName: null, toItemCode: null, toItemName: null, minutes: changeoverMinutes, note: null }])
      }
      if (request.method() !== 'GET') throw new Error(`Unexpected write ${pathname}`)
      if (pathname === '/api/equipments') return ok(route, [equipment])
      if (pathname.endsWith('/schedule')) return ok(route, { equipmentId: 'press', timeZone: 'Asia/Seoul', calendar: null, downtimes: [], days: [] })
      if (pathname.endsWith('/availability')) return ok(route, { equipmentId: 'press', from: url.searchParams.get('from'), to: url.searchParams.get('to'),
        calendarSet: false, workingHours: 168, downtimeHours: 0, availableHours: 168, capacityPerHour: 10, capacity: 1680 })
      if (pathname === '/api/equipment-load') { loadReads++; return ok(route, { from: url.searchParams.get('from'), to: url.searchParams.get('to'), equipment: [{
        equipmentId: 'press', equipmentCode: 'PRESS', equipmentName: 'Press', equipmentStatus: 'active', capacityPerHour: 10,
        calendarSet: false, availableHours: 168, downtimeHours: 0, plannedHours: 2, draftHours: 0, loadPercent: 1.2, overloaded: false,
        unplannedOrders: 0, unmeasuredOrders: 0, changeovers: null, orders: [{ workOrderId: 'order', workOrderNumber: 'WO-SETUP',
          workOrderTitle: 'Product', workOrderStatus: 'approved', targetItemId: 'product', targetItemCode: 'PROD',
          plannedStartAt: url.searchParams.get('from'), plannedEndAt: url.searchParams.get('to'), remainingQuantity: 10,
          changeoverMinutes, neededHours: 1.02, hoursInWindow: 1.02, setupCostEstimate: rate.hourlyCost == null ? null : changeoverMinutes * rate.hourlyCost / 60 }] }] }) }
      return ok(route, [])
    })
    await mockedLogin(page)
    await page.goto(`/projects/${projectId}/inventory?tab=equipment`)
    await page.getByRole('row', { name: /PRESS.*Press/ }).getByRole('button', { name: 'Schedule', exact: true }).click()
    const panel = page.getByRole('region', { name: 'Equipment hourly cost' })
    const input = panel.getByRole('spinbutton', { name: 'Hourly equipment cost', exact: true })
    if (mode !== 'lost-clear') await input.fill('60')
    await panel.getByRole('button', { name: 'Save hourly cost', exact: true }).click()
    if (mode !== 'normal') {
      await expect(input).toBeDisabled()
      release!()
      await expect(panel.getByRole('status')).toHaveText('Rate save is unconfirmed. Retry with these values to recover the saved rate.')
      await expect(input).toHaveValue(mode === 'lost-clear' ? '' : '60')
      await expect(input).toBeDisabled()
      if (mode === 'lost-clear') {
        const previousReads = reads
        await page.clock.fastForward(31_000) // The shared query client keeps reads fresh for 30 seconds.
        await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')))
        await expect.poll(() => reads).toBeGreaterThan(previousReads)
      }
      await panel.getByRole('button', { name: 'Save hourly cost', exact: true }).click()
      await expect.poll(() => commands[1]).toEqual(commands[0])
    }
    await expect(panel.getByText(mode === 'lost-clear' ? 'Current planning rate: not set' : 'Current planning rate: 60 per hour', { exact: true })).toBeVisible()
    expect(rate.version).toBe(1)
    if (mode === 'normal') {
      await input.fill('70')
      // Another editor saved after this editor loaded version 1.
      rate = { ...rate, hourlyCost: 80, version: 2, updatedBy: 'another-editor' }
      await panel.getByRole('button', { name: 'Save hourly cost', exact: true }).click()
      await expect(panel.getByRole('alert')).toContainText('expectedVersion')
      await expect(input).toHaveValue('70')
      await panel.getByRole('button', { name: 'Reload current rate', exact: true }).click()
      await expect(input).toHaveValue('80')
      await input.fill('60')
      await panel.getByRole('button', { name: 'Save hourly cost', exact: true }).click()
      await expect(panel.getByText('Current planning rate: 60 per hour', { exact: true })).toBeVisible()
    }
    await page.getByText('Load by week', { exact: true }).click()
    const board = page.getByRole('region', { name: 'Equipment load' })
    await expect(board.getByText(mode === 'lost-clear' ? 'Setup estimate: unknown' : 'Setup estimate: 1', { exact: true })).toBeVisible()
    await expect(board.getByText('Whole-order setup at current equipment rates; shown separately from material and actual costs.', { exact: true })).toBeVisible()
    if (mode === 'normal') {
      const previousReads = loadReads
      const changeovers = page.getByRole('group', { name: 'Changeovers', exact: true })
      const pair = changeovers.getByRole('row', { name: /Any item.*Any item/ })
      await pair.getByRole('button', { name: 'Change time', exact: true }).click()
      await pair.getByLabel('New minutes', { exact: true }).fill('2')
      await pair.getByRole('button', { name: 'Save', exact: true }).click()
      await expect.poll(() => loadReads).toBeGreaterThan(previousReads)
      await expect(board.getByText('Setup estimate: 2', { exact: true })).toBeVisible()
    }
  })
}
