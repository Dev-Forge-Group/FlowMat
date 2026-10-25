import { errorMessage } from '../../../shared/lib/errorMessage'

/** "Show older" below a history list once it holds its latest 100 entries, with the error of the last try. */
export function OlderHistoryButton({ canLoad, pending, error, onLoad }: {
  canLoad: boolean; pending: boolean; error: unknown; onLoad: () => void
}) {
  return (
    <>
      {canLoad && <button type="button" disabled={pending} onClick={onLoad}>{pending ? 'Loading…' : 'Show older'}</button>}
      {error != null && <p role="alert">{errorMessage(error, 'Could not load the older entries.')}</p>}
    </>
  )
}
