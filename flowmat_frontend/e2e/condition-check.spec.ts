import { expect, test, type Page } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * Conditions checked while they are typed (shared/lib/conditionLanguage.ts), against a mocked API that is never asked:
 * the editor runs the condition language's WebAssembly build, so a connection's condition and a port's validation rule
 * say at once, in the server's words and position, what saving would refuse, including attrs the port's schema does not
 * declare.
 */
const node = (processId: string, processName: string, posX: number, posY: number) => ({ processId, projectId: 'prj-e2e',
  workflowId: 'wf-cond', processName, processType: 'process', nodeType: 'process', processStatus: 'ready', colorScheme: 'slate',
  posX, posY, width: 150, height: 72, processDesc: null, version: 1, versionNonce: 1 })
const port = (processIoId: string, processId: string, direction: 'input' | 'output', schemaJson: Record<string, unknown> | null) => ({
  processIoId, processId, itemId: null, ioName: `${direction} of ${processId}`, direction, ioType: 'data', quantity: null, unit: null,
  formula: null, colorScheme: direction === 'input' ? 'sky' : 'emerald', requiredYn: 'N', allowShortageYn: 'N', role: null,
  resourceType: 'data', schemaJson, validationRule: null })

async function openLine(page: Page) {
  const writes: string[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') {
      writes.push(`${request.method()} ${pathname}`)
      return route.fulfill({ status: 404, body: 'Unexpected write' })
    }
    if (pathname === '/api/workflows/wf-cond/canvas') {
      return ok(route, { workflow: { workflowId: 'wf-cond', projectId: 'prj-e2e', workflowName: 'Conditions', workflowDesc: null,
        workflowType: 'standard', workflowStatus: 'draft', currentUserRole: 'owner' }, graphSeq: 0,
        processes: [node('proc-mix', 'Mix', 80, 120), node('proc-bake', 'Bake', 480, 120)],
        processIos: [port('io-out', 'proc-mix', 'output', { type: 'object', properties: { grade: { type: 'string' } } }),
          port('io-in', 'proc-bake', 'input', null)],
        connections: [{ connectionId: 'conn-mb', projectId: 'prj-e2e', workflowId: 'wf-cond', fromProcessId: 'proc-mix',
          toProcessId: 'proc-bake', fromIoId: 'io-out', toIoId: 'io-in', sourceHandle: 'io-out', targetHandle: 'io-in',
          connectionType: 'data_flow', connectionLabel: null, flowRate: null, unit: null, delayTimeSec: 0, lossRate: 0, priority: 1,
          conditionExpr: null, capacity: null, failurePolicy: 'stop', defaultRoute: false, splitKind: null, splitValue: null,
          version: 1, versionNonce: 1 }],
        annotations: [], currentUserRole: 'owner' })
    }
    if (pathname === '/api/workflows/wf-cond/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/workflows/wf-cond')
  await expect(page.locator('.react-flow__edge')).toHaveCount(1, { timeout: 15_000 })
  await page.keyboard.press('v')
  return writes
}

test('a connection condition is checked while it is typed, against the schema of the port it leaves', async ({ page }) => {
  const writes = await openLine(page)
  // A quarter of the way along the line selects it; its Delete and Insert buttons sit at its middle.
  const point = await page.locator('.react-flow__edge[data-id="conn-mb"] .react-flow__edge-interaction').evaluate((element) => {
    const path = element as SVGPathElement
    const at = path.getPointAtLength(path.getTotalLength() / 4).matrixTransform(path.getScreenCTM()!)
    return { x: at.x, y: at.y }
  })
  await page.mouse.click(point.x, point.y)
  const condition = page.getByRole('textbox', { name: 'Condition expression' })
  await expect(condition).toBeVisible()
  const note = page.locator('#connection-condition-check')

  await condition.fill('quantity == 2')
  await expect(note).toHaveText("Invalid condition at position 10: unsupported comparison operator '=='")
  await expect(condition).toHaveAttribute('aria-invalid', 'true')

  await condition.fill('quantity >= 2 and (')
  await expect(note).toHaveText('Invalid condition at position 20: expected operand')

  // The output port declares grade, not weight.
  await condition.fill("attrs.grade = 'A' and attrs.weight > 1")
  await expect(note).toHaveText('Invalid condition at position 23: attrs.weight is not declared in schemaJson')

  await condition.fill("attrs.grade = 'A' or quantity >= 2")
  await expect(note).toHaveCount(0)
  await expect(condition).not.toHaveAttribute('aria-invalid')
  await condition.fill('')
  await expect(note).toHaveCount(0)
  expect(writes).toEqual([])
})

test("a port's validation rule is checked against the schema typed beside it", async ({ page }) => {
  await openLine(page)
  await page.locator('.react-flow__node[data-id="proc-bake"]').click({ position: { x: 24, y: 18 } })
  const rule = page.getByRole('textbox', { name: 'Validation rule' })
  const schema = page.getByRole('textbox', { name: 'Data schema (JSON object contract)' })
  const note = page.locator('#port-rule-check')

  // With no schema, attrs are not checked, as on the server; the grammar still is.
  await rule.fill('attrs.weight > 1')
  await expect(note).toHaveCount(0)
  await rule.fill('attrs.weight >')
  await expect(note).toHaveText('Invalid condition at position 15: expected operand')

  await rule.fill('attrs.weight > 1')
  await schema.fill('{"type":"object","properties":{"grade":{"type":"string"}}}')
  await expect(note).toHaveText('Invalid condition at position 1: attrs.weight is not declared in schemaJson')
  await schema.fill('{"type":"object","properties":{"grade":{"type":"string"},"weight":{"type":"number"}}}')
  await expect(note).toHaveCount(0)
})
