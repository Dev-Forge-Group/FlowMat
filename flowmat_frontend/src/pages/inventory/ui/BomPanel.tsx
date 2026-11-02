import { useMemo, useState, type FormEvent } from 'react'
import {
  useBomActionMutation,
  useBomBuildableQuery,
  useBomLineMutations,
  useBomRequirementsQuery,
  useBomsQuery,
  useBuildableBomsQuery,
  useCreateBomMutation,
  type BomAction,
} from '../../../entities/bom/api/useBoms'
import type { BomDto, BomLineType, BuildableQuantityDto, ItemDto, UnitDto } from '../../../shared/types/api'
import { errorMessage, errorStatus } from '../../../shared/lib/errorMessage'
import { formatQty } from '../../../shared/lib/formatQty'
import {
  BOM_ACTION_LABELS, bomActions, groupByTarget, isEditable, overlappingApproved, periodLabel, replacementEndDay, scrapLabel,
  scrapRateFromPercent, substituteLabel, substituteTargets,
} from '../model/bomModel'
import { shortBy, usableByMaterial } from '../model/buildableModel'
import { BomRevisionCompare } from './BomRevisionCompare'
import { BomLineImport } from './BomLineImport'
import { BomCopyForm } from './BomCopyForm'
import { BomPeriodsPanel } from './BomPeriodsPanel'
import { BomWhereUsed } from './BomWhereUsed'
import { BomCostRollup } from './BomCostRollup'
import { BomExplosion } from './BomExplosion'
import { BomScrapPerformance } from './BomScrapPerformance'
import { hasSubAssemblies, subAssemblyIds } from '../model/bomExplosionModel'
import { LINE_TYPE_OPTIONS, lineSummary, lineTypeTag } from '../model/bomLineTypeModel'
import { ItemScanInput } from './ItemScanInput'
import { pickableItems } from '../model/itemStatusModel'

const STATUS_COLORS: Record<BomDto['bomStatus'], string> = {
  draft: '#64748b',
  pending_approval: '#b45309',
  approved: '#15803d',
  retired: '#9ca3af',
}

const cell = { padding: '6px 6px' } as const

/** What an approved BOM could make from usable stock now; empty for other revisions. */
function BuildableCell({ entry, itemLabel }: { entry: BuildableQuantityDto | undefined; itemLabel: Map<string, string> }) {
  if (!entry) return null
  if (entry.problem) {
    return <span style={{ color: '#b45309' }} title={entry.problem}>can't tell</span>
  }
  if (entry.buildable == null) return null
  const limiting = entry.limitingItemId ? itemLabel.get(entry.limitingItemId) ?? entry.limitingItemId : null
  return (
    <span
      style={{ color: Number(entry.buildable) > 0 ? undefined : '#dc2626' }}
      title={limiting ? `${limiting} runs out first` : undefined}
    >
      can make {formatQty(entry.buildable)} {entry.targetUnit}
    </span>
  )
}

