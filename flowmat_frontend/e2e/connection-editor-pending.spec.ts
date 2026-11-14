import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

test('a connection save response preserves the next split choice typed while saving', async ({ page }) => {
  const workflow = { workflowId: 'wf-pending-link', projectId: 'prj-e2e', workflowName: 'Pending connection',
    workflowStatus: 'draft', workflowType: 'standard' }
  const node = (processId: string, name: string, nodeType: string, posX: number) => ({ processId,
    projectId: 'prj-e2e', workflowId: workflow.workflowId, processName: name,
    processType: nodeType === 'split' ? 'gateway' : 'process', nodeType, processStatus: 'ready',
    colorScheme: 'slate', posX, posY: 160, width: 150, height: 72, version: 1, versionNonce: 1 })
  const connection: Record<string, unknown> = { connectionId: 'conn-pending', projectId: 'prj-e2e',
    workflowId: workflow.workflowId, fromProcessId: 'proc-split', toProcessId: 'proc-output',
    fromIoId: null, toIoId: null, sourceHandle: 'out-default', targetHandle: 'in-default',
    connectionType: 'material_flow', connectionLabel: 'Split edge', flowRate: null, unit: null,
    delayTimeSec: null, lossRate: null, priority: 1, conditionExpr: null, capacity: null,
    failurePolicy: 'stop', defaultRoute: false, splitKind: 'share', splitValue: 25, version: 1, versionNonce: 1 }
  const sent: Record<string, unknown>[] = []
  let releaseFirst!: () => void
  const firstResponse = new Promise<void>((resolve) => { releaseFirst = resolve })

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'PUT' && pathname === '/api/process-connections/conn-pending') {
      const body = request.postDataJSON() as Record<string, unknown>
      sent.push({ connectionId: 'conn-pending', ...body })
      if (sent.length === 1) await firstResponse
      Object.assign(connection, body, { version: Number(connection.version) + 1 })
      return ok(route, connection)
    }
    if (pathname === `/api/workflows/${workflow.workflowId}/canvas`) return ok(route, { workflow, graphSeq: 0,
      processes: [node('proc-split', 'Divide', 'split', 80), node('proc-output', 'Output', 'process', 500)],
      processIos: [], connections: [connection], annotations: [], currentUserRole: 'owner' })
    if (pathname === `/api/workflows/${workflow.workflowId}/editor-document`) return ok(route, {
      schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [],
    })
    if (pathname === '/api/workflows') return ok(route, [workflow])
    return ok(route, [])
  })

  try {
    await mockedLogin(page)
    await page.goto(`/projects/prj-e2e/workflows/${workflow.workflowId}`)
    const edgePath = page.locator('.react-flow__edge[data-id="conn-pending"] .react-flow__edge-interaction')
    await expect(edgePath).toBeAttached()
    await page.getByRole('button', { name: 'Pointer', exact: true }).first().click()
    // The initial fit can move the SVG while the toolbar is being clicked. Read
    // the connection's screen coordinates only after choosing the pointer.
    const point = await edgePath.evaluate((element) => {
      const path = element as SVGPathElement
      const point = path.getPointAtLength(path.getTotalLength() / 4).matrixTransform(path.getScreenCTM()!)
      return { x: point.x, y: point.y }
    })
    await page.mouse.click(point.x, point.y)
    const kind = page.getByRole('combobox', { name: 'Split share' })
    const save = page.getByRole('button', { name: 'Save Connection', exact: true })
    await expect(kind).toHaveValue('share')
    await page.getByRole('textbox', { name: 'Label', exact: true }).fill('Saved 70')
    await page.getByRole('spinbutton', { name: 'Split value' }).fill('70')
    await save.click()
    await expect.poll(() => sent.length).toBe(1)
    await kind.selectOption('rest')
    await expect(kind).toHaveValue('rest')

    releaseFirst()
    await expect(page.locator('.inspector__title').filter({ hasText: 'Saved 70' })).toHaveText('Saved 70')
    // Allow the server-prop commit and its passive effects to complete before checking the draft.
    await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
    await expect(kind).toHaveValue('rest')
    await save.click()
    await expect.poll(() => sent.length).toBe(2)
    expect(sent[1]).toMatchObject({ connectionId: 'conn-pending', splitKind: 'rest' })
    expect(sent[1]).not.toHaveProperty('splitValue')
  } finally {
    releaseFirst()
  }
})
