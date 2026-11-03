import type { ReachDirection } from './graphReading'

/** What the canvas is being read for, kept in the address so a copied link opens the same view. */
export type ReadingState =
  | { kind: 'focus'; processId: string; reach: ReachDirection | null }
  | { kind: 'route'; from: string; to: string }

/** `#focus=<id>`, `#focus=<id>&reach=upstream|downstream` or `#route=<from>~<to>`; empty for no reading. */
export function formatReadingHash(state: ReadingState | null): string {
  if (!state) return ''
  if (state.kind === 'route') return `#route=${encodeURIComponent(state.from)}~${encodeURIComponent(state.to)}`
  return `#focus=${encodeURIComponent(state.processId)}${state.reach ? `&reach=${state.reach}` : ''}`
}

/**
 * The reading an address asks for, if every process it names is known; anything else (other keys, a bad direction, a
 * process that is gone) reads as no reading rather than a broken view.
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
  const processId = params.get('focus')
  if (!processId || !knownProcessIds.has(processId)) return null
  const reachValue = params.get('reach')
  const reach = reachValue === 'upstream' || reachValue === 'downstream' ? reachValue : null
  if (reachValue != null && reach == null) return null
  return { kind: 'focus', processId, reach }
}
