import { useCallback, useEffect, useRef, type MutableRefObject } from 'react'
import type { WorkflowCanvasViewModel } from '../../../entities/workflow/model/types'
import { deleteConfirmation, deletePlan, type DeleteSelection } from './canvasDeletion'
import { useCanvasInteractionStore } from './canvasInteractionStore'
import type { useWorkflowCanvasActions } from './useWorkflowCanvasActions'
import { useWorkspaceStore } from './workspaceStore'

type Actions = Pick<ReturnType<typeof useWorkflowCanvasActions>,
  'deleteConnection' | 'deleteElements' | 'deleteNode' | 'duplicateNode' | 'updateNode'>

/** The canvas's delete commands, which it hands over once it has mounted. */
export interface CanvasDeleteApi {
  deleteSelection(): Promise<void>
  deleteNode(nodeId: string): Promise<void>
  deleteEdge(edgeId: string): Promise<void>
}

/**
 * What the canvas asks the workspace to do: delete (after confirming the connections that go with a process), and the
 * delete, duplicate, recolor and rename requests its nodes and edges leave in the stores.
 */
export function useCanvasRequests(canvasRef: MutableRefObject<WorkflowCanvasViewModel>, actions: Actions) {
  const { deleteConnection, deleteElements, deleteNode, duplicateNode, updateNode } = actions
  const deleteApiRef = useRef<CanvasDeleteApi>({
    deleteSelection: async () => {},
    deleteNode: async () => {},
    deleteEdge: async () => {},
  })

  const handleBeforeDelete = useCallback(async (selection: DeleteSelection) => {
    const question = deleteConfirmation(selection, canvasRef.current.edges)
    return question === null || window.confirm(question)
  }, [canvasRef])

  const handleDeleteElements = useCallback(async (selection: DeleteSelection) => {
    const plan = deletePlan(selection, canvasRef.current.edges)
    if (plan.kind === 'connection') await deleteConnection(plan.connectionId)
    else if (plan.kind === 'process') await deleteNode(plan.processId)
    else if (plan.kind === 'elements') await deleteElements(selection)
  }, [canvasRef, deleteConnection, deleteElements, deleteNode])

  const pendingDeleteNodeId = useCanvasInteractionStore((s) => s.pendingDeleteNodeId)
  const pendingDeleteEdgeId = useCanvasInteractionStore((s) => s.pendingDeleteEdgeId)
  const clearDeleteRequest = useCanvasInteractionStore((s) => s.clearDeleteRequest)
  const pendingDuplicateNodeId = useCanvasInteractionStore((s) => s.pendingDuplicateNodeId)
  const clearDuplicateRequest = useCanvasInteractionStore((s) => s.clearDuplicateRequest)
  const pendingColorChange = useCanvasInteractionStore((s) => s.pendingColorChange)
  const clearColorChange = useCanvasInteractionStore((s) => s.clearColorChange)
  const pendingRename = useWorkspaceStore((s) => s.pendingRename)
  const clearPendingRename = useWorkspaceStore((s) => s.clearPendingRename)

  useEffect(() => {
    if (!pendingDeleteNodeId) return
    clearDeleteRequest()
    void deleteApiRef.current.deleteNode(pendingDeleteNodeId)
  }, [pendingDeleteNodeId, clearDeleteRequest])

  useEffect(() => {
    if (!pendingDeleteEdgeId) return
    clearDeleteRequest()
    void deleteApiRef.current.deleteEdge(pendingDeleteEdgeId)
  }, [pendingDeleteEdgeId, clearDeleteRequest])

  useEffect(() => {
    if (!pendingDuplicateNodeId) return
    clearDuplicateRequest()
    void duplicateNode(pendingDuplicateNodeId)
  }, [pendingDuplicateNodeId, clearDuplicateRequest, duplicateNode])

  useEffect(() => {
    if (!pendingColorChange) return
    clearColorChange()
    void updateNode({ processId: pendingColorChange.nodeId, colorScheme: pendingColorChange.colorScheme })
  }, [pendingColorChange, clearColorChange, updateNode])

  useEffect(() => {
    if (!pendingRename) return
    clearPendingRename()
    void updateNode({ processId: pendingRename.nodeId, processName: pendingRename.name })
  }, [pendingRename, clearPendingRename, updateNode])

  return { deleteApiRef, handleBeforeDelete, handleDeleteElements }
}
