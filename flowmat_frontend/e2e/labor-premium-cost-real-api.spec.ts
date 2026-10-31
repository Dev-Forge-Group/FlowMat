import { randomUUID } from 'node:crypto'
import { expect, test, type APIResponse, type BrowserContext } from '@playwright/test'
import { disposableProject } from './support/disposableProject'

/**
 * Candidate destination: e2e/labor-premium-cost-real-api.spec.ts in an isolated frontend copy containing its existing
 * e2e/support/disposableProject.ts. BASE_URL must be that copy's Vite URL, proxying only to disposable Postgres/Redis/backend.
 * Required opt-ins: REAL_API_E2E=1, FLOWMAT_FEATURE_E2E_DISPOSABLE=1, FLOWMAT_LABOR_E2E_DISPOSABLE=1.
 * Account fixture: the normal demo-owner / demo1234 seed only. The owner signs in once through the UI; no second account,
 * invitation, mail, Slack notification, SQL or historical-date mutation is used. Project cleanup runs in finally.
 */
test.skip(process.env.REAL_API_E2E !== '1' || process.env.FLOWMAT_FEATURE_E2E_DISPOSABLE !== '1'
  || process.env.FLOWMAT_LABOR_E2E_DISPOSABLE !== '1' || !process.env.BASE_URL,
  'Requires BASE_URL for an isolated Vite proxy and explicit feature/labor disposable-backend opt-ins')

test.use({ trace: 'off' })

interface TimeZone { timeZone: string; version: number }
interface PremiumRules { today: string; current: { effectiveFrom: string } | null }
interface PersonCost {
  userId: string
  workedMinutes: number
  cost: number
  costComplete: boolean
  premiumCost: number
  premiumsComplete: boolean
}
interface CostLine {
  runLaborId: string
  userId: string
  workedMinutes: number
  rateDate: string
  rateBasis: string
  hourlyRate: number | null
  cost: number | null
  premiumFactor: number | null
  premiumCost: number | null
}
interface RunCost {
  productionRunId: string
  workedMinutes: number
  cost: number
  costComplete: boolean
  premiumCost: number
  premiumsComplete: boolean
  people: PersonCost[]
  lines: CostLine[]
}
interface OrderCost {
  workOrderId: string
  runs: number
  workedMinutes: number
  cost: number
  costComplete: boolean
  premiumCost: number
  premiumsComplete: boolean
  people: PersonCost[]
}

function serverTime(response: APIResponse): number {
  const timestamp = Date.parse(response.headers().date ?? '')
  expect(Number.isFinite(timestamp), 'The isolated backend response must expose its HTTP Date header').toBe(true)
  return timestamp
}

function fixedZoneAtNoon(timestamp: number) {
  // A fixed IANA offset puts the project's current hour at 12, with time to record ten past hours on today's date.
  // Etc/GMT names use the opposite sign to their UTC offset. These IDs are accepted by the project's IANA-zone API.
  const offsetHours = 12 - new Date(timestamp).getUTCHours()
  const timeZone = offsetHours === 0 ? 'Etc/UTC' : `Etc/GMT${offsetHours > 0 ? '-' : '+'}${Math.abs(offsetHours)}`
  return { timeZone, offsetHours }
}

function projectDay(timestamp: number, offsetHours: number): string {
  return new Date(timestamp + offsetHours * 3_600_000).toISOString().slice(0, 10)
}

