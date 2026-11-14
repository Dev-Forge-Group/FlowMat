import { useRef, useState } from 'react'
import { useImportItemsMutation } from '../../../entities/catalog/api/useImportItemsMutation'
import { errorMessage } from '../../../shared/lib/errorMessage'
import type { ItemDto, ItemImportResultDto, ItemImportRowDto, UnitDto } from '../../../shared/types/api'
import { itemsCsv, rowsFromCsv } from '../model/itemCsvModel'

const cell = { padding: '4px 6px' } as const

function download(text: string, name: string) {
  const url = URL.createObjectURL(new Blob([text], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = name
  link.click()
  URL.revokeObjectURL(url)
}

/**
 * Items to and from a spreadsheet (docs/domain/item-import.md). Export writes the columns the import reads. Import checks
 * the whole file first and shows what would change; saving happens only when no row is wrong. A line can be left out
 * (the wrong ones in one press), and the file is then checked again without it, so the rest can still be saved.
 */
export function ItemCsvPanel({ projectId, items, units }: { projectId: string; items: ItemDto[]; units: UnitDto[] }) {
  const importMutation = useImportItemsMutation(projectId)
  const [file, setFile] = useState<{ name: string; rows: ItemImportRowDto[]; ignored: string[] } | null>(null)
  const [check, setCheck] = useState<ItemImportResultDto | null>(null)
  const [done, setDone] = useState<ItemImportResultDto | null>(null)
  const [readError, setReadError] = useState<string | null>(null)
  const [checking, setChecking] = useState(false)
  const [saving, setSaving] = useState(false)
  /** Rows left out of the import, by their number from 1. */
  const [leftOut, setLeftOut] = useState<number[]>([])
  const selectionGeneration = useRef(0)
  const unitCodes = new Map(units.map((unit) => [unit.unitId, unit.unitCode]))

  async function choose(chosen: File | undefined) {
    if (saving) return
    const generation = ++selectionGeneration.current
    importMutation.reset()
    setCheck(null)
    setDone(null)
    setFile(null)
    setLeftOut([])
    setReadError(null)
    setChecking(Boolean(chosen))
    if (!chosen) return
    try {
      let text: string
      try {
        text = await chosen.text()
      } catch {
        if (generation === selectionGeneration.current) setReadError('The item file could not be read. Choose the file again.')
        return
      }
      if (generation !== selectionGeneration.current) return
      const parsed = rowsFromCsv(text)
      if (!parsed.ok) {
        setReadError(parsed.error)
        return
      }
      setFile({ name: chosen.name, rows: parsed.rows, ignored: parsed.ignored })
      try {
        const result = await importMutation.mutateAsync({ rows: parsed.rows, dryRun: true })
        if (generation === selectionGeneration.current) setCheck(result)
      } catch (error) {
        if (generation === selectionGeneration.current) setReadError(errorMessage(error, 'The file could not be checked.'))
      }
    } finally {
      if (generation === selectionGeneration.current) setChecking(false)
    }
  }

  /** Checks the same file again with these rows left out; the earlier check stays until the new one answers. */
  async function leaveOut(rows: number[]) {
    if (!file || checking || saving) return
    const generation = ++selectionGeneration.current
    const next = [...new Set(rows)].sort((a, b) => a - b)
    const previous = leftOut
    // The tick moves at once; what each line would do follows when the check answers.
    setLeftOut(next)
    setChecking(true)
    setReadError(null)
    try {
      const result = await importMutation.mutateAsync({ rows: file.rows, dryRun: true, skipRows: next })
      if (generation === selectionGeneration.current) setCheck(result)
    } catch (error) {
      if (generation === selectionGeneration.current) {
        // The shown check is still the one for the earlier choice.
        setLeftOut(previous)
        setReadError(errorMessage(error, 'The file could not be checked.'))
      }
    } finally {
      if (generation === selectionGeneration.current) setChecking(false)
    }
  }

  async function save() {
    if (!file || !check || check.errors > 0 || check.created + check.updated === 0 || checking || saving) return
    setSaving(true)
    setReadError(null)
    try {
      const result = await importMutation.mutateAsync({ rows: file.rows, dryRun: false, skipRows: leftOut })
      setDone(result)
      setCheck(result.applied ? null : result)
      if (result.applied) {
        setFile(null)
        setLeftOut([])
      }
    } catch (error) {
      setReadError(errorMessage(error, 'The item changes could not be confirmed.'))
    } finally {
      setSaving(false)
    }
  }

  const changes = check ? check.created + check.updated : 0
  const listed = check ? check.rows.filter((row) => row.action !== 'unchanged') : []
  const wrong = check ? check.rows.filter((row) => row.action === 'error').map((row) => row.row) : []
  // Leaving out every line is refused by the server, so the last line that is still in cannot be taken out.
  const lastIn = file ? file.rows.length - leftOut.length <= 1 : true

  return (
    <section aria-busy={checking || saving} aria-label="Items spreadsheet" style={{ display: 'grid', gap: 8, marginBottom: 12, fontSize: 13 }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
        <button
          type="button"
          disabled={items.length === 0}
          onClick={() => download(itemsCsv(items, (id) => unitCodes.get(id)), `items-${new Date().toISOString().slice(0, 10)}.csv`)}
        >
          Export CSV
        </button>
        <label style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
          <span>Import CSV</span>
          <input type="file" accept=".csv,text/csv" aria-label="Import items file" disabled={saving} onChange={(e) => {
            const chosen = e.target.files?.[0]
            void choose(chosen)
            e.target.value = ''
          }} />
        </label>
        <span className="inspector-hint">Matched by item code: new codes are added, existing ones updated; blank cells leave a value as it is.</span>
      </div>
      {readError && <p role="alert" style={{ color: '#dc2626', margin: 0 }}>{readError}</p>}
      {checking && <p role="status" style={{ margin: 0 }}>Reading and checking item file...</p>}
      {done?.applied && (
        <p role="status" style={{ color: '#047857', margin: 0 }}>
          Saved: {done.created} added, {done.updated} updated, {done.unchanged} unchanged
          {done.skipped > 0 ? `, ${done.skipped} left out` : ''}.
        </p>
      )}
      {file && check && (
        <div aria-label="Import check" style={{ border: '1px solid var(--border)', borderRadius: 8, padding: 10 }}>
          <p style={{ margin: '0 0 6px' }}>
            <strong>{file.name}</strong>: {check.created} to add, {check.updated} to update, {check.unchanged} unchanged
            {check.errors > 0 && <strong style={{ color: '#dc2626' }}>, {check.errors} with problems</strong>}
            {check.skipped > 0 && <span>, {check.skipped} left out</span>}
            {file.ignored.length > 0 && <span style={{ opacity: 0.7 }}> · ignored columns: {file.ignored.join(', ')}</span>}
          </p>
          {listed.length > 0 && (
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
              <tbody>
                {listed.map((row) => (
                  <tr key={row.row} style={{ borderBottom: '1px solid var(--border)', opacity: row.action === 'skipped' ? 0.6 : 1 }}>
                    <td style={{ ...cell, width: 1 }}>
                      <input
                        type="checkbox"
                        aria-label={`Import line ${row.row + 1}`}
                        title="Untick to leave this line out of the import"
                        checked={!leftOut.includes(row.row)}
                        disabled={checking || saving || (!leftOut.includes(row.row) && lastIn)}
                        onChange={(e) => void leaveOut(e.target.checked
                          ? leftOut.filter((one) => one !== row.row)
                          : [...leftOut, row.row])}
                      />
                    </td>
                    <td style={{ ...cell, opacity: 0.6, whiteSpace: 'nowrap' }}>line {row.row + 1}</td>
                    <td style={cell}>
                      <code>{row.itemCode ?? '-'}</code>
                    </td>
                    <td style={{ ...cell, color: row.action === 'error' ? '#dc2626' : undefined, fontWeight: row.action === 'error' ? 600 : 400 }}>
                      {row.action === 'create' ? 'add' : row.action === 'skipped' ? 'left out' : row.action}
                    </td>
                    <td style={cell}>{row.message ?? ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <div style={{ display: 'flex', gap: 8, marginTop: 8, alignItems: 'center' }}>
            <button type="button" disabled={check.errors > 0 || changes === 0 || checking || saving} onClick={() => void save()}>
              {changes === 0 ? 'Nothing to save' : `Save ${changes} change${changes === 1 ? '' : 's'}`}
            </button>

            {check.errors > 0 && (
              <>
                <button type="button" disabled={checking || saving || wrong.length >= (file.rows.length - leftOut.length)}
                  onClick={() => void leaveOut([...leftOut, ...wrong])}>
                  Leave out the {wrong.length} line{wrong.length === 1 ? '' : 's'} with problems
                </button>
                <span className="inspector-hint">Or fix them and choose the file again.</span>
              </>
            )}
          </div>
        </div>
      )}
      {(checking || file) && (
        <button type="button" disabled={saving} style={{ background: 'transparent', justifySelf: 'start' }} onClick={() => void choose(undefined)}>
          Cancel
        </button>
      )}
    </section>
  )
}
