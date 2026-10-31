import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { QueryClient } from '@tanstack/react-query'

const hooks = vi.hoisted(() => ({
  slots: [] as { deps?: unknown[]; value?: unknown; cleanup?: () => void }[],
  cursor: 0,
  effects: [] as (() => void)[],
}))

vi.mock('react', async (original) => ({
  ...await original<typeof import('react')>(),
  useSyncExternalStore: (_subscribe: unknown, snapshot: () => unknown) => snapshot(),
  useMemo: (create: () => unknown, deps: unknown[]) => {
    const index = hooks.cursor++
    const slot = hooks.slots[index]
    if (!slot || deps.some((value, i) => value !== slot.deps?.[i])) {
      hooks.slots[index] = { deps, value: create() }
    }
    return hooks.slots[index].value
  },
  useEffect: (effect: () => (() => void) | undefined, deps: unknown[]) => {
    const index = hooks.cursor++
    const slot = hooks.slots[index]
    if (!slot || deps.some((value, i) => value !== slot.deps?.[i])) {
      hooks.effects.push(() => {
        slot?.cleanup?.()
        hooks.slots[index] = { deps, cleanup: effect() }
      })
    }
  },
}))

function token(user: string, revision = 1) {
  return `fixture.${btoa(JSON.stringify({ sub: user, revision }))}.signature`
}

async function load() {
  const { tokenStorage } = await import('../../entities/auth/lib/authSession')
  const { QueryProvider } = await import('./QueryProvider')
  function render() {
    hooks.cursor = 0
    const element = QueryProvider({ children: null })
    hooks.effects.splice(0).forEach((effect) => effect())
    return { client: element.props.client as QueryClient, key: element.key }
  }
  return { tokenStorage, render }
}

beforeEach(() => {
  vi.resetModules()
  hooks.slots = []
  hooks.effects = []
  const values = new Map<string, string>()
  vi.stubGlobal('window', Object.assign(new EventTarget(), {
    localStorage: {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => void values.set(key, value),
      removeItem: (key: string) => void values.delete(key),
    },
  }))
})

afterEach(() => {
  hooks.slots.forEach((slot) => slot.cleanup?.())
  vi.unstubAllGlobals()
})

describe('query cache session isolation', () => {
  it('replaces fresh owner identity, permissions and projects on logout then viewer login', async () => {
    const { tokenStorage, render } = await load()
    tokenStorage.setAccessToken(token('owner'), { cookieBacked: true })
    const owner = render()
    owner.client.setQueryData(['current-user'], { userId: 'owner' })
    owner.client.setQueryData(['current-user-permissions'], { canManageUsers: true })
    owner.client.setQueryData(['projects'], [{ projectId: 'owner-private' }])
    tokenStorage.clear()
    tokenStorage.setAccessToken(token('viewer'), { cookieBacked: true })
    const viewer = render()
    expect(viewer.client.getQueryData(['current-user'])).toBeUndefined()
    expect(viewer.client.getQueryData(['current-user-permissions'])).toBeUndefined()
    expect(viewer.client.getQueryData(['projects'])).toBeUndefined()
    expect(viewer.key).not.toBe(owner.key)
    expect(owner.client.getQueryCache().getAll()).toHaveLength(0)
  })

  it('isolates a delayed owner query and mutation callback after viewer login', async () => {
    const { tokenStorage, render } = await load()
    tokenStorage.setAccessToken(token('owner'))
    const owner = render()
    let resolve!: (value: { userId: string }) => void
    const pending = owner.client.fetchQuery({
      queryKey: ['current-user'],
      queryFn: () => new Promise<{ userId: string }>((done) => { resolve = done }),
    }).catch(() => undefined)
    tokenStorage.clear()
    tokenStorage.setAccessToken(token('viewer'))
    const viewer = render()
    viewer.client.setQueryData(['current-user'], { userId: 'viewer' })
    resolve({ userId: 'owner' })
    await pending
    // Existing mutation onSuccess closures retain the client from their originating session.
    owner.client.setQueryData(['current-user-permissions'], { canManageUsers: true })
    expect(viewer.client.getQueryData(['current-user'])).toEqual({ userId: 'viewer' })
    expect(viewer.client.getQueryData(['current-user-permissions'])).toBeUndefined()
  })

  it('preserves the client and mounted subtree on a same-user token refresh', async () => {
    const { tokenStorage, render } = await load()
    tokenStorage.setAccessToken(token('owner'))
    const before = render()
    before.client.setQueryData(['projects'], [{ projectId: 'active-project' }])
    tokenStorage.setAccessToken(token('owner', 2))
    const after = render()
    expect(after.client === before.client).toBe(true)
    expect(after.key).toBe(before.key)
    expect(after.client.getQueryData(['projects'])).toEqual([{ projectId: 'active-project' }])
  })
})
