import { create } from 'zustand'
import type { KernelIssue, KernelValidation } from '../../../shared/lib/flowKernel'

/** What the check as drawn says about one process or connection: counts and the codes, errors first. */
export interface IssueMark {
  errors: number
  warnings: number
  codes: string[]
}

export interface IssueMarks {
  processes: Record<string, IssueMark>
  connections: Record<string, IssueMark>
}

export const NO_MARKS: IssueMarks = { processes: {}, connections: {} }

/**
 * Where each issue of the check as drawn shows on the canvas: on its connection, or on its process (a port's issue on
 * the port's process). A cycle, which names no single target, marks each process on it.
 */
export function issueMarks(report: KernelValidation | null): IssueMarks {
  if (!report) return NO_MARKS
  const marks: IssueMarks = { processes: {}, connections: {} }
  const mark = (table: Record<string, IssueMark>, id: string, issue: KernelIssue) => {
    const entry = (table[id] ??= { errors: 0, warnings: 0, codes: [] })
    if (issue.severity === 'error') entry.errors += 1
    else entry.warnings += 1
    if (!entry.codes.includes(issue.code)) entry.codes.push(issue.code)
  }
  // The kernel lists errors first, so each mark's codes come errors first too.
  for (const issue of report.issues) {
    if (issue.connectionId) mark(marks.connections, issue.connectionId, issue)
    else if (issue.processId) mark(marks.processes, issue.processId, issue)
    else if (issue.code === 'CYCLE' && Array.isArray(issue.evidence?.processIds)) {
      for (const id of issue.evidence.processIds as unknown[]) if (typeof id === 'string') mark(marks.processes, id, issue)
    }
  }
  return marks
}

/** How a mark reads to someone: "2 errors, 1 warning as drawn: CODE, CODE". */
export function issueMarkLabel(mark: IssueMark): string {
  const parts = [
    mark.errors > 0 ? `${mark.errors} ${mark.errors === 1 ? 'error' : 'errors'}` : null,
    mark.warnings > 0 ? `${mark.warnings} ${mark.warnings === 1 ? 'warning' : 'warnings'}` : null,
  ].filter(Boolean)
  return `${parts.join(', ')} as drawn: ${mark.codes.join(', ')}`
}

/** The marks the canvas shows, set by the workspace page from its check as drawn. */
export const useIssueMarksStore = create<{ marks: IssueMarks; setMarks(marks: IssueMarks): void }>((set) => ({
  marks: NO_MARKS,
  setMarks: (marks) => set({ marks }),
}))
