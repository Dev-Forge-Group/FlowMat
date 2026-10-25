import { useMemo, useState, type FormEvent } from 'react'
import {
  useCloseLotMutation, useCreateLotMutation, useLotTraceQuery, useLotsQuery, useReleaseLotMutation, useReopenLotMutation,
} from '../../../entities/inventory/api/useLots'
import { useInventoriesQuery } from '../../../entities/inventory/api/useInventoriesQuery'
import { usePurchaseOrdersQuery } from '../../../entities/purchasing/api/usePurchaseOrders'
import { useInspectionStandardsQuery } from '../../../entities/quality/api/useInspectionStandards'
import { useQualityInspectionsQuery } from '../../../entities/quality/api/useQuality'
import { receiptCheckCounts, receiptCheckText } from '../../../entities/quality/model/standardModel'
import { QualitySection } from '../../../entities/quality/ui/QualitySection'
import { ReceiptChecklist } from '../../../entities/quality/ui/ReceiptChecklist'
import type { ItemDto, LotDto, LotStatus } from '../../../shared/types/api'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { formatQty } from '../../../shared/lib/formatQty'
import { EMPTY_LOT_FILTER, EXPIRES_SOON_DAYS, filterLots, type LotFilter } from '../model/listFilterModel'
import { LotRecall } from './LotRecall'
import { LotReleaseHistory } from './LotReleaseHistory'
import { lotsCsv } from '../model/lotExportModel'
import { ExpiryOutlook } from './ExpiryOutlook'
import { ItemScanInput } from './ItemScanInput'
import { pickableItems } from '../model/itemStatusModel'
import { lotOrigins, originText } from '../model/purchaseOrderModel'

const STATUS_COLORS: Record<LotStatus, string> = {
  available: '#15803d',
  reserved: '#1d4ed8',
  quarantined: '#b91c1c',
  inspection_pending: '#b45309',
  consumed: '#9ca3af',
  closed: '#6b7280',
}

const cell = { padding: '6px 6px' } as const

