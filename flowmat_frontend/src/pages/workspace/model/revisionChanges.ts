import {
  toEdgeViewModel, toNodeViewModel, toPortViewModel,
} from '../../../entities/workflow/model/toWorkflowCanvasViewModel'
import type { CanvasEdgeViewModel, CanvasNodeViewModel, CanvasPortViewModel } from '../../../entities/workflow/model/types'
import type { ProcessConnectionDto, ProcessDto, ProcessIoDto } from '../../../shared/types/api'

/**
 * What the draft on the canvas changed since a published revision, compared by stable id (archify's delta): processes and
 * connections added or changed, and the ones that are gone, which the canvas can only name. A revision keeps the draft's
 * ids when it is published, so the same process has the same id in both. The snapshot is read through the same mapping
 * as the canvas, so a value an older revision left out counts as that mapping's default on both sides.
 */
export type ChangeMark = 'added' | 'changed'

export interface DraftGraph {
  nodes: readonly CanvasNodeViewModel[]
  edges: readonly CanvasEdgeViewModel[]
}

export interface RevisionChanges {
  processes: ReadonlyMap<string, ChangeMark>
  connections: ReadonlyMap<string, ChangeMark>
  /** For a changed process or connection, the fields that differ; "ports" when one of a process's ports does. */
  fields: ReadonlyMap<string, readonly string[]>
  removedProcesses: readonly { processId: string; name: string }[]
  removedConnections: readonly { connectionId: string; text: string }[]
}

/**
 * Bookkeeping, layout and copies: saving, moving or resizing a process, which side its line leaves from, or someone editing
 * it right now changes no step; ports are compared one by one; source and target repeat the from and to processes.
 */
const IGNORED = new Set(['id', 'position', 'size', 'version', 'versionNonce', 'editingByUserId', 'inputs', 'outputs',
  'inputCount', 'outputCount', 'projectId', 'workflowId', 'handleId', 'source', 'target', 'sourceHandle', 'targetHandle'])

type Row = Record<string, unknown>

/** The changes, or null when the snapshot is not one this screen can read. */
export function revisionChanges(draft: DraftGraph, snapshot: unknown): RevisionChanges | null {
  if (!isRow(snapshot)) return null
  const processRows = rows(snapshot.processes, 'processId')
  const portRows = rows(snapshot.processIos, 'processIoId')
  const connectionRows = rows(snapshot.connections, 'connectionId')
  if (!processRows || !portRows || !connectionRows) return null

  const beforePorts = new Map([...portRows].map(([id, row]) => [id, toPortViewModel(row as unknown as ProcessIoDto)] as const))
  const beforeNodes = new Map([...processRows].map(([id, row]) => [id, toNodeViewModel(row as unknown as ProcessDto, [])] as const))
  // An older snapshot may leave flowRate out; the canvas has it as null.
  const beforeEdges = new Map([...connectionRows].map(([id, row]) =>
    [id, toEdgeViewModel({ flowRate: null, ...row } as unknown as ProcessConnectionDto)] as const))

  const processes = new Map<string, ChangeMark>()
  const fields = new Map<string, string[]>()
  for (const node of draft.nodes) {
    const before = beforeNodes.get(node.processId)
    if (!before) {
      processes.set(node.processId, 'added')
      continue
    }
    const differ = differingFields(node, before)
    if (differ.length > 0) {
      processes.set(node.processId, 'changed')
      fields.set(node.processId, differ)
    }
  }

  // A port added, removed or edited changes the process it belongs to.
  const draftPorts: CanvasPortViewModel[] = draft.nodes.flatMap((node) => [...node.inputs, ...node.outputs])
  const draftPortIds = new Set(draftPorts.map((port) => port.processIoId))
  const portOwners = new Set<string>()
  for (const port of draftPorts) {
    const before = beforePorts.get(port.processIoId)
    if (!before || differingFields(port, before).length > 0) portOwners.add(port.processId)
  }
  for (const [portId, before] of beforePorts) {
    if (!draftPortIds.has(portId)) portOwners.add(before.processId)
  }
  for (const processId of portOwners) {
    if (!beforeNodes.has(processId) || processes.get(processId) === 'added') continue
    if (!draft.nodes.some((node) => node.processId === processId)) continue
    processes.set(processId, 'changed')
    fields.set(processId, [...(fields.get(processId) ?? []), 'ports'])
  }

  const connections = new Map<string, ChangeMark>()
  for (const edge of draft.edges) {
    const before = beforeEdges.get(edge.connectionId)
    if (!before) {
      connections.set(edge.connectionId, 'added')
      continue
    }
    const differ = differingFields(edge, before)
    if (differ.length > 0) {
      connections.set(edge.connectionId, 'changed')
      fields.set(edge.connectionId, differ)
    }
  }

  const draftNames = new Map(draft.nodes.map((node) => [node.processId, node.name]))
  const nameOf = (processId: string) => beforeNodes.get(processId)?.name || draftNames.get(processId) || processId
  const draftProcessIds = new Set(draftNames.keys())
  const draftConnectionIds = new Set(draft.edges.map((edge) => edge.connectionId))
  const removedProcesses = [...beforeNodes.keys()]
    .filter((processId) => !draftProcessIds.has(processId))
    .map((processId) => ({ processId, name: nameOf(processId) }))
  const removedConnections = [...beforeEdges.entries()]
    .filter(([connectionId]) => !draftConnectionIds.has(connectionId))
    .map(([connectionId, before]) => ({ connectionId, text: `${nameOf(before.fromProcessId)} → ${nameOf(before.toProcessId)}` }))

  // In the canvas's own order, whichever check found the change.
  const inDraftOrder = new Map(draft.nodes.filter((node) => processes.has(node.processId))
    .map((node) => [node.processId, processes.get(node.processId)!] as const))
  return { processes: inDraftOrder, connections, fields, removedProcesses, removedConnections }
}

