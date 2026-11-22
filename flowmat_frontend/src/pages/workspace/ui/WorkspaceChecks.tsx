import { useCallback, useEffect, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import type { WorkflowCanvasViewModel } from '../../../entities/workflow/model/types'
import {
  useWorkflowValidationQuery,
  workflowValidationQueryKey,
  type WorkflowValidationIssue,
} from '../../../entities/workflow/api/useWorkflowValidationQuery'
import { useLiveValidation } from '../../../entities/workflow/api/useLiveValidation'
import { issueMarks, NO_MARKS, useIssueMarksStore } from '../model/issueMarks'
import { layoutWarnings, type LayoutSnapshot, type LayoutWarning } from '../model/layoutChecks'
import { validationSelection } from '../model/validationPanelModel'
import { useWorkspaceStore } from '../model/workspaceStore'
import { EditorPublish } from './EditorPublish'
import { LayoutWarningList } from './LayoutWarningList'
import { LiveValidation, LiveValidationButton } from './LiveValidation'
import { TryFlowPanel } from './TryFlowPanel'
import { ValidationIssueList } from './ValidationIssueList'

interface Props {
  canvas: WorkflowCanvasViewModel
  editable: boolean
  /** The canvas as drawn, when it can be measured; layout warnings come from it and are never part of publishing. */
  measureLayout(): LayoutSnapshot | null
  nameOf(processId: string): string
  reveal(processIds: string[]): void
}

/**
 * The row of checks above the canvas: the server's check (with the layout as drawn), publishing, the kernel's check as
 * drawn (its counts also mark the canvas) and trying the flow. Each opens its list below the row.
 */
export function WorkspaceChecks({ canvas, editable, measureLayout, nameOf, reveal }: Props) {
  const workflowId = canvas.workflow.workflowId
  const selectNode = useWorkspaceStore((s) => s.selectNode)
  const selectEdge = useWorkspaceStore((s) => s.selectEdge)
  const [showValidation, setShowValidation] = useState(false)
  const validationQuery = useWorkflowValidationQuery(workflowId, showValidation)
  const [layoutFound, setLayoutFound] = useState<LayoutWarning[] | null>(null)
  const liveValidation = useLiveValidation(canvas)
  const [liveOpen, setLiveOpen] = useState(false)
  const [tryOpen, setTryOpen] = useState(false)

  // A change to the graph makes the server's last answer stale.
  const queryClient = useQueryClient()
  useEffect(() => {
    void queryClient.invalidateQueries({ queryKey: workflowValidationQueryKey(workflowId) })
  }, [canvas.nodes, canvas.edges, workflowId, queryClient])
  // The canvas marks each process and connection the check as drawn has something to say about.
  useEffect(() => {
    useIssueMarksStore.getState().setMarks(issueMarks(liveValidation))
  }, [liveValidation])
  useEffect(() => () => useIssueMarksStore.getState().setMarks(NO_MARKS), [])

  /** An issue's button selects what it is about, from the server's check or the check as drawn. */
  const selectIssue = useCallback((issue: WorkflowValidationIssue) => {
    const target = validationSelection(issue)
    if (target?.kind === 'connection') selectEdge(target.id)
    else if (target?.kind === 'process') {
      selectNode(target.id)
      if (target.ioId) useWorkspaceStore.getState().selectPort(target.ioId)
    }
  }, [selectEdge, selectNode])

  const check = () => {
    const layout = measureLayout()
    setLayoutFound(layout ? layoutWarnings(layout) : null)
    if (showValidation) void validationQuery.refetch()
    else setShowValidation(true)
  }

  return (
    <>
      <section aria-label="Workflow validation" style={{ margin: '12px 16px 0' }}>
        <button type="button" onClick={check}>Check workflow</button>
        <EditorPublish workflowId={workflowId} projectId={canvas.workflow.projectId} editable={editable} onRefused={check} />
        <LiveValidationButton report={liveValidation} open={liveOpen} onToggle={() => setLiveOpen((open) => !open)} />
        <button type="button" aria-expanded={tryOpen} onClick={() => setTryOpen((open) => !open)}>Try it</button>
        {showValidation && (
          <div role="status" style={{ marginTop: 8 }}>
            {validationQuery.isPending && <p>Checking workflow...</p>}
            {validationQuery.isError && <p role="alert">{validationQuery.error.message}</p>}
            {validationQuery.data && (
              <>
                <p>{validationQuery.data.errors} errors, {validationQuery.data.warnings} warnings</p>
                {validationQuery.data.issues.length === 0 && <p>No issues found.</p>}
                <ValidationIssueList issues={validationQuery.data.issues} nameOf={nameOf} onSelect={selectIssue} />
              </>
            )}
            <LayoutWarningList warnings={layoutFound} nameOf={nameOf} onSelect={(warning) => {
              if (warning.code === 'LINE_OVER_PROCESS') {
                reveal([warning.from, warning.to, warning.processId])
                selectEdge(warning.connectionId)
              } else {
                reveal([...new Set(warning.ends.flat())])
                selectEdge(warning.connectionIds[0])
              }
            }} />
          </div>
        )}
      </section>

      {liveOpen && <LiveValidation report={liveValidation} nameOf={nameOf} onSelect={selectIssue} />}
      {tryOpen && <TryFlowPanel canvas={canvas} nameOf={nameOf} onSelectProcess={selectNode} />}
    </>
  )
}
