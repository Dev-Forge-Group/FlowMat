import { expect, test, type Route } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * A connection is drawn even when the canvas rebuilds its nodes around React Flow's first measurement of them.
 *
 * React Flow forgets a node's handle positions when it is handed a new node object without `measured`, and measures the
 * node again only after it has rendered it as measured. The canvas used to rebuild every node when the presence
 * snapshot arrived. When that landed between the first measurement and the render after it, the nodes never got their
 * handles back and the connection between them was never drawn: the real-backend checks failed this way about once in
 * fifty runs.
 *
 * The timing is forced here. React Flow's first measurement is held back, the presence snapshot is answered, and the
 * measurement is delivered right after the k-th commit that follows, before that commit's effects run. Before the fix
 * the connection stayed missing for k = 2 and 3.
 */
type Harness = Window & {
  __held: { deliver: () => void }[]
  __measured: boolean
  __measureNow: () => void
  __commits: number
  __measureAtCommit: number
  ResizeObserver: unknown
  __REACT_DEVTOOLS_GLOBAL_HOOK__: unknown
}

function holdFirstMeasurement() {
  const harness = window as unknown as Harness
  harness.__held = []
  harness.__measured = false
  harness.__commits = 0
  harness.__measureAtCommit = 0
  // Stands in for ResizeObserver: a node's first observation waits for __measureNow, everything else comes next frame.
  harness.ResizeObserver = class {
    private readonly callback: (entries: unknown[], observer: unknown) => void
    constructor(callback: (entries: unknown[], observer: unknown) => void) {
      this.callback = callback
    }
    observe(element: Element) {
      const deliver = () => {
        if (element.isConnected) this.callback([{ target: element, contentRect: element.getBoundingClientRect() }], this)
      }
      if (element.classList.contains('react-flow__node') && !harness.__measured) harness.__held.push({ deliver })
      else requestAnimationFrame(deliver)
    }
    unobserve() {}
    disconnect() {}
  }
  harness.__measureNow = () => {
    harness.__measured = true
    for (const held of harness.__held.splice(0)) held.deliver()
  }
  // React calls this at the end of every commit, before the commit's effects run.
  harness.__REACT_DEVTOOLS_GLOBAL_HOOK__ = {
    supportsFiber: true, renderers: new Map(), inject: () => 1, checkDCE() {}, onScheduleFiberRoot() {},
    onCommitFiberUnmount() {}, onPostCommitFiberRoot() {},
    onCommitFiberRoot() {
      if (harness.__measureAtCommit === 0 || harness.__measured) return
      harness.__commits += 1
      if (harness.__commits === harness.__measureAtCommit) harness.__measureNow()
    },
  }
}

test('a connection is drawn when the nodes are rebuilt around their first measurement', async ({ page }) => {
  const workflow = { workflowId: 'wf-measure', projectId: 'prj-e2e', workflowName: 'Measured', workflowDesc: null,
    workflowType: 'standard', workflowStatus: 'draft' }
  const node = (processId: string, processName: string, posX: number) => ({ processId, projectId: 'prj-e2e',
    workflowId: 'wf-measure', processName, processType: 'process', nodeType: 'process', processStatus: 'ready',
    colorScheme: 'slate', posX, posY: 160, width: 150, height: 72, processDesc: null, version: 1, versionNonce: 1 })
  const connection = { connectionId: 'conn-pipe', projectId: 'prj-e2e', workflowId: 'wf-measure', fromProcessId: 'proc-mix',
    toProcessId: 'proc-bake', fromIoId: null, toIoId: null, sourceHandle: 'out-default', targetHandle: 'in-default',
    connectionType: 'material_flow', connectionLabel: null, flowRate: null, unit: null, delayTimeSec: 0, lossRate: 0,
    priority: 1, conditionExpr: null, capacity: null, failurePolicy: 'stop', defaultRoute: false, splitKind: null,
    splitValue: null, version: 1, versionNonce: 1 }
  let presence: Route[] = []

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const { pathname } = new URL(route.request().url())
    if (await answerAuth(route, pathname)) return
    if (route.request().method() !== 'GET') return route.fulfill({ status: 404, contentType: 'application/json',
      body: JSON.stringify({ success: false, data: null, message: `Unmocked ${pathname}` }) })
    if (pathname === '/api/workflows/wf-measure/presence') { presence.push(route); return }
    if (pathname === '/api/workflows/wf-measure/canvas') {
      return ok(route, { workflow, graphSeq: 0, processes: [node('proc-mix', 'Mix', 80), node('proc-bake', 'Bake', 480)],
        processIos: [], connections: [connection], annotations: [], currentUserRole: 'owner' })
    }
    if (pathname === '/api/workflows/wf-measure/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    if (pathname === '/api/workflows') return ok(route, [workflow])
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.addInitScript(holdFirstMeasurement)

  for (const commit of [1, 2, 3, 4, 5]) {
    presence = []
    await page.goto('/projects/prj-e2e/workflows/wf-measure')
    await expect(page.locator('.react-flow__node')).toHaveCount(2)
    await expect.poll(() => presence.length).toBeGreaterThan(0)
    // Let the load's other renders finish, so the commits counted next are the presence snapshot's.
    await page.waitForTimeout(500)

    await page.evaluate((at) => { (window as unknown as Harness).__measureAtCommit = at }, commit)
    for (const route of presence) await ok(route, [])
    // A commit that never comes still gets its measurement, as a real observer would give it.
    await page.waitForTimeout(500)
    await page.evaluate(() => { const harness = window as unknown as Harness; if (!harness.__measured) harness.__measureNow() })

    await expect(page.locator('.react-flow__edge[data-id="conn-pipe"] .react-flow__edge-interaction'),
      `measured after commit ${commit} that followed the presence snapshot`).toBeAttached()
  }
})
