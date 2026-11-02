import { expect, test, type Page, type Request, type Response } from '@playwright/test'

test.skip(!process.env.REAL_API_E2E, 'Needs the real backend and demo login')
test.use({ viewport: { width: 1920, height: 1080 }, trace: 'off' })

const fileSchema = { type: 'object', properties: { path: { type: 'string' }, rows: { type: 'number' } }, required: ['path', 'rows'] }
const dataSchema = { type: 'object', properties: { rows: { type: 'number' }, valid: { type: 'boolean' } }, required: ['rows', 'valid'] }

// Observe requests caused by the UI. No API calls create projects, nodes, ports, connections, or executions.
function posted(page: Page, path: string) {
  return page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === `/api${path}`)
}
async function data(response: Response) {
  expect(response.ok(), `UI request returned ${response.status()}`).toBe(true)
  const envelope = await response.json()
  expect(envelope.success).toBe(true)
  return envelope.data as Record<string, string>
}

test('Data Flow: UI creates, publishes and completes File → Transform → Data without manufacturing rows', async ({ page }) => {
  test.setTimeout(180_000)
  page.setDefaultTimeout(15_000)
  let projectId = ''
  let token = ''
  try {
    await test.step('DF1: one login', async () => {
      await page.goto('/')
      await page.locator('input').nth(0).fill('demo-owner')
      await page.locator('input[type="password"]').fill('demo1234')
      const login = posted(page, '/auth/login')
      await page.getByRole('button', { name: 'Log in', exact: true }).click()
      token = (await data(await login)).accessToken
      await expect(page.getByText('안녕하세요, Demo Owner님', { exact: true })).toBeVisible()
    })

    await test.step('DF2: create a fresh project on the home screen', async () => {
      const name = `DF-E2E-${Date.now()}`
      const form = page.locator('form').filter({ has: page.getByPlaceholder('프로젝트 이름', { exact: true }) })
      await form.getByPlaceholder('프로젝트 이름', { exact: true }).fill(name)
      const created = posted(page, '/projects')
      await form.getByRole('button', { name: '만들기', exact: true }).click()
      projectId = (await data(await created)).projectId
      await page.getByRole('button').filter({ has: page.getByText(name, { exact: true }) }).click()
    })

    let workflowId = ''
    const nodes: string[] = []
    const layoutNodes = async () => {
      const updates = nodes.map((id) => page.waitForResponse((response) => response.request().method() === 'PUT'
        && new URL(response.url()).pathname === `/api/processes/${id}`))
      await page.getByRole('button', { name: 'Layout LR', exact: true }).click()
      const expected = []
      for (const response of await Promise.all(updates)) {
        const saved = await data(response)
        expected.push({ x: Number(saved.posX), y: Number(saved.posY) })
      }
      // The current canvas must reflect persisted layout coordinates without a reload.
      await expect.poll(async () => Promise.all(nodes.map((id) =>
        page.locator(`.react-flow__node[data-id="${id}"]`).evaluate((element) => {
          const transform = new DOMMatrixReadOnly(getComputedStyle(element).transform)
          return { x: transform.m41, y: transform.m42 }
        }),
      ))).toEqual(expected)
      await expect(page.locator('.react-flow__node')).toHaveCount(3)
      await page.getByRole('button', { name: 'Fit View', exact: true }).click()
    }
    await test.step('DF3: create CSV import and three canvas nodes', async () => {
      const form = page.locator('form').filter({ has: page.getByPlaceholder('워크플로우 이름', { exact: true }) })
      await form.getByPlaceholder('워크플로우 이름', { exact: true }).fill('CSV import')
      const created = posted(page, '/workflows')
      await form.getByRole('button', { name: '만들기', exact: true }).click()
      workflowId = (await data(await created)).workflowId
      await expect(page).toHaveURL(new RegExp(`/projects/${projectId}/workflows/${workflowId}$`))
      await expect(page.locator('.react-flow__pane')).toBeVisible()
      // File/Transform/Data roles use the existing generic Process node, as in DataFlowRunIntegrationTest;
      // the following port resource types and contracts express what flows through each step.
      for (const name of ['Read CSV', 'Validate rows', 'Store rows']) {
        await page.locator('.workspace-panel--left').getByRole('button').filter({ has: page.getByText('Process', { exact: true }) }).click()
        // Fit-to-content changes the camera as nodes are added. Pick an actually empty screen point.
        const pane = page.locator('.react-flow__pane')
        const position = await pane.evaluate((element) => {
          const box = element.getBoundingClientRect()
          const occupied = [...document.querySelectorAll('.react-flow__node')].map((node) => node.getBoundingClientRect())
          for (const y of [0.25, 0.5, 0.7]) for (const x of [0.18, 0.5, 0.8]) {
            const point = { x: box.width * x, y: box.height * y }
            const screenX = box.left + point.x, screenY = box.top + point.y
            if (!occupied.some((node) => screenX >= node.left - 40 && screenX <= node.right + 40
              && screenY >= node.top - 40 && screenY <= node.bottom + 40)) return point
          }
          throw new Error('No empty canvas position for the next data-flow node')
        })
        const added = posted(page, '/processes')
        await pane.click({ position })
        const id = (await data(await added)).processId
        nodes.push(id)
        await page.locator(`.react-flow__node[data-id="${id}"]`).click({ position: { x: 24, y: 18 } })
        await page.getByLabel('Name', { exact: true }).fill(name)
        const saved = page.waitForResponse((response) => response.request().method() === 'PUT'
          && new URL(response.url()).pathname === `/api/processes/${id}`)
        await page.getByRole('button', { name: 'Save Node', exact: true }).click()
        await data(await saved)
        await expect(page.locator(`.react-flow__node[data-id="${id}"]`)).toContainText(name)
      }
      await expect(page.locator('.react-flow__node')).toHaveCount(3)
      await layoutNodes()
      await test.step('a live drag survives selection changes before its save', async () => {
        const node = page.locator(`.react-flow__node[data-id="${nodes[0]}"]`)
        const position = () => node.evaluate((element) => {
          const transform = new DOMMatrixReadOnly(getComputedStyle(element).transform)
          return { x: transform.m41, y: transform.m42 }
        })
        await page.keyboard.press('v')
        await node.click({ position: { x: 24, y: 18 } })
        await expect(page.getByLabel('Name', { exact: true })).toHaveValue('Read CSV')
        const before = await position()
        const box = await node.boundingBox()
        expect(box).not.toBeNull()
        await page.mouse.move(box!.x + 24, box!.y + 18)
        let saves = 0
        const observeSave = (request: Request) => {
          if (request.method() === 'PATCH' && new URL(request.url()).pathname === `/api/processes/${nodes[0]}/position`) saves++
        }
        page.on('request', observeSave)
        await page.mouse.down()
        let held = true
        try {
          await page.mouse.move(box!.x + 56, box!.y + 50, { steps: 8 })
          await expect.poll(position).not.toEqual(before)
          const dragging = await position()
          // V switches to the select tool and clears the inspector while the pointer is still down.
          await page.keyboard.press('v')
          await expect(page.getByLabel('Name', { exact: true })).toHaveCount(0)
          await expect.poll(position).toEqual(dragging)
          expect(saves).toBe(0)
          const saved = page.waitForResponse((response) => response.request().method() === 'PATCH'
            && new URL(response.url()).pathname === `/api/processes/${nodes[0]}/position`)
          await page.mouse.up()
          held = false
          await data(await saved)
          expect(saves).toBe(1)
        } finally {
          if (held) await page.mouse.up()
          page.off('request', observeSave)
        }
      })
      await layoutNodes()
    })

    const ports: string[] = []
    const portField = (label: string) => page.locator('label').filter({ has: page.getByText(label, { exact: true }) })
    await test.step('DF4: four item-less ports with resource/schema/rule contracts', async () => {
      const definitions = [
        { node: nodes[0], direction: 'Output', name: 'rows', type: 'file', schema: fileSchema, rule: '' },
        { node: nodes[1], direction: 'Input', name: 'rows', type: 'file', schema: fileSchema, rule: '' },
        { node: nodes[1], direction: 'Output', name: 'valid rows', type: 'data', schema: dataSchema, rule: 'attrs.rows > 0' },
        { node: nodes[2], direction: 'Input', name: 'valid rows', type: 'data', schema: dataSchema, rule: 'attrs.valid = true' },
      ]
      for (const port of definitions) {
        await page.locator(`.react-flow__node[data-id="${port.node}"]`).click({ position: { x: 24, y: 18 } })
        await page.getByRole('button', { name: `Add ${port.direction}`, exact: true }).click()
        await expect(portField('Item').locator('select')).toHaveValue('')
        await expect(portField('Item').locator('select').getByRole('option', { name: /No item/ })).toHaveCount(1)
        await portField('Port Name').locator('input').fill(port.name)
        await portField('Resource Type').locator('input').fill(port.type)
        await portField('Quantity').locator('input').fill('0')
        await portField('Unit').locator('input').fill('ea')
        await portField('Required').locator('select').selectOption('Y')
        await portField('Data schema (JSON object contract)').locator('textarea').fill(JSON.stringify(port.schema))
        await portField('Validation rule').locator('input').fill(port.rule)
        const added = posted(page, '/process-ios')
        await page.getByRole('button', { name: 'Create Port', exact: true }).click()
        const created = await data(await added)
        expect(created.itemId).toBeNull()
        ports.push(created.processIoId)
        await expect(page.locator(`.react-flow__node[data-id="${port.node}"] .canvas-node__port-name`, { hasText: port.name })).toBeVisible()
      }
    })

    await test.step('DF5: drag output handles to input handles, then validate', async () => {
      await layoutNodes()
      await page.locator('.workspace-panel--left').getByRole('button', { name: 'Pointer', exact: true }).click()
      for (const [from, to] of [[ports[0], ports[1]], [ports[2], ports[3]]]) {
        const source = page.locator(`.react-flow__handle.source[data-handleid="${from}"]`)
        const target = page.locator(`.react-flow__handle.target[data-handleid="${to}"]`)
        await expect(source).toBeVisible()
        await expect(target).toBeVisible()
        const a = await source.boundingBox(), b = await target.boundingBox()
        expect(a).not.toBeNull(); expect(b).not.toBeNull()
        const added = posted(page, '/process-connections')
        await page.mouse.move(a!.x + a!.width / 2, a!.y + a!.height / 2)
        await page.mouse.down()
        await page.mouse.move(b!.x + b!.width / 2, b!.y + b!.height / 2, { steps: 20 })
        await page.mouse.up()
        await data(await added)
      }
      await expect(page.locator('.react-flow__edge')).toHaveCount(2)
      await page.getByRole('button', { name: 'Check workflow', exact: true }).click()
      await expect(page.getByText(/^0 errors, 0 warnings$/)).toBeVisible()
    })

    await test.step('DF6–DF7: publish v1 and start an actual graph Flow Run', async () => {
      await page.goto(`/projects/${projectId}/runs?workflowId=${workflowId}`)
      const published = posted(page, `/workflows/${workflowId}/revisions`)
      await page.getByRole('button', { name: 'Publish current workflow', exact: true }).click()
      await data(await published)
      await page.getByRole('tab', { name: 'Flow executions', exact: true }).click()
      await expect(page.getByRole('combobox', { name: 'Published revision', exact: true })).toContainText('v1')
      await page.getByRole('combobox', { name: 'Published revision', exact: true }).selectOption({ label: 'v1' })
      await page.getByRole('combobox', { name: 'Run type', exact: true }).selectOption('actual')
      await page.getByRole('combobox', { name: 'Execution mode', exact: true }).selectOption('graph')
      await page.getByRole('button', { name: 'Start Flow Run', exact: true }).click()
      await expect(page.getByText('#1 Read CSV', { exact: true })).toBeVisible()
      await expect(page.getByText('#2 Validate rows', { exact: true })).toHaveCount(0)
      await expect(page.getByText('#3 Store rows', { exact: true })).toHaveCount(0)
    })

    const step = (number: number, name: string) => page.getByText(`#${number} ${name}`, { exact: true }).locator('..').locator('..')
    await test.step('DF8: complete the file step and route to Transform', async () => {
      await step(1, 'Read CSV').getByRole('button', { name: 'Start', exact: true }).click()
      await page.getByRole('textbox', { name: 'Output snapshot for step #1 (JSON)', exact: true }).fill('{"attrs":{"path":"in.csv","rows":3}}')
      await step(1, 'Read CSV').getByRole('button', { name: 'Complete', exact: true }).click()
      await expect(step(2, 'Validate rows')).toContainText('from step #1')
    })
    await test.step('DF9: wrong output is rejected without completing or adding attempts', async () => {
      const transform = step(2, 'Validate rows')
      await transform.getByRole('button', { name: 'Start', exact: true }).click()
      await transform.getByRole('button', { name: 'Show attempts', exact: true }).click()
      await expect(transform.getByText('Attempt 1: running', { exact: true })).toBeVisible()
      await page.getByRole('textbox', { name: 'Output snapshot for step #2 (JSON)', exact: true }).fill('{"attrs":{"rows":"3","valid":true}}')
      const rejected = page.waitForResponse((response) => response.request().method() === 'POST'
        && /\/api\/flow-runs\/[^/]+\/steps\/[^/]+\/complete$/.test(new URL(response.url()).pathname))
      await transform.getByRole('button', { name: 'Complete', exact: true }).click()
      expect((await rejected).status()).toBe(409)
      await expect(page.getByRole('alert').filter({ hasText: /outputSnapshot.attrs.rows must be number/ })).toBeVisible()
      await expect(transform.getByRole('button', { name: 'Complete', exact: true })).toBeVisible()
      await expect(transform.getByText(/^Attempt \d+:/)).toHaveCount(1)
      await expect(transform.getByText('Attempt 1: running', { exact: true })).toBeVisible()
      await expect(page.getByText('#3 Store rows', { exact: true })).toHaveCount(0)
    })
    await test.step('DF10–DF11: complete Transform and Data, then finish', async () => {
      await page.getByRole('textbox', { name: 'Output snapshot for step #2 (JSON)', exact: true }).fill('{"attrs":{"rows":3,"valid":true}}')
      await step(2, 'Validate rows').getByRole('button', { name: 'Complete', exact: true }).click()
      await expect(step(3, 'Store rows')).toContainText('from step #2')
      await step(3, 'Store rows').getByRole('button', { name: 'Start', exact: true }).click()
      await page.getByRole('textbox', { name: 'Output snapshot for step #3 (JSON)', exact: true }).fill('{"attrs":{"stored":3}}')
      await step(3, 'Store rows').getByRole('button', { name: 'Complete', exact: true }).click()
      await page.getByRole('button', { name: 'Finish Flow Run', exact: true }).click()
      await expect(page.getByRole('combobox', { name: 'Flow Run', exact: true })).toContainText('finished')
    })
    await test.step('DF12: exactly one completed attempt per step, output values and events', async () => {
      for (const [number, name, output] of [[1, 'Read CSV', /"path":\s*"in\.csv"/], [2, 'Validate rows', /"valid":\s*true/], [3, 'Store rows', /"stored":\s*3/]] as const) {
        const card = step(number, name)
        if (await card.getByRole('button', { name: 'Show attempts', exact: true }).count()) await card.getByRole('button', { name: 'Show attempts', exact: true }).click()
        await expect(card.getByText(/^Attempt \d+:/)).toHaveCount(1)
        await expect(card.getByText('Attempt 1: completed', { exact: true })).toBeVisible()
        await expect(card.locator('pre').filter({ hasText: output })).toHaveCount(1)
      }
      const events = page.getByRole('listitem')
      await expect(events.filter({ hasText: 'run_started' })).toHaveCount(1)
      await expect(events.filter({ hasText: 'step_started' })).toHaveCount(3)
      await expect(events.filter({ hasText: 'step_completed' })).toHaveCount(3)
      await expect(events.filter({ hasText: 'run_finished' })).toHaveCount(1)
    })
    await test.step('DF13: manufacturing lists stay empty', async () => {
      await page.getByRole('tab', { name: 'Runs', exact: true }).click()
      await expect(page.getByText('No runs for this workflow yet. Start one from the form on the right.', { exact: true })).toBeVisible()
      await page.getByRole('tab', { name: 'Work Orders (0)', exact: true }).click()
      await expect(page.getByText(/No work orders yet/)).toBeVisible()
      await page.goto(`/projects/${projectId}/inventory`)
      await expect(page.getByRole('tab', { name: 'Items (0)', exact: true })).toBeVisible()
      await expect(page.getByText(/No items found/)).toBeVisible()
      for (const [tab, empty] of [['Stock', 'No stock records yet.'], ['LOTs', 'No LOTs yet.'], ['BOMs', 'No BOMs yet.']]) {
        await page.getByRole('tab', { name: tab, exact: true }).click()
        await expect(page.getByText(empty, { exact: false })).toBeVisible()
      }
    })
  } finally {
    if (projectId && token) await test.step('DF14: delete only the project created by this test', async () => {
      const removed = await page.request.delete(`/api/projects/${projectId}`, { headers: { Authorization: `Bearer ${token}` } })
      expect(removed.ok(), `Project cleanup returned ${removed.status()}`).toBe(true)
    })
  }
})
