import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * A Korean browser sees the novice path in Korean: the first-run guide, the check row, the issue and its one-click fix.
 * English stays the default for an English browser, and the language buttons switch and remember the choice.
 */
test.use({ locale: 'ko-KR' })

test('a Korean browser checks and fixes the flow in Korean, and can switch to English', async ({ page }) => {
  const workflow = { workflowId: 'wf-ko', projectId: 'prj-e2e', workflowName: '메론우유', workflowDesc: null,
    workflowType: 'standard', workflowStatus: 'draft' }
  const mix = { processId: 'mix', projectId: 'prj-e2e', workflowId: 'wf-ko', processName: '혼합', processType: 'process',
    nodeType: 'process', processStatus: 'ready', colorScheme: 'slate', posX: 120, posY: 160, width: 180, height: 80,
    processDesc: null, version: 1, versionNonce: 1 }
  let milk = { processIoId: 'mix-milk', processId: 'mix', itemId: 'milk', ioName: '원유', direction: 'input', ioType: 'material',
    quantity: 100, unit: 'kg', formula: null, colorScheme: 'sky', requiredYn: 'Y', allowShortageYn: 'N', resourceType: 'material' }
  const out = { ...milk, processIoId: 'mix-out', itemId: null, ioName: '혼합액', direction: 'output', quantity: 112,
    colorScheme: 'emerald', requiredYn: 'N' }

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'PUT' && pathname === '/api/process-ios/mix-milk') {
      milk = { ...milk, requiredYn: 'N' }
      return ok(route, milk)
    }
    if (request.method() !== 'GET') {
      return route.fulfill({ status: 404, contentType: 'application/json',
        body: JSON.stringify({ success: false, data: null, message: `Unmocked ${request.method()} ${pathname}` }) })
    }
    if (pathname === '/api/workflows/wf-ko/canvas') {
      return ok(route, { workflow, graphSeq: 0, processes: [mix], processIos: [milk, out], connections: [], annotations: [],
        currentUserRole: 'owner' })
    }
    if (pathname === '/api/workflows/wf-ko/editor-document') {
      return ok(route, { schemaVersion: 1, camera: { x: 0, y: 0, z: 1 }, nextElementSeq: 1, version: 0, versionNonce: 0, elements: [] })
    }
    if (pathname === '/api/workflows/wf-ko/validation') {
      return ok(route, milk.requiredYn === 'Y'
        ? { errors: 1, warnings: 0, issues: [{ severity: 'error', code: 'REQUIRED_INPUT_UNCONNECTED', processId: 'mix',
          ioId: 'mix-milk', connectionId: null, message: 'Required input port mix-milk has no connection.', supportedFixes: [] }] }
        : { errors: 0, warnings: 0, issues: [] })
    }
    if (pathname === '/api/workflows') return ok(route, [workflow])
    return ok(route, [])
  })

  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/workflows/wf-ko')
  await expect(page.locator('details[aria-label="여기서 시작"] summary')).toContainText('5단계 중', { timeout: 15_000 })

  const check = page.getByRole('region', { name: '공정도 검사' })
  await expect(check.getByRole('button', { name: '발행' })).toBeVisible()
  await check.getByRole('button', { name: '검사', exact: true }).click()
  await expect(check).toContainText('오류 1개, 경고 0개')
  await expect(check.getByRole('button', { name: '오류: 필수 입력에 연결이 없음 — Required input port mix-milk has no connection.' }))
    .toBeVisible()
  // The one-click fix is drawn as a button, not as text run on after the message.
  await expect(check.getByRole('button', { name: '재고에서 가져오기 (필수 아님)' })).toHaveCSS('border-top-width', '1px')
  await check.getByRole('button', { name: '재고에서 가져오기 (필수 아님)' }).click()
  await expect(check).toContainText('문제가 없습니다.')

  // The port form of the selected step speaks Korean too.
  await page.locator('.react-flow__node[data-id="mix"]').click({ position: { x: 24, y: 18 } })
  await expect(page.getByRole('button', { name: '입력 추가' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '포트 고치기' })).toBeVisible()
  await expect(page.getByRole('textbox', { name: '포트 이름' })).toHaveValue('원유')
  await expect(page.getByRole('button', { name: '포트 저장' })).toBeVisible()

  // The choice is kept: after English is chosen the page reloads in English and stays so.
  await page.getByRole('group', { name: 'Language' }).getByRole('button', { name: 'EN' }).click()
  await expect(page.getByRole('region', { name: 'Workflow validation' }).getByRole('button', { name: 'Check workflow' }))
    .toBeVisible({ timeout: 15_000 })
  await expect(page.locator('html')).toHaveAttribute('lang', 'en')
  await expect(page.getByRole('group', { name: 'Language' }).getByRole('button', { name: 'EN' })).toHaveAttribute('aria-pressed', 'true')
})

test('a Korean browser enters items and stock in Korean, with a way back to the flow', async ({ page }) => {
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const { pathname } = new URL(route.request().url())
    if (await answerAuth(route, pathname)) return
    if (route.request().method() !== 'GET') throw new Error(`Unexpected write ${pathname}`)
    if (pathname === '/api/projects/prj-e2e') return ok(route, { projectId: 'prj-e2e', projectName: '우유 시험', ownerId: 'demo-owner' })
    // The stock tab adds up open work orders; an empty list is not that shape.
    if (pathname === '/api/material-requirements') return ok(route, { orders: 0, problems: [], lines: [] })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto('/projects/prj-e2e/inventory?from=wf-ko')
  await expect(page.getByRole('link', { name: '공정도로 돌아가기' })).toBeVisible({ timeout: 15_000 })
  await expect(page.getByRole('tab', { name: '품목 (0)' })).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByRole('heading', { name: '품목 추가' })).toBeVisible()
  await page.getByRole('tab', { name: '재고', exact: true }).click()
  await expect(page.getByRole('heading', { name: '재고 추가' })).toBeVisible()
  await expect(page.getByRole('link', { name: '공정도로 돌아가기' })).toHaveAttribute('href', '/projects/prj-e2e/workflows/wf-ko')
})