export function BomPanel({ projectId, items, units }: { projectId: string; items: ItemDto[]; units: UnitDto[] }) {
  const bomsQuery = useBomsQuery(projectId)
  const boms = useMemo(() => bomsQuery.data ?? [], [bomsQuery.data])
  const groups = useMemo(() => groupByTarget(boms), [boms])
  const subAssemblies = useMemo(() => subAssemblyIds(boms), [boms])
  const itemLabel = useMemo(() => new Map(items.map((item) => [item.itemId, `${item.itemCode} · ${item.itemName}`])), [items])
  const unitCodes = useMemo(() => units.filter((unit) => unit.activeYn === 'Y').map((unit) => unit.unitCode), [units])
  const unitCodeById = useMemo(() => new Map(units.map((unit) => [unit.unitId, unit.unitCode])), [units])

  const [selectedId, setSelectedId] = useState<string | null>(null)
  const selected = boms.find((bom) => bom.bomId === selectedId) ?? null
  const buildableQuery = useBuildableBomsQuery(projectId)
  const buildableById = useMemo(
    () => new Map((buildableQuery.data ?? []).map((entry) => [entry.bomId, entry])),
    [buildableQuery.data],
  )

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 380px', gap: 24, alignItems: 'start' }}>
      <section>
        {bomsQuery.isLoading && <p>Loading BOMs...</p>}
        {bomsQuery.isError && <p style={{ color: '#dc2626' }}>{errorMessage(bomsQuery.error, 'Failed to load BOMs.')}</p>}
        {!bomsQuery.isLoading && boms.length === 0 && (
          <p className="inspector-hint">No BOMs yet. Create one for a product from the form on the right.</p>
        )}
        {[...groups.entries()].map(([targetItemId, revisions]) => (
          <div key={targetItemId} style={{ marginBottom: 16 }}>
            <h4 style={{ margin: '0 0 6px' }}>{itemLabel.get(targetItemId) ?? targetItemId}</h4>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
              <tbody>
                {revisions.map((bom) => (
                  <tr
                    key={bom.bomId}
                    onClick={() => setSelectedId(bom.bomId)}
                    style={{
                      borderBottom: '1px solid var(--border)',
                      cursor: 'pointer',
                      background: bom.bomId === selectedId ? 'var(--accent-bg)' : undefined,
                    }}
                  >
                    <td style={cell}>
                      <code>v{bom.bomVersion}</code>
                      {(bom.effectiveFrom || bom.effectiveTo) && (
                        <span style={{ fontSize: 11, opacity: 0.7 }}> {periodLabel(bom)}</span>
                      )}
                    </td>
                    <td style={cell}>{bom.bomName}</td>
                    <td style={cell}>
                      {formatQty(bom.baseQuantity)} {bom.baseUnit}
                    </td>
                    <td style={cell}>{lineSummary(bom.lines)}</td>
                    <td style={{ ...cell, fontSize: 12 }}>
                      <BuildableCell entry={buildableById.get(bom.bomId)} itemLabel={itemLabel} />
                    </td>
                    <td style={{ ...cell, color: STATUS_COLORS[bom.bomStatus], fontWeight: 600 }}>
                      {bom.bomStatus.replace('_', ' ')}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ))}
        <BomWhereUsed projectId={projectId} items={items} onOpen={setSelectedId} />
        <BomCostRollup projectId={projectId} onOpen={setSelectedId} />
      </section>

      <section style={{ border: '1px solid var(--border)', borderRadius: 12, padding: 18 }}>
        {selected ? (
          <BomDetail
            key={selected.bomId}
            projectId={projectId}
            bom={selected}
            revisions={(groups.get(selected.targetItemId) ?? []).filter((bom) => bom.bomId !== selected.bomId)}
            productsWithBom={new Set(groups.keys())}
            subAssemblies={subAssemblies}
            items={items}
            itemLabel={itemLabel}
            unitCodes={unitCodes}
            unitCodeById={unitCodeById}
            onClose={() => setSelectedId(null)}
            onRevisionCreated={setSelectedId}
          />
        ) : (
          <CreateBomForm
            projectId={projectId}
            items={items}
            unitCodes={unitCodes}
            unitCodeById={unitCodeById}
            existing={groups}
            onCreated={setSelectedId}
          />
        )}
      </section>
      <BomPeriodsPanel projectId={projectId} boms={boms} />
    </div>
  )
}

function CreateBomForm({
  projectId,
  items,
  unitCodes,
  unitCodeById,
  existing,
  onCreated,
}: {
  projectId: string
  items: ItemDto[]
  unitCodes: string[]
  unitCodeById: Map<string, string>
  existing: Map<string, BomDto[]>
  onCreated: (bomId: string) => void
}) {
  const createMutation = useCreateBomMutation(projectId)
  const [form, setForm] = useState({ targetItemId: '', bomName: '', baseQuantity: '1', baseUnit: unitCodes[0] ?? 'ea' })
  // A product with a BOM gets new revisions from that BOM instead of a second BOM.
  const candidates = pickableItems(items).filter((item) => !existing.has(item.itemId))

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    try {
      const created = await createMutation.mutateAsync({
        targetItemId: form.targetItemId,
        bomName: form.bomName.trim(),
        baseQuantity: Number(form.baseQuantity),
        baseUnit: form.baseUnit,
      })
      onCreated(created.bomId)
    } catch {
      // Shown below the form.
    }
  }

  return (
    <>
      <h3 style={{ marginTop: 0 }}>New BOM</h3>
      <form onSubmit={(e) => void handleSubmit(e)} style={{ display: 'grid', gap: 10 }}>
        <label style={{ display: 'grid', gap: 4 }}>
          <span>Product *</span>
          <select
            value={form.targetItemId}
            onChange={(e) => {
              const unitId = items.find((item) => item.itemId === e.target.value)?.unitId
              const code = unitId ? unitCodeById.get(unitId) : undefined
              setForm((f) => ({ ...f, targetItemId: e.target.value, baseUnit: code ?? f.baseUnit }))
            }}
            required
          >
            <option value="" disabled>Select the item this BOM produces</option>
            {candidates.map((item) => (
              <option key={item.itemId} value={item.itemId}>{item.itemCode} · {item.itemName}</option>
            ))}
          </select>
        </label>
        <label style={{ display: 'grid', gap: 4 }}>
          <span>Name *</span>
          <input value={form.bomName} onChange={(e) => setForm((f) => ({ ...f, bomName: e.target.value }))} required />
        </label>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 100px', gap: 8 }}>
          <label style={{ display: 'grid', gap: 4 }}>
            <span>Makes *</span>
            <input
              type="number"
              min="0"
              step="any"
              value={form.baseQuantity}
              onChange={(e) => setForm((f) => ({ ...f, baseQuantity: e.target.value }))}
              required
            />
          </label>
          <label style={{ display: 'grid', gap: 4 }}>
            <span>Unit</span>
            <select value={form.baseUnit} onChange={(e) => setForm((f) => ({ ...f, baseUnit: e.target.value }))}>
              {unitCodes.map((code) => <option key={code} value={code}>{code}</option>)}
            </select>
          </label>
        </div>
        <span style={{ fontSize: 11, opacity: 0.6 }}>
          Material quantities are per this amount of product. Only drafts can be edited; approval freezes a revision.
        </span>
        <button type="submit" disabled={createMutation.isPending || !form.targetItemId}>
          {createMutation.isPending ? 'Creating...' : 'Create draft'}
        </button>
        {createMutation.isError && (
          <p style={{ color: '#dc2626', fontSize: 12, margin: 0 }}>{errorMessage(createMutation.error, 'Failed to create BOM.')}</p>
        )}
      </form>
    </>
  )
}

function BomDetail({
  projectId,
  bom,
  revisions,
  productsWithBom,
  subAssemblies,
  items,
  itemLabel,
  unitCodes,
  unitCodeById,
  onClose,
  onRevisionCreated,
}: {
  projectId: string
  bom: BomDto
  /** The product's other revisions, to compare with. */
  revisions: BomDto[]
  /** Products that already have a BOM, which a copy cannot go to. */
  productsWithBom: Set<string>
  /** Items with an approved BOM of their own; as materials they are sub-assemblies (docs/domain/multi-level-bom.md). */
  subAssemblies: Set<string>
  items: ItemDto[]
  itemLabel: Map<string, string>
  unitCodes: string[]
  unitCodeById: Map<string, string>
  onClose: () => void
  onRevisionCreated: (bomId: string) => void
}) {
  const { add, remove } = useBomLineMutations(projectId)
  const actionMutation = useBomActionMutation(projectId)
  const [endEarlier, setEndEarlier] = useState(false)
  const editable = isEditable(bom)
  const [line, setLine] = useState({
    childItemId: '', quantity: '', unit: unitCodes[0] ?? 'kg', lineType: 'material' as BomLineType, phantom: false, scrap: '',
    substituteFor: '', optional: false,
  })
  const [scrapError, setScrapError] = useState<string | null>(null)
  // Only a material with its own approved BOM can be a phantom (docs/domain/multi-level-bom.md P1).
  const canBePhantom = line.lineType === 'material' && subAssemblies.has(line.childItemId)
  // A plain material may stand in for one already on the BOM (ADR-032 decision 3).
  const targets = substituteTargets(bom.lines)
  const canBeSubstitute = line.lineType === 'material' && !(canBePhantom && line.phantom) && targets.length > 0
    && !line.optional
  const label = (itemId: string) => itemLabel.get(itemId) ?? itemId
  const [productionQty, setProductionQty] = useState(String(bom.baseQuantity))
  const requirementsQuery = useBomRequirementsQuery(bom.bomStatus === 'draft' ? null : bom.bomId, Number(productionQty))
  const buildableQuery = useBomBuildableQuery(projectId, bom.bomStatus === 'draft' ? null : bom.bomId)
  const buildable = buildableQuery.data
  const usable = useMemo(() => usableByMaterial(buildable), [buildable])

  async function handleAddLine(e: FormEvent) {
    e.preventDefault()
    // Only a material plans scrap (ADR-031 decision 3).
    const scrap = scrapRateFromPercent(line.lineType === 'material' ? line.scrap : '')
    setScrapError(scrap.ok ? null : scrap.error)
    if (!scrap.ok) return
    try {
      await add.mutateAsync({
        bomId: bom.bomId, childItemId: line.childItemId, quantity: Number(line.quantity), unit: line.unit, lineType: line.lineType,
        phantom: canBePhantom && line.phantom ? true : undefined, scrapRate: scrap.rate,
        substituteFor: canBeSubstitute && line.substituteFor ? line.substituteFor : undefined,
        optionalYn: line.lineType === 'material' && line.optional && !(canBeSubstitute && line.substituteFor) ? 'Y' : undefined,
      })
      setLine((l) => ({ ...l, childItemId: '', quantity: '', phantom: false, scrap: '', substituteFor: '', optional: false }))
    } catch {
      // Shown below.
    }
  }

  async function handleAction(action: BomAction) {
    let note: string | undefined
    if (action === 'reject') {
      const reason = window.prompt('Why is this BOM rejected?')
      if (!reason) return
      note = reason
    }
    if (action === 'retire' && !window.confirm('Retire this revision? New production runs will no longer use it.')) return
    try {
      const result = await actionMutation.mutateAsync({ bomId: bom.bomId, action, note,
        endEarlier: action === 'approve' && endDay !== null && endEarlier })
      if (action === 'revisions') onRevisionCreated(result.bomId)
    } catch {
      // Shown below.
    }
  }

  const error = add.error ?? remove.error ?? actionMutation.error
  // Approval keeps other approved revisions and refuses an overlap (docs/domain/multi-level-bom.md M1-M2).
  const overlaps = bom.bomStatus === 'draft' || bom.bomStatus === 'pending_approval' ? overlappingApproved(bom, revisions) : []
  // The replacement helper: approve and end those revisions the day before this one starts, when that fits (M4-M6).
  const endDay = bom.bomStatus === 'pending_approval' ? replacementEndDay(bom, overlaps) : null
  const revisionStatus = errorStatus(actionMutation.error)
  const revisionUnconfirmed = actionMutation.variables?.action === 'revisions' && Boolean(actionMutation.error)
    && !(revisionStatus != null && revisionStatus >= 400 && revisionStatus < 500)

  return (
    <>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
        <h3 style={{ marginTop: 0 }}>
          {bom.bomName} <code>v{bom.bomVersion}</code>
        </h3>
        <button type="button" onClick={onClose} style={{ background: 'transparent', fontSize: 12 }}>New BOM</button>
      </div>
      <p style={{ margin: '0 0 8px', fontSize: 13 }}>
        Makes {formatQty(bom.baseQuantity)} {bom.baseUnit} of {itemLabel.get(bom.targetItemId) ?? bom.targetItemId} ·{' '}
        <strong style={{ color: STATUS_COLORS[bom.bomStatus] }}>{bom.bomStatus.replace('_', ' ')}</strong>
      </p>
      {bom.approvedBy && (
        <p style={{ margin: '0 0 8px', fontSize: 12, opacity: 0.7 }}>
          Approved by {bom.approvedBy}{bom.approvedAt ? ` on ${new Date(bom.approvedAt).toLocaleString()}` : ''}
        </p>
      )}
      {bom.note && <p style={{ margin: '0 0 8px', fontSize: 12, whiteSpace: 'pre-line', opacity: 0.8 }}>{bom.note}</p>}

      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13, marginBottom: 10 }}>
        <tbody>
          {bom.lines.length === 0 && (
            <tr><td style={{ ...cell, opacity: 0.6 }}>No materials yet.</td></tr>
          )}
          {bom.lines.map((l) => (
            <tr key={l.bomLineId} style={{ borderBottom: '1px solid var(--border)' }}>
              <td style={cell}>
                {itemLabel.get(l.childItemId) ?? l.childItemId}
                {subAssemblies.has(l.childItemId) && !l.phantom && <span className="inspector-hint"> · has its own BOM</span>}
                {l.phantom && <span className="inspector-hint"> · phantom: its own BOM's materials are used</span>}
                {lineTypeTag(l.lineType) && <span className="inspector-hint"> · {lineTypeTag(l.lineType)}</span>}
                {scrapLabel(l.scrapRate) && <span className="inspector-hint"> · {scrapLabel(l.scrapRate)}</span>}
                {l.substituteFor && <span className="inspector-hint"> · {substituteLabel(l, bom.lines, label)}</span>}
                {l.optionalYn === 'Y' && <span className="inspector-hint"> · optional, per work order</span>}
              </td>
              <td style={{ ...cell, textAlign: 'right' }}>{formatQty(l.quantity)} {l.unit}</td>
              {editable && (
                <td style={{ ...cell, width: 1 }}>
                  <button
                    type="button"
                    onClick={() => remove.mutate({ bomId: bom.bomId, bomLineId: l.bomLineId })}
                    style={{ fontSize: 11 }}
                    aria-label="Remove material"
                  >
                    ✕
                  </button>
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>

      {editable && (
        <form onSubmit={(e) => void handleAddLine(e)} style={{ display: 'grid', gridTemplateColumns: '1fr 70px 64px 76px auto', gap: 6, marginBottom: 12 }}>
          <ItemScanInput
            items={pickableItems(items).filter((item) => item.itemId !== bom.targetItemId)}
            style={{ gridColumn: '1 / -1', fontSize: 12 }}
            onPick={(item) => {
              const code = item.unitId ? unitCodeById.get(item.unitId) : undefined
              setLine((l) => ({ ...l, childItemId: item.itemId, unit: code ?? l.unit }))
            }}
          />
          <select
            aria-label="Line type"
            value={line.lineType}
            onChange={(e) => setLine((l) => ({ ...l, lineType: e.target.value as BomLineType }))}
            style={{ gridColumn: '1 / -1', fontSize: 12 }}
          >
            {LINE_TYPE_OPTIONS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
          </select>
          <select
            aria-label="Material"
            value={line.childItemId}
            onChange={(e) => {
              // Default the unit to the material's own stock unit.
              const unitId = items.find((item) => item.itemId === e.target.value)?.unitId
              const code = unitId ? unitCodeById.get(unitId) : undefined
              setLine((l) => ({ ...l, childItemId: e.target.value, unit: code ?? l.unit }))
            }}
            required
          >
            <option value="" disabled>Material</option>
            {pickableItems(items)
              .filter((item) => item.itemId !== bom.targetItemId)
              .map((item) => <option key={item.itemId} value={item.itemId}>{item.itemCode} · {item.itemName}</option>)}
          </select>
          {canBePhantom && (
            <label style={{ gridColumn: '1 / -1', fontSize: 12, display: 'flex', gap: 6, alignItems: 'center' }}>
              <input type="checkbox" checked={line.phantom} onChange={(e) => setLine((l) => ({ ...l, phantom: e.target.checked }))} />
              Phantom: use its own BOM's materials instead of stocking it
            </label>
          )}
          {line.lineType === 'material' && !line.substituteFor && (
            <label style={{ gridColumn: '1 / -1', fontSize: 12, display: 'flex', gap: 6, alignItems: 'center' }}>
              <input type="checkbox" checked={line.optional} onChange={(e) => setLine((l) => ({ ...l, optional: e.target.checked }))} />
              Optional: planned only for work orders that pick it
            </label>
          )}
          {canBeSubstitute && (
            <select
              aria-label="Substitute for"
              title="Approved in place of a material already on the BOM; the plan keeps using that material"
              value={line.substituteFor}
              onChange={(e) => setLine((l) => ({ ...l, substituteFor: e.target.value }))}
              style={{ gridColumn: '1 / -1', fontSize: 12 }}
            >
              <option value="">Main material (not a substitute)</option>
              {targets.map((target) => (
                <option key={target.bomLineId} value={target.bomLineId}>Substitute for {label(target.childItemId)}</option>
              ))}
            </select>
          )}
          <input
            type="number"
            min="0"
            step="any"
            placeholder="Qty"
            aria-label="Material quantity"
            value={line.quantity}
            onChange={(e) => setLine((l) => ({ ...l, quantity: e.target.value }))}
            required
          />
          <select aria-label="Material unit" value={line.unit} onChange={(e) => setLine((l) => ({ ...l, unit: e.target.value }))}>
            {unitCodes.map((code) => <option key={code} value={code}>{code}</option>)}
          </select>
          <input
            type="number"
            min="0"
            max="100"
            step="any"
            placeholder="Scrap %"
            aria-label="Scrap percent"
            title={line.lineType === 'material'
              ? 'Planned scrap on top of the quantity, 0 to 100%'
              : 'Only a material line plans scrap'}
            value={line.lineType === 'material' ? line.scrap : ''}
            disabled={line.lineType !== 'material'}
            onChange={(e) => setLine((l) => ({ ...l, scrap: e.target.value }))}
          />
          <button type="submit" disabled={add.isPending}>Add</button>
        </form>
      )}
      {editable && scrapError && <p role="alert" style={{ color: '#dc2626', fontSize: 12, margin: '0 0 8px' }}>{scrapError}</p>}
      {editable && <BomLineImport projectId={projectId} bomId={bom.bomId} />}

      {overlaps.length > 0 && (
        <p data-testid="bom-period-overlap" style={{ fontSize: 12, color: '#b45309', margin: '0 0 8px' }}>
          Its effective period ({periodLabel(bom)}) overlaps approved{' '}
          {overlaps.map((other) => `v${other.bomVersion} (${periodLabel(other)})`).join(', ')}. Approving does not retire
          them: end their period or retire them, and give this revision its own, before approving.
        </p>
      )}
      {endDay && (
        <label style={{ display: 'flex', gap: 6, alignItems: 'center', fontSize: 12, margin: '0 0 8px' }}>
          <input type="checkbox" checked={endEarlier} onChange={(event) => setEndEarlier(event.target.checked)} />
          End {overlaps.map((other) => `v${other.bomVersion}`).join(', ')} on {endDay} when approving
        </label>
      )}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        {bomActions(bom.bomStatus).map((action) => (
          <button key={action} type="button" disabled={actionMutation.isPending} onClick={() => void handleAction(action)}>
            {BOM_ACTION_LABELS[action]}
          </button>
        ))}
      </div>
      {error && <p style={{ color: '#dc2626', fontSize: 12, margin: '8px 0 0' }}>{errorMessage(error, 'The BOM change failed.')}</p>}
      {revisionUnconfirmed && (
        <p role="status" style={{ fontSize: 12, margin: '8px 0 0' }}>
          Revision creation is unconfirmed. Retry New revision on this source to recover the same revision.
        </p>
      )}

      {bom.bomStatus !== 'draft' && (
        <div style={{ marginTop: 16, borderTop: '1px solid var(--border)', paddingTop: 12 }}>
          {buildable && buildable.buildable != null && (
            <p style={{ margin: '0 0 8px', fontSize: 13 }} data-testid="bom-buildable">
              Can make now:{' '}
              <strong style={{ color: Number(buildable.buildable) > 0 ? undefined : '#dc2626' }}>
                {formatQty(buildable.buildable)} {buildable.targetUnit}
              </strong>
              {buildable.limitingItemId && (
                <span style={{ opacity: 0.75 }}> · {itemLabel.get(buildable.limitingItemId) ?? buildable.limitingItemId} runs out first</span>
              )}
              {Number(buildable.buildable) > 0 && (
                <button
                  type="button"
                  onClick={() => setProductionQty(String(buildable.buildable))}
                  style={{ fontSize: 11, marginLeft: 8 }}
                  title="Work out the materials for this quantity"
                >
                  Use
                </button>
              )}
            </p>
          )}
          {buildableQuery.isError && (
            <p style={{ color: '#dc2626', fontSize: 12, margin: '0 0 8px' }}>
              {errorMessage(buildableQuery.error, 'Could not work out what the stock can make.')}
            </p>
          )}
          <label style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 13 }}>
            <span>Materials needed to make</span>
            <input
              type="number"
              min="0"
              step="any"
              value={productionQty}
              onChange={(e) => setProductionQty(e.target.value)}
              style={{ width: 90 }}
            />
          </label>
          {requirementsQuery.isError && (
            <p style={{ color: '#dc2626', fontSize: 12 }}>{errorMessage(requirementsQuery.error, 'Could not calculate.')}</p>
          )}
          {requirementsQuery.data && (
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13, marginTop: 6 }}>
              <tbody>
                {requirementsQuery.data.lines.map((r) => {
                  const have = usable.get(r.childItemId)
                  const short = shortBy(r.requiredItemQuantity, have)
                  return (
                    <tr key={r.bomLineId} style={{ borderBottom: '1px solid var(--border)' }}>
                      <td style={cell}>
                        {itemLabel.get(r.childItemId) ?? r.childItemId}
                        {r.viaItemId && <span className="inspector-hint"> · via {itemLabel.get(r.viaItemId) ?? r.viaItemId}</span>}
                        {scrapLabel(r.scrapRate) && <span className="inspector-hint"> · incl. {scrapLabel(r.scrapRate)}</span>}
                        {(r.substitutes ?? []).map((s) => (
                          <span key={s.bomLineId} className="inspector-hint" style={{ display: 'block' }}>
                            or {label(s.childItemId)} {formatQty(s.requiredItemQuantity)} {s.itemUnit}
                            {s.lineCost != null && ` (cost ${formatQty(s.lineCost)})`}
                          </span>
                        ))}
                      </td>
                      <td style={{ ...cell, textAlign: 'right' }}>
                        <strong>{formatQty(r.requiredItemQuantity)} {r.itemUnit}</strong>
                        {r.lineUnit !== r.itemUnit && (
                          <span style={{ opacity: 0.6 }}> ({formatQty(r.requiredQuantity)} {r.lineUnit})</span>
                        )}
                      </td>
                      <td style={{ ...cell, textAlign: 'right', fontSize: 12 }}>
                        {have !== undefined && (
                          <span style={{ color: short ? '#dc2626' : undefined, opacity: short ? 1 : 0.7 }}>
                            {formatQty(have)} in stock{short ? ` · ${formatQty(short)} short` : ''}
                          </span>
                        )}
                      </td>
                      <td style={{ ...cell, textAlign: 'right', opacity: r.lineCost == null ? 0.5 : 1 }}>
                        {r.lineCost == null ? 'no cost' : formatQty(r.lineCost)}
                      </td>
                    </tr>
                  )
                })}
                <tr>
                  <td style={cell}><strong>Material cost</strong></td>
                  <td style={cell} />
                  <td style={cell} />
                  <td style={{ ...cell, textAlign: 'right' }}>
                    <strong>{formatQty(requirementsQuery.data.materialCost ?? 0)}</strong>
                    {requirementsQuery.data.costComplete === false && (
                      <span style={{ display: 'block', fontSize: 11, color: '#b45309' }}>some materials have no unit cost</span>
                    )}
                  </td>
                </tr>
              </tbody>
            </table>
          )}
          {(requirementsQuery.data?.optionals ?? []).length > 0 && (
            <table aria-label="Optional, per work order" style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13, marginTop: 6 }}>
              <tbody>
                {(requirementsQuery.data?.optionals ?? []).map((optional) => (
                  <tr key={optional.bomLineId} style={{ borderBottom: '1px solid var(--border)' }}>
                    <td style={cell}>
                      {label(optional.childItemId)}
                      <span className="inspector-hint"> · optional, only for work orders that pick it</span>
                    </td>
                    <td style={{ ...cell, textAlign: 'right' }}>
                      + {formatQty(optional.requiredItemQuantity)} {optional.itemUnit}
                      {optional.lineCost != null && ` (cost ${formatQty(optional.lineCost)})`}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {(requirementsQuery.data?.outputs ?? []).length > 0 && (
            <table aria-label="Comes out of the batch" style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13, marginTop: 6 }}>
              <tbody>
                {(requirementsQuery.data?.outputs ?? []).map((output) => (
                  <tr key={output.bomLineId} style={{ borderBottom: '1px solid var(--border)' }}>
                    <td style={cell}>
                      {itemLabel.get(output.itemId) ?? output.itemId}
                      <span className="inspector-hint"> · {lineTypeTag(output.lineType)}</span>
                    </td>
                    <td style={{ ...cell, textAlign: 'right' }}>comes out {formatQty(output.itemQuantity)} {output.itemUnit}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {bom.bomStatus === 'approved' && hasSubAssemblies(bom, subAssemblies) && Number(productionQty) > 0 && (
            <BomExplosion bomId={bom.bomId} quantity={Number(productionQty)} />
          )}
          {(bom.bomStatus === 'approved' || bom.bomStatus === 'retired') && (
            <BomScrapPerformance projectId={projectId} bomId={bom.bomId} itemLabel={itemLabel} />
          )}
        </div>
      )}
      <div style={{ marginTop: 12 }}>
        <BomCopyForm projectId={projectId} bom={bom} items={items} productsWithBom={productsWithBom} onCopied={onRevisionCreated} />
      </div>
      <BomRevisionCompare
        bom={bom}
        revisions={revisions}
        itemLabel={itemLabel}
        productUnit={(() => {
          const unitId = items.find((item) => item.itemId === bom.targetItemId)?.unitId
          return unitId ? unitCodeById.get(unitId) : undefined
        })()}
      />
    </>
  )
}
