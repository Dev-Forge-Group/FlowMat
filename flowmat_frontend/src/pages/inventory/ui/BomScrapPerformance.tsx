import { useBomScrapPerformanceQuery } from '../../../entities/bom/api/useBoms'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { formatQty } from '../../../shared/lib/formatQty'
import { lossGap, percentLabel } from '../model/bomModel'

const cell = { padding: '4px 6px' } as const
const TONE_COLOR = { over: '#b45309', under: '#047857', even: undefined } as const

/**
 * Planned scrap against what the revision's finished runs lost, material by material (ADR-034): a guide to the scrap rate
 * of the next revision. Shown once some run has finished.
 */
export function BomScrapPerformance({ projectId, bomId, itemLabel }: {
  projectId: string
  bomId: string
  itemLabel: Map<string, string>
}) {
  const query = useBomScrapPerformanceQuery(projectId, bomId)
  if (query.isError) {
    return <p style={{ color: '#dc2626', fontSize: 12 }}>{errorMessage(query.error, 'Could not work out the actual scrap.')}</p>
  }
  const data = query.data
  if (!data?.runs || !data.lines?.length) return null
  return (
    <div style={{ marginTop: 12 }}>
      <h4 style={{ margin: '0 0 4px', fontSize: 13 }}>Scrap: planned vs actual</h4>
      <p className="inspector-hint" style={{ margin: '0 0 6px', fontSize: 12 }}>
        Over {data.runs} finished run{data.runs === 1 ? '' : 's'} of v{data.bomVersion}, against what their output takes without scrap.
      </p>
      <table aria-label="Scrap: planned vs actual" style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
        <thead>
          <tr style={{ opacity: 0.7 }}>
            <th style={{ ...cell, textAlign: 'left', fontWeight: 500 }}>Material</th>
            <th style={{ ...cell, textAlign: 'right', fontWeight: 500 }}>Planned</th>
            <th style={{ ...cell, textAlign: 'right', fontWeight: 500 }}>Actual</th>
            <th style={{ ...cell, textAlign: 'right', fontWeight: 500 }}>Used</th>
          </tr>
        </thead>
        <tbody>
          {data.lines.map((line) => {
            const gap = lossGap(line.plannedScrapRate, line.actualLossRate)
            return (
              <tr key={line.itemId} style={{ borderBottom: '1px solid var(--border)' }}>
                <td style={cell}>
                  {itemLabel.get(line.itemId) ?? line.itemCode}
                  {!line.complete && <span className="inspector-hint"> · some inputs could not be counted</span>}
                </td>
                <td style={{ ...cell, textAlign: 'right' }}>{percentLabel(line.plannedScrapRate)}</td>
                <td style={{ ...cell, textAlign: 'right', color: gap ? TONE_COLOR[gap.tone] : undefined }}>
                  {line.actualLossRate == null ? '-' : percentLabel(line.actualLossRate)}
                  {gap && gap.tone !== 'even' && ` (${gap.points > 0 ? '+' : ''}${gap.points} pts)`}
                </td>
                <td style={{ ...cell, textAlign: 'right', whiteSpace: 'nowrap' }}>
                  {formatQty(line.actual)} of {formatQty(line.net)} {line.unit ?? ''} · {line.runs} run{line.runs === 1 ? '' : 's'}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
