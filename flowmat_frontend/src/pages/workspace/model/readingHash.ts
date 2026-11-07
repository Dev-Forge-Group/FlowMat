import { getWorkflowPaletteDefinitions } from '../../../entities/workflow/model/nodeCatalog'
import type { ReachDirection } from './graphReading'

/** What the canvas is being read for, kept in the address so a copied link opens the same view. */
export type ReadingState =
  | { kind: 'focus'; processId: string; reach: ReachDirection | null }
  | { kind: 'route'; from: string; to: string }
  /** One or two process kinds, as the palette names them. */
  | { kind: 'lens'; kinds: readonly string[] }

const LENS_KINDS = new Set(getWorkflowPaletteDefinitions().map((definition) => definition.nodeType))

/**
 * `#focus=<id>`, `#focus=<id>&reach=upstream|downstream`, `#route=<from>~<to>` or `#lens=<kind>[~<kind>]`; empty for no
 * reading.
 */
export function formatReadingHash(state: ReadingState | null): string {
  if (!state) return ''
  if (state.kind === 'route') return `#route=${encodeURIComponent(state.from)}~${encodeURIComponent(state.to)}`
  if (state.kind === 'lens') return `#lens=${state.kinds.map(encodeURIComponent).join('~')}`
  return `#focus=${encodeURIComponent(state.processId)}${state.reach ? `&reach=${state.reach}` : ''}`
}

/** The production run shown over the canvas (`run=<id>`), kept beside any reading so either can change alone. */
export function parseRunParam(hash: string): string | null {
  return new URLSearchParams(hash.replace(/^#/, '')).get('run') || null
}

/** A reading hash with the run shown over the canvas added, e.g. `#focus=p1&run=r1`, or `#run=r1` with no reading. */
export function withRunParam(readingHash: string, runId: string | null): string {
  return withViewParams(readingHash, runId, null)
}

/** The published revision the draft is compared with (`changes=<revision id>`), kept beside the reading like the run. */
export function parseChangesParam(hash: string): string | null {
  return new URLSearchParams(hash.replace(/^#/, '')).get('changes') || null
}

/** A reading hash with what is shown over the canvas added: `#focus=p1&run=r1&changes=v3`, or only those with no reading. */
export function withViewParams(readingHash: string, runId: string | null, revisionId: string | null): string {
  const extra = [
    runId ? `run=${encodeURIComponent(runId)}` : '',
    revisionId ? `changes=${encodeURIComponent(revisionId)}` : '',
  ].filter(Boolean).join('&')
  if (!extra) return readingHash
  return readingHash ? `${readingHash}&${extra}` : `#${extra}`
}

/**
 * The reading an address asks for, if every process or kind it names is known; anything else (other keys, a bad direction,
 * a process that is gone, a third kind) reads as no reading rather than a broken view.
 */
export function parseReadingHash(hash: string, knownProcessIds: ReadonlySet<string>): ReadingState | null {
  const params = new URLSearchParams(hash.replace(/^#/, ''))
  const routeValue = params.get('route')
  if (routeValue != null) {
    const parts = routeValue.split('~')
    if (parts.length !== 2) return null
    const [from, to] = parts
    return from && to && from !== to && knownProcessIds.has(from) && knownProcessIds.has(to) ? { kind: 'route', from, to } : null
  }
  const lensValue = params.get('lens')
  if (lensValue != null) {
    const kinds = lensValue.split('~')
    const valid = kinds.length <= 2 && new Set(kinds).size === kinds.length && kinds.every((kind) => LENS_KINDS.has(kind))
    return valid ? { kind: 'lens', kinds } : null
  }
  const processId = params.get('focus')
  if (!processId || !knownProcessIds.has(processId)) return null
  const reachValue = params.get('reach')
  const reach = reachValue === 'upstream' || reachValue === 'downstream' ? reachValue : null
  if (reachValue != null && reach == null) return null
  return { kind: 'focus', processId, reach }
}