export function LotPanel({ projectId, items }: { projectId: string; items: ItemDto[] }) {
  const lotsQuery = useLotsQuery(projectId)
  const lots = lotsQuery.data ?? []
  const itemLabel = useMemo(() => new Map(items.map((item) => [item.itemId, `${item.itemCode} · ${item.itemName}`])), [items])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const selected = lots.find((lot) => lot.lotId === selectedId) ?? null
  const [filter, setFilter] = useState<LotFilter>(EMPTY_LOT_FILTER)
  // Receipt checks are worked out only while the filter asks for them, since that reads every inspection of the project.
  const [receipt, setReceipt] = useState<'any' | 'missing' | 'failed'>('any')
  const checking = receipt !== 'any'
  const standardsQuery = useInspectionStandardsQuery(projectId, checking)
  const inspectionsQuery = useQualityInspectionsQuery(projectId, {}, checking)
  const receiptCounts = useMemo(
    () => (checking && standardsQuery.data && inspectionsQuery.data
      ? receiptCheckCounts(lots, standardsQuery.data, inspectionsQuery.data)
      : null),
    [checking, lots, standardsQuery.data, inspectionsQuery.data],
  )
  const shown = filterLots(lots, filter, (itemId) => itemLabel.get(itemId) ?? itemId).filter((lot) => {
    if (!receiptCounts) return true
    const counts = receiptCounts.get(lot.lotId)
    return receipt === 'missing' ? (counts?.missing ?? 0) > 0 : (counts?.failed ?? 0) > 0
  })

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 380px', gap: 24, alignItems: 'start' }}>
      <section>
        {lotsQuery.isLoading && <p>Loading LOTs...</p>}
        {lotsQuery.isError && <p style={{ color: '#dc2626' }}>{errorMessage(lotsQuery.error, 'Failed to load LOTs.')}</p>}
        {!lotsQuery.isLoading && lots.length === 0 && (
          <p className="inspector-hint">
            No LOTs yet. Turn on “Track stock per LOT” for an item, then register LOTs here.
          </p>
        )}
        <ExpiryOutlook projectId={projectId} lots={lots} itemLabel={(itemId) => itemLabel.get(itemId) ?? itemId} onOpen={setSelectedId} />
        {lots.length > 0 && (
          <div role="search" aria-label="Filter LOTs" style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', marginBottom: 8, fontSize: 12 }}>
            <input
              value={filter.text}
              onChange={(e) => setFilter((f) => ({ ...f, text: e.target.value }))}
              placeholder="LOT number or item"
              aria-label="Search LOTs"
            />
            <select value={filter.status} onChange={(e) => setFilter((f) => ({ ...f, status: e.target.value as LotFilter['status'] }))} aria-label="LOT status">
              <option value="all">any status</option>
              <option value="open">not closed</option>
              {(['available', 'reserved', 'quarantined', 'inspection_pending', 'consumed', 'closed'] as const).map((status) => (
                <option key={status} value={status}>{status}</option>
              ))}
            </select>
            <select value={filter.expiry} onChange={(e) => setFilter((f) => ({ ...f, expiry: e.target.value as LotFilter['expiry'] }))} aria-label="LOT expiry">
              <option value="any">any expiry</option>
              <option value="expired">expired</option>
              <option value="soon">expires within {EXPIRES_SOON_DAYS} days</option>
              <option value="none">no expiry date</option>
            </select>
            <select value={receipt} onChange={(e) => setReceipt(e.target.value as typeof receipt)} aria-label="Receipt checks">
              <option value="any">any receipt checks</option>
              <option value="missing">required receipt check missing</option>
              <option value="failed">receipt check failed</option>
            </select>
            {checking && !receiptCounts && <span className="inspector-hint">Checking receipt inspections...</span>}
            {shown.length !== lots.length && (
              <span className="inspector-hint">
                {shown.length} of {lots.length}{' '}
                <button type="button" style={{ fontSize: 11 }} onClick={() => {
                  setFilter(EMPTY_LOT_FILTER)
                  setReceipt('any')
                }}>Clear</button>
              </span>
            )}
            <button
              type="button"
              disabled={shown.length === 0}
              style={{ fontSize: 11 }}
              onClick={() => {
                const url = URL.createObjectURL(
                  new Blob([lotsCsv(shown, (itemId) => itemLabel.get(itemId) ?? itemId)], { type: 'text/csv;charset=utf-8' }),
                )
                const link = document.createElement('a')
                link.href = url
                link.download = `lots-${new Date().toISOString().slice(0, 10)}.csv`
                link.click()
                URL.revokeObjectURL(url)
              }}
            >
              Download CSV
            </button>
          </div>
        )}
        {lots.length > 0 && (
          <table aria-label="LOTs" style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr style={{ borderBottom: '2px solid var(--border)', textAlign: 'left' }}>
                <th style={cell}>LOT</th>
                <th style={cell}>Item</th>
                <th style={{ ...cell, textAlign: 'right' }}>On hand</th>
                <th style={{ ...cell, textAlign: 'right' }}>Reserved</th>
                <th style={cell}>Expiry</th>
                <th style={cell}>Status</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((lot) => (
                <tr
                  key={lot.lotId}
                  onClick={() => setSelectedId(lot.lotId)}
                  style={{
                    borderBottom: '1px solid var(--border)',
                    cursor: 'pointer',
                    background: lot.lotId === selectedId ? 'var(--accent-bg)' : undefined,
                  }}
                >
                  <td style={cell}>
                    <code>{lot.lotNo}</code>
                    {receiptCounts?.has(lot.lotId) && (
                      <div style={{ fontSize: 11, color: '#c2410c' }}>
                        {receiptCheckText(receiptCounts.get(lot.lotId) ?? { missing: 0, failed: 0 })}
                      </div>
                    )}
                  </td>
                  <td style={cell}>{itemLabel.get(lot.itemId) ?? lot.itemId}</td>
                  <td style={{ ...cell, textAlign: 'right' }}>{formatQty(lot.quantityOnHand)}</td>
                  <td style={{ ...cell, textAlign: 'right' }}>{formatQty(lot.quantityReserved)}</td>
                  <td style={{ ...cell, color: lot.expired ? '#b91c1c' : undefined }}>
                    {lot.expiryDate ?? '-'}
                    {lot.expired && <strong style={{ marginLeft: 6, fontSize: 11 }}>expired</strong>}
                  </td>
                  <td style={{ ...cell, color: STATUS_COLORS[lot.lotStatus], fontWeight: 600 }}>{lot.lotStatus}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section style={{ border: '1px solid var(--border)', borderRadius: 12, padding: 18 }}>
        {selected ? (
          <LotDetail key={selected.lotId} projectId={projectId} lot={selected} itemLabel={itemLabel} onClose={() => setSelectedId(null)} />
        ) : (
          <CreateLotForm projectId={projectId} items={pickableItems(items.filter((item) => item.lotManageYn === 'Y'))} onCreated={setSelectedId} />
        )}
      </section>
    </div>
  )
}

function CreateLotForm({ projectId, items, onCreated }: { projectId: string; items: ItemDto[]; onCreated: (lotId: string) => void }) {
  const createMutation = useCreateLotMutation(projectId)
  const [form, setForm] = useState({ itemId: '', lotNo: '', expiryDate: '' })

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    try {
      const lot = await createMutation.mutateAsync({
        itemId: form.itemId,
        lotNo: form.lotNo.trim(),
        expiryDate: form.expiryDate || undefined,
      })
      setForm({ itemId: form.itemId, lotNo: '', expiryDate: '' })
      onCreated(lot.lotId)
    } catch {
      // Shown below.
    }
  }

  return (
    <>
      <h3 style={{ marginTop: 0 }}>Register LOT</h3>
      {items.length === 0 ? (
        <p className="inspector-hint">No item tracks LOTs yet. Edit an item and tick “Track stock per LOT”.</p>
      ) : (
        <form onSubmit={(e) => void handleSubmit(e)} style={{ display: 'grid', gap: 10 }}>
          <ItemScanInput items={items} onPick={(item) => setForm((f) => ({ ...f, itemId: item.itemId }))} />
          <label style={{ display: 'grid', gap: 4 }}>
            <span>Item *</span>
            <select value={form.itemId} onChange={(e) => setForm((f) => ({ ...f, itemId: e.target.value }))} required>
              <option value="" disabled>Select item</option>
              {items.map((item) => <option key={item.itemId} value={item.itemId}>{item.itemCode} · {item.itemName}</option>)}
            </select>
          </label>
          <label style={{ display: 'grid', gap: 4 }}>
            <span>LOT number *</span>
            <input value={form.lotNo} onChange={(e) => setForm((f) => ({ ...f, lotNo: e.target.value }))} placeholder="e.g. 2026-09-A01" required />
          </label>
          <label style={{ display: 'grid', gap: 4 }}>
            <span>Expiry date</span>
            <input type="date" value={form.expiryDate} onChange={(e) => setForm((f) => ({ ...f, expiryDate: e.target.value }))} />
          </label>
          <span style={{ fontSize: 11, opacity: 0.6 }}>
            LOT numbers are unique within the project. Receive stock into the LOT from the Stock tab.
          </span>
          <button type="submit" disabled={createMutation.isPending}>{createMutation.isPending ? 'Saving...' : 'Register'}</button>
          {createMutation.isError && (
            <p style={{ color: '#dc2626', fontSize: 12, margin: 0 }}>{errorMessage(createMutation.error, 'Failed to register LOT.')}</p>
          )}
        </form>
      )}
    </>
  )
}

function LotDetail({
  projectId,
  lot,
  itemLabel,
  onClose,
}: {
  projectId: string
  lot: LotDto
  itemLabel: Map<string, string>
  onClose: () => void
}) {
  const [direction, setDirection] = useState<'backward' | 'forward'>('backward')
  const traceQuery = useLotTraceQuery(lot.lotId, direction)
  const closeMutation = useCloseLotMutation(projectId)
  const releaseMutation = useReleaseLotMutation(projectId)
  const reopenMutation = useReopenLotMutation(projectId)
  const inventoriesQuery = useInventoriesQuery(projectId)
  // A LOT bought in names the orders it came on, back to the supplier (docs/domain/purchasing.md PO13).
  const origins = lotOrigins(usePurchaseOrdersQuery(projectId).data ?? [], lot)

  return (
    <>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
        <h3 style={{ marginTop: 0 }}><code>{lot.lotNo}</code></h3>
        <button type="button" onClick={onClose} style={{ background: 'transparent', fontSize: 12 }}>Register LOT</button>
      </div>
      <p style={{ margin: '0 0 6px', fontSize: 13 }}>
        {itemLabel.get(lot.itemId) ?? lot.itemId} ·{' '}
        <strong style={{ color: STATUS_COLORS[lot.lotStatus] }}>{lot.lotStatus.replace('_', ' ')}</strong>
      </p>
      <p style={{ margin: '0 0 12px', fontSize: 12, opacity: 0.75 }}>
        {formatQty(lot.quantityOnHand)} on hand, {formatQty(lot.quantityReserved)} reserved
        {lot.productionRunId ? ' · produced by a run' : ''}
        {lot.lotStatus === 'quarantined' ? ' · release it from the Stock tab' : ''}
        {lot.lotStatus === 'inspection_pending'
          ? ' · held until its receipt checks pass: record them below, then Release LOT'
          : ''}
        {lot.expired ? ` · expired ${lot.expiryDate}: it cannot go into production or be reserved; issue it to scrap it` : ''}
      </p>
      {origins.length > 0 && (
        <ul aria-label="LOT origin" style={{ margin: '0 0 12px', paddingLeft: 18, fontSize: 12 }}>
          {origins.map((origin, index) => <li key={index}>{originText(origin)}</li>)}
        </ul>
      )}

      {lot.lotStatus !== 'closed' && (
        <button
          type="button"
          disabled={closeMutation.isPending || lot.quantityOnHand !== 0}
          title={lot.quantityOnHand !== 0 ? 'Only an empty LOT can be closed.' : undefined}
          onClick={() => {
            if (window.confirm(`Close LOT ${lot.lotNo}? It can no longer take any stock movement.`)) closeMutation.mutate(lot.lotId)
          }}
          style={{ marginBottom: 12 }}
        >
          Close LOT
        </button>
      )}
      {closeMutation.isError && (
        <p style={{ color: '#dc2626', fontSize: 12 }}>{errorMessage(closeMutation.error, 'Failed to close LOT.')}</p>
      )}
      {lot.lotStatus === 'inspection_pending' && (
        <button type="button" disabled={releaseMutation.isPending} style={{ marginBottom: 12, marginLeft: 6 }}
          onClick={() => {
            releaseMutation.reset()
            releaseMutation.mutate(lot.lotId)
          }}>
          {releaseMutation.isPending ? 'Releasing...' : 'Release LOT'}
        </button>
      )}
      {lot.lotStatus === 'closed' && (
        <button type="button" disabled={reopenMutation.isPending} style={{ marginBottom: 12 }}
          onClick={() => {
            if (window.confirm(`Reopen LOT ${lot.lotNo}? It can take stock again.`)) reopenMutation.mutate(lot.lotId)
          }}>
          Reopen LOT
        </button>
      )}
      {releaseMutation.isError && (
        <p role="alert" style={{ color: '#dc2626', fontSize: 12 }}>{errorMessage(releaseMutation.error, 'Failed to release LOT.')}</p>
      )}
      {releaseMutation.data?.checkedStandards === 0 && (
        <p role="status" style={{ fontSize: 12 }}>
          Released without checks: this item has no required receipt standard. Add one under Quality → Inspection standards
          to check its next LOTs.
        </p>
      )}
      <LotReleaseHistory lotId={lot.lotId} />
      {reopenMutation.isError && (
        <p role="alert" style={{ color: '#dc2626', fontSize: 12 }}>{errorMessage(reopenMutation.error, 'Failed to reopen LOT.')}</p>
      )}

      <div role="tablist" style={{ display: 'flex', gap: 6, marginBottom: 8 }}>
        {(['backward', 'forward'] as const).map((value) => (
          <button
            key={value}
            type="button"
            role="tab"
            aria-selected={direction === value}
            onClick={() => setDirection(value)}
            style={{ fontWeight: direction === value ? 600 : 400, opacity: direction === value ? 1 : 0.65 }}
          >
            {value === 'backward' ? 'Made from' : 'Used in'}
          </button>
        ))}
      </div>
      {traceQuery.isLoading && <p style={{ fontSize: 12 }}>Tracing...</p>}
      {traceQuery.isError && <p style={{ color: '#dc2626', fontSize: 12 }}>{errorMessage(traceQuery.error, 'Trace failed.')}</p>}
      {traceQuery.data && traceQuery.data.nodes.length === 0 && (
        <p className="inspector-hint" style={{ fontSize: 12 }}>
          {direction === 'backward' ? 'No material LOTs recorded for this LOT.' : 'This LOT has not gone into any other LOT.'}
        </p>
      )}
      <ul style={{ listStyle: 'none', padding: 0, margin: 0, fontSize: 13 }}>
        {traceQuery.data?.nodes.map((node) => (
          <li key={`${node.lot.lotId}-${node.viaLotId}`} style={{ padding: '4px 0', paddingLeft: (node.depth - 1) * 16 }}>
            <span style={{ opacity: 0.5 }}>{direction === 'backward' ? '↳ ' : '→ '}</span>
            <code>{node.lot.lotNo}</code> {itemLabel.get(node.lot.itemId) ?? node.lot.itemId}
            <span style={{ color: STATUS_COLORS[node.lot.lotStatus], marginLeft: 6, fontSize: 12 }}>{node.lot.lotStatus}</span>
            {node.consumedQty !== null && (
              <span style={{ opacity: 0.6, fontSize: 12 }}> · used {formatQty(node.consumedQty)} {node.unit ?? ''}</span>
            )}
          </li>
        ))}
      </ul>

      <LotRecall key={lot.lotId} projectId={projectId} lot={lot} />

      <section aria-label="LOT quality" style={{ marginTop: 16, borderTop: '1px solid var(--border)', paddingTop: 12 }}>
        <h4 style={{ margin: '0 0 4px' }}>Quality</h4>
        <ReceiptChecklist projectId={projectId} lotId={lot.lotId} itemId={lot.itemId} />
        <QualitySection
          projectId={projectId}
          filter={{ lotId: lot.lotId }}
          targets={[{ key: `lot:${lot.lotId}`, itemId: lot.itemId, lotId: lot.lotId, direction: 'lot' }]}
          targetLabel={() => lot.lotNo}
          productionRunId={null}
          itemLabel={(itemId) => itemLabel.get(itemId) ?? itemId}
          emptyHint=""
          stock={inventoriesQuery.data ?? []}
        />
      </section>
    </>
  )
}
