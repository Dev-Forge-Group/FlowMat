import { describe, expect, it } from 'vitest'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, isAbsolute, join, relative, resolve } from 'node:path'

const forbiddenSpecifiers = [
  'react',
  'react-dom',
  '@xyflow/react',
  'zustand',
  'axios',
  '@tanstack/react-query',
  '@stomp/stompjs',
  'html-to-image',
  'konva',
  'pixi.js',
]

describe('flowmat-editor import boundary', () => {
  it('keeps production core code independent from UI, API, state, and renderer libraries', () => {
    const files = collectProductionTypeScriptFiles(join(process.cwd(), 'src', 'lib', 'flowmat-editor'))
    const violations = files.flatMap((file) => {
      const source = readFileSync(file, 'utf8')
      return forbiddenSpecifiers
        .filter((specifier) => hasForbiddenImport(source, specifier))
        .map((specifier) => `${file}: ${specifier}`)
    })

    expect(violations).toEqual([])
  })

  it('keeps every library file free of relative imports that leave the library', () => {
    // Product adapters (for example canvas annotations) live under src/entities and import
    // this library, never the other way round, so the folder can be published as a package.
    const libraryRoot = join(process.cwd(), 'src', 'lib', 'flowmat-editor')
    const violations = collectSourceFiles(libraryRoot).flatMap((file) =>
      relativeSpecifiers(readFileSync(file, 'utf8'))
        .filter((specifier) => !isInsideDirectory(libraryRoot, resolve(dirname(file), specifier)))
        .map((specifier) => `${file}: ${specifier}`),
    )

    expect(violations).toEqual([])
  })
})

function collectSourceFiles(directory: string): string[] {
  return readdirSync(directory).flatMap((entry) => {
    const path = join(directory, entry)
    if (statSync(path).isDirectory()) return collectSourceFiles(path)
    return /\.tsx?$/.test(entry) ? [path] : []
  })
}

function relativeSpecifiers(source: string): string[] {
  const pattern = /(?:from\s+|import\s+|import\(\s*|require\(\s*)['"](\.[^'"]*)['"]/g
  return Array.from(source.matchAll(pattern), (match) => match[1])
}

function isInsideDirectory(directory: string, target: string): boolean {
  const path = relative(directory, target)
  return path === '' || (!path.startsWith('..') && !isAbsolute(path))
}

function collectProductionTypeScriptFiles(directory: string): string[] {
  const files: string[] = []

  for (const entry of readdirSync(directory)) {
    const path = join(directory, entry)
    const stat = statSync(path)

    if (stat.isDirectory()) {
      files.push(...collectProductionTypeScriptFiles(path))
      continue
    }

    if (entry.endsWith('.ts') && !entry.endsWith('.test.ts')) {
      files.push(path)
    }
  }

  return files
}

function hasForbiddenImport(source: string, specifier: string): boolean {
  const escaped = escapeRegExp(specifier)
  const exactOrSubpath = `${escaped}(?:/[^'"]*)?`
  const patterns = [
    new RegExp(`from\\s+['"]${exactOrSubpath}['"]`),
    new RegExp(`import\\s+['"]${exactOrSubpath}['"]`),
    new RegExp(`import\\(\\s*['"]${exactOrSubpath}['"]\\s*\\)`),
    new RegExp(`require\\(\\s*['"]${exactOrSubpath}['"]\\s*\\)`),
  ]

  return patterns.some((pattern) => pattern.test(source))
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
