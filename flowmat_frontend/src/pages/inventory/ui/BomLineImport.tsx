import { useRef, useState } from 'react'
import { useImportBomLinesMutation } from '../../../entities/bom/api/useBoms'
import { errorMessage } from '../../../shared/lib/errorMessage'
import type { BomLineImportResultDto, BomLineImportRowDto } from '../../../shared/types/api'
import { bomLinesFromCsv } from '../model/bomModel'

const cell = { padding: '4px 6px' } as const

const plural = (count: number, one: string, many = `${one}s`) => `${count} ${count === 1 ? one : many}`

/**
 * A draft's materials from a spreadsheet (docs/domain/item-import.md "BOM 자재"): item_code, quantity and unit columns, and
 * optional type (material, by_product or waste), phantom and scrap_percent columns.
 * The file is checked with the approval rules first; saving happens only when no line is wrong. A line can be left out
 * (the wrong ones in one press), and the file is then checked again without it (B1). When the import replaces the
 * materials, a material of the draft that only a left-out line named is gone afterwards, and the check says so.
 */
export function BomLineImport({ projectId, bomId }: { projectId: string; bomId: string }) {
  const importMutation = useImportBomLinesMutation(projectId)
  const [rows, setRows] = useState<BomLineImportRowDto[] | null>(null)
  const [replace, setReplace] = useState(false)
  const replaceRef = useRef(false)
  /** Rows left out of the import, by their number from 1. */
  const [leftOut, setLeftOut] = useState<number[]>([])
  const [checking, setChecking] = useState(false)
  const [saving, setSaving] = useState(false)
  const selectionGeneration = useRef(0)
  const [check, setCheck] = useState<BomLineImportResultDto | null>(null)
  const [message, setMessage] = useState<{ error: boolean; text: string } | null>(null)

  /** The request leaves skipRows out when nothing is left out, as it always did. */
  const skipping = (skipRows: number[]) => (skipRows.length > 0 ? { skipRows } : {})

  async function checkRows(next: BomLineImportRowDto[], replaceLines: boolean, skipRows: number[], generation: number) {
    setCheck(null)
    setMessage(null)
    setChecking(true)
    try {
      const result = await importMutation.mutateAsync({ bomId, rows: next, replace: replaceLines, dryRun: true, ...skipping(skipRows) })
      if (generation === selectionGeneration.current) setCheck(result)
    } catch (error) {
      if (generation === selectionGeneration.current) setMessage({ error: true, text: errorMessage(error, 'The file could not be checked.') })
    } finally {
      if (generation === selectionGeneration.current) setChecking(false)
    }
  }

  /** Checks the same file again with these rows left out; the earlier check stays until the new one answers. */
  async function leaveOut(next: number[]) {
    if (!rows || checking || saving) return
    const generation = ++selectionGeneration.current
    const chosen = [...new Set(next)].sort((a, b) => a - b)
    const previous = leftOut
    // The tick moves at once; what each line would do follows when the check answers.
    setLeftOut(chosen)
    setChecking(true)
    setMessage(null)
    try {
      const result = await importMutation.mutateAsync({ bomId, rows, replace, dryRun: true, ...skipping(chosen) })
      if (generation === selectionGeneration.current) setCheck(result)
    } catch (error) {
      if (generation === selectionGeneration.current) {
        // The shown check is still the one for the earlier choice.
        setLeftOut(previous)
        setMessage({ error: true, text: errorMessage(error, 'The file could not be checked.') })
      }
    } finally {
      if (generation === selectionGeneration.current) setChecking(false)
    }
  }

  async function save() {
    if (!rows || !check || check.errors > 0 || checking || saving) return
    setSaving(true)
    setMessage(null)
    try {
      const result = await importMutation.mutateAsync({ bomId, rows, replace, dryRun: false, ...skipping(leftOut) })
      if (result.applied) {
        setRows(null)
        setCheck(null)
        setLeftOut([])
        const removed = result.removed ? `, removed ${result.removed}` : ''
        const without = result.skipped > 0 ? `, ${plural(result.skipped, 'line')} left out` : ''
        setMessage({ error: false, text: `Added ${plural(result.added, 'material')}${removed}${without}.` })
      } else {
        setCheck(result)
      }
    } catch (error) {
      setMessage({ error: true, text: errorMessage(error, 'The material changes could not be confirmed.') })
    } finally {
      setSaving(false)
    }
  }

  async function choose(file: File | undefined) {
    if (saving) return
    const generation = ++selectionGeneration.current
    setRows(null)
    setCheck(null)
    setLeftOut([])
    setMessage(null)
    setChecking(Boolean(file))
    if (!file) return
    try {
      let text: string
      try {
        text = await file.text()
      } catch {
        if (generation === selectionGeneration.current) setMessage({ error: true, text: 'The materials file could not be read. Choose the file again.' })
        return
      }
      if (generation !== selectionGeneration.current) return
      const parsed = bomLinesFromCsv(text)
      if (!parsed.ok) {
        setMessage({ error: true, text: parsed.error })
        return
      }
      setRows(parsed.rows)
      await checkRows(parsed.rows, replaceRef.current, [], generation)
    } finally {
      if (generation === selectionGeneration.current) setChecking(false)
    }
  }

  const wrong = check ? check.rows.filter((row) => row.action === 'error').map((row) => row.row) : []
  // Leaving out every line is refused by the server, so the last line that is still in cannot be taken out.
  const lastIn = rows ? rows.length - leftOut.length <= 1 : true

  return (
    <div aria-busy={checking || saving} aria-label="Materials from CSV" style={{ display: 'grid', gap: 6, marginBottom: 12, fontSize: 12 }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
        <label style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
          <span>Materials from CSV</span>
          <input type="file" accept=".csv,text/csv" aria-label="Import materials file" disabled={saving} onChange={(e) => {
            const file = e.target.files?.[0]
            void choose(file)
            e.target.value = ''
          }} />
        </label>
        <label style={{ display: 'inline-flex', gap: 4, alignItems: 'center' }}>
          <input
            type="checkbox"
            checked={replace}
            disabled={saving}
            onChange={(e) => {
              replaceRef.current = e.target.checked
              setReplace(e.target.checked)
              if (rows) void checkRows(rows, e.target.checked, leftOut, ++selectionGeneration.current)
            }}
          />
          Replace current materials
        </label>
      </div>
      {checking && <p role="status" style={{ margin: 0 }}>Reading and checking materials file...</p>}
      {message && (
        <p role={message.error ? 'alert' : 'status'} style={{ margin: 0, color: message.error ? '#dc2626' : '#047857' }}>
          {message.text}
        </p>
      )}
      {rows && check && (
        <div aria-label="Materials import check" style={{ border: '1px solid var(--border)', borderRadius: 8, padding: 8 }}>
          <p style={{ margin: '0 0 4px' }}>
            {check.added} to add{check.removed ? `, ${check.removed} to remove` : ''}
            {check.errors > 0 && <strong style={{ color: '#dc2626' }}>, {check.errors} with problems</strong>}
            {check.skipped > 0 && <span>, {check.skipped} left out</span>}
          </p>
          {replace && check.skippedCurrent > 0 && (
            <p style={{ margin: '0 0 4px', color: '#b45309' }}>
              {check.skippedCurrent === 1
                ? '1 left-out line names a material this BOM has now. Replacing removes it, and it is not added back.'
                : `${check.skippedCurrent} left-out lines name materials this BOM has now. Replacing removes them, and they are not added back.`}
            </p>
          )}
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <tbody>
              {check.rows.map((row) => (
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
                    {row.action === 'skipped' ? 'left out' : row.action}
                  </td>
                  <td style={{ ...cell, color: row.action === 'error' ? '#dc2626' : row.action === 'skipped' ? '#b45309' : undefined }}>
                    {row.message ?? ''}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <div style={{ display: 'flex', gap: 8, marginTop: 6 }}>
            <button type="button" disabled={check.errors > 0 || checking || saving} onClick={() => void save()}>
              {replace ? 'Replace materials' : `Add ${plural(check.added, 'material')}`}
            </button>
            {check.errors > 0 && (
              <button type="button" disabled={checking || saving || wrong.length >= rows.length - leftOut.length}
                onClick={() => void leaveOut([...leftOut, ...wrong])}>
                Leave out the {plural(wrong.length, 'line')} with problems
              </button>
            )}
          </div>
        </div>
      )}
      {(checking || rows) && (
        <button type="button" disabled={saving} style={{ background: 'transparent', justifySelf: 'start' }} onClick={() => void choose(undefined)}>
          Cancel
        </button>
      )}
    </div>
  )
}
