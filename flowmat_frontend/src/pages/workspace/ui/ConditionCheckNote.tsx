import type { ConditionReport } from '../../../shared/lib/conditionLanguage'

/**
 * What the server would say about a condition, said while it is typed (shared/lib/conditionLanguage.ts): the reason and
 * the position, in the server's words. Nothing while the condition is fine, blank, or the checker is not loaded.
 */
export function conditionProblem(report: ConditionReport | null): string | null {
  if (!report) return null
  if (!report.ok) return report.message
  const declared = report.declaredError
  return declared ? `Invalid condition at position ${declared.position}: ${declared.reason}` : null
}

export function ConditionCheckNote({ id, report }: { id: string; report: ConditionReport | null }) {
  const problem = conditionProblem(report)
  if (!problem) return null
  return <small id={id} role="alert" className="condition-check-note" style={{ color: '#dc2626' }}>{problem}</small>
}
