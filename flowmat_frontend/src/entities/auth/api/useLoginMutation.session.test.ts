import { QueryClient, type MutationOptions } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const mock = vi.hoisted(() => ({ client: undefined as QueryClient | undefined }))
vi.mock('@tanstack/react-query', async (original) => ({
  ...await original<typeof import('@tanstack/react-query')>(),
  useMutation: (options: MutationOptions<unknown, Error, unknown>) => ({
    mutateAsync: (input: unknown) => mock.client!.getMutationCache().build(mock.client!, options).execute(input),
  }),
}))

beforeEach(() => {
  vi.resetModules()
  mock.client = new QueryClient()
  const values = new Map([['flowmat_refresh_cookie', '1']])
  vi.stubGlobal('window', Object.assign(new EventTarget(), { localStorage: {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => void values.set(key, value),
    removeItem: (key: string) => void values.delete(key),
  } }))
  vi.stubGlobal('document', { cookie: 'XSRF-TOKEN=fixture' })
})
afterEach(() => { mock.client?.clear(); vi.unstubAllGlobals() })

const token = (user: string) => `fixture.${btoa(JSON.stringify({ sub: user }))}.signature`

describe('login while a previous cookie session is restoring', () => {
  it.each([false, true])('finishes as the requested viewer with browser locks=%s', async (webLocks) => {
    let queue = Promise.resolve()
    vi.stubGlobal('navigator', webLocks ? { locks: {
      request: (_name: string, _options: unknown, operation: () => Promise<unknown>) => {
        const pending = queue.then(operation)
        queue = pending.then(() => undefined, () => undefined)
        return pending
      },
    } } : {})
    let finishRestore!: (response: Response) => void
    let finishLogin!: (response: Response) => void
    vi.stubGlobal('fetch', vi.fn((path: string) => new Promise<Response>((resolve) => {
      if (path === '/api/auth/refresh') finishRestore = resolve
      if (path === '/api/auth/login') finishLogin = resolve
    })))
    const { refreshAccessToken, tokenStorage, parseJwtUserId } = await import('../lib/authSession')
    const { useLoginMutation } = await import('./useLoginMutation')
    const restore = refreshAccessToken()
    const login = useLoginMutation().mutateAsync({ userIdOrEmail: 'viewer', password: 'fixture-password' })
      .then(() => true, () => false)
    await vi.waitFor(() => expect(typeof finishRestore).toBe('function'))
    finishRestore(new Response(JSON.stringify({ success: true, data: { accessToken: token('owner') } })))
    expect(await restore).toBe(true)
    await vi.waitFor(() => expect(typeof finishLogin).toBe('function'))
    finishLogin(new Response(JSON.stringify({ success: true, data: { accessToken: token('viewer') } })))
    expect(await login).toBe(true)
    expect(parseJwtUserId(tokenStorage.getAccess() ?? '')).toBe('viewer')
  })
})
