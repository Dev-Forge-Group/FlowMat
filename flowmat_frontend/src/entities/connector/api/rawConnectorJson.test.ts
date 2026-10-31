import { afterEach, describe, expect, it, vi } from 'vitest'
import { httpClient } from '../../../shared/api/httpClient'
import { refreshAccessToken } from '../../auth/lib/authSession'

vi.mock('../../auth/lib/authSession', () => ({ tokenStorage: { getAccess: () => 'access', getSessionVersion: () => 0 }, refreshAccessToken: vi.fn() }))

afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks() })

describe('raw connector JSON uses the existing authenticated client', () => {
  it('retains exact decimal text across authentication refresh', async () => {
    const text = '{"literal":9007199254740993.2300}'
    const fetchMock = vi.fn().mockResolvedValueOnce(new Response('{"success":false,"message":"expired"}', { status: 401 }))
      .mockResolvedValueOnce(new Response('{"success":true,"data":{},"message":null}', { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    vi.mocked(refreshAccessToken).mockResolvedValue(true)
    await httpClient.putJson('/processes/p/execution-binding', text)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    for (const [, options] of fetchMock.mock.calls) {
      expect(options.body).toBe(text)
      expect(options.credentials).toBe('same-origin')
      expect(options.headers.Authorization).toBe('Bearer access')
    }
  })

  it('rejects malformed JSON without making a network request', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    await expect(httpClient.postJson('/projects/p/json-mapping/preview', '{')).rejects.toThrow('valid JSON')
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
