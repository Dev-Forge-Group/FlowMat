import { useEffect, useMemo, useRef } from 'react'
import { useProductionRunsQuery } from '../../../entities/production/api/useProductionRunsQuery'
import { useRunStepsQuery } from '../../../entities/production/api/useRunSteps'
import { runOverlay, runSummary } from './runOverlay'

/** How often a running run's steps are read again while it is shown. */
const REFRESH_MS = 15_000

/**
 * The workflow's production runs, and the one shown over the canvas (`runId`, from the address) with each process's mark
 * and a one-line summary. View only; a running run is read again every 15 seconds while it is shown. Nothing is asked of
 * the server until a run is shown or the list is wanted (`listing`), so opening the canvas costs no request.
 */
export function useRunOverlay(workflowId: string, runId: string | null, known: ReadonlySet<string>, listing: boolean) {
  const runs = useProductionRunsQuery(runId || listing ? workflowId : '')
  const run = runId ? runs.data?.find((candidate) => candidate.productionRunId === runId) ?? null : null
  const steps = useRunStepsQuery(run ? run.productionRunId : '')
  const refetch = useRef(steps.refetch)
  refetch.current = steps.refetch
  const running = run?.runStatus === 'running'

  useEffect(() => {
    if (!running) return
    const timer = window.setInterval(() => void refetch.current(), REFRESH_MS)
    return () => window.clearInterval(timer)
  }, [running, runId])

  const overlay = useMemo(() => (run && steps.data ? runOverlay(steps.data, known) : null), [run, steps.data, known])
  let summary: string | null = null
  if (run) summary = overlay ? runSummary(run.runNumber, run.runStatus, overlay) : `Run ${run.runNumber}: reading its steps…`
  else if (runId && runs.isSuccess) summary = 'The linked run is not one of this workflow\'s runs.'

  return { runs: runs.data ?? [], loading: !runs.data && runs.isFetching, run, marks: overlay?.marks ?? null, summary }
}

export type RunOverlayView = ReturnType<typeof useRunOverlay>
