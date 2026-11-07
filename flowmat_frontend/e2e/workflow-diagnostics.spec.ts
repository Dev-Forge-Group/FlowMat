import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * The workflow check's structured issues against a mocked API (archify's diagnostic shape): under each issue the edits
 * that clear it, the warnings it explains and does not list, and folded evidence with process IDs shown as names. An issue
 * from an older server, without those fields, shows as before.
 */
const workflow = { workflowId: 'wf-diag', projectId: 'prj-e2e', workflowName: 'Diagnostics line', workflowDesc: null,
  workflowType: 'standard', workflowStatus: 'draft' }
const node = (processId: string, processName: string, posX: number, nodeType = 'process') => ({ processId,
  projectId: 'prj-e2e', workflowId: 'wf-diag', processName, processType: 'process', nodeType, processStatus: 'ready',
  colorScheme: 'slate', posX, posY: 120, width: 150, height: 72, processDesc: null, version: 1, versionNonce: 1 })

const validation = {
  errors: 1,
  warnings: 2,
  issues: [
    { severity: 'warning', code: 'CYCLE', processId: null, ioId: null, connectionId: null,
      message: 'Workflow contains a directed cycle; automatic execution must define loop semantics.' },
    { severity: 'warning', code: 'UNEXPECTED_TERMINAL', processId: 'proc-left', ioId: null, connectionId: null,
      message: 'Process proc-left has no outgoing connection, but this workflow marks where results leave with Output nodes.',
      evidence: { nodeType: 'process', outgoing: 0, outputNodes: 1, allowedTerminalKinds: ['output', 'storage'] },
      supportedFixes: ['connect "Left" to a later step', 'change "Left" to an Output node if results leave the workflow there'],
      suppresses: [] },
    { severity: 'error', code: 'CONNECTION_ORPHAN', processId: null, ioId: null, connectionId: 'conn-gr',
      message: 'Connection conn-gr points to a deleted process or port.',
      evidence: { missing: ['fromPort'], fromProcessId: 'proc-gate', toProcessId: 'proc-right', fromIoId: 'deleted-port' },
      supportedFixes: ['delete the connection from "Gate" to "Right"'],
      suppresses: ['PROCESS_ISOLATED', 'UNEXPECTED_ROOT'] },
  ],
}

test('the workflow check shows fixes, explained warnings and evidence under each issue', async ({ page }) => {
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/workflows/wf-diag/canvas') {
      return ok(route, { workflow, graphSeq: 0, processes: [node('proc-in', 'In', 80, 'input'),
        node('proc-gate', 'Gate', 320, 'exclusive'), node('proc-left', 'Left', 560), node('proc-right', 'Right', 800)],
      processIos: [], connections: [], annotations: [], currentUserRole: 'owner' })
    }
    if (pathname === '/api/workflows/wf-diag/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    if (pathname === '/api/workflows/wf-diag/validation') return ok(route, validation)
    if (pathname === '/api/workflows') return ok(route, [workflow])
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/workflows/wf-diag')
  await expect(page.locator('.react-flow__node')).toHaveCount(4, { timeout: 15_000 })

  const check = page.getByRole('region', { name: 'Workflow validation' })
  await check.getByRole('button', { name: 'Check workflow', exact: true }).click()
  await expect(check.getByText(/^1 errors, 2 warnings$/)).toBeVisible()
  await expect(check.getByText('2 with a suggested fix')).toBeVisible()

  // Errors first; the issue buttons keep their names.
  const issueButtons = check.locator('.validation-issues > li > button')
  await expect(issueButtons).toHaveText([/^error: CONNECTION_ORPHAN/, /^warning: CYCLE/, /^warning: UNEXPECTED_TERMINAL/])

  const orphan = check.locator('.validation-issues > li').filter({ has: page.getByRole('button', { name: /CONNECTION_ORPHAN/ }) })
  await expect(orphan.getByRole('list', { name: 'Suggested fixes' }).getByRole('listitem'))
    .toHaveText(['Fix: delete the connection from "Gate" to "Right"'])
  await expect(orphan).toContainText('Also explains 2 warnings not listed separately: PROCESS_ISOLATED, UNEXPECTED_ROOT')
  // The evidence is folded until asked for, and names the processes.
  const evidence = orphan.locator('details')
  await expect(evidence.locator('dl')).toBeHidden()
  await evidence.getByText('Evidence').click()
  await expect(evidence.locator('dt')).toHaveText(['missing', 'from process', 'to process', 'from io id'])
  await expect(evidence.locator('dd')).toHaveText(['fromPort', 'Gate', 'Right', 'deleted-port'])

  // An issue without the new fields shows as before: no fixes, no evidence.
  const cycle = check.locator('.validation-issues > li').filter({ has: page.getByRole('button', { name: /CYCLE/ }) })
  await expect(cycle.getByRole('list', { name: 'Suggested fixes' })).toHaveCount(0)
  await expect(cycle.locator('details')).toHaveCount(0)

  // An issue still selects what it is about.
  const terminal = check.locator('.validation-issues > li').filter({ has: page.getByRole('button', { name: /UNEXPECTED_TERMINAL/ }) })
  await expect(terminal.getByRole('list', { name: 'Suggested fixes' }).getByRole('listitem')).toHaveCount(2)
  const chip = page.getByRole('region', { name: 'Canvas reading' })
  await expect(chip).toContainText('Select a process to follow its connections.')
  await terminal.getByRole('button', { name: /UNEXPECTED_TERMINAL/ }).click()
  await expect(chip.getByRole('button', { name: 'Upstream 0' })).toBeVisible()
})
