import type { CanvasReadingHighlight } from './canvasReadingModel'
import type { ChangeMark } from './revisionChanges'
import type { RunMark } from './runOverlay'

/** A compared revision's marks: what was added or changed since it. */
export interface ChangeMarks {
  processes: ReadonlyMap<string, ChangeMark>
  connections: ReadonlyMap<string, ChangeMark>
}

/**
 * A process's classes over the canvas (index.css "Canvas reading"): part of what is read (with its tone, and the origin
 * marked), near it, or receding; a shown run's mark from its latest step ("run-mark run-<mark>"); and what changed since
 * a compared revision ("change-mark change-<mark>"). Empty when none applies.
 */
export function processMarkClass(processId: string, reading: CanvasReadingHighlight | null,
  runMarks: ReadonlyMap<string, RunMark> | null, changeMarks: ChangeMarks | null): string {
  const read = !reading ? ''
    : reading.nodeIds.has(processId) ? `reading-on reading-${reading.tone}${processId === reading.origin ? ' reading-origin' : ''}`
      : reading.nearIds?.has(processId) ? 'reading-near' : 'reading-dim'
  const mark = runMarks?.get(processId)
  const change = changeMarks?.processes.get(processId)
  return [read, mark ? `run-mark run-${mark}` : '', change ? `change-mark change-${change}` : ''].filter(Boolean).join(' ')
}

/** A connection's classes: part of what is read or receding, and what changed since a compared revision ("change-<mark>"). */
export function connectionMarkClass(connectionId: string, reading: CanvasReadingHighlight | null,
  changeMarks: ChangeMarks | null): string {
  const read = !reading ? '' : reading.edgeIds.has(connectionId) ? `reading-on reading-${reading.tone}` : 'reading-dim'
  const change = changeMarks?.connections.get(connectionId)
  return [read, change ? `change-${change}` : ''].filter(Boolean).join(' ')
}
