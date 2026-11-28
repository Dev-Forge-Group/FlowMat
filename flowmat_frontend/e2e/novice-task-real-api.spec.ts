import { expect, test, type Page, type Response } from '@playwright/test'

test.skip(process.env.REAL_API_E2E !== '1' || process.env.FLOWMAT_FEATURE_E2E_DISPOSABLE !== '1',
  'Requires an explicitly disposable backend database')
// Reduced motion: the camera jumps instead of gliding, so a handle is where it was read when the drag starts.
test.use({ viewport: { width: 1920, height: 1080 }, locale: 'ko-KR', reducedMotion: 'reduce', trace: 'off' })

/**
 * The novice trial's task (docs/operations/novice-trial.md §2), done the way a participant does it: in a Korean browser,
 * through the screens only, from the home page to the plan's answer. Nothing is made through the API; the API is used only
 * to delete the project afterwards. The answer is the trial's: 178.5715 kg of raw milk needed, 28.5715 kg short.
 * Each step's time is recorded as a note: a machine's time, a floor under what a person needs, not the trial itself.
 */
function posted(page: Page, path: string | RegExp) {
  return page.waitForResponse((response) => response.request().method() === 'POST'
    && (typeof path === 'string' ? new URL(response.url()).pathname === `/api${path}` : path.test(new URL(response.url()).pathname)))
}
async function data(response: Response) {
  expect(response.ok(), `${response.request().method()} ${new URL(response.url()).pathname}: ${response.status()}`).toBe(true)
  const envelope = await response.json()
  expect(envelope.success).toBe(true)
  return envelope.data as Record<string, string>
}

