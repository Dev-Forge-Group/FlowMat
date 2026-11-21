import { describe, expect, it } from 'vitest'
import type { KernelIssue } from '../../../shared/lib/flowKernel'
import { issueMarkLabel, issueMarks, NO_MARKS } from './issueMarks'

const issue = (fields: Partial<KernelIssue>): KernelIssue => ({ severity: 'warning', code: 'X', processId: null, ioId: null,
  connectionId: null, message: '', evidence: {}, supportedFixes: [], suppresses: [], ...fields })

describe('issue marks on the canvas', () => {
  it('puts each issue on its connection or process, a port issue on its process and a cycle on every process in it', () => {
    const marks = issueMarks({ errors: 2, warnings: 3, issues: [
      issue({ severity: 'error', code: 'CONNECTION_ORPHAN', connectionId: 'c1' }),
      issue({ severity: 'error', code: 'REQUIRED_INPUT_UNCONNECTED', processId: 'p1', ioId: 'io1' }),
      issue({ code: 'UNIT_UNKNOWN', processId: 'p1', ioId: 'io2' }),
      issue({ code: 'UNIT_UNKNOWN', processId: 'p1', ioId: 'io3' }),
      issue({ code: 'CYCLE', evidence: { processIds: ['p1', 'p2'] } }),
    ] })
    expect(marks.connections).toEqual({ c1: { errors: 1, warnings: 0, codes: ['CONNECTION_ORPHAN'] } })
    expect(marks.processes).toEqual({
      p1: { errors: 1, warnings: 3, codes: ['REQUIRED_INPUT_UNCONNECTED', 'UNIT_UNKNOWN', 'CYCLE'] },
      p2: { errors: 0, warnings: 1, codes: ['CYCLE'] },
    })
  })

  it('has none without a check and reads as counts and codes', () => {
    expect(issueMarks(null)).toBe(NO_MARKS)
    expect(issueMarkLabel({ errors: 1, warnings: 2, codes: ['A', 'B'] })).toBe('1 error, 2 warnings as drawn: A, B')
    expect(issueMarkLabel({ errors: 0, warnings: 1, codes: ['B'] })).toBe('1 warning as drawn: B')
  })
})
