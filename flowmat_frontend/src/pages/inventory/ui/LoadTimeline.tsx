import { Fragment } from 'react'
import type { EquipmentLoadRowDto } from '../../../entities/production/api/useEquipmentLoad'
import { dayLabels, timelineBars } from '../model/equipmentLoadModel'

const LANE = 18
const FILL: Record<string, string> = { approved: '#dbeafe', in_progress: '#fef3c7' }

/**
 * The week's orders as bars per equipment (docs/domain/equipment-load.md "타임라인"), from the same rows as the load table:
 * dashed for drafts, red-edged where approved or running orders overlap. Read only; dates change on the work order.
 */
export function LoadTimeline({ rows, start, from, to }: { rows: EquipmentLoadRowDto[]; start: Date; from: string; to: string }) {
  return (
    <section aria-label="Load timeline" style={{ display: 'grid', gap: 4 }}>
      <h4 style={{ margin: '8px 0 0' }}>Timeline</h4>
      <div style={{ display: 'grid', gridTemplateColumns: '140px 1fr', gap: 6, alignItems: 'start' }}>
        <span />
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)' }}>
          {dayLabels(start).map((day) => <span key={day} className="inspector-hint">{day}</span>)}
        </div>
        {rows.map((row) => {
          const name = row.equipmentCode ?? row.equipmentName
          const { bars, lanes } = timelineBars(row.orders, from, to, row.concurrentWorkOrders ?? false, row.concurrentLimit ?? null)
          const status = new Map(row.orders.map((one) => [one.workOrderId, one.workOrderStatus]))
          return (
            <Fragment key={row.equipmentId}>
              <span>{name}</span>
              <div style={{ position: 'relative', height: lanes * LANE + 4, borderBottom: '1px solid var(--border)' }}>
                {Array.from({ length: 7 }, (_, day) => (
                  <span key={day} aria-hidden style={{ position: 'absolute', top: 0, bottom: 0, left: `${(day / 7) * 100}%`,
                    borderLeft: '1px solid var(--border)' }} />
                ))}
                <ul aria-label={`Timeline of ${name}`} style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                  {bars.map((bar) => (
                    <li key={bar.workOrderId} title={bar.title}
                      aria-label={`${bar.workOrderNumber}${bar.draft ? ' · draft' : ''}${bar.clash ? ' · clashes' : ''}`}
                      style={{
                        position: 'absolute', left: `${bar.left}%`, width: `${bar.width}%`, top: bar.lane * LANE + 2, height: LANE - 4,
                        boxSizing: 'border-box', borderRadius: 3, fontSize: 10, lineHeight: `${LANE - 6}px`, padding: '0 3px',
                        overflow: 'hidden', whiteSpace: 'nowrap',
                        background: bar.draft ? 'transparent' : FILL[status.get(bar.workOrderId) ?? ''] ?? '#e2e8f0',
                        border: bar.clash ? '2px solid #b91c1c' : bar.draft ? '1px dashed #64748b' : '1px solid #93c5fd',
                      }}>
                      {bar.workOrderNumber}
                    </li>
                  ))}
                </ul>
              </div>
            </Fragment>
          )
        })}
      </div>
      <span className="inspector-hint">
        Bars are planned windows (hover for the order). Dashed: draft. Red edge: approved or running orders overlapping each other.
      </span>
    </section>
  )
}