test('novice task: draw melon milk and plan 1,000 bottles through the Korean screens only', async ({ page }) => {
  test.setTimeout(240_000)
  page.setDefaultTimeout(15_000)
  const started = Date.now()
  const times: string[] = []
  const lap = (name: string) => times.push(`${name} ${((Date.now() - started) / 1000).toFixed(1)}s`)
  let token = ''
  let projectId = ''
  let completed = false
  try {
    await page.goto('/')
    await page.locator('input').nth(0).fill('demo-owner')
    await page.locator('input[type="password"]').fill('demo1234')
    const login = posted(page, '/auth/login')
    await page.getByRole('button', { name: 'Log in', exact: true }).click()
    token = (await data(await login)).accessToken
    lap('login')

    // Home: one form makes the project and its first workflow, and opens the editor.
    const tag = Date.now().toString(36).toUpperCase()
    const form = page.locator('form').filter({ has: page.getByPlaceholder('프로젝트 이름', { exact: true }) })
    await form.getByPlaceholder('프로젝트 이름', { exact: true }).fill(`우유 시험 ${tag}`)
    const project = posted(page, '/projects')
    const firstWorkflow = posted(page, '/workflows')
    await form.getByRole('button', { name: '만들기', exact: true }).click()
    projectId = (await data(await project)).projectId
    const workflowId = (await data(await firstWorkflow)).workflowId
    await expect(page).toHaveURL(new RegExp(`/projects/${projectId}/workflows/${workflowId}$`))
    lap('project')

    // The guide leads to the items, and the inventory screen back to the flow.
    const guide = page.locator('details[aria-label="여기서 시작"]')
    await guide.getByRole('link', { name: '품목', exact: true }).click()
    await expect(page.getByRole('link', { name: '공정도로 돌아가기' })).toBeVisible()
    const items: Record<string, string> = {}
    const itemForm = page.locator('section').filter({ has: page.getByRole('heading', { name: '품목 추가' }) })
    const itemField = (label: string) => itemForm.locator('label').filter({ has: page.getByText(label, { exact: true }) })
    for (const [code, name, type, unit] of [
      ['MILK', '원유', 'material', 'unit_kg'], ['SYRUP', '멜론 시럽', 'material', 'unit_kg'], ['SUGAR', '설탕', 'material', 'unit_kg'],
      ['MIX', '혼합액', 'semi', 'unit_kg'], ['BOTTLE', '빈 병', 'material', 'unit_ea'], ['FILLED', '충전된 병', 'semi', 'unit_ea'],
      ['BOX', '상자', 'material', 'unit_ea'], ['PRODUCT', '메론우유', 'product', 'unit_ea'],
    ]) {
      await itemField('코드 *').locator('input').fill(`${code}-${tag}`)
      await itemField('이름 *').locator('input').fill(name)
      await itemField('종류').locator('input').fill(type)
      await itemField('단위').locator('select').selectOption(unit)
      const added = posted(page, '/items')
      await itemForm.getByRole('button', { name: '추가', exact: true }).click()
      items[name] = (await data(await added)).itemId
    }
    lap('items')

    await page.getByRole('tab', { name: '재고', exact: true }).click()
    const stockForm = page.locator('section').filter({ has: page.getByRole('heading', { name: '재고 추가' }) })
    const stockField = (label: string) => stockForm.locator('label').filter({ has: page.getByText(label, { exact: true }) })
    for (const [name, quantity] of [['원유', '150'], ['멜론 시럽', '20'], ['설탕', '50'], ['빈 병', '2000'], ['상자', '50']]) {
      await stockField('품목 *').locator('select').selectOption(items[name])
      await stockField('현재고 *').locator('input').fill(quantity)
      const added = posted(page, '/inventories')
      await stockForm.getByRole('button', { name: '추가', exact: true }).click()
      await data(await added)
    }
    lap('stock')
    await page.getByRole('link', { name: '공정도로 돌아가기' }).click()
    await expect(page).toHaveURL(new RegExp(`/workflows/${workflowId}$`))

    // Three steps, placed on empty canvas and renamed.
    const pane = page.locator('.react-flow__pane')
    const nodes: string[] = []
    for (const name of ['혼합', '충전', '포장']) {
      await page.locator('.workspace-panel--left').getByRole('button').filter({ has: page.getByText('Process', { exact: true }) }).click()
      const position = await pane.evaluate((element) => {
        const box = element.getBoundingClientRect()
        const occupied = [...document.querySelectorAll('.react-flow__node')].map((node) => node.getBoundingClientRect())
        for (const y of [0.3, 0.55, 0.75]) for (const x of [0.15, 0.45, 0.75]) {
          const point = { x: box.width * x, y: box.height * y }
          const screenX = box.left + point.x, screenY = box.top + point.y
          const clear = !occupied.some((node) => screenX >= node.left - 40 && screenX <= node.right + 40
            && screenY >= node.top - 40 && screenY <= node.bottom + 40)
          if (clear && document.elementFromPoint(screenX, screenY) === element) return point
        }
        throw new Error('No empty canvas position for the next step')
      })
      const added = posted(page, '/processes')
      await pane.click({ position })
      const id = (await data(await added)).processId
      nodes.push(id)
      await page.locator(`.react-flow__node[data-id="${id}"]`).click({ position: { x: 24, y: 18 } })
      await page.getByLabel('이름', { exact: true }).fill(name)
      const saved = page.waitForResponse((response) => response.request().method() === 'PUT'
        && new URL(response.url()).pathname === `/api/processes/${id}`)
      await page.getByRole('button', { name: '노드 저장', exact: true }).click()
      await data(await saved)
    }
    lap('steps')

    // Ten ports. Choosing the item fills the name and the unit; a material taken from stock is not required.
    const portField = (label: string) => page.locator('.inspector label').filter({ has: page.getByText(label, { exact: true }) })
    const ports: Record<string, string> = {}
    const definitions: Array<[number, 'input' | 'output', string, string, 'Y' | 'N']> = [
      [0, 'input', '원유', '100', 'N'], [0, 'input', '멜론 시럽', '10', 'N'], [0, 'input', '설탕', '5', 'N'], [0, 'output', '혼합액', '112', 'Y'],
      [1, 'input', '혼합액', '0.2', 'Y'], [1, 'input', '빈 병', '1', 'N'], [1, 'output', '충전된 병', '1', 'Y'],
      [2, 'input', '충전된 병', '24', 'Y'], [2, 'input', '상자', '1', 'N'], [2, 'output', '메론우유', '24', 'Y'],
    ]
    for (const [node, direction, name, quantity, required] of definitions) {
      await page.locator(`.react-flow__node[data-id="${nodes[node]}"]`).click({ position: { x: 24, y: 18 } })
      await page.getByRole('button', { name: direction === 'input' ? '입력 추가' : '출력 추가', exact: true }).click()
      await portField('품목').locator('select').selectOption(items[name])
      await expect(portField('포트 이름').locator('input')).toHaveValue(name)
      await portField('수량').locator('input').fill(quantity)
      if (direction === 'input') await portField('필수').locator('select').selectOption(required)
      const added = posted(page, '/process-ios')
      await page.getByRole('button', { name: '포트 만들기', exact: true }).click()
      ports[`${node}-${direction}-${name}`] = (await data(await added)).processIoId
    }
    lap('ports')

    // Lay the steps out left to right, then drag each output to the next step's input.
    const laidOut = nodes.map((id) => page.waitForResponse((response) => response.request().method() === 'PUT'
      && new URL(response.url()).pathname === `/api/processes/${id}`))
    await page.getByRole('button', { name: 'Layout LR', exact: true }).click()
    await Promise.all(laidOut)
    await page.getByRole('tab', { name: 'View', exact: true }).click()
    await page.getByRole('button', { name: 'Fit View', exact: true }).click()
    await page.getByRole('tab', { name: 'Home', exact: true }).click()
    await page.locator('.workspace-panel--left').getByRole('button', { name: 'Pointer', exact: true }).click()
    // The selected step's passport floats over the canvas; with nothing selected the handles are clear.
    await page.keyboard.press('Escape')
    const passport = page.getByRole('button', { name: 'Close semantic passport' })
    if (await passport.isVisible()) await passport.click()
    for (const [from, to] of [[ports['0-output-혼합액'], ports['1-input-혼합액']], [ports['1-output-충전된 병'], ports['2-input-충전된 병']]]) {
      const source = page.locator(`.react-flow__handle.source[data-handleid="${from}"]`)
      const target = page.locator(`.react-flow__handle.target[data-handleid="${to}"]`)
      await expect(source).toBeVisible()
      await expect(target).toBeVisible()
      // Wait for the camera to settle after the layout's own fit.
      await expect.poll(async () => {
        const first = JSON.stringify(await source.boundingBox())
        await page.waitForTimeout(150)
        return first === JSON.stringify(await source.boundingBox())
      }).toBe(true)
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
    lap('connections')

    // Check, publish, plan.
    const checks = page.getByRole('region', { name: '공정도 검사' })
    await checks.getByRole('button', { name: '검사', exact: true }).click()
    await expect(checks).toContainText('오류 0개')
    const published = posted(page, /\/api\/workflows\/[^/]+\/revisions$/)
    await checks.getByRole('button', { name: '발행', exact: true }).click()
    await data(await published)
    await expect(checks).toContainText('v1 발행했습니다.')
    lap('publish')

    await checks.getByRole('button', { name: '계획', exact: true }).click()
    const plan = page.getByRole('region', { name: '공정도 계획' })
    // The default output is the last one nothing continues from: melon milk, counted in bottles.
    await expect(plan.getByRole('combobox')).toHaveValue(ports['2-output-메론우유'])
    await plan.getByPlaceholder('수량 (ea)').fill('1000')
    await plan.getByRole('button', { name: '계획', exact: true }).click()
    const row = (name: string) => plan.getByRole('row', { name, exact: true })
    await expect(row('원유')).toContainText('178.5715 kg')
    await expect(row('원유')).toContainText('28.5715 kg 부족')
    await expect(row('멜론 시럽')).toContainText('17.8572 kg')
    await expect(row('설탕')).toContainText('8.9286 kg')
    await expect(row('빈 병')).toContainText('1000 ea')
    await expect(row('상자')).toContainText('41.6667 ea')
    lap('plan')
    test.info().annotations.push({ type: 'times', description: times.join(' · ') })
    console.log(`novice task times: ${times.join(' · ')}`)
    completed = true
  } finally {
    if (projectId && token) {
      const removed = await page.request.delete(`/api/projects/${projectId}`, { headers: { Authorization: `Bearer ${token}` } })
      if (completed) expect(removed.ok(), `Project cleanup returned ${removed.status()}`).toBe(true)
    }
  }
})
