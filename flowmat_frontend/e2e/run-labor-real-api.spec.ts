import { expect, test } from '@playwright/test'

test.skip(process.env.REAL_API_E2E !== '1' || process.env.FLOWMAT_LABOR_E2E_DISPOSABLE !== '1',
  'Requires an explicitly disposable backend for labor acceptance')
test.use({ trace: 'off' })

test('real worked time survives reload, replays once and cancels out of the work order actual total', async ({ page, request }) => {
  test.setTimeout(60_000)
  await page.goto('/')
  await page.getByRole('textbox', { name: 'demo-owner', exact: true }).fill('demo-owner')
  await page.locator('input[type="password"]').fill('demo1234')
  const loginReply = page.waitForResponse((reply) => reply.request().method() === 'POST'
    && new URL(reply.url()).pathname === '/api/auth/login')
  await page.getByRole('button', { name: 'Log in', exact: true }).click()
  const login = await loginReply
  expect(login.ok()).toBe(true)
  const headers = { Authorization: `Bearer ${(await login.json()).data.accessToken}` }
  await expect(page.getByRole('button', { name: 'Logout', exact: true })).toBeVisible()
  const api = async (method: string, path: string, data?: unknown) => {
    const reply = await request.fetch(`/api${path}`, { method, headers, data, timeout: 10_000 })
    const envelope = await reply.json()
    expect(reply.ok(), `${method} ${path}: ${envelope.message}`).toBe(true)
    return envelope.data
  }
  const project = await api('POST', '/projects', { projectName: `Labor acceptance ${Date.now()}`, ownerId: 'demo-owner' })
  const projectId = project.projectId as string
  const workflow = await api('POST', '/workflows', { projectId, workflowName: 'Worked time only' })
  await api('POST', '/processes', { workflowId: workflow.workflowId, processName: 'Work', nodeType: 'process' })
  const revision = await api('POST', `/workflows/${workflow.workflowId}/revisions`)
  const order = await api('POST', '/work-orders', { projectId, workflowId: workflow.workflowId,
    workOrderTitle: 'Labor acceptance', targetQuantity: 1 })
  await api('POST', `/work-orders/${order.workOrderId}/approve`)
  const run = await api('POST', '/production-runs/start', { projectId, workflowId: workflow.workflowId,
    workflowRevisionId: revision.workflowRevisionId, workOrderId: order.workOrderId, plannedOutputQty: 1, runType: 'actual' })
  expect(run.bomId).toBeNull()
  const laborPath = `/production-runs/${run.productionRunId}/labor`
  const comparisonPath = `/work-orders/${order.workOrderId}/labor-comparison`
  expect(await api('GET', comparisonPath)).toMatchObject({ runs: 1, actualMinutes: 0 })

  await page.goto(`/projects/${projectId}/runs/${run.productionRunId}`)
  const panel = page.getByRole('region', { name: 'Labor', exact: true })
  await expect(panel).toContainText('No worked time yet')
  await panel.getByLabel('Worker', { exact: true }).selectOption('demo-owner')
  await panel.getByLabel('Worked minutes').fill('25')
  await panel.getByLabel('Labor note').fill('Timer check')
  const sent = page.waitForRequest((call) => call.method() === 'POST' && new URL(call.url()).pathname === `/api${laborPath}`)
  await panel.getByRole('button', { name: 'Record worked time', exact: true }).click()
  const input = (await sent).postDataJSON() as Record<string, unknown>
  await expect(panel).toContainText('25 min: demo-owner 25')
  expect(input).toMatchObject({ userId: 'demo-owner', workedMinutes: 25, note: 'Timer check' })
  expect(typeof input.requestId).toBe('string')

  const replayed = await api('POST', laborPath, input)
  expect(replayed.workedMinutes).toBe(25)
  expect(replayed.lines).toHaveLength(1)
  expect(replayed.lines[0]).toMatchObject({ userId: 'demo-owner', workedMinutes: 25, recordedBy: 'demo-owner', cancelled: false })
  expect(await api('GET', comparisonPath)).toMatchObject({ runs: 1, actualMinutes: 25 })
  await page.reload()
  await expect(panel).toContainText('25 min: demo-owner 25')
  await expect(panel).toContainText('Timer check')

  page.once('dialog', (dialog) => void dialog.accept('Wrong timer'))
  await panel.getByRole('button', { name: 'Cancel worked time', exact: true }).click()
  await expect(panel).toContainText('cancelled: Wrong timer')
  await expect(panel).toContainText('No worked time yet')
  const cancelled = await api('GET', laborPath)
  expect(cancelled.lines).toHaveLength(1)
  expect(cancelled.lines[0]).toMatchObject({ cancelled: true, cancelledBy: 'demo-owner', cancelReason: 'Wrong timer' })
  expect(await api('GET', comparisonPath)).toMatchObject({ runs: 1, actualMinutes: 0 })
  await page.reload()
  await expect(panel).toContainText('cancelled: Wrong timer')
})
