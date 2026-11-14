import { expect, test } from '@playwright/test'
import { uniqueSuffix } from './support/uniqueSuffix'

test.skip(!process.env.REAL_API_E2E || process.env.FLOWMAT_NOTIFICATION_E2E_DISPOSABLE !== '1',
  'Needs the disposable notification project/viewer fixture')
test.use({ trace: 'off' })

test('a stock alert tells only the project owner once per opening and opens the real stock screen', async ({ page, request }) => {
  test.setTimeout(60_000)
  const projectId = 'prj_notification_e2e'
  const suffix = uniqueSuffix()
  const login = async (userId: string) => {
    const reply = await request.post('/api/auth/login', { data: { userIdOrEmail: userId, password: 'demo1234' } })
    expect(reply.ok()).toBe(true)
    return { Authorization: `Bearer ${(await reply.json()).data.accessToken}` }
  }
  const headers = await login('demo-owner')
  const viewerHeaders = await login('notification-e2e-member')
  const api = async (method: string, path: string, data?: unknown) => {
    const reply = await request.fetch(`/api${path}`, { method, headers, data, timeout: 10_000 })
    const envelope = await reply.json()
    expect(reply.ok(), `${method} ${path}: ${envelope.message}`).toBe(true)
    return envelope.data
  }
  const itemCode = `NT7-${suffix}`
  const item = await api('POST', '/items', { projectId, itemCode, itemName: 'Alert notification fixture', unitId: 'unit_ea' })
  const stock = await api('POST', '/inventories', {
    projectId, itemId: item.itemId, quantity: 20, minThreshold: 10, location: `WH-${suffix}`,
  })
  const move = (transactionType: string, quantity: number, requestId = crypto.randomUUID()) =>
    api('POST', '/inventory-transactions', { inventoryId: stock.inventoryId, transactionType, quantity, requestId })
  const notifications = async () => {
    const rows = await api('GET', '/notifications')
    return rows.filter((row: { type: string; title: string }) => row.type === 'stock_alert' && row.title.includes(itemCode))
  }
  const idempotencyKey = crypto.randomUUID()
  const issued = await move('issue', 15, idempotencyKey)
  const repeated = await move('issue', 15, idempotencyKey)
  expect(repeated.inventoryTransactionId).toBe(issued.inventoryTransactionId)
  expect(await api('GET', `/inventories/${stock.inventoryId}`)).toMatchObject({ quantity: 5 })
  let told = await notifications()
  expect(told).toHaveLength(1)
  expect(told[0]).toMatchObject({
    projectId, title: `Low stock: ${itemCode} at WH-${suffix}`,
    body: 'Available 5 is below the minimum 10.', refType: 'stock_alert', read: false,
  })
  const firstAlert = told[0].refId
  await move('issue', 5)
  expect(await notifications()).toHaveLength(1)
  const critical = await api('GET', `/stock-alerts?projectId=${projectId}`)
  expect(critical.find((row: { inventoryId: string }) => row.inventoryId === stock.inventoryId))
    .toMatchObject({ stockAlertId: firstAlert, severity: 'critical', resolved: false, actualValue: 0 })

  const viewerList = await request.get('/api/notifications', { headers: viewerHeaders })
  expect(viewerList.ok()).toBe(true)
  expect((await viewerList.json()).data).toEqual([])
  const forbidden = await request.post(`/api/notifications/${told[0].notificationId}/read`, { headers: viewerHeaders })
  expect(forbidden.status()).toBe(404)

  await move('receipt', 30)
  await api('POST', `/notifications/${told[0].notificationId}/read`)
  await move('issue', 25)
  told = await notifications()
  expect(told).toHaveLength(2)
  const reopened = told.find((row: { read: boolean }) => !row.read)
  expect(reopened).toBeTruthy()
  expect(reopened.refId).not.toBe(firstAlert)
  const history = await api('GET', `/stock-alerts?projectId=${projectId}&openOnly=false`)
  expect(history.filter((row: { inventoryId: string }) => row.inventoryId === stock.inventoryId)).toHaveLength(2)
  expect(history.find((row: { stockAlertId: string }) => row.stockAlertId === firstAlert)).toMatchObject({ resolved: true })

  await page.goto('/')
  await page.getByRole('textbox', { name: 'demo-owner' }).fill('demo-owner')
  await page.getByRole('textbox', { name: '••••••••' }).fill('demo1234')
  await page.getByRole('button', { name: 'Log in', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Logout', exact: true })).toBeVisible()
  const panel = page.getByRole('region', { name: 'Notifications', exact: true })
  const row = panel.getByRole('listitem', { name: reopened.title, exact: true }).filter({ has: page.getByText('New', { exact: true }) })
  await expect(row).toContainText('Available 5 is below the minimum 10.')
  const read = page.waitForResponse((reply) => reply.request().method() === 'POST'
    && new URL(reply.url()).pathname === `/api/notifications/${reopened.notificationId}/read`)
  await row.getByRole('link', { name: 'Open', exact: true }).click()
  expect((await read).status()).toBe(200)
  await expect(page).toHaveURL(new RegExp(`/projects/${projectId}/inventory\\?tab=stock$`))
  await expect(page.getByRole('region', { name: 'Stock alerts' })).toContainText(itemCode)
  expect((await notifications()).every((row: { read: boolean }) => row.read)).toBe(true)
})
