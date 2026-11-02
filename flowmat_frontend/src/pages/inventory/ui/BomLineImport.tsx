import { useRef, useState } from 'react'
import { useImportBomLinesMutation } from '../../../entities/bom/api/useBoms'
import { errorMessage } from '../../../shared/lib/errorMessage'
import type { BomLineImportResultDto, BomLineImportRowDto } from '../../../shared/types/api'
import { bomLinesFromCsv } from '../model/bomModel'

const cell = { padding: '4px 6px' } as const

/**
 * A draft's materials from a spreadsheet (docs/domain/item-import.md "BOM 자재"): item_code, quantity and unit columns, and
 * optional type (material, by_product or waste), phantom and scrap_percent columns.
 * The file is checked with the approval rules first; saving happens only when no line is wrong.
 */
export function BomLineImport({ projectId, bomId }: { projectId: string; bomId: string }) {
  const importMutation = useImportBomLinesMutation(projectId)
  const [rows, setRows] = useState<BomLineImportRowDto[] | null>(null)
  const [replace, setReplace] = useState(false)
  const replaceRef = useRef(false)
  const [checking, setChecking] = useState(false)
  const [saving, setSaving] = useState(false)
  const selectionGeneration = useRef(0)
  const [check, setCheck] = useState<BomLineImportResultDto | null>(null)
  const [message, setMessage] = useState<{ error: boolean; text: string } | null>(null)

  async function checkRows(next: BomLineImportRowDto[], replaceLines: boolean, generation: number) {
    setCheck(null)
    setMessage(null)
    setChecking(true)
    try {
      const result = await importMutation.mutateAsync({ bomId, rows: next, replace: replaceLines, dryRun: true })
      if (generation === selectionGeneration.current) setCheck(result)
    } catch (error) {
      if (generation === selectionGeneration.current) setMessage({ error: true, text: errorMessage(error, 'The file could not be checked.') })
    } finally {
      if (generation === selectionGeneration.current) setChecking(false)
    }
  }

  async function save() {
    if (!rows || !check || check.errors > 0 || checking || saving) return
    setSaving(true)
    setMessage(null)
    try {
      const result = await importMutation.mutateAsync({ bomId, rows, replace, dryRun: false })
      if (result.applied) {
        setRows(null)
        setCheck(null)
        const removed = result.removed ? `, removed ${result.removed}` : ''
        setMessage({ error: false, text: `Added ${result.added} material${result.added === 1 ? '' : 's'}${removed}.` })
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
      await checkRows(parsed.rows, replaceRef.current, generation)
    } finally {
      if (generation === selectionGeneration.current) setChecking(false)
    }
  }

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
              if (rows) void checkRows(rows, e.target.checked, ++selectionGeneration.current)
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
        <div style={{ border: '1px solid var(--border)', borderRadius: 8, padding: 8 }}>
          <p style={{ margin: '0 0 4px' }}>
            {check.added} to add{check.removed ? `, ${check.removed} to remove` : ''}
            {check.errors > 0 && <strong style={{ color: '#dc2626' }}>, {check.errors} with problems</strong>}
          </p>
          {check.errors > 0 && (
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <tbody>
                {check.rows
                  .filter((row) => row.action === 'error')
                  .map((row) => (
                    <tr key={row.row} style={{ borderBottom: '1px solid var(--border)' }}>
                      <td style={{ ...cell, opacity: 0.6, whiteSpace: 'nowrap' }}>line {row.row + 1}</td>
                      <td style={cell}>
                        <code>{row.itemCode ?? '-'}</code>
                      </td>
                      <td style={{ ...cell, color: '#dc2626' }}>{row.message}</td>
                    </tr>
                  ))}
              </tbody>
            </table>
          )}
          <div style={{ display: 'flex', gap: 8, marginTop: 6 }}>
            <button type="button" disabled={check.errors > 0 || checking || saving} onClick={() => void save()}>
              {replace ? 'Replace materials' : `Add ${check.added} material${check.added === 1 ? '' : 's'}`}
            </button>

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
