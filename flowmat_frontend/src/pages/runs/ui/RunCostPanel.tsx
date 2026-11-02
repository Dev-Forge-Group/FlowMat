import { RunByProductValuePanel } from './RunByProductValuePanel'
import { RunWasteDisposalPanel } from './RunWasteDisposalPanel'
import { useRunCostQuery, useRunMaterialUsageQuery } from '../../../entities/production/api/useRunCost'
import { errorMessage } from '../../../shared/lib/errorMessage'
import type { RunMaterialUsageDto } from '../../../shared/types/api'
import { basisLabel, formatVariance, varianceTone, yieldLabel, type VarianceTone } from '../model/usageModel'
import { formatQty } from './runDisplay'
import { costBasisLabel } from '../model/costBasisModel'

const cell = { padding: '4px 6px' } as const
const num = { ...cell, textAlign: 'right' } as const
const TONE_COLORS: Record<VarianceTone, string | undefined> = { over: '#b45309', under: '#0369a1', even: undefined, unknown: undefined }

/**
 * What the run's inputs cost at current or original finish-time unit costs, and per unit made (docs/domain/material-cost.md). Items without a
 * unit cost are shown as such and keep the total marked incomplete. A run started from a BOM also shows its use against
 * the BOM, scaled to the output.
 */
export function RunCostPanel({ runId }: { runId: string }) {
  const costQuery = useRunCostQuery(runId)
  const usageQuery = useRunMaterialUsageQuery(runId)
  const cost = costQuery.data
  const usage = usageQuery.data

  return (
    <section aria-label="Material cost" style={{ border: '1px solid var(--border)', borderRadius: 12, padding: 18 }}>
      <h3 style={{ marginTop: 0 }}>Material cost</h3>
      {cost && <p aria-label="Price basis" className="inspector-hint">{costBasisLabel(cost)}</p>}
      {costQuery.isError && <p style={{ color: '#dc2626', fontSize: 12 }}>{errorMessage(costQuery.error, 'Could not work out the cost.')}</p>}
      {cost && cost.lines.length === 0 && <p className="inspector-hint">No inputs recorded yet.</p>}
      {cost && cost.lines.length > 0 && (
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
          <tbody>
            {cost.lines.map((line) => (
              <tr key={line.itemId} style={{ borderBottom: '1px solid var(--border)' }}>
                <td style={cell}>
                  {line.itemCode}
                  {line.itemName ? ` · ${line.itemName}` : ''}
                  {line.costBasis === 'ESTIMATED' && <span style={{ marginLeft: 6 }}>· estimated price</span>}
                </td>
                <td style={num}>{line.quantity == null ? '-' : `${formatQty(line.quantity)} ${line.unit ?? ''}`}</td>
                <td style={{ ...num, opacity: line.cost == null ? 0.5 : 1 }}>{line.cost == null ? 'no cost' : formatQty(line.cost)}</td>
              </tr>
            ))}
            <tr>
              <td style={cell}><strong>Total</strong></td>
              <td style={cell} />
              <td style={num}><strong>{formatQty(cost.materialCost)}</strong></td>
            </tr>
          </tbody>
        </table>
      )}
      {cost && cost.lines.length > 0 && (
        <p style={{ fontSize: 12, margin: '8px 0 0' }}>
          {!cost.costComplete
            ? 'Some inputs have no known cost or compatible unit; the total leaves them out and there is no cost per unit.'
            : cost.costPerUnit != null
              ? `Per unit made: ${formatQty(cost.costPerUnit)} (${formatQty(cost.outputQuantity ?? 0)} made)`
              : 'Cost per unit appears once the run has an output quantity.'}
        </p>
      )}
      {usage?.bomId && usage.lines.length > 0 && <UsageAgainstBom usage={usage} />}
      <RunByProductValuePanel runId={runId} />
      <RunWasteDisposalPanel runId={runId} />
    </section>
  )
}

function UsageAgainstBom({ usage }: { usage: RunMaterialUsageDto }) {
  return (
    <div aria-label="Use against BOM" style={{ marginTop: 14 }}>
      <h4 style={{ margin: '0 0 4px' }}>Use against BOM{usage.bomVersion != null ? ` v${usage.bomVersion}` : ''}</h4>
      <p className="inspector-hint" style={{ margin: '0 0 6px', fontSize: 12 }}>
        {basisLabel(usage, formatQty)} {yieldLabel(usage, formatQty)}
      </p>
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
        <thead>
          <tr style={{ opacity: 0.7 }}>
            <th style={{ ...cell, textAlign: 'left', fontWeight: 500 }}>Item</th>
            <th style={{ ...num, fontWeight: 500 }}>Standard</th>
            <th style={{ ...num, fontWeight: 500 }}>Used</th>
            <th style={{ ...num, fontWeight: 500 }}>Difference</th>
          </tr>
        </thead>
        <tbody>
          {usage.lines.map((line) => (
            <tr key={line.itemId} style={{ borderBottom: '1px solid var(--border)' }}>
              <td style={{ ...cell, wordBreak: 'break-word' }}>
                {line.itemCode}
                {!line.inBom && (
                  <span style={{ marginLeft: 6, fontSize: 10, opacity: 0.7 }}>{line.optional ? 'optional, not picked' : 'not in BOM'}</span>
                )}
                {line.substituteFor && (
                  <span style={{ marginLeft: 6, fontSize: 10, opacity: 0.7 }}>
                    substitute for {usage.lines.find((other) => other.itemId === line.substituteFor)?.itemCode ?? 'a BOM material'}
                  </span>
                )}
              </td>
              <td style={{ ...num, whiteSpace: 'nowrap' }}>{line.standard == null ? '-' : `${formatQty(line.standard)} ${line.unit ?? ''}`}</td>
              <td style={{ ...num, whiteSpace: 'nowrap' }}>{line.actual == null ? '-' : `${formatQty(line.actual)} ${line.unit ?? ''}`}</td>
              <td style={{ ...num, color: TONE_COLORS[varianceTone(line)] }}>{formatVariance(line, formatQty)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p style={{ fontSize: 12, margin: '8px 0 0' }}>
        {usage.varianceCost === 0 && usage.varianceCostComplete
          ? 'Used as the BOM allows.'
          : `Difference at unit cost: ${usage.varianceCost > 0 ? '+' : ''}${formatQty(usage.varianceCost)}`
            + (usage.varianceCostComplete ? '' : ' (items without a unit cost left out)')}
      </p>
    </div>
  )
}
