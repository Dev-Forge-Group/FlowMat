import type { ApiEnvelope } from '../../../shared/types/api'

const COOKIE_SESSION_KEY = 'flowmat_refresh_cookie'
const AUTH_CHANGE_EVENT = 'flowmat-auth-changed'
let accessToken: string | null = null
let sessionVersion = 0

function isBrowser() {
  return typeof window !== 'undefined' && typeof window.localStorage !== 'undefined'
}

function notifyAuthChanged() {
  if (!isBrowser()) return
  window.dispatchEvent(new Event(AUTH_CHANGE_EVENT))
}

export function subscribeAuthChange(listener: () => void) {
  if (!isBrowser()) return () => {}

  window.addEventListener(AUTH_CHANGE_EVENT, listener)
  return () => window.removeEventListener(AUTH_CHANGE_EVENT, listener)
}

/** Decode the `sub` claim from a JWT without verifying the signature. */
export function parseJwtUserId(token: string): string | null {
  try {
    const b64 = token.split('.')[1]
    if (!b64) return null
    const payload = JSON.parse(atob(b64.replace(/-/g, '+').replace(/_/g, '/'))) as Record<string, unknown>
    return typeof payload.sub === 'string' ? payload.sub : null
  } catch {
    return null
  }
}

export const tokenStorage = {
  getAccess: () => accessToken,
  getSessionVersion: () => sessionVersion,
  getRefresh: () => null,
  hasCookieBackedSession: () => isBrowser() && window.localStorage.getItem(COOKIE_SESSION_KEY) === '1',
  hasRefreshSessionHint: () => {
    if (!isBrowser()) return false
    return window.localStorage.getItem(COOKIE_SESSION_KEY) === '1'
  },
  set: (access: string, _refresh?: string | null, options?: { cookieBacked?: boolean }) => {
    if (!isBrowser()) return
    const currentUser = accessToken ? parseJwtUserId(accessToken) : null
    const sameSession = accessToken === access || (currentUser !== null && currentUser === parseJwtUserId(access))
    if (!sameSession) sessionVersion += 1
    accessToken = access
    if (options?.cookieBacked === true) {
      window.localStorage.setItem(COOKIE_SESSION_KEY, '1')
    } else if (options?.cookieBacked === false) {
      window.localStorage.removeItem(COOKIE_SESSION_KEY)
    }
    notifyAuthChanged()
  },
  setAccessToken: (access: string, options?: { cookieBacked?: boolean }) => {
    tokenStorage.set(access, null, options)
  },
  clear: () => {
    if (!isBrowser()) return
    if (accessToken !== null || tokenStorage.hasRefreshSessionHint()) sessionVersion += 1
    accessToken = null
    window.localStorage.removeItem(COOKIE_SESSION_KEY)
    notifyAuthChanged()
  },
}

let refreshPromise: { version: number; promise: Promise<boolean> } | null = null

async function performRefresh(version: number): Promise<boolean> {
  if (version !== sessionVersion) return false
  const cookieBacked = tokenStorage.hasCookieBackedSession()
  if (!cookieBacked) {
    tokenStorage.clear()
    return false
  }

  try {
    const csrfToken = await getCsrfToken()
    if (version !== sessionVersion) return false

    const res = await fetch('/api/auth/refresh', {
      method: 'POST',
      credentials: 'same-origin',
      headers: {
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': csrfToken,
      },
    })
    if (version !== sessionVersion) return false
    if (!res.ok) {
      // Only a 401 says the refresh cookie is gone or was rejected. A rate limit, a server error or a CSRF hiccup says
      // nothing about the session, so keep the hint and let the next attempt restore it instead of forcing a new login.
      if (res.status === 401) tokenStorage.clear()
      return false
    }

    const json = (await res.json()) as ApiEnvelope<{ accessToken: string; refreshToken?: string | null }>
    if (version !== sessionVersion) return false
    if (!json.success || !json.data?.accessToken) {
      tokenStorage.clear()
      return false
    }

    tokenStorage.setAccessToken(json.data.accessToken, { cookieBacked: true })
    return true
  } catch {
    // The network or the CSRF bootstrap failed: the session itself may still be valid.
    return false
  }

}

async function getCsrfToken(): Promise<string> {
  if (!isBrowser()) return ''
  const existing = document.cookie
    .split('; ')
    .find((cookie) => cookie.startsWith('XSRF-TOKEN='))
    ?.slice('XSRF-TOKEN='.length)
  if (existing) return decodeURIComponent(existing)

  const res = await fetch('/api/auth/csrf', { credentials: 'same-origin' })
  if (!res.ok) throw new Error('Unable to initialize CSRF protection.')
  const json = (await res.json()) as ApiEnvelope<string>
  if (!json.success || !json.data) throw new Error('Unable to initialize CSRF protection.')
  return json.data
}

export async function refreshAccessToken(): Promise<boolean> {
  const version = sessionVersion
  if (refreshPromise?.version === version) {
    return refreshPromise.promise
  }

  const refresh = withRefreshCookieLock(() => performRefresh(version))
  const promise = refresh.finally(() => {
    if (refreshPromise?.promise === promise) refreshPromise = null
  })
  refreshPromise = { version, promise }

  return promise
}

/** Serialize operations that consume or revoke the shared refresh cookie across tabs. */
let refreshCookieQueue: Promise<void> = Promise.resolve()

export function withRefreshCookieLock<T>(operation: () => Promise<T>): Promise<T> {
  if (isBrowser() && navigator.locks) {
    return navigator.locks.request('flowmat-refresh', { mode: 'exclusive' }, operation)
  }
  // Keep login, logout and refresh ordered within this tab when Web Locks is unavailable.
  const pending = refreshCookieQueue.then(operation)
  refreshCookieQueue = pending.then(() => undefined, () => undefined)
  return pending
}

export { getCsrfToken }
