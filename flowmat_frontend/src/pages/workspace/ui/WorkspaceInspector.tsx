import { Suspense, lazy, type ComponentProps, type MutableRefObject } from 'react'
import type { WorkflowCanvasViewModel } from '../../../entities/workflow/model/types'
import { useWorkspaceStore } from '../model/workspaceStore'
import type { ConnectionInspector as ConnectionInspectorView } from './ConnectionInspector'
import { EditorElementInspector } from './EditorElementInspector'
import type { NodeInspector as NodeInspectorView } from './NodeInspector'
import { preloadConnectionInspector, preloadNodeInspector } from './workspacePreload'
import type { WorkspaceEditorCommandApi, WorkspaceEditorSelectionSnapshot } from './WorkspaceEditorLayer'

const ConnectionInspector = lazy(() =>
  preloadConnectionInspector().then((module) => ({ default: module.ConnectionInspector }))
)
const NodeInspector = lazy(() =>
  preloadNodeInspector().then((module) => ({ default: module.NodeInspector }))
)

type NodeProps = ComponentProps<typeof NodeInspectorView>
type ConnectionProps = ComponentProps<typeof ConnectionInspectorView>

interface Props {
  canvas: WorkflowCanvasViewModel
  editorSelection: WorkspaceEditorSelectionSnapshot
  editorCommandsRef: MutableRefObject<WorkspaceEditorCommandApi | null>
  onNodeSubmit: NodeProps['onNodeSubmit']
  onNodeDelete: NodeProps['onNodeDelete']
  onPortCreate: NodeProps['onPortCreate']
  onPortUpdate: NodeProps['onPortUpdate']
  onPortDelete: NodeProps['onPortDelete']
  onConnectionSubmit: ConnectionProps['onSubmit']
  onConnectionDelete: ConnectionProps['onDelete']
}

function InspectorFallback() {
  return (
    <div className="inspector-summary">
      <p>Loading inspector...</p>
    </div>
  )
}

/**
 * The right panel: the selected editor elements, process (with its ports) or connection, else what is on the canvas.
 * An editor element selection comes first, since selecting one clears the workflow selection.
 */
export function WorkspaceInspector({ canvas, editorSelection, editorCommandsRef, ...on }: Props) {
  const selectedProcessId = useWorkspaceStore((s) => s.selectedProcessId)
  const selectedConnectionId = useWorkspaceStore((s) => s.selectedConnectionId)
  const selectedPortId = useWorkspaceStore((s) => s.selectedPortId)
  const inspectorMode = useWorkspaceStore((s) => s.inspectorMode)
  const inlineEditingEdgeId = useWorkspaceStore((s) => s.inlineEditingEdgeId)
  const stopInlineEditEdge = useWorkspaceStore((s) => s.stopInlineEditEdge)

  const mode = editorSelection.elements.length > 0 ? 'editor' : inspectorMode
  const node = selectedProcessId ? canvas.nodeMap[selectedProcessId] ?? null : null
  const edge = selectedConnectionId ? canvas.edges.find((candidate) => candidate.id === selectedConnectionId) ?? null : null
  const port = selectedPortId ? canvas.portMap[selectedPortId] ?? null : null
  const sourceType = edge ? canvas.nodeMap[edge.fromProcessId]?.nodeType : undefined

  if (mode === 'editor') return <EditorElementInspector selection={editorSelection} commandsRef={editorCommandsRef} />
  if (mode === 'node') {
    return (
      <Suspense fallback={<InspectorFallback />}>
        <NodeInspector
          node={node}
          selectedPort={port}
          rules={[]}
          onNodeSubmit={on.onNodeSubmit}
          onNodeDelete={on.onNodeDelete}
          onPortCreate={on.onPortCreate}
          onPortUpdate={on.onPortUpdate}
          onPortDelete={on.onPortDelete}
          onOpenRuleBuilder={() => {}}
        />
      </Suspense>
    )
  }
  if (mode === 'connection') {
    return (
      <Suspense fallback={<InspectorFallback />}>
        <ConnectionInspector
          edge={edge}
          rules={[]}
          onSubmit={async (input) => { stopInlineEditEdge(); await on.onConnectionSubmit(input) }}
          focusLabel={inlineEditingEdgeId === edge?.id}
          sourceIsExclusive={sourceType === 'exclusive'}
          sourceIsSplit={sourceType === 'split'}
          sourceSchema={edge?.fromIoId ? canvas.portMap[edge.fromIoId]?.schemaJson ?? null : null}
          onDelete={on.onConnectionDelete}
          onOpenRuleBuilder={() => {}}
        />
      </Suspense>
    )
  }
  if (mode === 'multi') {
    return (
      <div className="inspector-summary">
        <p>Multiple nodes selected</p>
        <p className="inspector-hint">
          Shift+click or drag to multi-select. Bulk delete is not fully supported in the inspector yet.
        </p>
      </div>
    )
  }
  return (
    <div className="inspector-summary">
      <p>
        {canvas.nodes.length} nodes | {canvas.edges.length} connections
      </p>
      <p className="inspector-hint">Click a node or connection to inspect it.</p>
    </div>
  )
}
