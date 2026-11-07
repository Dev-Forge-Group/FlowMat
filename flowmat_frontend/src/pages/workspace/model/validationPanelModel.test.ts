import { describe, expect, it } from 'vitest'
import type { WorkflowValidationIssue } from '../../../entities/workflow/api/useWorkflowValidationQuery'
import {
  evidenceRows, fixableCount, issueFixes, issueHides, sortedValidationIssues, validationSelection,
} from './validationPanelModel'

const issue = (severity: 'error' | 'warning', code: string): WorkflowValidationIssue => ({
  severity, code, processId: null, ioId: null, connectionId: null, message: code,
})

describe('validation panel', () => {
  it('places errors before warnings without mutating the API result', () => {
    const original = [issue('warning', 'CYCLE'), issue('error', 'CONNECTION_ORPHAN')]
    expect(sortedValidationIssues(original).map((entry) => entry.code))
      .toEqual(['CONNECTION_ORPHAN', 'CYCLE'])
    expect(original[0].code).toBe('CYCLE')
  })

  it('resolves connection and port selection targets', () => {
    expect(validationSelection({ ...issue('error', 'X'), connectionId: 'connection-1' }))
      .toEqual({ kind: 'connection', id: 'connection-1' })
    expect(validationSelection({ ...issue('error', 'X'), processId: 'process-1', ioId: 'port-1' }))
      .toEqual({ kind: 'process', id: 'process-1', ioId: 'port-1' })
    expect(validationSelection(issue('warning', 'CYCLE'))).toBeNull()
  })

  it('reads fixes and hidden warnings, and an older server sends neither', () => {
    const structured = {
      ...issue('error', 'CONNECTION_ORPHAN'),
      supportedFixes: ['delete the connection from "Gate" to "Right"'],
      suppresses: ['PROCESS_ISOLATED', 'UNEXPECTED_ROOT'],
    }
    expect(issueFixes(structured)).toEqual(['delete the connection from "Gate" to "Right"'])
    expect(issueHides(structured)).toEqual(['PROCESS_ISOLATED', 'UNEXPECTED_ROOT'])
    expect(issueFixes(issue('warning', 'CYCLE'))).toEqual([])
    expect(issueHides(issue('warning', 'CYCLE'))).toEqual([])
    expect(fixableCount([structured, issue('warning', 'CYCLE'), { ...issue('warning', 'X'), supportedFixes: [] }])).toBe(1)
  })

  it('shows evidence as words, with process IDs as their names', () => {
    const names = new Map([['prc-1', 'Mix'], ['prc-2', 'Bake']])
    const nameOf = (id: string) => names.get(id) ?? id
    expect(evidenceRows({
      nodeType: 'process', incoming: 0, allowedRootKinds: ['input', 'storage'], fromProcessId: 'prc-1',
      processIds: ['prc-1', 'prc-2'], sourceHasSchema: false, missing: [], percentTotal: 120.5,
    }, nameOf)).toEqual([
      { key: 'nodeType', label: 'node type', text: 'process' },
      { key: 'incoming', label: 'incoming', text: '0' },
      { key: 'allowedRootKinds', label: 'allowed root kinds', text: 'input, storage' },
      { key: 'fromProcessId', label: 'from process', text: 'Mix' },
      { key: 'processIds', label: 'processes', text: 'Mix, Bake' },
      { key: 'sourceHasSchema', label: 'source has schema', text: 'no' },
      { key: 'missing', label: 'missing', text: '—' },
      { key: 'percentTotal', label: 'percent total', text: '120.5' },
    ])
    expect(evidenceRows(undefined, nameOf)).toEqual([])
  })
})
