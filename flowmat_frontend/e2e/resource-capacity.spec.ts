import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

/**
 * The factory's energy and water limits in project settings (docs/architecture/adr/ADR-046-resources-first-class.md
 * decision 5) against a mocked API: the owner changes a limit, adds one and saves the whole list.
 */
test('the owner sets the factory\'s resource limits', async ({ page }) => {
  const projectId = 'limits-fixture'
  let limits = [{ resourceType: 'energy', unit: 'kWh', amount: 10, per: 'hour' }]
  const saved: unknown[] = []
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request(), path = new URL(request.url()).pathname
    if (await answerAuth(route, path)) return
    if (path === `/api/projects/${projectId}/resource-capacities`) {
      if (request.method() === 'PUT') {
        const body = request.postDataJSON()
        saved.push(body)
        limits = body.capacities
      }
      return ok(route, limits)
    }
    if (request.method() !== 'GET') throw new Error(`Unexpected write ${path}`)
    if (path === '/api/project-members') return ok(route, [{ projectMemberId: 'm', projectId, userId: 'demo-owner', projectRole: 'owner', memberStatus: 'active' }])
    if (path === '/api/projects') return ok(route, [{ projectId, projectName: 'Limits fixture', projectStatus: 'active' }])
    return ok(route, [])
  })
  await mockedLogin(page)
  await page.goto(`/projects/${projectId}/settings`)
  const panel = page.getByRole('region', { name: 'Resource limits' })
  const first = panel.getByRole('row', { name: 'Limit 1' })
  await expect(first.getByLabel('Amount')).toHaveValue('10')

  await first.getByLabel('Amount').fill('12.5')
  await panel.getByRole('button', { name: 'Add limit' }).click()
  const second = panel.getByRole('row', { name: 'Limit 2' })
  await second.getByLabel('Resource').selectOption('water')
  await second.getByLabel('Unit').fill('l')
  await panel.getByRole('button', { name: 'Save limits' }).click()
  await expect(panel.getByRole('alert')).toHaveText('Each limit needs a unit and an amount above 0.')
  expect(saved).toHaveLength(0)

  await second.getByLabel('Amount').fill('1000')
  await second.getByLabel('Per').selectOption('day')
  await panel.getByRole('button', { name: 'Save limits' }).click()
  await expect(panel.getByRole('button', { name: 'Save limits' })).toBeDisabled()
  expect(saved).toEqual([{ capacities: [
    { resourceType: 'energy', unit: 'kWh', amount: 12.5, per: 'hour' },
    { resourceType: 'water', unit: 'l', amount: 1000, per: 'day' },
  ] }])
  await expect(panel.getByRole('row', { name: 'Limit 2' }).getByLabel('Amount')).toHaveValue('1000')
})
