import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchHttpAttentionPage, httpAttentionPageKey, useHttpExecutionAttention } from './useHttpExecutionAttention'

const mock = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('../../../shared/api/httpClient', () => ({ httpClient: { get: mock.get } }))
vi.mock('@tanstack/react-query', () => ({ useQuery: (options: unknown) => options }))

describe('project HTTP review summaries', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('keeps users, projects, filters and opaque pages in distinct cache entries', () => {
    const key = httpAttentionPageKey('alice', 'project', 'ALL', null)
    expect(httpAttentionPageKey('bob', 'project', 'ALL', null)).not.toEqual(key)
    expect(httpAttentionPageKey('alice', 'other', 'ALL', null)).not.toEqual(key)
    expect(httpAttentionPageKey('alice', 'project', 'PENDING', null)).not.toEqual(key)
    expect(httpAttentionPageKey('alice', 'project', 'ALL', 'cursor')).not.toEqual(key)
  })

  it.each([['', 'project', true], ['alice', '', true], ['alice', 'project', false]])(
    'does not fetch without a user, project and explicit open action (%s, %s, %s)', (userId, projectId, open) => {
      const options = Reflect.apply(useHttpExecutionAttention, undefined, [userId, projectId, 'ALL', null, open]) as unknown as { enabled: boolean }
      expect(options.enabled).toBe(false)
      expect(mock.get).not.toHaveBeenCalled()
    },
  )

  it('encodes the project and opaque cursor without changing their values', async () => {
    mock.get.mockResolvedValue({ success: true, data: { items: [], nextCursor: null }, message: null })
    await fetchHttpAttentionPage('project/one', 'OUTCOME_UNKNOWN', 'cursor/+?=& one')
    const url = new URL(mock.get.mock.calls[0][0], 'https://flowmat.invalid')
    expect(url.pathname).toBe('/projects/project%2Fone/http-executions/attention')
    expect(Object.fromEntries(url.searchParams)).toEqual({ limit: '25', state: 'OUTCOME_UNKNOWN', cursor: 'cursor/+?=& one' })
  })

  it('omits the cursor on the first page', async () => {
    mock.get.mockResolvedValue({ success: true, data: { items: [], nextCursor: 'next' }, message: null })
    await expect(fetchHttpAttentionPage('project', 'ALL', null)).resolves.toEqual({ items: [], nextCursor: 'next' })
    expect(mock.get).toHaveBeenCalledExactlyOnceWith('/projects/project/http-executions/attention?limit=25&state=ALL')
  })

  it('preserves the existing envelope rejection when the response data is null', async () => {
    mock.get.mockResolvedValue({ success: true, data: null, message: null })
    await expect(fetchHttpAttentionPage('project', 'ALL', null)).rejects.toMatchObject({ message: 'Unknown error' })
  })

  it.each([{ items: [], nextCursor: 7 }, { items: null, nextCursor: null }])('rejects an unusable page without displaying its contents', async (data) => {
    mock.get.mockResolvedValue({ success: true, data, message: null })
    await expect(fetchHttpAttentionPage('project', 'ALL', null)).rejects.toThrow('The HTTP review list is unavailable.')
  })
})
