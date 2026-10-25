import type { BomDto, ItemDto, MaterialRequirementDto, ReorderLineDto, StockAlertDto, WorkOrderDto } from '../../../shared/types/api'
import { approvedRevision, localDay } from './bomModel'
import { isActiveItem } from './itemStatusModel'
import { csvCell } from './ledgerModel'
import type { StorageLocationDto } from '../../../entities/inventory/api/useStorageLocations'

const SEVERITY_ORDER: Record<StockAlertDto['severity'], number> = { critical: 0, warning: 1, info: 2 }

/** Open before closed, then the most severe first; newest first within each (the server's order). */
export function orderAlerts(alerts: StockAlertDto[]): StockAlertDto[] {
  return alerts
    .map((alert, index) => ({ alert, index }))
    .sort(
      (a, b) =>
        Number(a.alert.resolved) - Number(b.alert.resolved) ||
        (SEVERITY_ORDER[a.alert.severity] ?? 9) - (SEVERITY_ORDER[b.alert.severity] ?? 9) ||
        a.index - b.index,
    )
    .map(({ alert }) => alert)
}

/** "FLOUR · Flour at WH-A (LOT L1): 3 kg available, minimum 10 kg" */
export function describeAlert(alert: StockAlertDto, format: (value: number) => string = String): string {
  const item = [alert.itemCode ?? alert.itemId, alert.itemName].filter(Boolean).join(' · ')
  const place = [alert.location ? ` at ${alert.location}` : '', alert.lotNo ? ` (LOT ${alert.lotNo})` : ''].join('')
  const unit = alert.unit ? ` ${alert.unit}` : ''
  const numbers =
    alert.alertType === 'expiry'
      ? expiryText(alert.actualValue)
      : alert.alertType === 'low'
        ? `${format(alert.actualValue)}${unit} available, minimum ${format(alert.thresholdValue)}${unit}`
        : `${format(alert.actualValue)}${unit} on hand, maximum ${format(alert.thresholdValue)}${unit}`
  return `${item}${place}: ${numbers}`
}

/** From the days left until the LOT's expiry date. */
function expiryText(daysLeft: number): string {
  if (daysLeft < 0) return `expired ${-daysLeft} day${daysLeft === -1 ? '' : 's'} ago; scrap it, it cannot go into production`
  if (daysLeft === 0) return 'expires today'
  return `expires in ${daysLeft} day${daysLeft === 1 ? '' : 's'}`
}

/**
 * How much to order to get back to safety stock once the order arrives: the shortfall now plus what will be used
 * while waiting for it (daily use × lead time). Without a known daily use or lead time it is just the shortfall.
 */
export function suggestedOrder(
  line: Pick<ReorderLineDto, 'shortageQuantity' | 'leadTimeDays'>,
  dailyUse: number | null | undefined,
): number {
  const duringLeadTime = dailyUse && line.leadTimeDays ? dailyUse * line.leadTimeDays : 0
  return Math.max(0, line.shortageQuantity + duringLeadTime)
}

/** What an order costs at the item's unit cost; null when the cost is not known (none, or 0). */
export function orderValue(quantity: number, unitCost: number | null | undefined): number | null {
  return unitCost ? Math.round(quantity * unitCost * 10000) / 10000 : null
}

/**
 * Whole purchase units that hold at least {@code quantity} stock units (docs/domain/item-details.md "구매 단위"); null
 * when the item is bought in its stock unit.
 */
export function packsFor(quantity: number, purchaseUnitQty: number | null | undefined): number | null {
  if (!purchaseUnitQty || purchaseUnitQty <= 0) return null
  // A hair of tolerance so 50 / 25 stays 2 despite floating point.
  return Math.max(0, Math.ceil(quantity / purchaseUnitQty - 1e-9))
}

/** Stock units in a number of purchase units, e.g. 2 bags of 25 kg are 50 kg; null when either is missing. */
export function fromPacks(packs: number, purchaseUnitQty: number | null | undefined): number | null {
  if (!purchaseUnitQty || purchaseUnitQty <= 0 || !Number.isFinite(packs) || packs < 0) return null
  return Math.round(packs * purchaseUnitQty * 10000) / 10000
}

