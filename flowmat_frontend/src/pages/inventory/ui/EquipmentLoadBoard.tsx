import { useMemo, useState } from 'react'
import { useEquipmentLoadQuery } from '../../../entities/production/api/useEquipmentLoad'
import { useWorkOrdersQuery } from '../../../entities/production/api/useWorkOrders'
import { useProjectRole } from '../../../entities/project/api/useProjectRole'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { formatQty } from '../../../shared/lib/formatQty'
import {
  changeoverPlanText,
  dateInputValue,
  loadLabel,
  loadTone,
  orderLine,
  parseDateInput,
  shiftWeeks,
  weekRange,
  weekStart,
  type LoadTone,
} from '../model/equipmentLoadModel'
import { formatHours } from '../model/equipmentScheduleModel'
import { LoadOrderMove } from './LoadOrderMove'
import { LoadTimeline } from './LoadTimeline'

const cell = { padding: '4px 6px', verticalAlign: 'top' } as const

const TONE: Record<LoadTone, string> = { over: '#b91c1c', high: '#b45309', ok: '#047857', idle: 'var(--border)' }

/**
 * A week of equipment load (docs/domain/equipment-load.md): each equipment's available hours against what its approved and
 * running orders need, with drafts shown apart. Orders that run past the week count in proportion. An order can be moved to
 * a new start from here (M1-M4).
 */
export function EquipmentLoadBoard({ projectId }: { projectId: string }) {
  const [start, setStart] = useState(() => weekStart(new Date()))
  const range = useMemo(() => weekRange(start), [start])
  const query = useEquipmentLoadQuery(projectId, range.from, range.to, true)
  const rows = query.data?.equipment ?? []
  const role = useProjectRole(projectId)
  const orders = useWorkOrdersQuery(projectId).data
  const byId = new Map((Array.isArray(orders) ? orders : []).map((order) => [order.workOrderId, order]))

  return (
    <section aria-label="Equipment load" style={{ display: 'grid', gap: 8, fontSize: 12 }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
        <button type="button" onClick={() => setStart(shiftWeeks(start, -1))}>Previous week</button>
        <label>Week of <input type="date" value={dateInputValue(start)} onChange={(event) => {
          const picked = parseDateInput(event.target.value)
          if (picked) setStart(weekStart(picked))
        }} /></label>
        <button type="button" onClick={() => setStart(shiftWeeks(start, 1))}>Next week</button>
        <button type="button" onClick={() => setStart(weekStart(new Date()))}>This week</button>
        <button type="button" disabled={query.isFetching} onClick={() => void query.refetch()}>
          {query.isFetching ? 'Loading...' : 'Refresh'}</button>
      </div>
      {query.isError && <p role="alert">{errorMessage(query.error)}</p>}
      {query.data && rows.length === 0 && <p className="inspector-hint">No equipment has been added.</p>}
      {rows.length > 0 && <table aria-label="Load by equipment" style={{ borderCollapse: 'collapse', width: '100%', textAlign: 'left' }}>
        <thead><tr><th style={cell}>Equipment</th><th style={cell}>Available</th><th style={cell}>Planned</th>
          <th style={cell}>Drafts</th><th style={cell}>Load</th><th style={cell}>Orders</th></tr></thead>
        <tbody>{rows.map((row) => {
          const tone = loadTone(row)
          return <tr key={row.equipmentId} style={{ borderTop: '1px solid var(--border)' }}>
            <td style={cell}>{row.equipmentCode ?? row.equipmentName}
              {row.equipmentStatus !== 'active' && <div className="inspector-hint">{row.equipmentStatus}</div>}
              {row.efficiencyPercent != null && row.efficiencyPercent !== 100
                && <div className="inspector-hint">{formatQty(row.efficiencyPercent)}% efficient</div>}
              {row.concurrentWorkOrders && <div className="inspector-hint">
                {row.concurrentLimit ? `runs up to ${row.concurrentLimit} orders side by side` : 'runs orders side by side'}</div>}
              {!row.calendarSet && <div className="inspector-hint">no calendar</div>}</td>
            <td style={cell}>{formatHours(row.availableHours)}
              {row.downtimeHours > 0 && <div className="inspector-hint">{formatHours(row.downtimeHours)} down</div>}</td>
            <td style={cell}>{formatHours(row.plannedHours)}</td>
            <td style={cell}>{row.draftHours > 0 ? formatHours(row.draftHours) : '—'}</td>
            <td style={{ ...cell, minWidth: 110 }}>
              <div style={{ height: 6, borderRadius: 3, background: 'var(--border)' }}>
                <div style={{ width: `${Math.min(100, row.loadPercent ?? 0)}%`, height: '100%', borderRadius: 3, background: TONE[tone] }} />
              </div>
              <span style={{ color: tone === 'over' ? TONE.over : undefined }}>{loadLabel(row)}{row.overloaded && ' · Overloaded'}</span>
            </td>
            <td style={cell}>
              {row.orders.map((order) => <div key={order.workOrderId}>
                {orderLine(order)}
                {' '}<LoadOrderMove projectId={projectId} order={order} full={byId.get(order.workOrderId)} role={role} />
                {'setupCostEstimate' in order && <div className="inspector-hint">
                  Setup estimate: {order.setupCostEstimate == null ? 'unknown' : formatQty(order.setupCostEstimate)}
                </div>}
              </div>)}
              {row.changeovers && (
                <div className="inspector-hint" style={{ color: row.changeovers.suggestedOrder ? '#b45309' : undefined }}>
                  {changeoverPlanText(row.changeovers)}
                </div>
              )}
              {row.unplannedOrders > 0 && <div className="inspector-hint">{row.unplannedOrders} open order(s) without planned dates</div>}
              {row.unmeasuredOrders > 0 && <div className="inspector-hint">
                {row.unmeasuredOrders} order(s) without a quantity or capacity per hour are not counted</div>}
            </td>
          </tr>
        })}</tbody>
      </table>}
      {rows.some((row) => row.orders.some((order) => 'setupCostEstimate' in order)) &&
        <p className="inspector-hint" style={{ margin: 0 }}>Whole-order setup at current equipment rates; shown separately from material and actual costs.</p>}
      {rows.length > 0 && <LoadTimeline rows={rows} start={start} from={range.from} to={range.to} />}
    </section>
  )
}
