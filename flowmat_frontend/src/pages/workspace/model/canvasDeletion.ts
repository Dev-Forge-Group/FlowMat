import { getRelatedConnectionIds } from '../../../entities/workflow/model/connectionPolicy'

type Edge = { id: string; fromProcessId: string; toProcessId: string }

/** What is being deleted on the canvas: processes and connections, by id. */
export interface DeleteSelection {
  nodeIds: string[]
  edgeIds: string[]
}

/** How a selection is deleted: one connection, one process with its connections, or everything at once. */
export type DeletePlan =
  | { kind: 'nothing' }
  | { kind: 'connection'; connectionId: string }
  | { kind: 'process'; processId: string }
  | { kind: 'elements' }

/**
 * The question to ask before deleting, when processes would take connections with them; none when nothing else goes.
 * The connections not in the selection are counted as implicit for more than one process.
 */
export function deleteConfirmation({ nodeIds, edgeIds }: DeleteSelection, edges: readonly Edge[]): string | null {
  if (nodeIds.length === 0) return null
  const connected = new Set<string>()
  for (const nodeId of nodeIds) {
    for (const edgeId of getRelatedConnectionIds([...edges], nodeId)) connected.add(edgeId)
  }
  if (connected.size === 0) return null
  if (nodeIds.length === 1) {
    return `Deleting this node will also remove ${connected.size} connected connection${connected.size === 1 ? '' : 's'}. Continue?`
  }
  const implicit = [...connected].filter((edgeId) => !edgeIds.includes(edgeId)).length
  return `Deleting ${nodeIds.length} nodes will also remove ${connected.size} connected connections`
    + `${implicit > 0 ? ` (${implicit} implicit)` : ''}. Continue?`
}

/**
 * One connection is deleted on its own, and so is one process whose selected connections are all its own (they go with
 * it); anything else is deleted together.
 */
export function deletePlan({ nodeIds, edgeIds }: DeleteSelection, edges: readonly Edge[]): DeletePlan {
  if (nodeIds.length === 0 && edgeIds.length === 0) return { kind: 'nothing' }
  if (nodeIds.length === 0 && edgeIds.length === 1) return { kind: 'connection', connectionId: edgeIds[0] }
  if (nodeIds.length === 1) {
    const own = new Set(getRelatedConnectionIds([...edges], nodeIds[0]))
    if (edgeIds.every((edgeId) => own.has(edgeId))) return { kind: 'process', processId: nodeIds[0] }
  }
  return { kind: 'elements' }
}