/** One reorder line with what the list shows next to it. */
export interface ReorderRow {
  line: ReorderLineDto
  dailyUse: number | null
  suggested: number
  unitCost: number | null
  purchaseUnit?: string | null
  purchaseUnitQty?: number | null
}

/** What would actually be ordered: whole purchase units when the item has one, otherwise the suggestion. */
export function orderQuantity(row: Pick<ReorderRow, 'suggested' | 'purchaseUnitQty'>): number {
  const packs = packsFor(row.suggested, row.purchaseUnitQty)
  return packs === null ? row.suggested : Math.round(packs * (row.purchaseUnitQty as number) * 10000) / 10000
}

/** The reorder list as a purchase list (CSV with a byte order mark, like the other stock exports). */
export function reorderCsv(rows: ReorderRow[]): string {
  const header = [
    'item_code', 'item_name', 'unit', 'usable', 'safety_stock', 'short', 'lead_time_days', 'daily_use', 'suggested_order',
    'purchase_unit', 'packs', 'order_quantity', 'unit_cost', 'order_value',
  ]
  const lines = rows.map((row) =>
    [
      row.line.itemCode, row.line.itemName, row.line.unit, row.line.availableQuantity, row.line.safetyStockQty, row.line.shortageQuantity,
      row.line.leadTimeDays, row.dailyUse, row.suggested, row.purchaseUnit ?? null, packsFor(row.suggested, row.purchaseUnitQty),
      orderQuantity(row), row.unitCost || null, orderValue(orderQuantity(row), row.unitCost),
    ]
      .map(csvCell)
      .join(','),
  )
  return '\ufeff' + [header.join(','), ...lines].join('\r\n') + '\r\n'
}

/**
 * What open work orders need, as a purchase list (docs/domain/material-requirements.md): every material, the biggest
 * shortfall first as the server sends them, with the shortage rounded up to the purchase unit and the item's status,
 * since an item that is not active is not reordered.
 */
/** "Bread 30" for a work order's share, "via SPONGE 3" for the share from making a short sub-assembly. */
export function needLabel(need: MaterialRequirementDto['lines'][number]['orders'][number], quantity: string | number = need.required): string {
  return need.viaItemCode ? `via ${need.viaItemCode} ${quantity}` : `${need.workOrderTitle ?? ''} ${quantity}`
}

export function needsCsv(lines: MaterialRequirementDto['lines'], items: Map<string, ItemDto>): string {
  const header = ['item_code', 'item_name', 'unit', 'needed', 'usable', 'short', 'purchase_unit', 'packs', 'item_status', 'work_orders',
    'being_made', 'made_here', 'left_after', 'safety_stock', 'under_safety', 'on_order']
  const rows = lines.map((line) => {
    const item = items.get(line.itemId)
    const packs = line.shortage > 0 && item?.purchaseUnit ? packsFor(line.shortage, item.purchaseUnitQty) : null
    const after = leftAfterOrders(line, item?.safetyStockQty)
    return [
      line.itemCode, line.itemName, line.unit, line.required, line.usable, line.shortage, packs == null ? null : item?.purchaseUnit ?? null,
      packs, item?.itemStatus ?? null, line.orders.map((order) => needLabel(order)).join('; '), line.plannedSupply ?? 0,
      line.madeHere ? 'yes' : 'no', after.left, item?.safetyStockQty ? item.safetyStockQty : null, after.underSafety, line.onOrder ?? 0,
    ]
      .map(csvCell)
      .join(',')
  })
  return '\ufeff' + [header.join(','), ...rows].join('\r\n') + '\r\n'
}

/** The alerts of stock records at the given places (lower-cased codes, see locationModel.codesWithin); all without them. */
export function alertsWithin(alerts: StockAlertDto[], codes: Set<string> | null): StockAlertDto[] {
  if (!codes) return alerts
  return alerts.filter((alert) => alert.location !== null && codes.has(alert.location.trim().toLowerCase()))
}

/**
 * What a material has left once the open work orders are made from usable stock, what is being made and what is on order,
 * and how far that is under the item's safety stock (docs/domain/material-requirements.md "주문 뒤 남는 양",
 * docs/domain/purchasing.md PO8). Never below zero: what is missing beyond that is the shortage. No safety stock (none or 0)
 * is never under it.
 */
