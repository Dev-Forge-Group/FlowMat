import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { uniqueSuffix } from './support/uniqueSuffix'

test.skip(!process.env.REAL_API_E2E || process.env.FLOWMAT_NOTIFICATION_E2E_DISPOSABLE !== '1',
  'Requires an explicitly disposable DB with notification-e2e-member/project fixtures')
test.use({ trace: 'off' })

async function apiFor(request: APIRequestContext, userId = 'demo-owner') {
  const login = await request.post('/api/auth/login', { data: { userIdOrEmail: userId, password: 'demo1234' } })
  expect(login.ok()).toBe(true)
  const headers = { Authorization: `Bearer ${(await login.json()).data.accessToken}` }
  const api = async (method: string, path: string, data?: unknown) => {
    const response = await request.fetch(`/api${path}`, { method, headers, data })
    const envelope = await response.json()
    expect(response.ok(), `${method} ${path}: ${envelope.message}`).toBe(true)
    expect(envelope.success).toBe(true)
    return envelope.data
  }
  return { api, headers }
}

async function loginUi(page: Page, userId: string) {
  await page.goto('/')
  await page.getByRole('textbox', { name: 'demo-owner' }).fill(userId)
  await page.getByRole('textbox', { name: '••••••••' }).fill('demo1234')
  await page.getByRole('button', { name: 'Log in', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Logout', exact: true })).toBeVisible()
}

test('real assignments show only the recipient their NCR, warehouse and work order notifications', async ({ request, page }) => {
  const projectId = 'prj_notification_e2e'
  const memberId = 'notification-e2e-member'
  const suffix = uniqueSuffix()
  const owner = await apiFor(request)
  const member = await apiFor(request, memberId)
  const ncr = await owner.api('POST', '/nonconformities', { projectId, title: `Private dents ${suffix}` })
  const description = `<img src=x onerror="window.notificationInjection=true"> ${suffix}`
  await owner.api('POST', `/nonconformities/${ncr.nonconformityId}/actions`, {
    actionType: 'corrective', description, ownerId: memberId, dueDate: '2030-01-31',
  })
  const item = await owner.api('POST', '/items', {
    projectId, itemCode: `NTF-${suffix}`, itemName: 'Notification fixture', unitId: 'unit_ea',
  })
  const inventory = await owner.api('POST', '/inventories', {
    projectId, itemId: item.itemId, quantity: 3, location: `DOCK-${suffix}`,
  })
  const task = await owner.api('POST', '/warehouse-tasks', {
    projectId, inventoryId: inventory.inventoryId, quantity: 3, toLocation: `SHELF-${suffix}`,
  })
  for (let i = 0; i < 2; i++) {
    await owner.api('PUT', `/warehouse-tasks/${task.taskId}/assignee`, { assignedTo: memberId })
  }
  const order = await owner.api('POST', '/work-orders', {
    projectId, workOrderTitle: `Assigned rolls ${suffix}`, assignedTo: memberId,
  })
  const notifications = await member.api('GET', '/notifications?unreadOnly=true')
  expect(notifications).toHaveLength(3)
  const action = notifications.find((notification: { type: string }) => notification.type === 'ncr_action')
  expect(action).toMatchObject({ projectId, refId: ncr.nonconformityId, body: `${description} · due 2030-01-31`, read: false })
  expect(notifications.find((notification: { type: string }) => notification.type === 'warehouse_task'))
    .toMatchObject({ projectId, refId: task.taskId, body: `From DOCK-${suffix} to SHELF-${suffix}.` })
  expect(notifications.find((notification: { type: string }) => notification.type === 'work_order'))
    .toMatchObject({ projectId, refId: order.workOrderId, body: order.workOrderTitle })
  const forbidden = await request.post(`/api/notifications/${action.notificationId}/read`, { headers: owner.headers })
  expect(forbidden.status()).toBe(404)

  await loginUi(page, memberId)
  const panel = page.getByRole('region', { name: 'Notifications', exact: true })
  await expect(panel).toContainText('3 unread')
  await expect(panel).toContainText(description)
  await expect(panel.locator('img')).toHaveCount(0)
  expect(await page.evaluate(() => 'notificationInjection' in window)).toBe(false)
  const ncrRow = panel.getByRole('listitem', { name: action.title, exact: true })
  await expect(ncrRow.getByRole('link', { name: 'Open' })).toHaveAttribute('href', `/projects/${projectId}/inventory?tab=quality`)
  await ncrRow.getByRole('button', { name: 'Mark read', exact: true }).click()
  await expect(panel).toContainText('2 unread')
  await panel.getByRole('button', { name: 'Mark all read', exact: true }).click()
  await expect(panel).toContainText('All read')

  await owner.api('POST', `/nonconformities/${ncr.nonconformityId}/actions`, {
    actionType: 'preventive', description: `Private follow-up ${suffix}`, ownerId: memberId,
  })
  expect(await member.api('GET', '/notifications/unread-count')).toEqual({ unread: 1 })

  // Current membership, rather than the age of the token, controls the old notification bodies.
  const members = await owner.api('GET', `/project-members?projectId=${projectId}`)
  const membership = members.find((entry: { userId: string }) => entry.userId === memberId)
  expect(membership).toBeTruthy()
  await owner.api('DELETE', `/project-members/${membership.projectMemberId}`)
  expect(await member.api('GET', '/notifications')).toEqual([])
  expect(await member.api('GET', '/notifications/unread-count')).toEqual({ unread: 0 })
  expect(await member.api('POST', '/notifications/read-all')).toEqual({ marked: 0 })
  const revoked = await request.post(`/api/notifications/${action.notificationId}/read`, { headers: member.headers })
  expect(revoked.status()).toBe(404)
  await page.reload()
  await expect(panel).toContainText('Nothing new for you.')
  await expect(panel).not.toContainText(description)
})

test('real location safety stock counts child bins and can be changed and removed in the stock UI', async ({ request, page }) => {
  const { api } = await apiFor(request)
  const suffix = uniqueSuffix()
  const project = await api('POST', '/projects', { projectName: `Place API ${suffix}`, ownerId: 'demo-owner' })
  const projectId = project.projectId
  try {
    const warehouse = await api('POST', '/storage-locations', { projectId, locationCode: 'WH-A', locationType: 'warehouse' })
    await api('POST', '/storage-locations', {
      projectId, locationCode: 'BIN-A1', locationType: 'bin', parentLocationId: warehouse.locationId,
    })
    await api('POST', '/storage-locations', { projectId, locationCode: 'WH-B', locationType: 'warehouse' })
    const item = await api('POST', '/items', { projectId, itemCode: 'BOLT', itemName: 'Bolt', unitId: 'unit_ea' })
    for (const [location, quantity] of [['BIN-A1', 10], ['WH-B', 50]] as const) {
      await api('POST', '/inventories', { projectId, itemId: item.itemId, location, quantity })
    }
    await api('PUT', '/stock-alerts/place-safety-stocks', {
      projectId, itemId: item.itemId, locationId: warehouse.locationId, safetyStockQty: 20,
    })
    expect(await api('GET', `/stock-alerts/reorder?projectId=${projectId}&location=wh-a`)).toMatchObject([
      { itemId: item.itemId, availableQuantity: 10, shortageQuantity: 10, location: 'WH-A' },
    ])
    expect(await api('GET', `/stock-alerts/reorder?projectId=${projectId}&location=WH-B`)).toEqual([])
    await loginUi(page, 'demo-owner')
    await page.goto(`/projects/${projectId}/inventory?tab=stock`)
    const section = page.getByRole('region', { name: 'Below safety stock' })
    await section.getByLabel('Reorder at').selectOption('WH-A')
    await expect(section).toContainText('1 item below safety stock at WH-A')
    const group = section.getByRole('group', { name: 'Safety stock at WH-A' })
    const form = group.getByRole('form', { name: 'Set safety stock at WH-A' })
    await form.getByLabel('Item to watch').selectOption(item.itemId)
    await form.getByLabel('Safety stock here').fill('5')
    await form.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(group).toContainText('BOLT · Bolt: 5 ea')
    expect(await api('GET', `/stock-alerts/reorder?projectId=${projectId}&location=WH-A`)).toEqual([])
    await group.getByRole('button', { name: 'Remove', exact: true }).click()
    await expect(group).not.toContainText('BOLT · Bolt: 5 ea')
    expect(await api('GET', `/stock-alerts/place-safety-stocks?projectId=${projectId}`)).toEqual([])
  } finally {
    await api('DELETE', `/projects/${projectId}`)
  }
})
