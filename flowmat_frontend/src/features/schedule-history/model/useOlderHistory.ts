import { useState } from 'react'
import { fetchOlderHistory } from '../api/historyPage'

/** The lists answer their latest 100 entries; more may be older. */
const LATEST = 100

type Pages<T> = { head: string; older: T[]; cursor: string | null; done: boolean }

/**
 * Entries older than a history list's latest 100, a page at a time from its search API (docs/domain/worker-calendar.md WC67).
 * A new save changes the newest entry and starts over, since every page moves.
 */
export function useOlderHistory<T extends { historyId: string }>(searchPath: string, latest: T[]) {
  const head = `${searchPath} ${latest[0]?.historyId ?? ''}`
  const [stored, setStored] = useState<Pages<T>>({ head, older: [], cursor: null, done: false })
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const pages = stored.head === head ? stored : { head, older: [], cursor: null, done: false }
  const after = pages.older.length > 0 ? pages.cursor
    : latest.length >= LATEST ? latest[latest.length - 1].historyId : null
  const canLoad = !pages.done && after !== null

  async function load() {
    if (!after) return
    setPending(true)
    setError(null)
    try {
      const page = await fetchOlderHistory<T>(searchPath, after)
      const entries = Array.isArray(page?.entries) ? page.entries : []
      setStored({ head, older: [...pages.older, ...entries], cursor: page?.nextCursor ?? null, done: !page?.nextCursor })
    } catch (caught) {
      setError(caught)
    } finally {
      setPending(false)
    }
  }

  return { older: pages.older, canLoad, load, pending, error }
}