/** One line for the reading chip, e.g. "Since v3: processes 1 added, 1 changed, 1 removed; connections 1 added." */
export function changesSummary(revisionNo: number, changes: RevisionChanges): string {
  const tally = (marks: ReadonlyMap<string, ChangeMark>, removed: number) => [
    [[...marks.values()].filter((mark) => mark === 'added').length, 'added'],
    [[...marks.values()].filter((mark) => mark === 'changed').length, 'changed'],
    [removed, 'removed'],
  ].filter(([count]) => Number(count) > 0).map(([count, label]) => `${count} ${label}`).join(', ')
  const parts = [
    ['processes', tally(changes.processes, changes.removedProcesses.length)],
    ['connections', tally(changes.connections, changes.removedConnections.length)],
  ].filter(([, text]) => text).map(([noun, text]) => `${noun} ${text}`)
  return parts.length === 0 ? `No changes since v${revisionNo}.` : `Since v${revisionNo}: ${parts.join('; ')}.`
}

function differingFields(after: object, before: object): string[] {
  const left = after as Row
  const right = before as Row
  return Object.keys(left).filter((key) => !IGNORED.has(key) && !same(left[key], right[key]))
}

function same(left: unknown, right: unknown): boolean {
  if (left == null || right == null) return left == null && right == null
  if (typeof left === 'number' || typeof right === 'number') return Number(left) === Number(right)
  if (Array.isArray(left) || Array.isArray(right)) {
    return Array.isArray(left) && Array.isArray(right) && left.length === right.length
      && left.every((entry, index) => same(entry, right[index]))
  }
  if (isRow(left) && isRow(right)) {
    const keys = new Set([...Object.keys(left), ...Object.keys(right)])
    return [...keys].every((key) => same(left[key], right[key]))
  }
  return left === right
}

/** A snapshot collection by id; an older schema may leave it out, anything else unreadable makes the whole snapshot so. */
function rows(value: unknown, idField: string): Map<string, Row> | null {
  if (value == null) return new Map()
  if (!Array.isArray(value)) return null
  const indexed = new Map<string, Row>()
  for (const entry of value) {
    if (!isRow(entry) || typeof entry[idField] !== 'string' || indexed.has(entry[idField] as string)) return null
    indexed.set(entry[idField] as string, entry)
  }
  return indexed
}

function isRow(value: unknown): value is Row {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
