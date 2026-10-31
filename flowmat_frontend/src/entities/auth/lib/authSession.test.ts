import { afterEach, describe, expect, it, vi } from 'vitest'

const HINT = 'flowmat_refresh_cookie'

function fakeStorage() {
  const values = new Map<string, string>()
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => void values.set(key, value),
    removeItem: (key: string) => void values.delete(key),
  }
}

/** A fresh copy of the module in a fake browser that remembers a cookie-backed session. */
async function load(respond: () => Promise<Response>) {
  vi.resetModules()
  const localStorage = fakeStorage()
  localStorage.setItem(HINT, '1')
  vi.stubGlobal('window', { localStorage, dispatchEvent: () => true, addEventListener() {}, removeEventListener() {} })
  vi.stubGlobal('document', { cookie: 'XSRF-TOKEN=csrf-1' })
  vi.stubGlobal('fetch', vi.fn(respond))
  const session = await import('./authSession')
  return { session, localStorage }
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('refreshAccessToken', () => {
  it.each([200, 401])('ignores a previous account refresh completion with status %i', async (status) => {
    let finish!: (response: Response) => void
    const { session } = await load(() => new Promise<Response>((done) => { finish = done }))
    session.tokenStorage.setAccessToken('fixture.owner.session', { cookieBacked: true })
    const pending = session.refreshAccessToken()
    await vi.waitFor(() => expect(typeof finish).toBe('function'))
    session.tokenStorage.clear()
    session.tokenStorage.setAccessToken('fixture.viewer.session', { cookieBacked: true })
    const viewer = session.tokenStorage.getAccess()
    finish(new Response(JSON.stringify({ success: true, data: { accessToken: 'fixture.owner.refreshed' } }), { status }))
    expect(await pending).toBe(false)
    expect(session.tokenStorage.getAccess() === viewer).toBe(true)
    expect(session.tokenStorage.hasCookieBackedSession()).toBe(true)
  })

  it('forgets the session when the server rejects the refresh cookie', async () => {
    const { session, localStorage } = await load(async () => new Response('{}', { status: 401 }))
    expect(await session.refreshAccessToken()).toBe(false)
    expect(localStorage.getItem(HINT)).toBeNull()
  })

  it.each([403, 429, 503])('keeps the session for a passing %i, so a later attempt can restore it', async (status) => {
    const { session, localStorage } = await load(async () => new Response('{}', { status }))
    expect(await session.refreshAccessToken()).toBe(false)
    expect(localStorage.getItem(HINT)).toBe('1')
  })

  it('keeps the session when the network fails', async () => {
    const { session, localStorage } = await load(async () => {
      throw new TypeError('Failed to fetch')
    })
    expect(await session.refreshAccessToken()).toBe(false)
    expect(localStorage.getItem(HINT)).toBe('1')
  })

  it('takes the new access token when the refresh goes through', async () => {
    const { session, localStorage } = await load(
      async () => new Response(JSON.stringify({ success: true, data: { accessToken: 'a.b.c' } }), { status: 200 }),
    )
    expect(await session.refreshAccessToken()).toBe(true)
    expect(session.tokenStorage.getAccess()).toBe('a.b.c')
    expect(localStorage.getItem(HINT)).toBe('1')
  })
})
