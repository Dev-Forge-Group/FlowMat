import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

beforeEach(() => {
  vi.resetModules()
  const values = new Map<string, string>()
  vi.stubGlobal('window', Object.assign(new EventTarget(), { localStorage: {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => void values.set(key, value),
    removeItem: (key: string) => void values.delete(key),
  } }))
  vi.stubGlobal('document', { cookie: 'XSRF-TOKEN=fixture' })
})
afterEach(() => vi.unstubAllGlobals())

describe('HTTP requests from retired sessions', () => {
  it.each([200, 401])('rejects a delayed owner %i without replaying the operation as viewer', async (status) => {
    const { tokenStorage } = await import('../../entities/auth/lib/authSession')
    const { httpClient } = await import('./httpClient')
    tokenStorage.setAccessToken('fixture.owner.session', { cookieBacked: true })
    let finish!: (response: Response) => void
    const fetch = vi.fn().mockImplementationOnce(() => new Promise<Response>((done) => { finish = done }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ success: true, data: { accessToken: 'fixture.viewer.refreshed' } })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ success: true, data: { projectId: 'wrong-account-write' } })))
    vi.stubGlobal('fetch', fetch)
    const operation = httpClient.post('/projects', { projectName: 'Owner request' })
    tokenStorage.clear()
    tokenStorage.setAccessToken('fixture.viewer.session', { cookieBacked: true })
    finish(new Response(JSON.stringify({ success: status === 200, data: {} }), { status }))
    await expect(operation).rejects.toThrow(/session changed/i)
    expect(fetch.mock.calls.length).toBe(1)
  })
})
