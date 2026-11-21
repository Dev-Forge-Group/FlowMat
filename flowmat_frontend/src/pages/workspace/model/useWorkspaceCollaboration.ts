import { useCallback, useEffect, useRef, useState, type MutableRefObject } from 'react'
import type { QueryClient } from '@tanstack/react-query'
import type { CanvasAnnotationPoint, WorkflowCanvasViewModel } from '../../../entities/workflow/model/types'
import type { GraphChangeMessage, PresenceMessage } from '../../../entities/workflow/api/useWorkflowSync'
import { fetchWorkflowGraphChanges } from '../../../entities/workflow/api/workflowGraphChanges'
import { fetchWorkflowPresenceSnapshot } from '../../../entities/workflow/api/workflowPresenceSnapshot'
import { applyGraphChangesToCanvas } from '../../../entities/workflow/model/applyGraphChangesToCanvas'
import { editorDocumentQueryKey } from '../../../entities/editor-document/api/editorDocumentApi'
import { useWorkspaceStore } from './workspaceStore'

/**
 * Working on a workflow together: who else is here (cursors, the node each edits, freehand strokes being drawn), and the
 * graph changes others make, applied in order to the canvas cache, resynced on a gap or reconnect, and held back for
 * what this user is editing inline until they finish.
 */
