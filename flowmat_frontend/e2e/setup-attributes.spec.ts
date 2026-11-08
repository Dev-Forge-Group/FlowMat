import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

for (const mode of ['normal', 'lost-reply'] as const) {
  test(`multidimensional item setup and equipment rule with ${mode}`, async ({ page }) => {
    if (mode === 'normal') await page.clock.install()
    const projectId = 'setup-attributes-fixture'
    const equipment = { equipmentId: 'press', projectId, equipmentCode: 'PRESS', equipmentName: 'Press', equipmentType: 'machine', equipmentStatus: 'active', details: {} }
    const item = { itemId: 'product', projectId, itemCode: 'PROD', itemName: 'Product', itemType: 'product', itemStatus: 'active', resourceCategory: 'material', unitId: 'ea', unit: 'ea', lotManageYn: 'N', details: {} }
    const nextItem = { ...item, itemId: 'next-product', itemCode: 'NEXT', itemName: 'Next product' }
    let listedItems = [item, nextItem]
    let previewReads = 0
    let attributes = { itemId: 'product', attributes: {} as Record<string, string>, version: 0 }
    const attributeCommands: { attributes: Record<string, string>; expectedVersion: number }[] = []
    type Rule = { changeoverId: string; equipmentId: string; fromAttributes: Record<string, string>; toAttributes: Record<string, string>; priority: number; minutes: number; note: string | null; version: number }
    let rules: Rule[] = []
    const ruleCommands: { id: string; input: Omit<Rule, 'changeoverId' | 'equipmentId' | 'version'> & { expectedVersion: number } }[] = []
    await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
      const request = route.request(), url = new URL(request.url()), { pathname } = url
      if (await answerAuth(route, pathname)) return
      if (pathname === '/api/items/product/setup-attributes') {
        if (request.method() === 'GET') return ok(route, attributes)
        const input = request.postDataJSON(); attributeCommands.push(input)
        if (input.expectedVersion === attributes.version) attributes = { ...attributes, attributes: input.attributes, version: attributes.version + 1 }
        else if (input.expectedVersion !== attributes.version - 1 || JSON.stringify(input.attributes) !== JSON.stringify(attributes.attributes))
          return route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ success: false, data: null, message: 'expectedVersion changed; reload attributes.' }) })
        if (mode === 'lost-reply' && attributeCommands.length === 1) return route.abort('failed')
        return ok(route, attributes)
      }
      if (pathname === '/api/equipments/press/setup-preview') {
        previewReads++
        const fromItemId = url.searchParams.get('fromItemId')!, toItemId = url.searchParams.get('toItemId')!
        const values: Record<string, Record<string, string>> = { product: attributes.attributes, 'next-product': { mold: 'M2' } }
        const match = fromItemId === toItemId ? undefined : [...rules].sort((a, b) => a.priority - b.priority).find((rule) =>
          Object.entries(rule.fromAttributes).every(([key, value]) => values[fromItemId]?.[key] === value)
          && Object.entries(rule.toAttributes).every(([key, value]) => values[toItemId]?.[key] === value))
        return ok(route, { equipmentId: 'press', fromItemId, toItemId, minutes: match?.minutes ?? 0,
          ruleType: match ? 'ATTRIBUTE_RULE' : 'NONE', changeoverId: match?.changeoverId ?? null })
      }
      if (pathname.startsWith('/api/equipments/press/setup-changeovers')) {
        if (request.method() === 'GET') return ok(route, rules)
        const id = pathname.split('/').at(-1)!
        if (request.method() === 'DELETE') {
          rules = rules.filter((rule) => rule.changeoverId !== id)
          return ok(route, rules)
        }
        const input = request.postDataJSON(); ruleCommands.push({ id, input })
        const current = rules.find((rule) => rule.changeoverId === id)
        if (!current && input.expectedVersion === 0) rules.push({ ...input, changeoverId: id, equipmentId: 'press', version: 1 })
        else if (current && input.expectedVersion === current.version) Object.assign(current, input, { version: current.version + 1 })
        else if (!current || input.expectedVersion !== current.version - 1 || input.minutes !== current.minutes)
          return route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ success: false, data: null, message: 'expectedVersion changed; reload rules.' }) })
        if (mode === 'lost-reply' && ruleCommands.length === 1) return route.abort('failed')
        return ok(route, rules.find((rule) => rule.changeoverId === id))
      }
      if (request.method() !== 'GET') throw new Error(`Unexpected write ${pathname}`)
      if (pathname === '/api/items') return ok(route, listedItems)
      if (pathname === '/api/equipments') return ok(route, [equipment])
      if (pathname === '/api/units') return ok(route, [{ unitId: 'ea', unitCode: 'ea', unitName: 'Each' }])
      if (pathname === '/api/stock-analysis' || pathname === '/api/stock-waste') return ok(route, { lines: [] })
      if (pathname === '/api/inventory-ledger') return ok(route, { items: [], hasMore: false, nextCursor: null })
      if (pathname.endsWith('/hourly-cost')) return ok(route, { equipmentId: 'press', hourlyCost: null, version: 0 })
      if (pathname.endsWith('/schedule')) return ok(route, { equipmentId: 'press', timeZone: 'Asia/Seoul', calendar: null, downtimes: [], days: [] })
      if (pathname.endsWith('/availability')) return ok(route, { equipmentId: 'press', calendarSet: false, workingHours: 168, downtimeHours: 0, availableHours: 168, capacity: null })
      return ok(route, [])
    })
    await mockedLogin(page)
    await page.goto(`/projects/${projectId}/inventory?tab=items`)
    await page.getByRole('row', { name: /PROD.*Product/ }).getByRole('button', { name: 'Details', exact: true }).click()
    const attrs = page.getByRole('region', { name: 'Item setup attributes' })
    for (const [index, name, value] of [[1, 'color', 'red'], [2, 'mold', 'M1']] as const) {
      await attrs.getByRole('button', { name: 'Add attribute', exact: true }).click()
      await attrs.getByRole('textbox', { name: `Attribute name ${index}`, exact: true }).fill(name)
      await attrs.getByRole('textbox', { name: `Attribute value ${index}`, exact: true }).fill(value)
    }
    await attrs.getByRole('button', { name: 'Save setup attributes', exact: true }).click()
    if (mode === 'lost-reply') {
      await expect(attrs.getByRole('status')).toContainText('unconfirmed')
      await expect(attrs.getByRole('textbox', { name: 'Attribute value 1', exact: true })).toBeDisabled()
      await attrs.getByRole('button', { name: 'Save setup attributes', exact: true }).click()
      await expect.poll(() => attributeCommands[1]).toEqual(attributeCommands[0])
    }
    await expect(attrs.getByRole('textbox', { name: 'Attribute value 2', exact: true })).toBeEnabled()
    expect(attributes).toMatchObject({ attributes: { color: 'red', mold: 'M1' }, version: 1 })
    await page.setViewportSize({ width: 375, height: 800 })
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375)
    await page.setViewportSize({ width: 1280, height: 800 })
    if (mode === 'normal') {
      await attrs.getByRole('textbox', { name: 'Attribute value 1', exact: true }).fill('blue')
      attributes = { ...attributes, attributes: { color: 'green', mold: 'M1' }, version: 2 }
      await attrs.getByRole('button', { name: 'Save setup attributes', exact: true }).click()
      await expect(attrs.getByRole('alert')).toContainText('expectedVersion')
      await expect(attrs.getByRole('textbox', { name: 'Attribute value 1', exact: true })).toHaveValue('blue')
      await attrs.getByRole('button', { name: 'Reload current attributes', exact: true }).click()
      await expect(attrs.getByRole('textbox', { name: 'Attribute value 1', exact: true })).toHaveValue('green')
    }
    await page.goto(`/projects/${projectId}/inventory?tab=equipment`)
    await page.getByRole('row', { name: /PRESS.*Press/ }).getByRole('button', { name: 'Schedule', exact: true }).click()
    const panel = page.getByRole('region', { name: 'Setup attribute rules' })
    await panel.getByRole('button', { name: 'Add from attribute', exact: true }).click()
    await panel.getByRole('textbox', { name: 'From attribute name 1', exact: true }).fill('mold')
    await panel.getByRole('textbox', { name: 'From attribute value 1', exact: true }).fill('M1')
    await panel.getByRole('button', { name: 'Add to attribute', exact: true }).click()
    await panel.getByRole('textbox', { name: 'To attribute name 1', exact: true }).fill('mold')
    await panel.getByRole('textbox', { name: 'To attribute value 1', exact: true }).fill('M2')
    await panel.getByRole('spinbutton', { name: 'Rule priority', exact: true }).fill('10')
    await panel.getByRole('spinbutton', { name: 'Rule minutes', exact: true }).fill('30')
    await panel.getByRole('button', { name: 'Add setup rule', exact: true }).click()
    if (mode === 'lost-reply') {
      await expect(panel.getByRole('status')).toContainText('unconfirmed')
      await expect(panel.getByRole('spinbutton', { name: 'Rule minutes', exact: true })).toBeDisabled()
      await panel.getByRole('button', { name: 'Add setup rule', exact: true }).click()
      await expect.poll(() => ruleCommands[1]).toEqual(ruleCommands[0])
    }
    await expect(panel.getByText('Priority 10: mold=M1 → mold=M2 · 30 min', { exact: true })).toBeVisible()
    expect(rules).toHaveLength(1); expect(rules[0].version).toBe(1)
    const preview = page.getByRole('region', { name: 'Saved setup preview' })
    await preview.getByRole('combobox', { name: 'From item for preview', exact: true }).selectOption('product')
    await preview.getByRole('combobox', { name: 'To item for preview', exact: true }).selectOption('next-product')
    await expect(preview.getByRole('status')).toHaveText('Setup attribute rule: 30 min')
    await preview.getByRole('combobox', { name: 'From item for preview', exact: true }).selectOption('next-product')
    await expect(preview.getByRole('status')).toHaveText('No applicable rule: 0 min')
    await preview.getByRole('combobox', { name: 'From item for preview', exact: true }).selectOption('product')
    await expect(preview.getByRole('status')).toHaveText('Setup attribute rule: 30 min')
    await panel.getByRole('button', { name: 'Edit setup rule 10', exact: true }).click()
    await panel.getByRole('spinbutton', { name: 'Rule minutes', exact: true }).fill('45')
    await expect(preview.getByRole('status')).toHaveText('Setup attribute rule: 30 min')
    if (mode === 'normal') rules[0] = { ...rules[0], version: 2, minutes: 60 }
    await panel.getByRole('button', { name: 'Save setup rule', exact: true }).click()
    if (mode === 'normal') {
      await expect(panel.getByRole('alert')).toContainText('expectedVersion')
      await expect(panel.getByRole('spinbutton', { name: 'Rule minutes', exact: true })).toHaveValue('45')
      await panel.getByRole('button', { name: 'Reload current rules', exact: true }).click()
      await expect(panel.getByText('Priority 10: mold=M1 → mold=M2 · 60 min', { exact: true })).toBeVisible()
    } else await expect(panel.getByText('Priority 10: mold=M1 → mold=M2 · 45 min', { exact: true })).toBeVisible()
    if (mode === 'normal') await preview.getByRole('button', { name: 'Reload saved preview', exact: true }).click()
    await expect(preview.getByRole('status')).toHaveText(mode === 'normal' ? 'Setup attribute rule: 60 min' : 'Setup attribute rule: 45 min')
    const readsBeforeDelete = previewReads
    await page.setViewportSize({ width: 375, height: 800 })
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375)
    page.once('dialog', (dialog) => void dialog.accept())
    await panel.getByRole('button', { name: 'Delete setup rule 10', exact: true }).click()
    await expect(panel.getByText('No attribute rules set.', { exact: true })).toBeVisible()
    expect(rules).toHaveLength(0)
    await expect.poll(() => previewReads).toBeGreaterThan(readsBeforeDelete)
    await expect(preview.getByRole('status')).toHaveText('No applicable rule: 0 min')
    if (mode === 'normal') {
      listedItems = [nextItem] // Another editor removed the previously selected from item.
      await page.clock.fastForward(31_000)
      await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')))
      await expect(preview.getByRole('combobox', { name: 'From item for preview', exact: true }).locator('option[value="product"]')).toHaveCount(0)
      await expect(preview.getByRole('status')).toHaveCount(0)
      await expect(preview.getByRole('button', { name: 'Reload saved preview', exact: true })).toBeDisabled()
    }
  })
}
