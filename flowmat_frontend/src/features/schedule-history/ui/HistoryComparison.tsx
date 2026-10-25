import { useState } from 'react'
import { compareHistory, type HistoryEntry, type HistoryKind } from '../model/compareHistory'

function entryLabel(entry: HistoryEntry, index: number): string {
  const at = new Date(entry.savedAt)
  const when = Number.isNaN(at.getTime()) ? entry.savedAt : at.toLocaleString()
  return `${index + 1}. ${when} · ${entry.savedBy} · ${entry.action} · ${entry.effectiveFrom ?? 'from the start'}`
}

/** Uses the history already authorized and loaded by the containing panel. */
export function HistoryComparison({ entries, kind }: { entries: readonly HistoryEntry[]; kind: HistoryKind }) {
  const [open, setOpen] = useState(false)
  const [selection, setSelection] = useState<{ from?: string; to?: string }>({})
  const before = entries.find((entry) => entry.historyId === selection.from) ?? entries[1]
  const after = entries.find((entry) => entry.historyId === selection.to && entry.historyId !== before?.historyId)
    ?? entries.find((entry) => entry.historyId !== before?.historyId)
  if (!before || !after) return null
  const rows = compareHistory(before, after, kind)
  const changed = rows.filter((row) => row.changed).length
  return (
    <div style={{ minWidth: 0, maxWidth: '100%', marginTop: 8 }}>
      <button type="button" aria-expanded={open} onClick={() => setOpen((value) => !value)}>
        {open ? 'Hide comparison' : 'Compare changes'}
      </button>
      {open && (
        <fieldset aria-label="History comparison" style={{ minWidth: 0, margin: '8px 0', padding: 12, border: '1px solid var(--border)', borderRadius: 8 }}>
          <legend>History comparison</legend>
          <p className="inspector-hint">Choose two history entries to compare their saved settings.</p>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'end' }}>
            <label style={{ display: 'grid', gap: 4, flex: '1 1 180px', minWidth: 0 }}>
              Compare from
              <select aria-label="Compare from" value={before.historyId} onChange={(event) => setSelection({ from: event.target.value, to: after.historyId })}
                style={{ minWidth: 0, width: '100%' }}>
                {entries.map((entry, index) => <option key={entry.historyId} value={entry.historyId} disabled={entry.historyId === after.historyId}>
                  {entryLabel(entry, index)}
                </option>)}
              </select>
            </label>
            <label style={{ display: 'grid', gap: 4, flex: '1 1 180px', minWidth: 0 }}>
              Compare to
              <select aria-label="Compare to" value={after.historyId} onChange={(event) => setSelection({ from: before.historyId, to: event.target.value })}
                style={{ minWidth: 0, width: '100%' }}>
                {entries.map((entry, index) => <option key={entry.historyId} value={entry.historyId} disabled={entry.historyId === before.historyId}>
                  {entryLabel(entry, index)}
                </option>)}
              </select>
            </label>
            <button type="button" onClick={() => setSelection({ from: after.historyId, to: before.historyId })}>Swap entries</button>
          </div>
          <p role="status">{changed === 0 ? 'Saved settings are identical.' : `${changed} difference${changed === 1 ? '' : 's'}.`}</p>
          <div style={{ overflowX: 'auto', maxWidth: '100%' }}>
            <table aria-label="Saved settings comparison" style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
              <thead><tr><th scope="col">Setting</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Change</th></tr></thead>
              <tbody>
                {rows.map((row) => <tr key={row.field} style={{ background: row.changed ? 'var(--surface-hover)' : undefined }}>
                  <th scope="row" style={{ textAlign: 'left', padding: 6 }}>{row.field}</th>
                  <td style={{ padding: 6, overflowWrap: 'anywhere' }}>{row.before}</td>
                  <td style={{ padding: 6, overflowWrap: 'anywhere' }}>{row.after}</td>
                  <td style={{ padding: 6 }}>{row.changed ? 'Changed' : 'Unchanged'}</td>
                </tr>)}
              </tbody>
            </table>
          </div>
        </fieldset>
      )}
    </div>
  )
}
