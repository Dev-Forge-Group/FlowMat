import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/** Editors approve purchase orders from their step's value up, in turn, before the owner (docs/domain/purchasing.md PO50-PO51), mocked. */
test.use({ timezoneId: 'UTC' })

test('the owner sets approval steps and approves a draft when it is their turn', async ({ page }) => {
  const projectId = 'prj-po-steps'
  let settings: Record<string, unknown> = { projectId, approvalLimit: 1000, version: 0, updatedBy: 'demo-owner', updatedAt: '2041-09-01T08:00:00Z',
    approvalSteps: [{ fromValue: 100, approverId: 'kim', canApprove: true }] }
  const draft = (orderNumber: string, approvals: unknown[]) => ({ purchaseOrderId: orderNumber, projectId, orderNumber, supplierName: 'Mill Co',
    status: 'draft', expectedDate: null, note: null, createdBy: 'demo-owner', createdAt: '2041-09-01T09:00:00Z', orderedBy: null, orderedAt: null,
    endedBy: null, endedAt: null, endReason: null, orderValue: 1500, valueComplete: true, receipts: [], dateChanges: [], returns: [],
    approvedBy: null, approvedAt: null, needsApproval: true, approvals,
    lines: [{ lineId: `${orderNumber}-l1`, lineNo: 1, itemId: 'flour', itemCode: 'FLOUR', itemName: 'flour', unitCode: 'kg', lotTracked: false,
      quantity: 10, receivedQuantity: 0, remainingQuantity: 10, expectedDate: null, note: null, unitPrice: 150, lineValue: 1500 }] })
  const kim = { approverId: 'kim', fromValue: 100, projectOwner: false, approvedAt: '2041-09-02T10:00:00Z' }
  const lee = { approverId: 'lee', fromValue: 500, projectOwner: false, approvedAt: null }
  const boss = { approverId: 'demo-owner', fromValue: 1000, projectOwner: true, approvedAt: null }
  let orders: Record<string, unknown>[] = [draft('PO-0001', [kim, lee, boss]), draft('PO-0002', [kim, { ...lee, approvedAt: '2041-09-02T11:00:00Z' }, boss])]
  const sent: unknown[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (await answerAuth(route, pathname)) return
    if (request.method() === 'PUT' && pathname === '/api/purchase-settings') {
      const body = request.postDataJSON() as Record<string, unknown>
      sent.push(body)
      settings = { ...settings, version: 1, approvalSteps: (body.approvalSteps as { fromValue: number; approverId: string }[])
        .map((one) => ({ ...one, canApprove: true })) }
      return ok(route, settings)
    }
    if (request.method() === 'POST' && pathname === '/api/purchase-orders/PO-0002/approve') {
      orders = [orders[0], { ...orders[1], approvedBy: 'demo-owner', approvedAt: '2041-09-02T12:00:00Z',
        approvals: [kim, { ...lee, approvedAt: '2041-09-02T11:00:00Z' }, { ...boss, approvedAt: '2041-09-02T12:00:00Z' }] }]
      return ok(route, orders[1])
    }
    if (request.method() !== 'GET') return route.fulfill({ status: 404 })
    if (pathname === '/api/purchase-settings') return ok(route, settings)
    if (pathname === '/api/purchase-orders') return ok(route, orders)
    if (pathname === '/api/project-members') {
      return ok(route, [{ projectMemberId: 'm0', projectId, userId: 'demo-owner', projectRole: 'owner', memberStatus: 'active' },
        { projectMemberId: 'm1', projectId, userId: 'kim', projectRole: 'editor', memberStatus: 'active' },
        { projectMemberId: 'm2', projectId, userId: 'lee', projectRole: 'editor', memberStatus: 'active' },
        { projectMemberId: 'm3', projectId, userId: 'vic', projectRole: 'viewer', memberStatus: 'active' }])
    }
    if (pathname === '/api/projects') return ok(route, [{ projectId, projectName: 'Steps', ownerId: 'demo-owner' }])
    if (pathname === `/api/projects/${projectId}`) return ok(route, { projectId, projectName: 'Steps', ownerId: 'demo-owner' })
    if (pathname.endsWith('/time-zone')) return ok(route, { projectId, timeZone: 'UTC', version: 0 })
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/inventory?tab=purchasing`)

  // Kim approves from 100; lee joins from 500, before the owner from 1000. Only editors are offered.
  const approval = page.getByRole('group', { name: 'Purchase approval' })
  await expect(approval).toContainText('Purchase orders are approved in turn: from 100 by kim, from 1,000 by the project owner;', { timeout: 15_000 })
  await approval.getByRole('button', { name: 'Change approval steps' }).click()
  const form = approval.getByRole('form', { name: 'Approval steps' })
  await expect(form.getByLabel('Step 1 from')).toHaveValue('100')
  await expect(form.getByLabel('Step 1 approver')).toHaveValue('kim')
  await form.getByRole('button', { name: 'Add step' }).click()
  await expect(form.getByLabel('Step 2 approver').locator('option')).toHaveText(['Choose who approves', 'kim', 'lee'])
  await form.getByRole('button', { name: 'Save steps' }).click()
  await expect(approval.getByRole('alert')).toHaveText('Step 2: enter the value it starts from, 0 or more with at most four decimals. Use at most 10 digits before the decimal.')
  await form.getByLabel('Step 2 from').fill('500')
  await form.getByLabel('Step 2 approver').selectOption('lee')
  await form.getByRole('button', { name: 'Save steps' }).click()
  await expect(form).toHaveCount(0)
  expect(sent).toEqual([{ projectId, approvalLimit: 1000, expectedVersion: 0,
    approvalSteps: [{ fromValue: 100, approverId: 'kim' }, { fromValue: 500, approverId: 'lee' }] }])
  await expect(approval).toContainText('from 100 by kim, from 500 by lee, from 1,000 by the project owner;')

  // PO-0001 waits for lee before the owner, so the owner cannot approve it yet.
  const list = page.getByRole('table', { name: 'Purchase orders' })
  await list.getByRole('button', { name: 'PO-0001' }).click()
  const first = page.getByRole('region', { name: 'Purchase order PO-0001' })
  await expect(first.getByRole('note')).toHaveText('Needs approval in turn before it is ordered: kim ✓ 2041-09-02 10:00 → lee → the project owner')
  await expect(first.getByRole('button', { name: 'Approve' })).toHaveCount(0)

  // PO-0002 waits for the owner only now.
  await list.getByRole('button', { name: 'PO-0002' }).click()
  const second = page.getByRole('region', { name: 'Purchase order PO-0002' })
  await second.getByRole('button', { name: 'Approve' }).click()
  await expect(second.getByText('Approved by kim 2041-09-02 10:00, then lee 2041-09-02 11:00, then demo-owner 2041-09-02 12:00')).toBeVisible()
})
