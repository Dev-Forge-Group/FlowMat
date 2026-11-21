import type { CanvasEdgeViewModel } from '../../../entities/workflow/model/types'

export interface ProcessRelation {
  connectionId: string
  /** The process at the other end; the process itself for a loop. */
  otherId: string
  /** The connection's label, else its type in words. */
  note: string
}

export interface ProcessRelations {
  out: ProcessRelation[]
  in: ProcessRelation[]
  loop: ProcessRelation[]
}

/**
 * A process's connections as archify's relationship rows: those leaving it, those entering it and those from it to
 * itself, each in saved order. Connections to processes that are gone are left out, as everywhere in reading.
 */
export function processRelations(edges: CanvasEdgeViewModel[], processId: string, known: ReadonlySet<string>): ProcessRelations {
  const relations: ProcessRelations = { out: [], in: [], loop: [] }
  for (const edge of edges) {
    if (!known.has(edge.fromProcessId) || !known.has(edge.toProcessId)) continue
    const note = edge.label?.trim() || edge.connectionType.replace(/_/g, ' ')
    if (edge.fromProcessId === processId && edge.toProcessId === processId) {
      relations.loop.push({ connectionId: edge.connectionId, otherId: processId, note })
    } else if (edge.fromProcessId === processId) {
      relations.out.push({ connectionId: edge.connectionId, otherId: edge.toProcessId, note })
    } else if (edge.toProcessId === processId) {
      relations.in.push({ connectionId: edge.connectionId, otherId: edge.fromProcessId, note })
    }
  }
  return relations
}

/** How many connections a process has, each loop once. */
export function relationCount(relations: ProcessRelations): number {
  return relations.out.length + relations.in.length + relations.loop.length
}

/** What archify's Intent Trace announces for a process previewed from the keyboard. */
export function intentAnnouncement(name: string, relations: ProcessRelations): string {
  const loops = relations.loop.length > 0 ? `, ${relations.loop.length} self loop` : ''
  return `${name}. ${relations.out.length} outgoing, ${relations.in.length} incoming${loops}. `
    + `${relationCount(relations)} connections. Press Enter for details.`
}
