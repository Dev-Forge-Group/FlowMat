import { useRef, useState, type FormEvent, type SetStateAction } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import './inventory.css'
import { useItemsQuery } from '../../../entities/catalog/api/useItemsQuery'
import { useCreateItemMutation } from '../../../entities/catalog/api/useCreateItemMutation'
import { useDeleteItemMutation } from '../../../entities/catalog/api/useDeleteItemMutation'
import { useUpdateItemMutation } from '../../../entities/catalog/api/useUpdateItemMutation'
import { useUnitsQuery } from '../../../entities/catalog/api/useUnitsQuery'
import { useMyPermissionsQuery } from '../../../entities/auth/api/useMyPermissionsQuery'
import type { ItemDto } from '../../../shared/types/api'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { StockPanel } from './StockPanel'
import { CountPanel } from './CountPanel'
import { CountPlansPanel } from './CountPlansPanel'
import { CountHistory } from './CountHistory'
import { MovementsTab } from './MovementsTab'
import { UnitsPanel } from './UnitsPanel'
import { LotPanel } from './LotPanel'
import { BomPanel } from './BomPanel'
import { EquipmentPanel } from './EquipmentPanel'
import { LocationsPanel } from './LocationsPanel'
import { WarehouseTasksPanel } from './WarehouseTasksPanel'
import { PurchaseOrdersPanel } from './PurchaseOrdersPanel'
import { SuppliersPanel } from './SuppliersPanel'
import { QualityPanel } from './QualityPanel'
import { ItemCsvPanel } from './ItemCsvPanel'
import { ItemDetail } from './ItemDetail'
import { ItemInfoFields } from './ItemInfoFields'
import { EMPTY_ITEM_INFO, itemInfoForm, itemInfoPayload } from '../model/itemInfoModel'
import { EMPTY_ITEM_FILTER, duplicateCodes, filterItems, type ItemFilter } from '../model/listFilterModel'
import { StockAnalysisPanel } from './StockAnalysisPanel'
import { StockTransfersPanel } from './StockTransfersPanel'
import { StockValueTrend } from './StockValueTrend'
import { StockWastePanel } from './StockWastePanel'
import { WorkInstructionsPanel } from './WorkInstructionsPanel'

const TABS = ['items', 'stock', 'tasks', 'purchasing', 'count', 'count-plans', 'movements', 'analysis', 'lots', 'quality', 'boms', 'instructions', 'units', 'equipment',
  'locations'] as const
type Tab = (typeof TABS)[number]
const TAB_LABELS: Record<Tab, string> = {
  items: 'Items',
  stock: 'Stock',
  tasks: 'Tasks',
  purchasing: 'Purchasing',
  count: 'Count',
  'count-plans': 'Count plans',
  movements: 'Movements',
  analysis: 'Analysis',
  lots: 'LOTs',
  quality: 'Quality',
  boms: 'BOMs',
  instructions: 'Instructions',
  units: 'Units',
  equipment: 'Equipment',
  locations: 'Locations',
}

const ITEM_TYPE_SUGGESTIONS = ['generic', 'raw_material', 'component', 'semi_finished', 'finished_good', 'consumable']

const RESOURCE_CATEGORIES = ['material', 'labor', 'energy', 'equipment', 'other']
const ITEM_STATUSES = ['active', 'inactive', 'discontinued']

