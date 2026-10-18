import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * A work instruction step that belongs to a process (docs/domain/work-instruction.md W16,
 * docs/architecture/adr/ADR-011-production-steps.md decision 6) against a mocked API: the draft lists each step's process,
 * offers the processes of the chosen workflow, and sends the process with the new step.
 */
test('a step names the process it belongs to', async ({ page }) => {
  const step = (stepId: string, stepNo: number, text: string, processId: string | null, processName: string | null) => ({
    stepId, stepNo, text, required: true, recordsValue: false, valueLabel: null, valueMin: null, valueMax: null,
    standardId: null, standardName: null, standardStatus: null, processId, processName,
  })
  const instruction = {
    instructionId: 'wi-bread', projectId: 'prj-e2e', itemId: 'bread', itemCode: 'BREAD', itemName: 'bread', revisionNo: 1,
    status: 'draft', title: 'Baking bread', body: null, documentUrl: null, releasedBy: null, releasedAt: null,
    updatedAt: '2026-10-17T00:00:00Z', blocksFinish: true,
    steps: [step('st-1', 1, 'Weigh the flour', 'proc-mix', 'Mix'), step('st-2', 2, 'Wash hands', null, null)],
  }
  const workflow = { workflowId: 'wf-bake', projectId: 'prj-e2e', workflowName: 'Bread line', workflowDesc: null,
    workflowType: 'standard', workflowStatus: 'published' }
  const node = (processId: string, processName: string, posX: number) => ({ processId, projectId: 'prj-e2e', workflowId: 'wf-bake',
    processName, processType: 'process', nodeType: 'process', processStatus: 'ready', colorScheme: 'slate', posX, posY: 160,
    width: 150, height: 72, processDesc: null, version: 1, versionNonce: 1 })
  const sent: unknown[] = []

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'POST' && pathname === '/api/work-instructions/wi-bread/steps') {
      sent.push(request.postDataJSON())
      instruction.steps = [...instruction.steps, step('st-3', 3, 'Check the oven', 'proc-bake', 'Bake')]
      return ok(route, instruction)
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/items') {
      return ok(route, [{ itemId: 'bread', projectId: 'prj-e2e', itemCode: 'BREAD', itemName: 'bread', itemType: 'product',
        resourceCategory: null, resourceType: null, unitId: 'unit_ea', itemStatus: 'active', lotManageYn: 'N', unitCost: null }])
    }
    if (pathname === '/api/work-instructions') return ok(route, [instruction])
    if (pathname === '/api/workflows') return ok(route, [workflow])
    if (pathname === '/api/workflows/wf-bake/canvas') {
      return ok(route, { workflow, graphSeq: 0, processes: [node('proc-mix', 'Mix', 80), node('proc-bake', 'Bake', 480)],
        processIos: [], connections: [], annotations: [], currentUserRole: 'owner' })
    }
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/inventory?tab=instructions')
  await page.getByRole('combobox', { name: 'Product', exact: true }).selectOption('bread', { timeout: 15_000 })
  const detail = page.getByRole('region', { name: 'Instruction' })
  const steps = detail.getByRole('list', { name: 'Steps' })
  await expect(steps.getByRole('listitem').first()).toContainText('Weigh the flour · at Mix')
  // A step without a process belongs to the whole run.
  await expect(steps.getByRole('listitem').nth(1)).not.toContainText(' · at ')

  const add = detail.getByRole('form', { name: 'Add step' })
  await add.getByLabel('Step', { exact: true }).fill('Check the oven')
  // The process select appears once a workflow is chosen, and goes away with "(the whole run)".
  await expect(add.getByRole('combobox', { name: 'Belongs to process' })).toHaveCount(0)
  const workflowSelect = add.getByRole('combobox', { name: 'Belongs to workflow' })
  await workflowSelect.selectOption('wf-bake')
  const processSelect = add.getByRole('combobox', { name: 'Belongs to process' })
  await expect(processSelect.getByRole('option')).toHaveText(['(the whole run)', 'Mix', 'Bake'])
  await workflowSelect.selectOption('')
  await expect(processSelect).toHaveCount(0)
  await workflowSelect.selectOption('wf-bake')
  await processSelect.selectOption('proc-bake')
  await add.getByRole('button', { name: 'Add step' }).click()

  await expect(steps.getByRole('listitem')).toHaveCount(3)
  await expect(steps.getByRole('listitem').nth(2)).toContainText('Check the oven · at Bake')
  expect(sent).toEqual([{ text: 'Check the oven', required: true, recordsValue: false, valueLabel: null, processId: 'proc-bake' }])
})
