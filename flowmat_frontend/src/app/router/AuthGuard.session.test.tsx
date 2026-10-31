import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Navigate, Outlet } from 'react-router-dom'

const hooks = vi.hoisted(() => ({
  states: [] as unknown[], cursor: 0,
  effects: [] as (() => void)[], cleanups: [] as (() => void)[],
}))
vi.mock('react', async (original) => ({
  ...await original<typeof import('react')>(),
  useState: (initialize: unknown) => {
    const index = hooks.cursor++
    if (!(index in hooks.states)) hooks.states[index] = typeof initialize === 'function' ? initialize() : initialize
    return [hooks.states[index], (value: unknown) => { hooks.states[index] = value }]
  },
  useEffect: (effect: () => (() => void) | undefined) => { hooks.effects.push(() => {
    const cleanup = effect()
    if (cleanup) hooks.cleanups.push(cleanup)
  }) },
  useSyncExternalStore: (_subscribe: unknown, snapshot: () => unknown) => snapshot(),
}))

beforeEach(() => {
  vi.resetModules()
  hooks.states = []; hooks.effects = []; hooks.cleanups = []
  const values = new Map<string, string>()
  vi.stubGlobal('window', Object.assign(new EventTarget(), { localStorage: {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => void values.set(key, value),
    removeItem: (key: string) => void values.delete(key),
  } }))
  vi.stubGlobal('document', { cookie: 'XSRF-TOKEN=fixture' })
})
afterEach(() => { hooks.cleanups.forEach((cleanup) => cleanup()); vi.unstubAllGlobals() })

describe('AuthGuard session changes', () => {
  it('removes an already mounted protected outlet when refresh expires', async () => {
    const { tokenStorage, refreshAccessToken } = await import('../../entities/auth/lib/authSession')
    const { AuthGuard } = await import('./AuthGuard')
    tokenStorage.setAccessToken('fixture.owner.session', { cookieBacked: true })
    const render = () => {
      hooks.cursor = 0
      const view = AuthGuard()
      hooks.effects.splice(0).forEach((effect) => effect())
      return view
    }
    expect(render().type).toBe(Outlet)
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 401 })))
    expect(await refreshAccessToken()).toBe(false)
    expect(render().type).toBe(Navigate)
  })
})
