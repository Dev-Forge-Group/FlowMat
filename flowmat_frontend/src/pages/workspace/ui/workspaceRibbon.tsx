import type { MutableRefObject } from 'react'
import { Shapes } from 'lucide-react'
import type { WorkflowCanvasViewModel } from '../../../entities/workflow/model/types'
import type { WorkflowPaletteTool } from '../../../entities/workflow/model/nodeCatalog'
import {
  buildRibbonTabs,
  type RibbonButtonHandlers,
  type RibbonDynamicButtons,
  type RibbonGroupContent,
} from '../../../widgets/canvas-toolbar/config/ribbonConfig'
import { createWorkspaceSelectionCommands } from '../model/workspaceSelectionCommands'
import { ANNOTATION_TOOL_DEFINITIONS, EDITOR_TOOL_DEFINITIONS } from '../model/workspaceTools'
import type { ExportChoice } from './ExportMenu'
import type { WorkspaceEditorCommandApi, WorkspaceEditorSelectionSnapshot } from './WorkspaceEditorLayer'

type SelectionCommandOptions = Parameters<typeof createWorkspaceSelectionCommands>[0]

/** What the workspace's ribbon shows and does. */
export interface WorkspaceRibbonState {
  editorSelection: WorkspaceEditorSelectionSnapshot
  canEditAnnotations: boolean
  editorCommandApiRef: MutableRefObject<WorkspaceEditorCommandApi | null>
  canvas: WorkflowCanvasViewModel
  batchAnnotationMutation: { mutateAsync: SelectionCommandOptions['batchAnnotations'] }
  setWorkspaceMessage: (message: string) => void
  activeTool: WorkflowPaletteTool
  setActiveTool: (tool: WorkflowPaletteTool) => void
  addNode: () => Promise<unknown> | void
  isEditorCommandContext: boolean
  undo: () => Promise<unknown> | void
  redo: () => Promise<unknown> | void
  past: readonly { label: string }[]
  future: readonly { label: string }[]
  applyLayout: (direction: 'TB' | 'LR') => Promise<unknown> | void
  exportJson: () => void
  runExport: (choice: ExportChoice) => void
  handleSaveEditorDocument: () => Promise<unknown>
  handleReloadEditorDocument: () => Promise<unknown>
  fitViewRef: MutableRefObject<() => void>
  selectAllRef: MutableRefObject<() => void>
  paletteDefinitions: readonly { tool: WorkflowPaletteTool; label: string }[]
  remoteCursors: ReadonlyMap<string, unknown>
  syncUserId: string | null
  savedLabel: string | null | false
  workflows: readonly { workflowId: string; workflowName: string }[]
  navigate: (to: string) => void
}

