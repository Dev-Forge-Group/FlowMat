import { expect, test } from '@playwright/test'
import { disposableProject } from './support/disposableProject'

test.skip(process.env.REAL_API_E2E !== '1' || process.env.FLOWMAT_FEATURE_E2E_DISPOSABLE !== '1',
  'Requires an explicitly disposable backend database')
test.use({ viewport: { width: 1920, height: 1080 }, trace: 'off' })

type Step = { stepId: string; nodeId: string; status: string; instructionRequired: number; instructionDone: number;
  inputSnapshot: { quantity?: number }; outputSnapshot: { quantity?: number; item?: string } | null }

test('real production steps use recorded output, require the process instruction and block finishing a running step', async ({ page }) => {
  test.setTimeout(180_000)
  const fixture = await disposableProject(page, 'Production-steps')
  const { projectId, api, request } = fixture
  let runId = ''
  let finished = false
  let completed = false
  try {
    const workflowId = (await api<{ workflowId: string }>('POST', '/workflows', { projectId, workflowName: 'Mix then Bake' })).workflowId
    const node = async (processName: string) => (await api<{ processId: string }>('POST', '/processes', {
      workflowId, processName, nodeType: 'process',
    })).processId
    const mix = await node('Mix'); const bake = await node('Bake')
    await api('POST', '/process-connections', { workflowId, fromProcessId: mix, toProcessId: bake })
    const revision = await api<{ workflowRevisionId: string }>('POST', `/workflows/${workflowId}/revisions`)
    const itemId = (await api<{ itemId: string }>('POST', '/items', {
      projectId, itemCode: `BREAD-${Date.now()}`, itemName: 'Simulated bread', itemType: 'product', unitId: 'unit_kg',
    })).itemId
    const instructionId = (await api<{ instructionId: string }>('POST', '/work-instructions', {
      projectId, itemId, title: 'Check the oven', blocksFinish: true,
    })).instructionId
    const instruction = await api<{ steps: { stepId: string }[] }>('POST', `/work-instructions/${instructionId}/steps`, {
      text: 'Check Bake', required: true, processId: bake,
    })
    await api('POST', `/work-instructions/${instructionId}/release`)
    const run = await api<{ productionRunId: string; bomId: string | null }>('POST', '/production-runs/start', {
      projectId, workflowId, workflowRevisionId: revision.workflowRevisionId, targetItemId: itemId,
      plannedOutputQty: 100, runType: 'simulation',
    })
    runId = run.productionRunId
    expect(run.bomId).toBeNull()
    const steps = () => api<Step[]>('GET', `/production-runs/${runId}/steps`)
    const first = await steps()
    expect(first).toEqual([expect.objectContaining({ nodeId: mix, status: 'planned' })])
    const flow = (await api<{ flowRunId: string; productionRunId: string | null }[]>('GET', `/flow-runs?workflowId=${workflowId}`))
      .find((one) => one.productionRunId === runId)!
    expect((await request('POST', `/flow-runs/${flow.flowRunId}/steps/${first[0].stepId}/start`)).status()).toBe(409)
    await page.goto(`/projects/${projectId}/runs/${runId}`)
    const panel = page.getByRole('region', { name: 'Process steps', exact: true })
    await panel.getByRole('button', { name: 'Start Mix', exact: true }).click()
    await expect(panel.getByRole('button', { name: 'Complete Mix', exact: true })).toBeVisible()
    expect((await request('POST', `/production-runs/${runId}/finish`, { actualOutputQty: 97 })).status()).toBe(409)
    await api('POST', `/production-runs/${runId}/items`, {
      processId: mix, itemId, direction: 'output', plannedQty: 100, actualQty: 98, unit: 'kg',
    })
    const mixed = page.waitForResponse((response) => response.request().method() === 'POST'
      && new URL(response.url()).pathname === `/api/production-runs/${runId}/steps/${first[0].stepId}/complete`)
    await panel.getByRole('button', { name: 'Complete Mix', exact: true }).click()
    expect((await (await mixed).json()).data.outputSnapshot).toMatchObject({ quantity: 98, item: itemId, unit: 'kg' })
    await expect(panel.getByRole('button', { name: 'Start Bake', exact: true })).toBeVisible()
    const baking = (await steps()).find((one) => one.nodeId === bake)!
    expect(baking).toMatchObject({ instructionRequired: 1, instructionDone: 0, inputSnapshot: { quantity: 98 } })
    await panel.getByRole('button', { name: 'Start Bake', exact: true }).click()
    const refused = page.waitForResponse((response) => response.request().method() === 'POST'
      && new URL(response.url()).pathname === `/api/production-runs/${runId}/steps/${baking.stepId}/complete`)
    await panel.getByRole('button', { name: 'Complete Bake', exact: true }).click()
    expect((await refused).status()).toBe(409)
    await expect(panel.getByRole('alert')).toContainText('Confirm the required work instruction step')
    await api('POST', `/production-runs/${runId}/instruction/steps/${instruction.steps[0].stepId}/check`, {})
    await api('POST', `/production-runs/${runId}/items`, {
      processId: bake, itemId, direction: 'output', plannedQty: 100, actualQty: 97, unit: 'kg',
    })
    await page.reload()
    await expect(panel).toContainText('1 of 1 instruction steps confirmed')
    await panel.getByRole('button', { name: 'Complete Bake', exact: true }).click()
    await expect(panel.getByRole('button', { name: 'Complete Bake', exact: true })).toHaveCount(0)
    expect((await steps()).find((one) => one.nodeId === bake)?.outputSnapshot?.quantity).toBe(97)
    await api('POST', `/production-runs/${runId}/finish`, { actualOutputQty: 97 })
    finished = true
    expect(await api('GET', `/flow-runs/${flow.flowRunId}`)).toMatchObject({ status: 'finished' })
    expect(await api('GET', `/inventories?projectId=${projectId}`)).toEqual([])
    expect(await api('GET', `/boms?projectId=${projectId}`)).toEqual([])
    completed = true
  } finally {
    if (runId && !finished) await request('POST', `/production-runs/${runId}/cancel`, { reason: 'Disposable verification cleanup' }).catch(() => undefined)
    await fixture.dispose(completed)
  }
})
