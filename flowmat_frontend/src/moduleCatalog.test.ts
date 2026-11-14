import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * `modules.yml` at the product root names the frontend folders that belong to each module. The backend's
 * ModuleCatalogTest checks them as well, but the backend checks do not run for a frontend-only change, so a folder that
 * is renamed or removed is caught here, in the change that does it.
 */
describe('the module catalog', () => {
  it('names frontend paths that exist', () => {
    const root = join(process.cwd(), '..')
    const catalog = readFileSync(join(root, 'modules.yml'), 'utf8')
    const paths = [...new Set((catalog.match(/flowmat_frontend\/[\w./-]+/g) ?? []).map((path) => path.replace(/\.+$/, '')))]

    expect(paths.length).toBeGreaterThan(10)
    expect(paths.filter((path) => !existsSync(join(root, path)))).toEqual([])
  })
})
