import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

test.use({ timezoneId: 'UTC', locale: 'en-US' })

for (const remaining of ['one current process', 'no current processes'] as const) {
  test(`deleted processes keep their planned and actual labor visible with ${remaining}`, async ({ page }) => {
    const projectId = 'prj-deleted-step-plan'
    const workflowId = 'wf-deleted-step-plan'
    const workOrderId = 'wo-deleted-step-plan'
    const project = { projectId, projectName: 'Saved labor plans', ownerId: 'demo-owner' }
    const orderPath = `/api/work-orders/${workOrderId}`
    const order = { workOrderId, projectId, workflowId, workOrderNumber: 'WO-0042', workOrderTitle: 'Deleted process plan',
      workOrderStatus: 'in_progress', priority: 'normal', targetItemId: null, targetQuantity: 10, plannedStartAt: null,
      plannedEndAt: null, actualStartAt: null, actualEndAt: null, instruction: null, assignedTo: null, approvedBy: null,
      approvedAt: null, producedQuantity: 0, runCount: 1, bomId: null, instructionUrl: null, equipmentId: null }
    const comparison = { workOrderId, plannedMinutes: null, plannedComplete: true, runs: 1, actualMinutes: 100,
      people: [{ userId: 'former-worker', sharePercent: null, plannedMinutes: null, actualMinutes: 100 }],
      steps: [
        { nodeId: 'deleted-mix', nodeName: 'Retired mix', sequenceNo: 1, actualMinutes: 75, plannedMinutes: 60 },
        { nodeId: 'current-bake', nodeName: 'Current bake', sequenceNo: 2, actualMinutes: 20, plannedMinutes: 90 },
        { nodeId: 'deleted-pack', nodeName: 'Retired pack', sequenceNo: null, actualMinutes: 0, plannedMinutes: 30 },
      ], wholeRunMinutes: 5, stepPlannedMinutes: 180 }
    const processes = remaining === 'one current process'
      ? [{ processId: 'current-bake', processName: 'Current bake', nodeType: 'process' }]
      : []
    const unexpectedApi: string[] = []
    const writes: string[] = []
    const consoleErrors: string[] = []
    const pageErrors: string[] = []
    let processesRead = false
    page.on('console', (message) => {
      if (message.type() === 'error') consoleErrors.push(message.text())
    })
    page.on('pageerror', (error) => pageErrors.push(error.message))
    const emptyLists = new Set(['/api/items', '/api/units', '/api/equipments', '/api/boms', '/api/production-runs',
      '/api/organizations', '/api/notifications', `/api/projects/${projectId}/workers`, `/api/workflows/${workflowId}/revisions`])
    await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      const { pathname } = url
      if (await answerAuth(route, pathname)) return
      if (request.method() !== 'GET') {
        writes.push(`${request.method()} ${pathname}`)
      } else {
        if (pathname === '/api/projects') return ok(route, [project])
        if (pathname === `/api/projects/${projectId}`) return ok(route, project)
        if (pathname === `/api/projects/${projectId}/time-zone`) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
        if (pathname === '/api/project-members') return ok(route, [{ projectMemberId: 'owner-member', projectId, userId: 'demo-owner',
          projectRole: 'owner', memberStatus: 'active', joinedAt: null }])
        if (pathname === '/api/notifications/unread-count') return ok(route, { unread: 0 })
        if (pathname === '/api/workflows') return ok(route, [{ workflowId, projectId, workflowName: 'Current workflow', workflowStatus: 'active' }])
        if (pathname === '/api/work-orders') return ok(route, [order])
        if (pathname === `${orderPath}/labor-comparison`) return ok(route, comparison)
        if (pathname === '/api/processes' && url.searchParams.get('workflowId') === workflowId) {
          processesRead = true
          return ok(route, processes)
        }
        if (pathname === `${orderPath}/crew`) return ok(route, { workOrderId, plannedStartAt: null, plannedEndAt: null, members: [] })
        if (pathname === `${orderPath}/readiness`) return ok(route, { workOrderId, ready: true, remainingQuantity: 10, materials: [], checks: [] })
        if (pathname === `${orderPath}/allocations`) return ok(route, { workOrderId, allocations: [] })
        if (pathname === `${orderPath}/waste-disposal-estimate`) return ok(route, { workOrderId, bomId: null, quantity: 10,
          disposalCost: 0, costComplete: true, lines: [], problem: null })
        if (pathname === `${orderPath}/setup-comparison`) return ok(route, { workOrderId, equipmentId: null, plannedMinutes: null,
          plannedCost: null, runs: 1, actualMinutes: 0, actualCost: 0, actualCostComplete: true })
        if (pathname === `${orderPath}/running-comparison`) return ok(route, { workOrderId, equipmentId: null, capacityPerHour: null,
          plannedMinutes: null, plannedCost: null, runs: 1, actualMinutes: 0, actualCost: 0, actualCostComplete: true })
        if (pathname === `${orderPath}/labor-cost`) return ok(route, { workOrderId, runs: 1, workedMinutes: 100, cost: 0,
          costComplete: false, people: [{ userId: 'former-worker', workedMinutes: 100, cost: 0, costComplete: false }] })
        if (emptyLists.has(pathname)) return ok(route, [])
      }
      unexpectedApi.push(`${request.method()} ${pathname}`)
      return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ success: false, data: null,
        message: 'Unexpected mocked API request' }) })
    })

    await mockedLogin(page)
    await page.goto(`/projects/${projectId}/runs?view=work-orders`)
    await page.getByRole('row', { name: /Deleted process plan/ }).getByRole('button', { name: 'Readiness' }).click({ timeout: 15_000 })
    const labor = page.getByRole('region', { name: 'Labor planned and actual' })
    const savedSummary = 'By step: Retired mix 75 of 60 planned, Current bake 20 of 90 planned, Retired pack 0 of 30 planned, whole run 5'
    await expect(labor.getByText(savedSummary, { exact: true })).toBeVisible()
    await expect(labor).toContainText('Planned by step: 180 min')
    await expect(labor).toContainText('actual 100 min over 1 run')

    // Only surviving processes are editable; names and minutes from the saved comparison remain readable.
    await labor.getByRole('button', { name: 'Plan by step' }).click()
    const form = labor.getByRole('form', { name: 'Step plan' })
    await expect(form.getByRole('button', { name: 'Save step plan' })).toBeEnabled()
    await expect.poll(() => processesRead).toBe(true)
    await expect(form.getByLabel('Retired mix planned minutes')).toHaveCount(0)
    await expect(form.getByLabel('Retired pack planned minutes')).toHaveCount(0)
    if (remaining === 'one current process') {
      await expect(form.getByRole('spinbutton')).toHaveCount(1)
      await expect(form.getByLabel('Current bake planned minutes')).toHaveValue('90')
    } else {
      await expect(form.getByRole('spinbutton')).toHaveCount(0)
      await expect(form.getByText('The workflow has no process to plan.', { exact: true })).toBeVisible()
    }
    await expect(labor.getByText(savedSummary, { exact: true })).toBeVisible()
    await form.getByRole('button', { name: 'Cancel', exact: true }).click()
    await expect(form).toHaveCount(0)
    await expect(labor.getByText(savedSummary, { exact: true })).toBeVisible()
    await expect(labor).toContainText('Planned by step: 180 min')
    expect(writes).toEqual([])
    expect(unexpectedApi).toEqual([])
    expect(consoleErrors).toEqual([])
    expect(pageErrors).toEqual([])
  })
}
