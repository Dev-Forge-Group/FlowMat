import type { KernelValidation } from '../../../shared/lib/flowKernel'
import type { WorkflowValidationIssue } from '../../../entities/workflow/api/useWorkflowValidationQuery'
import { ValidationIssueList } from './ValidationIssueList'

/**
 * The workflow check as drawn (ADR-040 decision 7): the same rules and messages as "Check workflow", worked out in the
 * browser as the canvas changes. The count sits in the check row and turns red with errors; it opens the issues.
 */
export function LiveValidationButton({ report, open, onToggle }: {
  report: KernelValidation | null
  open: boolean
  onToggle: () => void
}) {
  if (!report) return null
  return (
    <button type="button" aria-expanded={open} onClick={onToggle}
      className={report.errors > 0 ? 'live-check live-check--errors' : 'live-check'}>
      {`As drawn: ${report.errors} errors, ${report.warnings} warnings`}
    </button>
  )
}

/** The issues of the check as drawn, below the check row while open. */
export function LiveValidation({ report, nameOf, onSelect }: {
  report: KernelValidation | null
  nameOf: (id: string) => string
  onSelect: (issue: WorkflowValidationIssue) => void
}) {
  if (!report) return null
  return (
    <section aria-label="Check as drawn" style={{ margin: '8px 16px 0' }}>
      {report.issues.length === 0
        ? <p>Nothing to fix as drawn.</p>
        : <ValidationIssueList issues={report.issues} nameOf={nameOf} onSelect={onSelect} />}
    </section>
  )
}
