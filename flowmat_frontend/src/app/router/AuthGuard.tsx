import { useEffect, useState, useSyncExternalStore } from 'react'
import { Navigate, Outlet } from 'react-router-dom'
import { refreshAccessToken, subscribeAuthChange, tokenStorage } from '../../entities/auth/lib/authSession'

/**
 * Layout route that redirects unauthenticated users to the home (login) page.
 * Protected pages are rendered via <Outlet />.
 */
export function AuthGuard() {
  const accessToken = useSyncExternalStore(subscribeAuthChange, tokenStorage.getAccess, () => null)
  const [restorationFailed, setRestorationFailed] = useState(false)
  const needsRestore = !accessToken && tokenStorage.hasRefreshSessionHint() && !restorationFailed

  useEffect(() => {
    if (!needsRestore) return

    let cancelled = false

    void refreshAccessToken().then((ok) => {
      if (!cancelled) {
        setRestorationFailed(!ok)
      }
    })

    return () => {
      cancelled = true
    }
  }, [needsRestore])

  if (needsRestore) {
    return <div className="workspace-loading">Restoring session...</div>
  }

  if (!accessToken) {
    return <Navigate to="/" replace />
  }

  return <Outlet />
}
