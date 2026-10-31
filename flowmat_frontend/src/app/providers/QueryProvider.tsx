import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useEffect, useMemo, useSyncExternalStore, type ReactNode } from 'react'
import { subscribeAuthChange, tokenStorage } from '../../entities/auth/lib/authSession'

function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        retry: 1,
      },
    },
  })
}

export function QueryProvider({ children }: { children: ReactNode }) {
  const sessionVersion = useSyncExternalStore(subscribeAuthChange, tokenStorage.getSessionVersion, tokenStorage.getSessionVersion)
  const queryClient = useMemo(createQueryClient, [sessionVersion])

  // Retire pending queries and their callbacks together with the previous session's subtree.
  useEffect(() => () => queryClient.clear(), [queryClient])

  return <QueryClientProvider key={sessionVersion} client={queryClient}>{children}</QueryClientProvider>
}