export function useWorkspaceCollaboration({ canvas, queryClient, inlineEditingNodeId, selectedProcessId,
  editingWorkflowNameRef }: {
  canvas: WorkflowCanvasViewModel
  queryClient: QueryClient
  inlineEditingNodeId: string | null
  selectedProcessId: string | null
  editingWorkflowNameRef: MutableRefObject<boolean>
}) {
  // Presence: remote cursor + node-editing state
  const [remoteCursors, setRemoteCursors] = useState<
    Map<string, { x: number; y: number; type: string }>
  >(new Map())
  const [editingPresence, setEditingPresence] = useState<ReadonlyMap<string, string>>(new Map())
  const [remoteAnnotationPreviews, setRemoteAnnotationPreviews] = useState<
    ReadonlyMap<string, { points: CanvasAnnotationPoint[]; annotationType: string }>
  >(new Map())
  const [syncUserId, setSyncUserId] = useState<string | null>(null)
  const sendPresenceRef = useRef<
    (msg: Omit<PresenceMessage, 'userId' | 'clientId' | 'workflowId' | 'timestamp'>) => void
  >(() => {})

  const handlePresence = useCallback((msg: PresenceMessage) => {
    if (!msg.userId) return
    const uid = msg.userId

    setRemoteCursors((prev) => {
      const next = new Map(prev)
      if (msg.type === 'LEAVE') {
        next.delete(uid)
      } else if (msg.type === 'CURSOR_MOVED' && msg.cursorX != null && msg.cursorY != null) {
        next.set(uid, { x: msg.cursorX, y: msg.cursorY, type: msg.type })
      } else if (msg.type === 'JOIN' || msg.type === 'NODE_EDITING') {
        next.set(uid, { x: 0, y: 0, type: msg.type })
      }
      return next
    })

    if (msg.type === 'ANNOTATION_DRAWING' || msg.type === 'LEAVE') {
      setRemoteAnnotationPreviews((prev) => {
        const next = new Map(prev)
        if (msg.type === 'LEAVE' || !msg.annotation?.inProgress) {
          next.delete(uid)
          return next
        }
        next.set(uid, {
          annotationType: msg.annotation.annotationType,
          points: msg.annotation.points.map(([x, y]) => ({ x, y })),
        })
        return next
      })
    }

    if (msg.type === 'NODE_EDITING' || msg.type === 'LEAVE') {
      setEditingPresence((prev) => {
        const next = new Map(prev)
        for (const [nodeId, editUid] of next) {
          if (editUid === uid) next.delete(nodeId)
        }
        if (msg.type === 'NODE_EDITING' && msg.editingProcessId) {
          next.set(msg.editingProcessId, uid)
        }
        return next
      })
    }
  }, [])

  const graphSeqRef = useRef(canvas.graphSeq)
  const deferredGraphResyncFromRef = useRef<number | null>(null)

  useEffect(() => {
    graphSeqRef.current = canvas.graphSeq
    deferredGraphResyncFromRef.current = null
  }, [canvas.workflow.workflowId, canvas.graphSeq])

  const invalidateEditorDocument = useCallback(() => {
    void queryClient.invalidateQueries({
      queryKey: editorDocumentQueryKey(canvas.workflow.workflowId),
    })
  }, [canvas.workflow.workflowId, queryClient])

  const applyGraphChanges = useCallback((changes: GraphChangeMessage[]) => {
    if (changes.length === 0) return

    const maxSeq = changes.reduce((max, change) => Math.max(max, change.seq), graphSeqRef.current)
    let deferredFromSeq: number | null = null

    queryClient.setQueryData<WorkflowCanvasViewModel>(
      ['workflow-canvas', canvas.workflow.workflowId],
      (current) => {
        if (!current) return current

        const applicableChanges = changes.filter((change) => {
          const state = useWorkspaceStore.getState()
          const shouldSkip =
            (change.changeType === 'NODE_UPDATED' && change.entityId === state.inlineEditingNodeId) ||
            (change.changeType === 'CONNECTION_UPDATED' && change.entityId === state.inlineEditingEdgeId) ||
            (change.changeType === 'WORKFLOW_UPDATED' && editingWorkflowNameRef.current)
          if (!shouldSkip) return true
          const candidate = Math.max(0, change.seq - 1)
          deferredFromSeq = deferredFromSeq == null ? candidate : Math.min(deferredFromSeq, candidate)
          return false
        })

        if (applicableChanges.length === 0) {
          return { ...current, graphSeq: maxSeq }
        }

        return applyGraphChangesToCanvas(current, applicableChanges)
      }
    )

    graphSeqRef.current = maxSeq
    if (deferredFromSeq != null) {
      deferredGraphResyncFromRef.current =
        deferredGraphResyncFromRef.current == null
          ? deferredFromSeq
          : Math.min(deferredGraphResyncFromRef.current, deferredFromSeq)
    }
    invalidateEditorDocument()
  }, [editingWorkflowNameRef, invalidateEditorDocument, queryClient, canvas.workflow.workflowId])

  const resyncGraphChanges = useCallback(async (sinceSeq: number) => {
    try {
      const data = await fetchWorkflowGraphChanges(canvas.workflow.workflowId, sinceSeq)
      if (data.resetRequired) {
        await queryClient.invalidateQueries({
          queryKey: ['workflow-canvas', canvas.workflow.workflowId],
        })
        invalidateEditorDocument()
        return
      }
      if (data.changes.length > 0) {
        applyGraphChanges(data.changes)
        return
      }
      if (data.currentSeq <= graphSeqRef.current) return
      graphSeqRef.current = data.currentSeq
      queryClient.setQueryData<WorkflowCanvasViewModel>(
        ['workflow-canvas', canvas.workflow.workflowId],
        (current) => (current ? { ...current, graphSeq: data.currentSeq } : current)
      )
    } catch {
      await queryClient.invalidateQueries({
        queryKey: ['workflow-canvas', canvas.workflow.workflowId],
      })
      invalidateEditorDocument()
    }
  }, [applyGraphChanges, invalidateEditorDocument, queryClient, canvas.workflow.workflowId])

  const loadPresenceSnapshot = useCallback(async (ownUserId: string | null) => {
    try {
      const snapshot = await fetchWorkflowPresenceSnapshot(canvas.workflow.workflowId)
      const cursorMap = new Map<string, { x: number; y: number; type: string }>()
      const editingMap = new Map<string, string>()
      for (const entry of snapshot) {
        if (!entry.userId || entry.userId === ownUserId) continue
        if (entry.cursorX != null && entry.cursorY != null) {
          cursorMap.set(entry.userId, { x: entry.cursorX, y: entry.cursorY, type: entry.type })
        } else {
          cursorMap.set(entry.userId, { x: 0, y: 0, type: entry.type })
        }
        if (entry.editingProcessId) {
          editingMap.set(entry.editingProcessId, entry.userId)
        }
      }
      setRemoteCursors(cursorMap)
      setEditingPresence(editingMap)
    } catch {
      // Ignore snapshot failures; live presence stream will continue to work.
    }
  }, [canvas.workflow.workflowId])

  const handleGraphChange = useCallback((msg: GraphChangeMessage) => {
    if (msg.changeType === 'RESET_REQUIRED') {
      void queryClient.invalidateQueries({ queryKey: ['workflow-canvas', canvas.workflow.workflowId] })
      invalidateEditorDocument()
      return
    }
    if (msg.seq <= graphSeqRef.current) return
    if (msg.seq === graphSeqRef.current + 1) {
      applyGraphChanges([msg])
      return
    }
    void resyncGraphChanges(graphSeqRef.current)
  }, [applyGraphChanges, canvas.workflow.workflowId, invalidateEditorDocument, queryClient, resyncGraphChanges])

  const handleReconnect = useCallback((forceSnapshot: boolean) => {
    if (forceSnapshot) {
      void queryClient.invalidateQueries({ queryKey: ['workflow-canvas', canvas.workflow.workflowId] })
    } else {
      void resyncGraphChanges(graphSeqRef.current)
    }
    invalidateEditorDocument()
    void loadPresenceSnapshot(syncUserId)
  }, [canvas.workflow.workflowId, invalidateEditorDocument, loadPresenceSnapshot, queryClient, resyncGraphChanges, syncUserId])

  useEffect(() => {
    if (inlineEditingNodeId || deferredGraphResyncFromRef.current == null) return
    const sinceSeq = deferredGraphResyncFromRef.current
    deferredGraphResyncFromRef.current = null
    void resyncGraphChanges(sinceSeq)
  }, [inlineEditingNodeId, resyncGraphChanges])

  const handleSyncReady = useCallback((api: {
    sendPresence: (msg: Omit<PresenceMessage, 'userId' | 'clientId' | 'workflowId' | 'timestamp'>) => void
    clientId: string
    ownUserId: string | null
  }) => {
    sendPresenceRef.current = api.sendPresence
    setSyncUserId(api.ownUserId)
    void loadPresenceSnapshot(api.ownUserId)
  }, [loadPresenceSnapshot])

  useEffect(() => {
    sendPresenceRef.current({ type: 'NODE_EDITING', editingProcessId: selectedProcessId ?? undefined })
  }, [selectedProcessId])

  // Item I: broadcast LEAVE when the tab closes or navigates away
  useEffect(() => {
    const onUnload = () => sendPresenceRef.current({ type: 'LEAVE' })
    window.addEventListener('beforeunload', onUnload)
    return () => {
      window.removeEventListener('beforeunload', onUnload)
      sendPresenceRef.current({ type: 'LEAVE' })
    }
  }, [])

  return {
    remoteCursors,
    editingPresence,
    remoteAnnotationPreviews,
    syncUserId,
    handlePresence,
    handleGraphChange,
    handleReconnect,
    handleSyncReady,
  }
}