test('real run and work-order labor costs expose daily premiums and keep the owner UI compatible', async ({ page, browser }) => {
  test.setTimeout(150_000)
  let ownerLogins = 0
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/auth/login') ownerLogins++
  })
  const fixture = await disposableProject(page, 'Labor-premium-cost')
  const { projectId, api, request } = fixture
  let anonymous: BrowserContext | undefined
  let completed = false
  try {
    const zonePath = `/projects/${projectId}/time-zone`
    const zoneReply = await request('GET', zonePath)
    expect(zoneReply.ok()).toBe(true)
    const zone = (await zoneReply.json()).data as TimeZone
    const { timeZone, offsetHours } = fixedZoneAtNoon(serverTime(zoneReply))
    await api('PUT', zonePath, { timeZone, expectedVersion: zone.version })

    const premiumsPath = `/projects/${projectId}/labor-premiums`
    const today = (await api<PremiumRules>('GET', premiumsPath)).today
    expect(today).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    // Both histories start on the backend's current project date, an allowed effective date with no backdating.
    const rule = await api<PremiumRules>('PUT', premiumsPath, {
      effectiveFrom: today, overtimeAfterMinutes: 480, overtimePercent: 50, reason: 'Disposable API acceptance',
    })
    expect(rule.current?.effectiveFrom).toBe(today)
    const workerPath = `/projects/${projectId}/workers/demo-owner`
    await api('PUT', `${workerPath}/labor-rates`, { hourlyRate: 60, effectiveFrom: today })

    const workflow = await api<{ workflowId: string }>('POST', '/workflows', {
      projectId, workflowName: 'Premium cost without BOM',
    })
    await api('POST', '/processes', { workflowId: workflow.workflowId, processName: 'Work', nodeType: 'process' })
    const revision = await api<{ workflowRevisionId: string }>('POST', `/workflows/${workflow.workflowId}/revisions`)
    const title = 'Premium cost actual API'
    const order = await api<{ workOrderId: string }>('POST', '/work-orders', {
      projectId, workflowId: workflow.workflowId, workOrderTitle: title, targetQuantity: 1,
    })
    await api('POST', `/work-orders/${order.workOrderId}/approve`)
    const run = await api<{ productionRunId: string; bomId: string | null }>('POST', '/production-runs/start', {
      projectId, workflowId: workflow.workflowId, workflowRevisionId: revision.workflowRevisionId,
      workOrderId: order.workOrderId, plannedOutputQty: 1, runType: 'actual',
    })
    expect(run.bomId).toBeNull()
    const runPath = `/production-runs/${run.productionRunId}`
    const runCostPath = `${runPath}/labor-cost`
    const orderCostPath = `/work-orders/${order.workOrderId}/labor-cost`

    await page.goto(`/projects/${projectId}/runs/${run.productionRunId}`)
    const laborPanel = page.getByRole('region', { name: 'Labor', exact: true })
    await laborPanel.getByLabel('Worker', { exact: true }).selectOption('demo-owner')
    await laborPanel.getByLabel('Worked minutes', { exact: true }).fill('120')
    await laborPanel.getByLabel('Labor note', { exact: true }).fill('Premium API acceptance')
    const recorded = page.waitForResponse((reply) => reply.request().method() === 'POST'
      && new URL(reply.url()).pathname === `/api${runPath}/labor`)
    await laborPanel.getByRole('button', { name: 'Record worked time', exact: true }).click()
    expect((await recorded).status()).toBe(200)
    await expect(laborPanel).toContainText('120 min: demo-owner 120')

    // The added fields are also present before attendance exists: the known base cost stays, with no known factor.
    const withoutAttendance = await api<RunCost>('GET', runCostPath)
    expect(withoutAttendance).toMatchObject({ workedMinutes: 120, cost: 120, costComplete: true,
      premiumCost: 120, premiumsComplete: false })
    expect(withoutAttendance.lines).toHaveLength(1)
    expect(withoutAttendance.lines[0]).toMatchObject({ userId: 'demo-owner', workedMinutes: 120,
      rateDate: today, rateBasis: 'recorded', hourlyRate: 60, cost: 120, premiumFactor: null, premiumCost: 120 })
    expect(withoutAttendance.people).toEqual([{ userId: 'demo-owner', workedMinutes: 120, cost: 120,
      costComplete: true, premiumCost: 120, premiumsComplete: false }])
    expect(await api<OrderCost>('GET', orderCostPath)).toMatchObject({ runs: 1, workedMinutes: 120, cost: 120,
      premiumCost: 120, premiumsComplete: false })
    await page.reload()
    const runCostPanel = page.getByRole('region', { name: 'Labor cost', exact: true })
    await expect(runCostPanel).toContainText('120 min · Labor cost: 120')
    await expect(runCostPanel).not.toContainText('with pay premiums')

    // Use the backend clock with a two-minute margin. Fixed offset/noon makes both ends past and on today's project date.
    const clockReply = await request('GET', premiumsPath)
    expect(clockReply.ok()).toBe(true)
    const now = serverTime(clockReply)
    const clockOut = now - 120_000
    const clockIn = clockOut - 10 * 3_600_000
    expect(projectDay(clockIn, offsetHours)).toBe(today)
    expect(projectDay(clockOut, offsetHours)).toBe(today)
    expect((await clockReply.json()).data.today).toBe(today)
    await api('POST', `${workerPath}/attendance`, { requestId: randomUUID(),
      clockIn: new Date(clockIn).toISOString(), clockOut: new Date(clockOut).toISOString(), note: 'Ten closed hours today' })
    const pay = await api<{ attendedMinutes: number; paidMinutes: number; amount: number; costComplete: boolean }>('GET',
      `${workerPath}/attendance-pay?${new URLSearchParams({ from: today, to: today })}`)
    expect(pay).toMatchObject({ attendedMinutes: 600, paidMinutes: 660, amount: 660, costComplete: true })

    const priced = await api<RunCost>('GET', runCostPath)
    expect(priced).toMatchObject({ productionRunId: run.productionRunId, workedMinutes: 120, cost: 120,
      costComplete: true, premiumCost: 132, premiumsComplete: true })
    expect(priced.lines).toHaveLength(1)
    expect(priced.lines[0]).toMatchObject({ userId: 'demo-owner', workedMinutes: 120, rateDate: today,
      rateBasis: 'recorded', hourlyRate: 60, cost: 120, premiumFactor: 1.1, premiumCost: 132 })
    expect(priced.people).toEqual([{ userId: 'demo-owner', workedMinutes: 120, cost: 120,
      costComplete: true, premiumCost: 132, premiumsComplete: true }])
    const byOrder = await api<OrderCost>('GET', orderCostPath)
    expect(byOrder).toMatchObject({ workOrderId: order.workOrderId, runs: 1, workedMinutes: 120,
      cost: 120, costComplete: true, premiumCost: 132, premiumsComplete: true })
    expect(byOrder.people).toEqual(priced.people)

    // The ordinary worked-time APIs keep their original, non-monetary shape.
    const worked = await api<{ workedMinutes: number; lines: Record<string, unknown>[] }>('GET', `${runPath}/labor`)
    expect(worked.workedMinutes).toBe(120)
    expect(worked.lines).toHaveLength(1)
    for (const field of ['cost', 'premiumCost', 'premiumsComplete']) expect(worked).not.toHaveProperty(field)
    for (const field of ['hourlyRate', 'cost', 'premiumFactor', 'premiumCost']) expect(worked.lines[0]).not.toHaveProperty(field)
    const comparison = await api('GET', `/work-orders/${order.workOrderId}/labor-comparison`)
    expect(comparison).toMatchObject({ runs: 1, actualMinutes: 120 })
    for (const field of ['cost', 'premiumCost', 'premiumsComplete']) expect(comparison).not.toHaveProperty(field)

    await page.reload()
    await expect(runCostPanel).toContainText('120 min · Labor cost: 120 · with pay premiums 132')
    await expect(runCostPanel.getByRole('listitem')).toHaveText('demo-owner: 120 min · 120 · with pay premiums 132')
    await expect(runCostPanel).not.toContainText('days without attendance at the rate')

    await page.goto(`/projects/${projectId}/runs?view=work-orders`)
    const orderRow = page.getByRole('row').filter({ hasText: title })
    await orderRow.getByRole('button', { name: 'Readiness', exact: true }).click()
    const orderCostPanel = page.getByRole('region', { name: 'Labor cost of the runs', exact: true })
    await expect(orderCostPanel).toContainText('1 run · 120 min · Labor cost: 120 · with pay premiums 132')
    await expect(orderCostPanel).toContainText('demo-owner: 120 min · 120 · with pay premiums 132')

    // An empty context has neither the owner's Bearer header nor its refresh cookie. Member 403s are covered in backend tests.
    anonymous = await browser.newContext({ baseURL: test.info().project.use.baseURL })
    for (const path of [runCostPath, orderCostPath]) {
      const denied = await anonymous.request.get(`/api${path}`)
      expect(denied.status(), `Anonymous GET ${path}`).toBe(401)
    }
    expect(ownerLogins, 'The owner session must be reused for fixture, UI checks and cleanup').toBe(1)
    completed = true
  } finally {
    try {
      await anonymous?.close()
    } finally {
      await fixture.dispose(completed)
    }
  }
})
