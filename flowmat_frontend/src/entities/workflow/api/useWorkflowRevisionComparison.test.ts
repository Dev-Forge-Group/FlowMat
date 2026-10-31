import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchWorkflowRevisionComparison, useWorkflowRevisionComparison, workflowRevisionComparisonKey } from './useWorkflowRevisionComparison'

const mock = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('../../../shared/api/httpClient', () => ({ httpClient: { get: mock.get } }))
vi.mock('@tanstack/react-query', () => ({ useQuery: (options: unknown) => options }))

const revision = (revisionId: string) => ({ revisionId, revisionNo: 1, schemaVersion: 2, status: 'published' })
const page = () => ({ workflowId: 'workflow', fromRevision: revision('from'), toRevision: revision('to'), totalChanges: 1,
  changes: [{ section: 'nodeBindings', entityId: 'node', path: '/requestMapping/value', kind: 'modified',
    before: 9007199254740992, after: 9007199254740992, beforeJson: '9007199254740993.123456789', afterJson: '9007199254740993.123456790' }], nextOffset: null })
const envelope = (data: unknown) => ({ success: true, data, message: null })

describe('workflow revision comparison queries', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('isolates identities, direction and page without sharing cached comparisons', () => {
    const original = workflowRevisionComparisonKey('alice', 'project', 'workflow', 'from', 'to', 0)
    const variants: [string, string, string, string, string, number][] = [
      ['bob', 'project', 'workflow', 'from', 'to', 0], ['alice', 'other', 'workflow', 'from', 'to', 0],
      ['alice', 'project', 'other', 'from', 'to', 0], ['alice', 'project', 'workflow', 'to', 'from', 0],
      ['alice', 'project', 'workflow', 'from', 'to', 100],
    ]
    for (const args of variants) expect(workflowRevisionComparisonKey(...args)).not.toEqual(original)
  })

  it.each([
    ['', 'project', 'workflow', 'from', 'to', true], ['alice', '', 'workflow', 'from', 'to', true],
    ['alice', 'project', '', 'from', 'to', true], ['alice', 'project', 'workflow', '', 'to', true],
    ['alice', 'project', 'workflow', 'from', '', true], ['alice', 'project', 'workflow', 'from', 'to', false],
  ])('requires the complete read context and an explicit open action (%s, %s, %s, %s, %s, %s)',
    (userId, projectId, workflowId, from, to, open) => {
      const options = Reflect.apply(useWorkflowRevisionComparison, undefined, [userId, projectId, workflowId, from, to, 0, open]) as unknown as { enabled: boolean }
      expect(options.enabled).toBe(false)
      expect(mock.get).not.toHaveBeenCalled()
    })

  it('does not retry refused reads or reuse the preceding page as placeholder data', () => {
    const options = Reflect.apply(useWorkflowRevisionComparison, undefined, ['alice', 'project', 'workflow', 'from', 'to', 0, true]) as unknown as Record<string, unknown>
    expect(options.enabled).toBe(true)
    expect(options.retry).toBe(false)
    expect(options.staleTime).toBe(0)
    expect(options.refetchOnMount).toBe('always')
    expect(options).not.toHaveProperty('placeholderData')
  })

  it('encodes revision IDs and workflow segments without altering the request direction', async () => {
    const data = { ...page(), workflowId: 'workflow/one', fromRevision: revision('from /+?'), toRevision: revision('to &=') }
    mock.get.mockResolvedValue(envelope(data))
    await fetchWorkflowRevisionComparison('workflow/one', 'from /+?', 'to &=', 0)
    const url = new URL(mock.get.mock.calls[0][0], 'https://flowmat.invalid')
    expect(url.pathname).toBe('/workflows/workflow%2Fone/revisions/compare')
    expect(Object.fromEntries(url.searchParams)).toEqual({ fromRevisionId: 'from /+?', toRevisionId: 'to &=', offset: '0', limit: '100' })
  })

  it('keeps exact numeric JSON strings even when parsed JSON numbers have already lost precision', async () => {
    mock.get.mockResolvedValue(envelope(page()))
    const result = await fetchWorkflowRevisionComparison('workflow', 'from', 'to', 0)
    expect(result.changes[0].beforeJson).toBe('9007199254740993.123456789')
    expect(result.changes[0].afterJson).toBe('9007199254740993.123456790')
  })

  it('accepts absent exact JSON fields from an older server without manufacturing rounded replacements', async () => {
    const data = page()
    const { beforeJson: _before, afterJson: _after, ...change } = data.changes[0]
    mock.get.mockResolvedValue(envelope({ ...data, changes: [change] }))
    const result = await fetchWorkflowRevisionComparison('workflow', 'from', 'to', 0)
    expect(result.changes[0]).not.toHaveProperty('beforeJson')
    expect(result.changes[0]).not.toHaveProperty('afterJson')
  })

  it.each([
    { workflowId: 'other' }, { fromRevision: revision('other') }, { toRevision: revision('other') },
    { changes: null }, { changes: [null] }, { totalChanges: -1 }, { totalChanges: 0.5 },
    { nextOffset: 0 }, { nextOffset: 1.5 }, { nextOffset: 2 },
    { changes: [{ section: 'nodeBindings', entityId: null, path: '', kind: 'unknown' }] },
    { changes: [{ section: 'nodeBindings', entityId: null, path: '', kind: ['added'] }] },
    { changes: [{ section: 'nodeBindings', entityId: null, path: '', kind: 'modified', beforeJson: 7 }] },
  ])('rejects a mismatched or unusable page (%j)', async (override) => {
    mock.get.mockResolvedValue(envelope({ ...page(), ...override }))
    await expect(fetchWorkflowRevisionComparison('workflow', 'from', 'to', 0)).rejects.toThrow('The revision comparison is unavailable.')
  })

  it('accepts an exhausted page beyond the total and null entity IDs', async () => {
    mock.get.mockResolvedValue(envelope({ ...page(), changes: [], nextOffset: null }))
    await expect(fetchWorkflowRevisionComparison('workflow', 'from', 'to', 100)).resolves.toMatchObject({ changes: [], nextOffset: null })
    const data = page()
    mock.get.mockResolvedValue(envelope({ ...data, changes: [{ ...data.changes[0], entityId: null }] }))
    await expect(fetchWorkflowRevisionComparison('workflow', 'from', 'to', 0)).resolves.toMatchObject({ changes: [{ entityId: null }] })
  })

  it('propagates the original access error without replacing it with a shape error', async () => {
    const refusal = { status: 403, message: 'Forbidden' }
    mock.get.mockRejectedValue(refusal)
    await expect(fetchWorkflowRevisionComparison('workflow', 'from', 'to', 0)).rejects.toBe(refusal)
  })
})
