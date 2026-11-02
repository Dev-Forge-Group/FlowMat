import { useState } from 'react'
import { useBomRequirementsQuery } from '../../../entities/bom/api/useBoms'
import { formatQty } from '../../../shared/lib/formatQty'
import type { BomDto } from '../../../shared/types/api'
import { compareBoms, costChange, scrapLabel, type BomLineChange, type BomLineSide } from '../model/bomModel'

const cell = { padding: '4px 6px' } as const

/**
 * "2 kg", with "· 5% scrap" when the material plans scrap (ADR-031) and "· substitute for X" when it stands in for another
 * (ADR-032); "-" when the revision has no such line.
 */
function sideText(side: BomLineSide | null, itemLabel: Map<string, string>): string {
  if (!side) return '-'
  const scrap = scrapLabel(side.scrapRate)
  const substitute = side.substituteFor ? ` · substitute for ${itemLabel.get(side.substituteFor) ?? side.substituteFor}` : ''
  return `${formatQty(side.quantity)} ${side.unit}${scrap ? ` · ${scrap}` : ''}${substitute}${side.optional ? ' · optional' : ''}`
}
const CHANGE_STYLE: Record<BomLineChange, { label: string; color?: string }> = {
  added: { label: 'added', color: '#047857' },
  removed: { label: 'removed', color: '#b91c1c' },
  changed: { label: 'changed', color: '#b45309' },
  same: { label: '' },
}

/**
 * What changed between this revision and another of the same product (docs/domain/inventory-bom-lot-contract.md §5),
 * older on the left. Quantities are compared per unit of product.
 */
export function BomRevisionCompare({
  bom,
  revisions,
  itemLabel,
  productUnit,
}: {
  bom: BomDto
  /** The product's other revisions. */
  revisions: BomDto[]
  itemLabel: Map<string, string>
  /** The product's own unit code, which requirement quantities are in. */
  productUnit: string | undefined
}) {
  const [otherId, setOtherId] = useState('')
  const other = revisions.find((revision) => revision.bomId === otherId) ?? null
  const [older, newer] = other && other.bomVersion > bom.bomVersion ? [bom, other] : [other, bom]
  // Price both for the same amount of product: the newer base when it is in the product's unit, else one unit.
  const costQuantity = newer && productUnit && newer.baseUnit === productUnit ? newer.baseQuantity : 1
  const olderCost = useBomRequirementsQuery(older ? older.bomId : null, costQuantity)
  const newerCost = useBomRequirementsQuery(older && newer ? newer.bomId : null, costQuantity)
  if (revisions.length === 0) return null
  const comparison = older && newer ? compareBoms(older, newer) : null
  const changed = comparison ? comparison.lines.filter((line) => line.change !== 'same').length : 0
  let costLine = ''
  if (olderCost.isError || newerCost.isError) {
    costLine = 'The material cost could not be worked out for both revisions.'
  } else if (older && newer && olderCost.data?.materialCost != null && newerCost.data?.materialCost != null) {
    const change = costChange(olderCost.data.materialCost, newerCost.data.materialCost)
    const sign = change.delta > 0 ? '+' : ''
    const percent = change.percent === null ? '' : `, ${change.percent > 0 ? '+' : ''}${change.percent}%`
    const incomplete = olderCost.data.costComplete === false || newerCost.data.costComplete === false
    costLine =
      `Material cost for ${formatQty(costQuantity)} ${productUnit ?? ''}: v${older.bomVersion} ${formatQty(olderCost.data.materialCost)}`
      + ` \u2192 v${newer.bomVersion} ${formatQty(newerCost.data.materialCost)} (${sign}${formatQty(change.delta)}${percent})`
      + (incomplete ? ' \u00b7 some materials have no unit cost' : '')
  }

  return (
    <div aria-label="Compare revisions" style={{ marginTop: 16, borderTop: '1px solid var(--border)', paddingTop: 12 }}>
      <label style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 13 }}>
        <span>Compare with</span>
        <select value={otherId} onChange={(e) => setOtherId(e.target.value)}>
          <option value="">choose a revision</option>
          {revisions.map((revision) => (
            <option key={revision.bomId} value={revision.bomId}>
              v{revision.bomVersion} ({revision.bomStatus.replace('_', ' ')})
            </option>
          ))}
        </select>
      </label>
      {comparison && older && newer && (
        <>
          <p style={{ margin: '8px 0 4px', fontSize: 12 }}>
            v{older.bomVersion} → v{newer.bomVersion}: {changed === 0 ? 'no material changes' : `${changed} material change${changed === 1 ? '' : 's'}`}
            {comparison.baseChanged &&
              ` · base ${formatQty(older.baseQuantity)} ${older.baseUnit} → ${formatQty(newer.baseQuantity)} ${newer.baseUnit}`}
          </p>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
            <thead>
              <tr style={{ opacity: 0.7 }}>
                <th style={{ ...cell, textAlign: 'left', fontWeight: 500 }}>Material</th>
                <th style={{ ...cell, textAlign: 'right', fontWeight: 500 }}>v{older.bomVersion}</th>
                <th style={{ ...cell, textAlign: 'right', fontWeight: 500 }}>v{newer.bomVersion}</th>
                <th style={cell} />
              </tr>
            </thead>
            <tbody>
              {comparison.lines.map((line) => (
                <tr key={`${line.childItemId}-${line.before?.unit ?? ''}-${line.after?.unit ?? ''}`} style={{ borderBottom: '1px solid var(--border)' }}>
                  <td style={cell}>{itemLabel.get(line.childItemId) ?? line.childItemId}</td>
                  <td style={{ ...cell, textAlign: 'right', whiteSpace: 'nowrap' }}>
                    {sideText(line.before, itemLabel)}
                  </td>
                  <td style={{ ...cell, textAlign: 'right', whiteSpace: 'nowrap' }}>
                    {sideText(line.after, itemLabel)}
                  </td>
                  <td style={{ ...cell, color: CHANGE_STYLE[line.change].color, fontWeight: 600 }}>{CHANGE_STYLE[line.change].label}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {costLine && (
            <p aria-label="Cost change" style={{ margin: '6px 0 0', fontSize: 12 }}>
              {costLine}
            </p>
          )}
        </>
      )}
    </div>
  )
}
