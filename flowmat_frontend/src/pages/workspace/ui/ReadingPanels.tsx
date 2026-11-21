import type { WorkflowCanvasViewModel } from '../../../entities/workflow/model/types'
import type { GuidedChapter } from '../model/guidedViews'
import { relationCount } from '../model/processRelations'
import type { CanvasReading } from '../model/useCanvasReading'
import type { RevisionChangesView } from '../model/useRevisionChanges'
import type { RunOverlayView } from '../model/useRunOverlay'
import { ChangesPicker } from './ChangesPicker'
import { DiagramGuide, type GuideAction } from './DiagramGuide'
import { GuidedViewsPanel } from './GuidedViewsPanel'
import { ProcessFinder, type FinderContext } from './ProcessFinder'
import { ProcessIndex } from './ProcessIndex'
import { RunPicker } from './RunPicker'
import { SemanticLens } from './SemanticLens'

/** archify keeps one reading panel open at a time: the finder, the lens, the guide, or FlowMat's run and revision pickers. */
export type ReadingPanel = 'finder' | 'lens' | 'guide' | 'runs' | 'changes' | 'index' | 'views'

interface Props {
  panel: ReadingPanel | null
  onClose(): void
  canvas: WorkflowCanvasViewModel
  reading: CanvasReading
  runView: RunOverlayView
  changesView: RevisionChangesView
  chapters: GuidedChapter[]
  /** The processes selected on the canvas, in reading order, for a new chapter. */
  selectedInOrder: string[]
  canEdit: boolean
  /** The process in the passport, which the index marks as current. */
  currentProcessId: string | null
  /** The process the Intent Trace previews, which the index marks too. */
  previewId: string | null
  onRename(processId: string, name: string): void
  /** A process chosen in the index or the finder: selected, and read or named as a route's start or end. */
  onChoose(processId: string): void
  onGuideAction(action: GuideAction): void
}

/** The open reading panel over the canvas, if any. */
export function ReadingPanels({ panel, onClose, canvas, reading, runView, changesView, chapters, selectedInOrder, canEdit,
  currentProcessId, previewId, onRename, onChoose, onGuideAction }: Props) {
  if (panel === 'runs') {
    return <RunPicker runs={runView.runs} loading={runView.loading} shownId={reading.run.id} onShow={reading.run.show}
      onClose={onClose} />
  }
  if (panel === 'changes') {
    return <ChangesPicker revisions={changesView.revisions} loading={changesView.loading} shownId={reading.changes.id}
      changes={changesView.changes} nameOf={reading.nameOf} onShow={reading.changes.show}
      onReveal={(processId) => reading.reveal([processId])} onClose={onClose} />
  }
  if (panel === 'lens') {
    return <SemanticLens kinds={reading.lens.kinds} chosen={reading.lens.chosen}
      status={reading.state?.kind === 'lens' ? reading.view?.text ?? null : null}
      onToggle={reading.lens.toggle} onClear={reading.clear} onClose={onClose} />
  }
  if (panel === 'views') {
    return <GuidedViewsPanel workflowId={canvas.workflow.workflowId} chapters={chapters} activeId={reading.guide.viewId}
      beat={reading.guide.beat} known={reading.knows} nameOf={reading.nameOf} canEdit={canEdit}
      selectedProcessIds={selectedInOrder} onShow={reading.guide.show} onClear={reading.clear} onClose={onClose} />
  }
  if (panel === 'index') {
    // As the finder does, but the index stays open to go on from one process to the next (archify).
    return <ProcessIndex nodes={canvas.nodes} currentId={currentProcessId} previewId={previewId}
      onRename={canEdit ? onRename : undefined}
      onPreview={(processId) => reading.intent.show(processId)}
      onClose={onClose} onChoose={onChoose} />
  }
  if (panel === 'finder') {
    return <ProcessFinder nodes={canvas.nodes} context={finderContext(reading)} linksOf={(processId) => linksOf(reading, processId)}
      onClose={onClose}
      onPick={(processId) => {
        onClose()
        onChoose(processId)
      }} />
  }
  if (panel === 'guide') {
    return <DiagramGuide processes={canvas.nodes.length} connections={canvas.edges.length} onClose={onClose}
      onAction={onGuideAction} />
  }
  return null
}

/** While a route is picked the finder names its start or its end; else it reads the process found. */
function finderContext(reading: CanvasReading): FinderContext {
  if (!reading.picking) return { kind: 'focus' }
  if (reading.picking.from == null) return { kind: 'route-source', badges: reading.picking.choices }
  return { kind: 'route-target', fromName: reading.nameOf(reading.picking.from), badges: reading.picking.choices }
}

function linksOf(reading: CanvasReading, processId: string): number {
  return relationCount(reading.relationsOf(processId))
}
