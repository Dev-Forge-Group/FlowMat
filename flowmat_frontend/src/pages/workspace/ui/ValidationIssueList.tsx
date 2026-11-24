import { Fragment } from 'react'
import type { WorkflowValidationIssue } from '../../../entities/workflow/api/useWorkflowValidationQuery'
import { evidenceRows, fixableCount, issueFixes, issueHides, sortedValidationIssues } from '../model/validationPanelModel'

/**
 * The workflow check's issues, errors first (archify's diagnostic shape). Each issue's button selects what it is about;
 * under it are the edits that clear it, the derived warnings it explains (not listed separately) and, folded, the evidence
 * the rule looked at, with process IDs as names. The button's name stays "severity: CODE — message". A required input
 * with nothing connected can be marked as taken from stock in one click, where the editor can change it.
 */
export function ValidationIssueList({ issues, nameOf, onSelect, onTakeFromStock }: {
  issues: WorkflowValidationIssue[]
  nameOf: (id: string) => string
  onSelect: (issue: WorkflowValidationIssue) => void
  /** Makes the input not required: its material comes from stock, not from a connection. */
  onTakeFromStock?: (ioId: string) => void
}) {
  const fixable = fixableCount(issues)
  return (
    <>
      {fixable > 0 && <p className="inspector-hint">{fixable} with a suggested fix</p>}
      <ul className="validation-issues">
        {sortedValidationIssues(issues).map((issue, index) => {
          const fixes = issueFixes(issue)
          const hides = issueHides(issue)
          const rows = evidenceRows(issue.evidence, nameOf)
          return (
            <li key={`${issue.code}-${issue.connectionId ?? issue.ioId ?? issue.processId ?? ''}-${index}`}>
              <button type="button" onClick={() => onSelect(issue)}>
                {issue.severity}: {issue.code} — {issue.message}
              </button>
              {onTakeFromStock && issue.code === 'REQUIRED_INPUT_UNCONNECTED' && issue.ioId && (
                <button type="button" onClick={() => onTakeFromStock(issue.ioId as string)}>
                  Take it from stock (not required)
                </button>
              )}
              {fixes.length > 0 && (
                <ul className="validation-issues__fixes" aria-label="Suggested fixes">
                  {fixes.map((fix) => <li key={fix}>Fix: {fix}</li>)}
                </ul>
              )}
              {hides.length > 0 && (
                <p className="validation-issues__hides">
                  Also explains {hides.length === 1 ? 'a warning' : `${hides.length} warnings`} not listed separately:{' '}
                  {hides.join(', ')}
                </p>
              )}
              {rows.length > 0 && (
                <details className="validation-issues__evidence">
                  <summary>Evidence</summary>
                  <dl>
                    {rows.map((row) => (
                      <Fragment key={row.key}>
                        <dt>{row.label}</dt>
                        <dd>{row.text}</dd>
                      </Fragment>
                    ))}
                  </dl>
                </details>
              )}
            </li>
          )
        })}
      </ul>
    </>
  )
}
