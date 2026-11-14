type NodeLike = { id: string }

/**
 * Keeps the previous node object for every node that did not change, and the previous array when none did.
 *
 * React Flow adopts a new node object as a new node, and one that arrives without `measured` loses its handle positions
 * until it is measured again; a connection to it is not drawn meanwhile. The canvas rebuilds its nodes whenever the
 * loaded canvas, the selection or someone's editing presence changes. Almost every node comes out the same, and handing
 * back the same object leaves React Flow's measurement of it alone and skips its re-render.
 */
export function reuseUnchangedNodes<T extends NodeLike>(previous: readonly T[], rebuilt: readonly T[]): T[] {
  const before = new Map(previous.map((node) => [node.id, node]))
  let unchanged = previous.length === rebuilt.length
  const nodes = rebuilt.map((node, index) => {
    const kept = before.get(node.id)
    if (!kept || !sameNode(kept, node)) {
      unchanged = false
      return node
    }
    if (previous[index] !== kept) unchanged = false
    return kept
  })
  return unchanged ? (previous as T[]) : nodes
}

/** The parts of a node that are rebuilt as new objects each time and compared by what they hold. */
const BY_CONTENT = new Set(['position', 'data', 'measured'])

function sameNode(a: object, b: object) {
  return sameEntries(a, b, (key, left, right) => BY_CONTENT.has(key) && sameEntries(left, right))
}

function sameEntries(a: unknown, b: unknown, alsoSame?: (key: string, left: unknown, right: unknown) => boolean) {
  if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) return false
  const left = a as Record<string, unknown>
  const right = b as Record<string, unknown>
  for (const key of new Set([...Object.keys(left), ...Object.keys(right)])) {
    if (!Object.is(left[key], right[key]) && !alsoSame?.(key, left[key], right[key])) return false
  }
  return true
}