/** The ribbon's tabs: its buttons' handlers and states, the tool buttons, and the Collaborate tab's presence and status. */
export function workspaceRibbonTabs(k: WorkspaceRibbonState) {
  const ribbonHandlers: RibbonButtonHandlers = {
    ...createWorkspaceSelectionCommands({
      selection: k.editorSelection,
      editable: k.canEditAnnotations,
      editorApi: k.editorCommandApiRef.current,
      annotations: k.canvas.annotations,
      batchAnnotations: (input) => k.batchAnnotationMutation.mutateAsync(input),
      onError: k.setWorkspaceMessage,
    }),
    'select-pointer': { onClick: () => k.setActiveTool('select'), active: k.activeTool === 'select' },
    'add-node': { onClick: () => void k.addNode() },
    undo: {
      onClick: () => {
        if (k.isEditorCommandContext) {
          k.editorCommandApiRef.current?.undo()
          return
        }
        void k.undo()
      },
      disabled: k.isEditorCommandContext ? !k.editorSelection.canUndo : k.past.length === 0,
      title: k.isEditorCommandContext
        ? (k.editorSelection.canUndo ? 'Undo editor document change' : 'Nothing to undo')
        : (k.past.length > 0 ? `Undo: ${k.past[k.past.length - 1].label}` : 'Nothing to undo'),
    },
    redo: {
      onClick: () => {
        if (k.isEditorCommandContext) {
          k.editorCommandApiRef.current?.redo()
          return
        }
        void k.redo()
      },
      disabled: k.isEditorCommandContext ? !k.editorSelection.canRedo : k.future.length === 0,
      title: k.isEditorCommandContext
        ? (k.editorSelection.canRedo ? 'Redo editor document change' : 'Nothing to redo')
        : (k.future.length > 0 ? `Redo: ${k.future[0].label}` : 'Nothing to redo'),
    },
    'layout-tb': {
      onClick: () => void k.applyLayout('TB'),
      disabled: k.canvas.nodes.length === 0,
      title: 'Auto layout top to bottom',
    },
    'layout-lr': {
      onClick: () => void k.applyLayout('LR'),
      disabled: k.canvas.nodes.length === 0,
      title: 'Auto layout left to right',
    },
    'export-json': { onClick: k.exportJson, disabled: k.canvas.nodes.length === 0, title: 'Export as JSON' },
    'export-png': {
      onClick: () => k.runExport({ kind: 'png' }),
      disabled: k.canvas.nodes.length === 0,
      title: 'Export as PNG',
    },
    'export-svg': {
      onClick: () => k.runExport({ kind: 'svg', theme: 'light' }),
      disabled: k.canvas.nodes.length === 0,
      title: 'Export as SVG (vector, the workflow only)',
    },
    'save-editor-document': {
      onClick: () => void k.handleSaveEditorDocument(),
      disabled: !k.canEditAnnotations,
      title: k.canEditAnnotations ? 'Save editor document elements' : 'Viewers cannot save editor elements',
    },
    'reload-editor-document': {
      onClick: () => void k.handleReloadEditorDocument(),
      title: 'Reload editor document elements',
    },
    'duplicate-selected': {
      onClick: () => k.editorCommandApiRef.current?.duplicateSelected(),
      disabled: !k.editorCommandApiRef.current || k.editorSelection.elements.length === 0,
      title: 'Duplicate selected shapes',
    },
    'delete-selected': {
      onClick: () => k.editorCommandApiRef.current?.deleteSelected(),
      disabled: !k.editorCommandApiRef.current || k.editorSelection.elements.length === 0,
      title: 'Delete selected shapes',
    },
    'bring-to-front': {
      onClick: () => k.editorCommandApiRef.current?.bringSelectedToFront(),
      disabled: !k.editorCommandApiRef.current || k.editorSelection.elements.length === 0,
      title: 'Bring to front',
    },
    'send-to-back': {
      onClick: () => k.editorCommandApiRef.current?.sendSelectedToBack(),
      disabled: !k.editorCommandApiRef.current || k.editorSelection.elements.length === 0,
      title: 'Send to back',
    },
    'fit-view': {
      onClick: () => k.fitViewRef.current(),
      title: 'Fit view',
    },
    'select-all': {
      onClick: () => k.selectAllRef.current(),
      title: 'Select all',
    },
  }

  const ribbonDynamicButtons: RibbonDynamicButtons = {
    tools: k.paletteDefinitions.map((definition) => ({
      id: `tool-${definition.tool}`,
      icon: Shapes,
      label: definition.label,
      onClick: () => k.setActiveTool(definition.tool),
      active: k.activeTool === definition.tool,
    })),
    draw: [
      ...ANNOTATION_TOOL_DEFINITIONS.map((definition) => ({
        id: `tool-${definition.tool}`,
        icon: definition.icon,
        label: definition.label,
        onClick: () => k.setActiveTool(definition.tool),
        active: k.activeTool === definition.tool,
        disabled: !k.canEditAnnotations,
        title: k.canEditAnnotations ? definition.description : 'Viewers cannot create annotations',
      })),
      ...EDITOR_TOOL_DEFINITIONS.map((definition) => ({
        id: `tool-${definition.tool}`,
        icon: definition.icon,
        label: definition.label,
        onClick: () => k.setActiveTool(definition.tool),
        active: k.activeTool === definition.tool,
        disabled: !k.canEditAnnotations,
        title: k.canEditAnnotations ? definition.description : 'Viewers cannot create editor elements',
      })),
    ],
  }

  // Collaborate tab (step 4, migration plan §4): same state/logic as before, only the
  // render location moves off the title bar and into the Collaborate tab's groups.
  const ribbonGroupContent: RibbonGroupContent = {
    presence: k.remoteCursors.size > 0 && (
      <div className="presence-avatars" title={`${k.remoteCursors.size} collaborators online`}>
        {[...k.remoteCursors.keys()]
          .filter((uid) => uid !== k.syncUserId)
          .slice(0, 5)
          .map((uid) => (
            <span key={uid} className="presence-avatar" title={uid}>
              {uid.slice(0, 2).toUpperCase()}
            </span>
          ))}
        {k.remoteCursors.size > 5 && (
          <span className="presence-avatar presence-avatar--overflow">+{k.remoteCursors.size - 5}</span>
        )}
      </div>
    ),
    status: k.savedLabel && (
      <span className={`save-status save-status--${k.savedLabel}`}>
        {k.savedLabel === 'saving' ? 'Saving...' : 'Saved'}
      </span>
    ),
    workflow: k.workflows.length > 1 && (
      <select
        className="workflow-switcher"
        value={k.canvas.workflow.workflowId}
        onChange={(e) => {
          k.navigate(`/projects/${k.canvas.workflow.projectId}/k.workflows/${e.target.value}`)
        }}
        title="Switch workflow"
      >
        {k.workflows.map((wf) => (
          <option key={wf.workflowId} value={wf.workflowId}>
            {wf.workflowName}
          </option>
        ))}
      </select>
    ),
  }

  return buildRibbonTabs(ribbonHandlers, ribbonDynamicButtons, ribbonGroupContent)
}
