import { expect, test } from '@playwright/test'
import { disposableProject } from './support/disposableProject'

test.skip(process.env.REAL_API_E2E !== '1' || process.env.FLOWMAT_FEATURE_E2E_DISPOSABLE !== '1',
  'Requires an explicitly disposable backend database')
test.use({ viewport: { width: 1920, height: 1080 }, trace: 'off' })

type Step = { stepId: string; nodeId: string; status: string; endedAt: string | null; scheduledAt: string | null;
  inputSnapshot: { quantity?: number }; outputSnapshot: { quantity?: number } | null }
type Event = { eventType: string; stepId: string; payload: Record<string, unknown> }

test('real transfers divide without duplicating, lose only what was sent, and enforce the input readiness time', async ({ page }) => {
  test.setTimeout(180_000)
  const fixture = await disposableProject(page, 'Transfer-acceptance')
  const { projectId, api, request } = fixture
  const runs = new Set<string>()
  let completed = false
  try {
    async function graph(name: string, definitions: [string, string][]) {
      const workflowId = (await api<{ workflowId: string }>('POST', '/workflows', { projectId, workflowName: name })).workflowId
      const nodes: Record<string, string> = {}
      for (const [processName, nodeType] of definitions) {
        nodes[processName] = (await api<{ processId: string }>('POST', '/processes', { workflowId, processName, nodeType })).processId
      }
      return { workflowId, nodes,
        link: async (from: string, to: string, options: Record<string, unknown> = {}) =>
          (await api<{ connectionId: string }>('POST', '/process-connections', {
            workflowId, fromProcessId: nodes[from], toProcessId: nodes[to], ...options,
          })).connectionId,
        start: async () => {
          const revision = await api<{ workflowRevisionId: string; schemaVersion: number }>('POST', `/workflows/${workflowId}/revisions`)
          expect(revision.schemaVersion).toBeGreaterThanOrEqual(3)
          const runId = (await api<{ flowRunId: string }>('POST', '/flow-runs/graph', {
            workflowId, workflowRevisionId: revision.workflowRevisionId, runType: 'test',
          })).flowRunId
          runs.add(runId)
          return runId
        },
      }
    }
    const steps = (runId: string) => api<Step[]>('GET', `/flow-runs/${runId}/steps`)
    const complete = async (runId: string, nodeId: string, quantity: number) => {
      const step = (await steps(runId)).find((one) => one.nodeId === nodeId)!
      await api('POST', `/flow-runs/${runId}/steps/${step.stepId}/start`)
      return api<Step>('POST', `/flow-runs/${runId}/steps/${step.stepId}/complete`, { outputSnapshot: { quantity, unit: 'kg' } })
    }
    const finish = async (runId: string) => {
      await api('POST', `/flow-runs/${runId}/finish`, {})
      runs.delete(runId)
    }

    await test.step('70/30 shares arrive at the Join as 100 kg', async () => {
      const g = await graph('Pure split', [['Before', 'process'], ['Divide', 'split'], ['Left', 'process'],
        ['Right', 'process'], ['Gather', 'join'], ['After', 'process']])
      await g.link('Before', 'Divide')
      await g.link('Divide', 'Left', { splitKind: 'share', splitValue: 70 })
      await g.link('Divide', 'Right', { splitKind: 'share', splitValue: 30 })
      await g.link('Left', 'Gather'); await g.link('Right', 'Gather'); await g.link('Gather', 'After')
      const run = await g.start()
      await complete(run, g.nodes.Before, 100)
      const divided = await steps(run)
      expect(divided.find((one) => one.nodeId === g.nodes.Left)?.inputSnapshot.quantity).toBe(70)
      expect(divided.find((one) => one.nodeId === g.nodes.Right)?.inputSnapshot.quantity).toBe(30)
      await complete(run, g.nodes.Left, 70); await complete(run, g.nodes.Right, 30)
      expect((await steps(run)).find((one) => one.nodeId === g.nodes.Gather)?.outputSnapshot?.quantity).toBe(100)
      await complete(run, g.nodes.After, 100); await finish(run)
    })

    await test.step('2 percent loss records 100 sent, 2 lost and 98 received', async () => {
      const g = await graph('Transfer loss', [['Before', 'process'], ['After', 'process']])
      const connectionId = await g.link('Before', 'After', { lossRate: 0.02 })
      const run = await g.start(); await complete(run, g.nodes.Before, 100)
      expect((await steps(run)).find((one) => one.nodeId === g.nodes.After)?.inputSnapshot.quantity).toBe(98)
      const events = await api<Event[]>('GET', `/flow-runs/${run}/events`)
      expect(events.filter((event) => event.eventType === 'transfer_loss')).toEqual([
        expect.objectContaining({ payload: expect.objectContaining({ connectionId, sent: 100, lossRate: 0.02, lost: 2, arrived: 98 }) }),
      ])
      await complete(run, g.nodes.After, 98); await finish(run)
    })

    await test.step('a two hour delay refuses an early start and an earlier schedule', async () => {
      const g = await graph('Delayed transfer', [['Before', 'process'], ['After', 'process']])
      const connectionId = await g.link('Before', 'After', { delayTimeSec: 7200 })
      const run = await g.start(); await complete(run, g.nodes.Before, 100)
      const recorded = await steps(run)
      const before = recorded.find((one) => one.nodeId === g.nodes.Before)!
      const after = recorded.find((one) => one.nodeId === g.nodes.After)!
      const expected = Date.parse(before.endedAt!) + 7_200_000
      expect(Math.abs(Date.parse(after.scheduledAt!) - expected)).toBeLessThanOrEqual(1)
      expect((await request('POST', `/flow-runs/${run}/steps/${after.stepId}/start`)).status()).toBe(409)
      expect((await request('PUT', `/flow-runs/${run}/steps/${after.stepId}/schedule`, {
        scheduledAt: new Date(expected - 3_600_000).toISOString(),
      })).status()).toBe(409)
      expect((await steps(run)).find((one) => one.stepId === after.stepId)?.scheduledAt).toBe(after.scheduledAt)
      expect(await api<Event[]>('GET', `/flow-runs/${run}/events`)).toEqual(expect.arrayContaining([
        expect.objectContaining({ stepId: after.stepId, eventType: 'step_scheduled',
          payload: expect.objectContaining({ reason: 'connection_delay', connectionId, delaySeconds: 7200 }) }),
      ]))
      await page.goto(`/projects/${projectId}/runs?workflowId=${g.workflowId}&view=flow-runs`)
      const card = page.getByText('#2 After', { exact: true }).locator('..').locator('..')
      await expect(card).toContainText('after a 2 h delay')
      const refused = page.waitForResponse((response) => response.request().method() === 'POST'
        && new URL(response.url()).pathname === `/api/flow-runs/${run}/steps/${after.stepId}/start`)
      await card.getByRole('button', { name: 'Start', exact: true }).click()
      expect((await refused).status()).toBe(409)
      await expect(card).toContainText('planned')
      await api('POST', `/flow-runs/${run}/cancel`, { reason: 'Disposable delay verification complete' })
      runs.delete(run)
    })
    expect(await api('GET', `/boms?projectId=${projectId}`)).toEqual([])
    expect(await api('GET', `/work-orders?projectId=${projectId}`)).toEqual([])
    completed = true
  } finally {
    for (const run of runs) await request('POST', `/flow-runs/${run}/cancel`, { reason: 'Disposable verification cleanup' }).catch(() => undefined)
    await fixture.dispose(completed)
  }
})
