import { expect, test, type Browser, type Page } from '@playwright/test'

test.skip(!process.env.REAL_API_E2E, 'Needs the real backend and demo seed')

/**
 * Two people drawing at once (docs/architecture/adr/ADR-042-drawing-layer-element-changes.md): two browsers open the same
 * workflow and each moves a different shape. The second browser never hears about the first one's save (its live
 * connection is cut), so it saves from an out-of-date document, which a whole-document save would refuse. As element
 * changes, both moves are kept. Moving the shape the first browser moved, from that out-of-date document, is refused and
 * named.
 */
test('two browsers moving different shapes both keep their moves', async ({ browser }) => {
  test.setTimeout(90_000)
  const first = await signIn(browser)
  const second = await signIn(browser, { deaf: true })
  const call = async (method: 'get' | 'post' | 'put' | 'delete', path: string, data?: Record<string, unknown>) => {
    const response = await first.page.request[method](`/api${path}`, { headers: first.headers, data })
    expect(response.ok(), `${method} ${path}: ${response.status()} ${await response.text()}`).toBe(true)
    return (await response.json()).data
  }
  const workflow = await call('post', '/workflows', { projectId: 'prj_demo_main', workflowName: `Shapes together ${Date.now()}` })
  const workflowId = workflow.workflowId as string
  try {
    const rectangle = (id: string, x: number, order: number) => ({ id, type: 'rectangle', x, y: 80, width: 120, height: 80, order,
      geometry: { cornerRadius: 0 }, style: { fill: '#dbeafe', stroke: '#1d4ed8', strokeWidth: 2, strokeStyle: 'solid', opacity: 1 } })
    await call('put', `/workflows/${workflowId}/editor-document`, { expectedVersion: 0, nextElementSeq: 3,
      camera: { x: 0, y: 0, zoom: 1 },
      elements: [rectangle('shape-1', 80, 1), rectangle('shape-2', 400, 2)] })

    for (const one of [first, second]) await open(one.page, workflowId)
    await move(first.page, 'shape-1', 0, 120)
    await expect.poll(async () => y(await call('get', `/workflows/${workflowId}/editor-document`), 'shape-1')).toBeGreaterThan(150)
    // The second browser still shows shape-1 where it was, and moves shape-2.
    await move(second.page, 'shape-2', 0, 120)
    await expect.poll(async () => y(await call('get', `/workflows/${workflowId}/editor-document`), 'shape-2')).toBeGreaterThan(150)
    const both = await call('get', `/workflows/${workflowId}/editor-document`)
    expect(y(both, 'shape-1')).toBeGreaterThan(150)
    expect(y(both, 'shape-2')).toBeGreaterThan(150)

    // The second browser now moves shape-1 too, from before the first browser's move: refused, and it says which.
    await second.page.reload()
    await open(second.page, workflowId, false)
    const stale = await call('get', `/workflows/${workflowId}/editor-document`)
    await call('put', `/workflows/${workflowId}/editor-document`, { expectedVersion: stale.version,
      nextElementSeq: stale.nextElementSeq, camera: stale.camera, elements: stale.elements.map((element: Record<string, unknown>) =>
        element.id === 'shape-1' ? { ...element, x: 10 } : element) })
    await move(second.page, 'shape-1', 0, 40)
    await expect(second.page.getByText('shape-1 was changed by someone else. Their version is shown.')).toBeVisible()
  } finally {
    await first.page.request.delete(`/api/workflows/${workflowId}`, { headers: first.headers })
    await first.page.context().close()
    await second.page.context().close()
  }
})

/** A new browser signed in as the demo owner; a deaf one hears no live updates. */
async function signIn(browser: Browser, { deaf = false } = {}): Promise<{ page: Page; headers: Record<string, string> }> {
  const context = await browser.newContext()
  const page = await context.newPage()
  page.setDefaultTimeout(15_000)
  if (deaf) {
    // Accept the live connection but never pass anything on, so this browser never refetches on someone else's save.
    await page.routeWebSocket(/\/ws/, () => {})
  }
  await page.goto('/')
  await page.locator('input').nth(0).fill('demo-owner')
  await page.locator('input').nth(1).fill('demo1234')
  const loginResponse = page.waitForResponse((response) =>
    response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/auth/login')
  await page.getByRole('button', { name: 'Log in' }).click()
  const login = await loginResponse
  expect(login.ok()).toBe(true)
  await page.waitForLoadState('networkidle')
  return { page, headers: { Authorization: `Bearer ${(await login.json()).data.accessToken as string}` } }
}

async function open(page: Page, workflowId: string, navigate = true) {
  if (navigate) await page.goto(`/projects/prj_demo_main/workflows/${workflowId}`)
  await expect(page.locator('[data-element-id="shape-2"]')).toBeVisible()
  // The pointer, so a press on a shape moves it (the canvas opens with the process tool).
  await page.keyboard.press('v')
}

async function move(page: Page, id: string, dx: number, dy: number) {
  const box = await page.locator(`[data-element-id="${id}"]`).boundingBox()
  if (!box) throw new Error(`${id} is not on the page`)
  const x = box.x + box.width / 2
  const y = box.y + box.height / 2
  await page.mouse.move(x, y)
  await page.mouse.down()
  for (let step = 1; step <= 5; step++) await page.mouse.move(x + (dx * step) / 5, y + (dy * step) / 5)
  await page.mouse.up()
}

function y(document: { elements: { id: string; y: number }[] }, id: string): number {
  const element = document.elements.find((one) => one.id === id)
  if (!element) throw new Error(`${id} is not in the document`)
  return element.y
}
