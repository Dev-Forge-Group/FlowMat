import type { WorkflowValidationIssue } from '../../../entities/workflow/api/useWorkflowValidationQuery'

export function sortedValidationIssues(issues: WorkflowValidationIssue[]): WorkflowValidationIssue[] {
  return [...issues].sort((left, right) =>
    Number(left.severity === 'warning') - Number(right.severity === 'warning'))
}

export function validationSelection(issue: WorkflowValidationIssue):
  | { kind: 'connection'; id: string }
  | { kind: 'process'; id: string; ioId: string | null }
  | null {
  if (issue.connectionId) return { kind: 'connection', id: issue.connectionId }
  if (issue.processId) return { kind: 'process', id: issue.processId, ioId: issue.ioId }
  return null
}

/** Edits that clear the issue by the check's own rules (archify's supportedFixes); none from an older server. */
export function issueFixes(issue: WorkflowValidationIssue): string[] {
  return issue.supportedFixes ?? []
}

/** Codes of the derived warnings the issue explains and the server therefore does not list. */
export function issueHides(issue: WorkflowValidationIssue): string[] {
  return issue.suppresses ?? []
}

export function fixableCount(issues: readonly WorkflowValidationIssue[]): number {
  return issues.filter((issue) => issueFixes(issue).length > 0).length
}

export interface EvidenceRow {
  key: string
  label: string
  text: string
}

/**
 * The evidence as label and text rows: camelCase keys as words, process IDs as their names (nameOf leaves anything else as
 * it is), lists joined, yes and no for flags.
 */
export function evidenceRows(evidence: Record<string, unknown> | undefined, nameOf: (id: string) => string): EvidenceRow[] {
  if (!evidence) return []
  return Object.entries(evidence).map(([key, value]) => ({ key, label: evidenceLabel(key), text: evidenceText(value, nameOf) }))
}

function evidenceLabel(key: string): string {
  const words = key.replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase()
  if (words === 'process ids') return 'processes'
  return words.replace(/ process id$/, ' process')
}

function evidenceText(value: unknown, nameOf: (id: string) => string): string {
  if (value === null || value === undefined) return '—'
  if (typeof value === 'string') return nameOf(value)
  if (typeof value === 'boolean') return value ? 'yes' : 'no'
  if (typeof value === 'number') return String(value)
  if (Array.isArray(value)) return value.length === 0 ? '—' : value.map((entry) => evidenceText(entry, nameOf)).join(', ')
  return JSON.stringify(value)
}
