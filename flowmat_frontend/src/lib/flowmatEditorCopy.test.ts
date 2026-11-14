import { createHash } from 'node:crypto'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * `lib/flowmat-editor` is a copy of Dev-Forge-Group/flowmat-editor, kept until FlowMat installs the published
 * `@dev-forge-group/flowmat-editor` package. Its source of truth is that repository: change the editor there, release
 * it, and bring the release here (or switch to the package), never edit the copy alone. The copy's contents are pinned
 * below, so an edit here fails until the hash is updated on purpose with the release it matches.
 */
const COPY_MATCHES = 'flowmat-editor 0.1.0 (Dev-Forge-Group/flowmat-editor 942d8f0), with its own boundary test paths'
const COPY_SHA256 = 'f7cab7d0dce689ca51ebee14b25a2b0247f452b054d83d26bee49086535ef850'

const SRC = join(process.cwd(), 'src')
const COPY = join(SRC, 'lib', 'flowmat-editor')

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    return statSync(path).isDirectory() ? files(path) : [path]
  })
}

function copyHash() {
  const hash = createHash('sha256')
  for (const file of files(COPY).map((path) => relative(COPY, path).split(sep).join('/')).sort()) {
    hash.update(file).update('\0').update(readFileSync(join(COPY, file))).update('\0')
  }
  return hash.digest('hex')
}

describe('the flowmat-editor copy', () => {
  it(`stays as released (${COPY_MATCHES})`, () => {
    expect(copyHash(), 'Change Dev-Forge-Group/flowmat-editor instead, then bring its release here').toBe(COPY_SHA256)
  })

  it('is imported only through its two entries, as the package will be', () => {
    const deep = files(SRC)
      .filter((file) => /\.(ts|tsx)$/.test(file) && !file.startsWith(COPY + sep))
      .flatMap((file) => [...readFileSync(file, 'utf8').matchAll(/from\s+['"]([^'"]*lib\/flowmat-editor\/[^'"]+)['"]/g)]
        .map((match) => match[1])
        .filter((specifier) => !specifier.endsWith('lib/flowmat-editor/react'))
        .map((specifier) => `${relative(SRC, file)}: ${specifier}`))
    expect(deep).toEqual([])
  })
})
