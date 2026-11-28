import { useCallback, useEffect, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import type { WorkflowCanvasViewModel } from '../../../entities/workflow/model/types'
import {
  useWorkflowValidationQuery,
  workflowValidationQueryKey,
  type WorkflowValidationIssue,
} from '../../../entities/workflow/api/useWorkflowValidationQuery'
import { useLiveValidation } from '../../../entities/workflow/api/useLiveValidation'
import { useUpdateProcessIoMutation } from '../../../entities/workflow/api/useUpdateProcessIoMutation'
import { issueMarks, NO_MARKS, useIssueMarksStore } from '../model/issueMarks'
import { layoutWarnings, type LayoutSnapshot, type LayoutWarning } from '../model/layoutChecks'
import { validationSelection } from '../model/validationPanelModel'
import { useWorkspaceStore } from '../model/workspaceStore'
import { EditorPublish } from './EditorPublish'
import { LayoutWarningList } from './LayoutWarningList'
import { LiveValidation, LiveValidationButton } from './LiveValidation'
import { PlanPanel } from './PlanPanel'
import { TryFlowPanel } from './TryFlowPanel'
import { ValidationIssueList } from './ValidationIssueList'
import { t } from '../../../shared/i18n/t'

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
  const [planOpen, setPlanOpen] = useState(false)
  // A required input with nothing connected, marked as taken from stock; the canvas change then re-runs both checks.
  const updatePort = useUpdateProcessIoMutation(workflowId)
  const takeFromStock = editable ? (ioId: string) => updatePort.mutate({ processIoId: ioId, requiredYn: 'N' }) : undefined

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
      <section aria-label={t('Workflow validation')} className="workspace-checks" style={{ margin: '12px 16px 0' }}>
        <button type="button" onClick={check}>{t('Check workflow')}</button>
        <EditorPublish workflowId={workflowId} projectId={canvas.workflow.projectId} editable={editable} onRefused={check} />
        <LiveValidationButton report={liveValidation} open={liveOpen} onToggle={() => setLiveOpen((open) => !open)} />
        <button type="button" aria-expanded={tryOpen} onClick={() => setTryOpen((open) => !open)}>{t('Try it')}</button>
        <button type="button" aria-expanded={planOpen} onClick={() => setPlanOpen((open) => !open)}>{t('Plan')}</button>
        {showValidation && (
          <div role="status" style={{ marginTop: 8 }}>
            {validationQuery.isPending && <p>{t('Checking workflow...')}</p>}
            {validationQuery.isError && <p role="alert">{validationQuery.error.message}</p>}
            {updatePort.isError && <p role="alert">{updatePort.error.message}</p>}
            {validationQuery.data && (
              <>
                <p>{t('{errors} errors, {warnings} warnings', { errors: validationQuery.data.errors, warnings: validationQuery.data.warnings })}</p>
                {validationQuery.data.issues.length === 0 && <p>{t('No issues found.')}</p>}
                <ValidationIssueList issues={validationQuery.data.issues} nameOf={nameOf} onSelect={selectIssue}
                  onTakeFromStock={takeFromStock} />
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

      {liveOpen && <LiveValidation report={liveValidation} nameOf={nameOf} onSelect={selectIssue} onTakeFromStock={takeFromStock} />}
      {tryOpen && <TryFlowPanel canvas={canvas} nameOf={nameOf} onSelectProcess={selectNode} />}
      {planOpen && <PlanPanel canvas={canvas} nameOf={nameOf} />}
    </>
  )
}
