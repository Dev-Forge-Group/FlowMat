import { QueryClient } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { httpExecutionsKey, httpProfilesKey, useHttpExecutions, useHttpExecutionMutations, useHttpProfileMutations } from './useHttpConnectors'
import { httpAttentionPageKey } from './useHttpExecutionAttention'

const mock = vi.hoisted(() => ({ client: undefined as QueryClient | undefined, putJson: vi.fn(), get: vi.fn(), post: vi.fn() }))
vi.mock('@tanstack/react-query', async (original) => ({
  ...await original<typeof import('@tanstack/react-query')>(), useQueryClient: () => mock.client,
  useQuery: (options: unknown) => options,
  useMutation: (options: { mutationFn: (input: unknown) => Promise<unknown>; onSuccess?: (value: unknown) => Promise<void>;
    onError?: () => Promise<void> }) => ({ mutateAsync: async (input: unknown) => {
    try { const value = await options.mutationFn(input); await options.onSuccess?.(value); return value }
    catch (error) { await options.onError?.(); throw error }
  } }),
}))
vi.mock('../../../shared/api/httpClient', () => ({ httpClient: { putJson: mock.putJson, get: mock.get, post: mock.post } }))

describe('HTTP profile conflict recovery', () => {
  beforeEach(() => { mock.client = new QueryClient({ defaultOptions: { queries: { staleTime: Infinity, retry: false } } }) })
  afterEach(() => { mock.client?.clear(); vi.clearAllMocks() })

  it('reloads a stale profile and its revision list after a refused edit, preserving other projects', async () => {
    const client = mock.client!
    client.setQueryData(httpProfilesKey('project'), [{ profileId: 'profile', version: 1 }])
    client.setQueryData([...httpProfilesKey('project'), 'profile', 'versions'], [{ revision: 1 }])
    client.setQueryData(httpProfilesKey('other'), [{ profileId: 'other', version: 2 }])
    mock.putJson.mockRejectedValue({ httpStatus: 409, message: 'expectedVersion changed' })
    const mutations = Reflect.apply(useHttpProfileMutations, undefined, ['project']) as ReturnType<typeof useHttpProfileMutations>
    const body = '{"name":"Edited","enabled":true,"baseOrigin":"https://api.example.com",'
      + '"credentialAlias":null,"expectedVersion":1,"operations":[]}'
    await expect(mutations.save.mutateAsync({ profileId: 'profile', body })).rejects.toMatchObject({ httpStatus: 409 })
    expect(mock.putJson).toHaveBeenCalledExactlyOnceWith('/projects/project/http-profiles/profile', body)
    expect(client.getQueryState(httpProfilesKey('project'))?.isInvalidated).toBe(true)
    expect(client.getQueryState([...httpProfilesKey('project'), 'profile', 'versions'])?.isInvalidated).toBe(true)
    expect(client.getQueryState(httpProfilesKey('other'))?.isInvalidated).toBe(false)
  })
})

describe('recovering durable HTTP results after a lost POST response', () => {
  beforeEach(() => { mock.client = new QueryClient({ defaultOptions: { queries: { staleTime: Infinity, retry: false } } }) })
  afterEach(() => { mock.client?.clear(); vi.clearAllMocks() })

  const pending = { executionId: 'execution', stepId: 'step', attemptId: 'attempt', requestId: 'request',
    transportStatus: 'PENDING', applyStatus: 'PENDING', httpStatus: null, errorCode: null, output: null,
    startedAt: '2026-10-24T00:00:00Z', completedAt: null }

  function readResults() {
    const query = Reflect.apply(useHttpExecutions, undefined, ['run', 'step']) as unknown as { queryFn: () => Promise<unknown> }
    return query.queryFn()
  }

  it('refreshes the run, steps, attempts, events and lineage after GET recovers an applied result without another POST', async () => {
    const client = mock.client!
    const affected = [['flow-runs', 'workflow'], ['flow-run-steps', 'run'], ['flow-run-attempts', 'run', 'step'],
      ['flow-run-events', 'run'], ['flow-run-lineage', 'run', 'step']]
    client.setQueryData(affected[0], [{ flowRunId: 'run', status: 'running' }])
    for (const key of affected.slice(1)) client.setQueryData(key, [{ status: 'running' }])
    client.setQueryData(['flow-runs', 'other-workflow'], [{ flowRunId: 'other-run', status: 'running' }])
    client.setQueryData(['flow-run-steps', 'other-run'], [{ status: 'running' }])
    client.setQueryData(httpExecutionsKey('run', 'step'), [pending])
    const reviewKey = httpAttentionPageKey('alice', 'project', 'ALL', null)
    client.setQueryData(reviewKey, { items: [], nextCursor: null })
    mock.get.mockResolvedValue({ success: true, data: [{ ...pending, transportStatus: 'SUCCEEDED', applyStatus: 'APPLIED',
      httpStatus: 202, output: { ok: true }, outputJson: '{"ok":true}', completedAt: '2026-10-24T00:00:01Z' }], message: null })
    await readResults()
    for (const key of affected) expect(client.getQueryState(key)?.isInvalidated, key.join('/')).toBe(true)
    expect(client.getQueryState(reviewKey)?.isInvalidated).toBe(true)
    expect(client.getQueryState(['flow-runs', 'other-workflow'])?.isInvalidated).toBe(false)
    expect(client.getQueryState(['flow-run-steps', 'other-run'])?.isInvalidated).toBe(false)
    expect(mock.post).not.toHaveBeenCalled()
  })

  it('does not repeatedly reload parent views when polling the same pending result', async () => {
    const client = mock.client!
    client.setQueryData(httpExecutionsKey('run', 'step'), [pending])
    client.setQueryData(['flow-run-steps', 'run'], [{ status: 'running' }])
    mock.get.mockResolvedValue({ success: true, data: [pending], message: null })
    await readResults()
    expect(client.getQueryState(['flow-run-steps', 'run'])?.isInvalidated).toBe(false)
    expect(mock.post).not.toHaveBeenCalled()
  })
})

describe('refreshing project HTTP review summaries after explicit actions', () => {
  beforeEach(() => { mock.client = new QueryClient({ defaultOptions: { queries: { staleTime: Infinity, retry: false } } }) })
  afterEach(() => { mock.client?.clear(); vi.clearAllMocks() })

  it.each(['execute', 'apply'] as const)('invalidates summary pages after %s without another HTTP action', async (action) => {
    const client = mock.client!
    const reviewKey = httpAttentionPageKey('alice', 'project', 'APPLY_FAILED', 'page-2')
    client.setQueryData(reviewKey, { items: [], nextCursor: null })
    const result = { executionId: 'execution', stepId: 'step', attemptId: 'attempt', requestId: 'request',
      transportStatus: 'SUCCEEDED', applyStatus: 'APPLIED', httpStatus: 202, errorCode: null,
      output: { ok: true }, outputJson: '{"ok":true}', startedAt: '2026-10-24T00:00:00Z', completedAt: '2026-10-24T00:00:01Z' }
    mock.post.mockResolvedValue({ success: true, data: result, message: null })
    const mutations = Reflect.apply(useHttpExecutionMutations, undefined, ['run', 'step']) as ReturnType<typeof useHttpExecutionMutations>
    await mutations[action].mutateAsync(action === 'execute' ? 'request' : 'execution')
    expect(client.getQueryState(reviewKey)?.isInvalidated).toBe(true)
    expect(client.getQueryData(httpExecutionsKey('run', 'step'))).toEqual([result])
    expect(mock.post).toHaveBeenCalledTimes(1)
    expect(mock.get).not.toHaveBeenCalled()
  })
})