export function InventoryRoute() {
  const { projectId = '' } = useParams<{ projectId: string }>()
  const [searchParams, setSearchParams] = useSearchParams()
  const [itemFilter, setItemFilter] = useState<ItemFilter>(EMPTY_ITEM_FILTER)
  const requestedTab = searchParams.get('tab')
  const tab: Tab = (TABS as readonly string[]).includes(requestedTab ?? '') ? (requestedTab as Tab) : 'items'
  const itemsQuery = useItemsQuery(projectId)
  const items = itemsQuery.data ?? []
  // Include inactive units so items that still reference one keep a readable label.
  const units = useUnitsQuery(true).data ?? []
  const unitLabel = new Map(units.map((unit) => [unit.unitId, unit.unitCode]))
  const canManageMasterData = useMyPermissionsQuery().data?.canManageMasterData ?? false
  const shownItems = filterItems(items, itemFilter)

  const createMutation = useCreateItemMutation()
  const updateMutation = useUpdateItemMutation()
  const deleteMutation = useDeleteItemMutation(projectId)

  const [editingItem, setEditingItem] = useState<ItemDto | null>(null)
  const [detailItemId, setDetailItemId] = useState<string | null>(null)
  // Derived after the state it reads: a callback in .find runs at once, so declaration order matters here.
  const detailItem = items.find((item) => item.itemId === detailItemId) ?? null
  const EMPTY_ITEM_FORM = {
    itemCode: '',
    itemName: '',
    itemType: 'generic',
    resourceCategory: 'material',
    unitId: '',
    itemStatus: 'active',
    lotManageYn: false,
    lotReleaseRequiredYn: false,
    safetyStockQty: '',
    leadTimeDays: '',
    unitCost: '',
    purchaseUnit: '',
    purchaseUnitQty: '',
    details: EMPTY_ITEM_INFO,
  }
  const [form, updateForm] = useState(EMPTY_ITEM_FORM)
  const formRevision = useRef(0)

  function setForm(next: SetStateAction<typeof EMPTY_ITEM_FORM>) {
    formRevision.current += 1
    updateForm(next)
  }

  function resetForm() {
    setForm(EMPTY_ITEM_FORM)
    setEditingItem(null)
    createMutation.reset()
    updateMutation.reset()
  }

  function startEdit(item: ItemDto) {
    setEditingItem(item)
    setForm({
      itemCode: item.itemCode,
      itemName: item.itemName,
      itemType: item.itemType ?? 'generic',
      resourceCategory: item.resourceCategory ?? 'material',
      unitId: item.unitId ?? '',
      itemStatus: item.itemStatus,
      lotManageYn: item.lotManageYn === 'Y',
      lotReleaseRequiredYn: item.lotReleaseRequiredYn === 'Y',
      safetyStockQty: item.safetyStockQty ? String(item.safetyStockQty) : '',
      leadTimeDays: item.leadTimeDays != null ? String(item.leadTimeDays) : '',
      unitCost: item.unitCost ? String(item.unitCost) : '',
      purchaseUnit: item.purchaseUnit ?? '',
      purchaseUnitQty: item.purchaseUnitQty != null ? String(item.purchaseUnitQty) : '',
      details: itemInfoForm(item.details),
    })
  }

  /** Blank safety stock or unit cost means "none" (0 when editing); blank lead time is left out. */
  function reorderFields(creating: boolean) {
    const safety = form.safetyStockQty.trim()
    const lead = form.leadTimeDays.trim()
    const cost = form.unitCost.trim()
    const packUnit = form.purchaseUnit.trim()
    const packQty = form.purchaseUnitQty.trim()
    return {
      safetyStockQty: safety ? Number(safety) : creating ? undefined : 0,
      leadTimeDays: lead ? Number(lead) : undefined,
      unitCost: cost ? Number(cost) : creating ? undefined : 0,
      // Empty when editing goes back to buying in the stock unit.
      purchaseUnit: packUnit || (creating ? undefined : ''),
      purchaseUnitQty: packUnit && packQty ? Number(packQty) : undefined,
    }
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    const submittedRevision = formRevision.current
    try {
      if (editingItem) {
        await updateMutation.mutateAsync({
          itemId: editingItem.itemId,
          projectId,
          itemName: form.itemName,
          itemType: form.itemType.trim() || undefined,
          resourceCategory: form.resourceCategory,
          unitId: form.unitId,
          itemStatus: form.itemStatus,
          lotManageYn: form.lotManageYn ? 'Y' : 'N',
          lotReleaseRequiredYn: form.lotManageYn && form.lotReleaseRequiredYn ? 'Y' : 'N',
          itemCode: form.itemCode.trim(),
          ...reorderFields(false),
          details: itemInfoPayload(form.details),
        })
      } else {
        await createMutation.mutateAsync({
          projectId,
          itemCode: form.itemCode,
          itemName: form.itemName,
          itemType: form.itemType.trim() || undefined,
          resourceCategory: form.resourceCategory,
          unitId: form.unitId || undefined,
          itemStatus: form.itemStatus,
          lotManageYn: form.lotManageYn ? 'Y' : 'N',
          lotReleaseRequiredYn: form.lotManageYn && form.lotReleaseRequiredYn ? 'Y' : 'N',
          ...reorderFields(true),
          details: itemInfoPayload(form.details),
        })
      }
      if (formRevision.current === submittedRevision) resetForm()
    } catch {
      // Surfaced through the mutation error state below the form.
    }
  }

  function handleDelete(itemId: string) {
    if (!window.confirm('Delete this item?')) return
    deleteMutation.mutate(itemId, {
      onSuccess: () => {
        if (editingItem?.itemId === itemId) resetForm()
      },
    })
  }

  const isPending = createMutation.isPending || updateMutation.isPending

  return (
    <div className="inventory-route" style={{ padding: 'clamp(12px, 4vw, 32px)', maxWidth: 1120, margin: '0 auto' }}>
      <Link to="/" style={{ fontSize: 13, color: 'var(--accent)' }}>Back to home</Link>
      <h1>Inventory</h1>

      {/* The tabs wrap rather than widen the page past the screen (a phone, or a narrow window). */}
      <div role="tablist" style={{ display: 'flex', flexWrap: 'wrap', columnGap: 4, borderBottom: '1px solid var(--border)', marginBottom: 20 }}>
        {TABS.map((name) => (
          <button
            key={name}
            type="button"
            role="tab"
            aria-selected={tab === name}
            onClick={() => setSearchParams(name === 'items' ? {} : { tab: name }, { replace: true })}
            style={{
              background: 'transparent',
              border: 'none',
              borderBottom: tab === name ? '2px solid var(--accent)' : '2px solid transparent',
              borderRadius: 0,
              padding: '8px 14px',
              fontWeight: tab === name ? 600 : 400,
              opacity: tab === name ? 1 : 0.65,
            }}
          >
            {name === 'items' ? `Items (${items.length})` : TAB_LABELS[name]}
          </button>
        ))}
      </div>

      {tab === 'stock' && <StockPanel projectId={projectId} items={items} />}
      {tab === 'tasks' && <WarehouseTasksPanel projectId={projectId} items={items} />}
      {tab === 'purchasing' && <>
        <PurchaseOrdersPanel projectId={projectId} items={items} />
        <SuppliersPanel projectId={projectId} items={items} />
      </>}
      {tab === 'count' && (
        <>
          <CountPanel projectId={projectId} items={items} />
          <CountHistory projectId={projectId} />
        </>
      )}
      {tab === 'count-plans' && <CountPlansPanel key={projectId} projectId={projectId} items={items} />}
      {tab === 'movements' && <MovementsTab projectId={projectId} items={items} />}
      {tab === 'analysis' && (
        <>
          <StockValueTrend projectId={projectId} />
          <StockWastePanel projectId={projectId} />
          <StockTransfersPanel projectId={projectId} />
          <StockAnalysisPanel projectId={projectId} />
        </>
      )}
      {tab === 'lots' && <LotPanel projectId={projectId} items={items} />}
      {tab === 'quality' && <QualityPanel projectId={projectId} items={items} />}
      {tab === 'boms' && <BomPanel projectId={projectId} items={items} units={units} />}
      {tab === 'instructions' && <WorkInstructionsPanel projectId={projectId} items={items} />}
      {tab === 'units' && <UnitsPanel canManage={canManageMasterData} />}
      {tab === 'equipment' && <EquipmentPanel projectId={projectId} />}
      {tab === 'locations' && <LocationsPanel projectId={projectId} />}

      {tab === 'items' && (
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 340px', gap: 24, alignItems: 'start' }}>
        <section>
          <ItemCsvPanel projectId={projectId} items={items} units={units} />
          {itemsQuery.isLoading && <p>Loading items...</p>}
          {itemsQuery.isError && <p style={{ color: '#dc2626' }}>Failed to load items.</p>}
          {deleteMutation.isError && (
            <p role="alert" style={{ color: '#dc2626', fontSize: 13 }}>
              {errorMessage(deleteMutation.error, 'The item could not be deleted.')}
            </p>
          )}
          {!itemsQuery.isLoading && items.length === 0 && (
            <p className="inspector-hint">No items found. Add one from the form on the right.</p>
          )}
          {duplicateCodes(items).length > 0 && (
            <p role="note" style={{ color: '#b45309', fontSize: 12, margin: '0 0 8px' }}>
              Some codes are used by more than one item:{' '}
              {duplicateCodes(items).map((duplicate) => `${duplicate.code} (${duplicate.count})`).join(', ')}. Edit one of them to give
              it a code of its own; imports cannot tell them apart.
            </p>
          )}
          {items.length > 0 && (
            <div role="search" aria-label="Filter items" style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', marginBottom: 8, fontSize: 12 }}>
              <input
                value={itemFilter.text}
                onChange={(e) => setItemFilter((f) => ({ ...f, text: e.target.value }))}
                placeholder="code, name, type, group, barcode or SKU"
                aria-label="Search items"
              />
              <select value={itemFilter.status} onChange={(e) => setItemFilter((f) => ({ ...f, status: e.target.value }))} aria-label="Item status">
                <option value="">any status</option>
                {ITEM_STATUSES.map((status) => <option key={status} value={status}>{status}</option>)}
              </select>
              <label style={{ display: 'inline-flex', gap: 4, alignItems: 'center' }}>
                <input
                  type="checkbox"
                  checked={itemFilter.lotTrackedOnly}
                  onChange={(e) => setItemFilter((f) => ({ ...f, lotTrackedOnly: e.target.checked }))}
                />
                LOT-tracked only
              </label>
              {shownItems.length !== items.length && (
                <span className="inspector-hint">
                  {shownItems.length} of {items.length}{' '}
                  <button type="button" style={{ fontSize: 11 }} onClick={() => setItemFilter(EMPTY_ITEM_FILTER)}>Clear</button>
                </span>
              )}
            </div>
          )}
          {detailItem && (
            <ItemDetail
              key={detailItem.itemId}
              projectId={projectId}
              item={detailItem}
              unit={detailItem.unitId ? (unitLabel.get(detailItem.unitId) ?? '') : ''}
              onEdit={() => startEdit(detailItem)}
              onClose={() => setDetailItemId(null)}
            />
          )}
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr style={{ borderBottom: '2px solid var(--border)', textAlign: 'left' }}>
                <th style={{ padding: '8px 6px' }}>Code</th>
                <th style={{ padding: '8px 6px' }}>Name</th>
                <th style={{ padding: '8px 6px' }}>Category</th>
                <th style={{ padding: '8px 6px' }}>Unit</th>
                <th style={{ padding: '8px 6px' }}>LOT</th>
                <th style={{ padding: '8px 6px' }}>Status</th>
                <th style={{ padding: '8px 6px' }}></th>
              </tr>
            </thead>
            <tbody>
              {shownItems.map((item) => (
                <tr
                  key={item.itemId}
                  style={{
                    borderBottom: '1px solid var(--border)',
                    background: editingItem?.itemId === item.itemId ? 'var(--accent-bg)' : undefined,
                  }}
                >
                  <td style={{ padding: '8px 6px' }}><code>{item.itemCode}</code></td>
                  <td style={{ padding: '8px 6px' }}>
                    {item.itemName}
                    {item.details?.itemGroup && <span className="inspector-hint"> · {item.details.itemGroup}</span>}
                  </td>
                  <td style={{ padding: '8px 6px', opacity: 0.7 }}>{item.resourceCategory ?? '-'}</td>
                  <td style={{ padding: '8px 6px', opacity: 0.7 }}>
                    {item.unitId ? unitLabel.get(item.unitId) ?? item.unitId : '-'}
                  </td>
                  <td style={{ padding: '8px 6px', opacity: 0.7 }}>{item.lotManageYn === 'Y' ? 'tracked' : '-'}</td>
                  <td style={{ padding: '8px 6px', opacity: 0.7 }}>{item.itemStatus}</td>
                  <td style={{ padding: '8px 6px', whiteSpace: 'nowrap' }}>
                    <button type="button" onClick={() => setDetailItemId(item.itemId)} style={{ marginRight: 4, fontSize: 12 }}>
                      Details
                    </button>
                    <button type="button" onClick={() => startEdit(item)} style={{ marginRight: 4, fontSize: 12 }}>
                      Edit
                    </button>
                    <button
                      type="button"
                      onClick={() => handleDelete(item.itemId)}
                      style={{ fontSize: 12, color: '#dc2626', border: '1px solid #fca5a5', background: '#fef2f2' }}
                    >
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>

        <section style={{ border: '1px solid var(--border)', borderRadius: 12, padding: 18 }}>
          <h3 style={{ marginTop: 0 }}>{editingItem ? 'Edit Item' : 'Add Item'}</h3>
          <form onSubmit={(e) => void handleSubmit(e)} style={{ display: 'grid', gap: 10 }}>
            <label style={{ display: 'grid', gap: 4 }}>
              <span>Code *</span>
              <input
                value={form.itemCode}
                onChange={(e) => setForm((f) => ({ ...f, itemCode: e.target.value }))}
                placeholder="e.g. MAT-001"
                required
              />
            </label>
            <label style={{ display: 'grid', gap: 4 }}>
              <span>Name *</span>
              <input value={form.itemName} onChange={(e) => setForm((f) => ({ ...f, itemName: e.target.value }))} required />
            </label>
            <label style={{ display: 'grid', gap: 4 }}>
              <span>Type</span>
              <input
                list="item-type-suggestions"
                value={form.itemType}
                onChange={(e) => setForm((f) => ({ ...f, itemType: e.target.value }))}
              />
              <datalist id="item-type-suggestions">
                {ITEM_TYPE_SUGGESTIONS.map((type) => <option key={type} value={type} />)}
              </datalist>
            </label>
            <label style={{ display: 'grid', gap: 4 }}>
              <span>Category</span>
              <select value={form.resourceCategory} onChange={(e) => setForm((f) => ({ ...f, resourceCategory: e.target.value }))}>
                {RESOURCE_CATEGORIES.map((category) => <option key={category} value={category}>{category}</option>)}
              </select>
            </label>
            <label style={{ display: 'grid', gap: 4 }}>
              <span>Unit</span>
              <select value={form.unitId} onChange={(e) => setForm((f) => ({ ...f, unitId: e.target.value }))}>
                <option value="">None</option>
                {units
                  .filter((unit) => unit.activeYn === 'Y' || unit.unitId === form.unitId)
                  .map((unit) => (
                    <option key={unit.unitId} value={unit.unitId}>
                      {unit.unitCode} · {unit.unitName} ({unit.unitType})
                    </option>
                  ))}
              </select>
            </label>
            <label style={{ display: 'grid', gap: 4 }}>
              <span>Status</span>
              <select value={form.itemStatus} onChange={(e) => setForm((f) => ({ ...f, itemStatus: e.target.value }))}>
                {ITEM_STATUSES.map((status) => <option key={status} value={status}>{status}</option>)}
              </select>
            </label>
            <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              <input
                type="checkbox"
                checked={form.lotManageYn}
                onChange={(e) => setForm((f) => ({ ...f, lotManageYn: e.target.checked }))}
              />
              <span>Track stock per LOT</span>
            </label>
            {form.lotManageYn && (
              <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                <input
                  type="checkbox"
                  checked={form.lotReleaseRequiredYn}
                  onChange={(e) => setForm((f) => ({ ...f, lotReleaseRequiredYn: e.target.checked }))}
                />
                <span>Hold new LOTs until their receipt checks pass</span>
              </label>
            )}
            {/* minWidth 0: side by side, the inputs' own width would otherwise push the whole form past its column. */}
            <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1fr)', gap: 8 }}>
              <label style={{ display: 'grid', gap: 4, minWidth: 0 }}>
                <span>Safety stock</span>
                <input
                  inputMode="decimal"
                  value={form.safetyStockQty}
                  placeholder="not watched"
                  style={{ minWidth: 0 }}
                  onChange={(e) => setForm((f) => ({ ...f, safetyStockQty: e.target.value }))}
                />
              </label>
              <label style={{ display: 'grid', gap: 4, minWidth: 0 }}>
                <span>Lead time (days)</span>
                <input
                  inputMode="numeric"
                  style={{ minWidth: 0 }}
                  value={form.leadTimeDays}
                  onChange={(e) => setForm((f) => ({ ...f, leadTimeDays: e.target.value }))}
                />
              </label>
            </div>
            <label style={{ display: 'grid', gap: 4 }}>
              <span>Unit cost</span>
              <input
                inputMode="decimal"
                value={form.unitCost}
                placeholder="per unit of this item"
                onChange={(e) => setForm((f) => ({ ...f, unitCost: e.target.value }))}
              />
            </label>
            <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1fr)', gap: 8 }}>
              <label style={{ display: 'grid', gap: 4, minWidth: 0 }}>
                <span>Bought in</span>
                <input
                  value={form.purchaseUnit}
                  maxLength={20}
                  placeholder="stock unit"
                  style={{ minWidth: 0 }}
                  onChange={(e) => setForm((f) => ({ ...f, purchaseUnit: e.target.value }))}
                />
              </label>
              <label style={{ display: 'grid', gap: 4, minWidth: 0 }}>
                <span>Units per {form.purchaseUnit.trim() || 'pack'}</span>
                <input
                  type="number"
                  min="0"
                  step="any"
                  value={form.purchaseUnitQty}
                  disabled={!form.purchaseUnit.trim()}
                  placeholder="1"
                  style={{ minWidth: 0 }}
                  onChange={(e) => setForm((f) => ({ ...f, purchaseUnitQty: e.target.value }))}
                />
              </label>
            </div>
            <ItemInfoFields value={form.details} onChange={(details) => setForm((f) => ({ ...f, details }))} />
            <div style={{ display: 'flex', gap: 8, marginTop: 4 }}>
              <button type="submit" disabled={isPending}>
                {isPending ? 'Saving...' : editingItem ? 'Save' : 'Add'}
              </button>
              {editingItem && (
                <button type="button" onClick={resetForm} style={{ background: 'transparent' }}>
                  Cancel
                </button>
              )}
            </div>
            {(createMutation.isError || updateMutation.isError) && (
              <p style={{ color: '#dc2626', fontSize: 12, margin: 0 }}>
                {errorMessage(createMutation.error ?? updateMutation.error, 'Failed to save item.')}
              </p>
            )}
          </form>
        </section>
      </div>
      )}
    </div>
  )
}
