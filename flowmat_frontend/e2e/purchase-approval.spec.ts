import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/** The project owner's approval of purchase orders from a limit up (docs/domain/purchasing.md PO33-PO35), mocked. */
test.use({ timezoneId: 'UTC' })

test('the owner sets an approval limit and approves a draft before it is ordered', async ({ page }) => {
  const projectId = 'prj-po-approval'
  let settings: Record<string, unknown> = { projectId, approvalLimit: null, version: null, updatedBy: null, updatedAt: null }
  const draft = { purchaseOrderId: 'po1', projectId, orderNumber: 'PO-0001', supplierName: 'Mill Co', status: 'draft', expectedDate: null,
    note: null, createdBy: 'demo-owner', createdAt: '2041-09-01T09:00:00Z', orderedBy: null, orderedAt: null, endedBy: null, endedAt: null,
    endReason: null, orderValue: 200, valueComplete: true, receipts: [], dateChanges: [], returns: [], approvedBy: null, approvedAt: null,
    needsApproval: false, lines: [{ lineId: 'l1', lineNo: 1, itemId: 'flour', itemCode: 'FLOUR', itemName: 'flour', unitCode: 'kg', lotTracked: false,
      quantity: 10, receivedQuantity: 0, remainingQuantity: 10, expectedDate: null, note: null, unitPrice: 20, lineValue: 200, returnedQuantity: 0 }] }
  let current: Record<string, unknown> = draft
  const sent: unknown[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'PUT' && pathname === '/api/purchase-settings') {
      const body = request.postDataJSON() as Record<string, unknown>
      sent.push(body)
      settings = { ...settings, approvalLimit: body.approvalLimit, version: 0, updatedBy: 'demo-owner', updatedAt: '2041-09-01T10:00:00Z' }
      current = { ...draft, needsApproval: true }
      return ok(route, settings)
    }
    if (request.method() === 'POST' && pathname === '/api/purchase-orders/po1/approve') {
      current = { ...draft, needsApproval: true, approvedBy: 'demo-owner', approvedAt: '2041-09-01T11:00:00Z' }
      return ok(route, current)
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/purchase-settings') return ok(route, settings)
    if (pathname === '/api/purchase-orders') return ok(route, [current])
    if (pathname === '/api/project-members') {
      return ok(route, [{ projectMemberId: 'member-1', projectId, userId: 'demo-owner', projectRole: 'owner', memberStatus: 'active' }])
    }
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Approval', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Approval', ownerId: 'demo-owner' })
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=purchasing`)
  const approval = page.getByRole('group', { name: 'Purchase approval' })
  await expect(approval).toContainText('Purchase orders do not need approval.', { timeout: 15_000 })
  await approval.getByRole('button', { name: 'Change approval limit' }).click()
  await approval.getByLabel('Limit', { exact: true }).fill('100')
  await approval.getByRole('button', { name: 'Save limit' }).click()
  await expect(approval).toContainText("Purchase orders worth 100 or more, or with a line not priced yet, need the project owner's approval.")
  expect(sent).toEqual([{ projectId, approvalLimit: 100, expectedVersion: null }])

  await page.getByRole('table', { name: 'Purchase orders' }).getByRole('button', { name: 'PO-0001' }).click()
  const detail = page.getByRole('region', { name: 'Purchase order PO-0001' })
  await expect(detail.getByRole('note')).toHaveText("Needs the project owner's approval before it is ordered.")
  await expect(detail.getByRole('button', { name: 'Order', exact: true })).toHaveCount(0)
  await detail.getByRole('button', { name: 'Approve' }).click()
  await expect(detail.getByText('Approved by demo-owner 2041-09-01 11:00')).toBeVisible()
  await expect(detail.getByRole('button', { name: 'Order', exact: true })).toBeVisible()
})
