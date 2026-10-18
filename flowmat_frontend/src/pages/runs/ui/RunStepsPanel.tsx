import { useRunStepActions, useRunStepsQuery } from '../../../entities/production/api/useRunSteps'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { stepDeadline, stepQuantityText, stepStartsFrom } from '../model/runStepsModel'

/**
 * The run's process steps (docs/architecture/adr/ADR-011-production-steps.md decision 9): one line for each, with Start or
 * Complete while the run is open, and when a running step is due or late (docs/domain/flow-run.md F12). A planned step that
 * waits for a connection's delay, or for the equipment's next working time (docs/architecture/adr/ADR-015-shift-aware-delay.md),
 * says from when. Nothing shows for a run without process steps.
 */
export function RunStepsPanel({ runId, open }: { runId: string; open: boolean }) {
  const stepsQuery = useRunStepsQuery(runId)
  const actions = useRunStepActions(runId)
  const steps = stepsQuery.data ?? []
  if (steps.length === 0) return null
  const failure = [actions.start, actions.complete].find((mutation) => mutation.isError)
  return (
    <section aria-label="Process steps" style={{ border: '1px solid var(--border)', borderRadius: 12, padding: 18 }}>
      <h3 style={{ marginTop: 0 }}>Process steps</h3>
      <ol style={{ margin: 0, paddingLeft: 20, display: 'grid', gap: 6, fontSize: 13 }}>
        {steps.map((step) => {
          const name = step.nodeName ?? step.nodeId
          const quantity = stepQuantityText(step)
          const deadline = stepDeadline(step)
          const startsFrom = stepStartsFrom(step)
          return (
            <li key={step.stepId}>
              <strong>{name}</strong> {step.status}
              {quantity && <span className="inspector-hint"> · {quantity}</span>}
              {startsFrom && <span className="inspector-hint">
                {' '}· from {new Date(startsFrom).toLocaleString(undefined, { dateStyle: 'short', timeStyle: 'short' })}</span>}
              {deadline && <span className="inspector-hint" style={deadline.late ? { color: '#b45309' } : undefined}>
                {' '}· {deadline.late ? 'late since' : 'due by'} {new Date(deadline.at).toLocaleTimeString()}</span>}
              {step.instructionRequired > 0 && <span className="inspector-hint">
                {' '}· {step.instructionDone} of {step.instructionRequired} instruction steps confirmed</span>}
              {open && !step.gateway && step.status === 'planned' && (
                <button type="button" style={{ marginLeft: 8 }} aria-label={`Start ${name}`} disabled={actions.start.isPending}
                  onClick={() => actions.start.mutate(step.stepId)}>Start</button>
              )}
              {open && step.status === 'running' && (
                <button type="button" style={{ marginLeft: 8 }} aria-label={`Complete ${name}`} disabled={actions.complete.isPending}
                  onClick={() => actions.complete.mutate(step.stepId)}>Complete</button>
              )}
            </li>
          )
        })}
      </ol>
      {failure && <p role="alert">{errorMessage(failure.error)}</p>}
      <p className="inspector-hint" style={{ marginBottom: 0 }}>Complete takes what was recorded for the process as its output.</p>
    </section>
  )
}