export function leftAfterOrders(
  line: Pick<MaterialRequirementDto['lines'][number], 'required' | 'usable' | 'plannedSupply' | 'onOrder'>,
  safetyStock: number | null | undefined,
): { left: number; underSafety: number } {
  const left = Math.max(0, Math.round((line.usable + (line.plannedSupply ?? 0) + (line.onOrder ?? 0) - line.required) * 10000) / 10000)
  const safety = safetyStock && safetyStock > 0 ? safetyStock : 0
  return { left, underSafety: safety > left ? Math.round((safety - left) * 10000) / 10000 : 0 }
}

/**
 * Draft work orders for every sub-assembly open work orders leave short, all levels at once (docs/domain/multi-level-bom.md
 * "모든 단계 한 번에"): the shortage of each made-here material, with its approved BOM. One that already has a draft order,
 * has no approved BOM or takes no new stock is left out, with why. Drafts do not count in the needs until approved.
 */
export function subAssemblyDrafts(
  lines: Pick<MaterialRequirementDto['lines'][number], 'itemId' | 'itemCode' | 'shortage' | 'madeHere'>[],
  boms: Pick<BomDto, 'bomId' | 'targetItemId' | 'bomStatus'>[],
  orders: Pick<WorkOrderDto, 'workOrderNumber' | 'workOrderStatus' | 'targetItemId'>[],
  items: Map<string, ItemDto>,
  /** The project's today (bomModel.zoneDay); the revision effective then is the one the drafts take. */
  day: string = localDay(),
): { drafts: { itemId: string; itemCode: string; quantity: number; bomId: string }[]; skipped: string[] } {
  const drafts: { itemId: string; itemCode: string; quantity: number; bomId: string }[] = []
  const skipped: string[] = []
  for (const line of lines) {
    if (!line.madeHere || line.shortage <= 0) continue
    const code = line.itemCode ?? line.itemId
    const draft = orders.find((order) => order.workOrderStatus === 'draft' && order.targetItemId === line.itemId)
    const item = items.get(line.itemId)
    const bom = approvedRevision(boms as BomDto[], line.itemId, day)
    if (draft) skipped.push(`${code} already has draft ${draft.workOrderNumber}`)
    else if (item && !isActiveItem(item)) skipped.push(`${code} is ${item.itemStatus}`)
    else if (!bom) skipped.push(`${code} has no approved BOM`)
    else drafts.push({ itemId: line.itemId, itemCode: code, quantity: line.shortage, bomId: bom.bomId })
  }
  return { drafts, skipped }
}

/** The places a reorder list can be for (docs/domain/stock-alert.md PS4): active places of the location list, by path. */
export function reorderPlaces(locations: StorageLocationDto[]): StorageLocationDto[] {
  return locations.filter((location) => location.active).sort((a, b) => a.path.localeCompare(b.path))
}

/** "2 items below safety stock at WH-A", or "No items below their safety stock" when none is short. */
export function reorderTitle(count: number, place: string | null): string {
  const at = place ? ` at ${place}` : ''
  return count === 0 ? `No items below their safety stock${at}` : `${count} item${count === 1 ? '' : 's'} below safety stock${at}`
}

/** The purchase list's file name, "reorder-2026-10-11.csv", or "reorder-WH-A-2026-10-11.csv" for a place. */
export function reorderFileName(place: string | null, day: string): string {
  return `reorder-${place ? `${place.replace(/[^A-Za-z0-9._-]+/g, '_')}-` : ''}${day}.csv`
}

/** A safety stock at a place before it is sent (PS2): the item, and a quantity of 0 or more, where 0 removes it. */
export function placeSafetyPayload(
  itemId: string,
  quantity: string,
): { input: { itemId: string; safetyStockQty: number | null }; error: null } | { input: null; error: string } {
  if (!itemId) return { input: null, error: 'Pick the item to watch.' }
  const text = quantity.trim()
  const value = Number(text)
  if (!text || !Number.isFinite(value) || value < 0) {
    return { input: null, error: 'Give the safety stock here as a number of 0 or more (0 removes it).' }
  }
  return { input: { itemId, safetyStockQty: value === 0 ? null : value }, error: null }
}
