import { appendFileSync, existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path'
import process from 'node:process'

/**
 * What CI needs to leave out an E2E spec file that already passed against the same code
 * (docs/operations/ci-selection.md). Both switches are off unless CI sets them, and neither changes the built app.
 *
 * - E2E_CHUNK_MAP=<file>: the build writes which source files each emitted file was made from.
 * - E2E_ASSET_LOG=<file>: `vite preview` appends the time and path of every request it serves, so the spec that was
 *   running when a file was fetched can be told apart.
 */
export function alreadyPassedPlugin(root) {
  return {
    name: 'flowmat-already-passed',
    generateBundle(_options, bundle) {
      const out = process.env.E2E_CHUNK_MAP
      if (!out) return
      const files = {}
      const cssModules = new Map()
      for (const [fileName, item] of Object.entries(bundle)) {
        if (item.type !== 'chunk') continue
        const ids = item.moduleIds.map((id) => sourceOf(root, id))
        files[fileName] = [...new Set(ids)].sort()
        // A stylesheet is emitted from the CSS modules of the chunks that import it.
        for (const css of item.viteMetadata?.importedCss ?? []) {
          const set = cssModules.get(css) ?? new Set()
          for (const id of ids) if (/\.css$/.test(id)) set.add(id)
          cssModules.set(css, set)
        }
      }
      for (const [fileName, item] of Object.entries(bundle)) {
        if (item.type !== 'asset') continue
        const ids = new Set((item.originalFileNames ?? []).map((id) => sourceOf(root, id)))
        for (const id of cssModules.get(fileName) ?? []) ids.add(id)
        files[fileName] = [...ids].sort()
      }
      mkdirSync(dirname(out), { recursive: true })
      writeFileSync(out, `${JSON.stringify({ files })}\n`)
    },
    configurePreviewServer(server) {
      const log = process.env.E2E_ASSET_LOG
      if (!log) return
      mkdirSync(dirname(log), { recursive: true })
      server.middlewares.use((request, _response, next) => {
        appendFileSync(log, `${Date.now()} ${request.url}\n`)
        next()
      })
    },
  }
}

/** A module id as a path from the frontend folder; anything from a package is covered by package-lock.json. */
function sourceOf(root, id) {
  const path = id.replace(/^\0/, '').replace(/\?.*$/, '')
  if (path.includes(`${sep}node_modules${sep}`) || path.includes('/node_modules/')) return 'node_modules'
  // Emitted files name their sources relative to the root; generated helpers have no file at all.
  const absolute = isAbsolute(path) ? path : resolve(root, path)
  const local = relative(root, absolute)
  if (local.startsWith('..') || !existsSync(absolute)) return `virtual:${path}`
  return local.split(sep).join('/')
}
