import type { PurchaseOrderDto, PurchaseOrderInput } from '../../../entities/purchasing/api/usePurchaseOrders'
import type { SupplierDto, SupplierInput, SupplierItemDto } from '../../../entities/purchasing/api/useSuppliers'
import { formatQty } from '../../../shared/lib/formatQty'
import { projectToday } from './equipmentScheduleModel'
import { isLate, knownSuppliers, owedQuantity, returnValue } from './purchaseOrderModel'

/** The supplier form as typed (docs/domain/purchasing.md PO18-PO19). */
export interface SupplierForm {
  supplierName: string
  contact: string
  leadTimeDays: string
  note: string
  active: boolean
}

export const EMPTY_SUPPLIER_FORM: SupplierForm = { supplierName: '', contact: '', leadTimeDays: '', note: '', active: true }

export function supplierForm(supplier: SupplierDto): SupplierForm {
  return {
    supplierName: supplier.supplierName,
    contact: supplier.contact ?? '',
    leadTimeDays: supplier.leadTimeDays == null ? '' : String(supplier.leadTimeDays),
    note: supplier.note ?? '',
    active: supplier.active,
  }
}

/** The body to save, or what is wrong with the form; the server checks again (PO18). */
export function supplierPayload(form: SupplierForm): { input: SupplierInput | null; error: string | null } {
  const name = form.supplierName.trim()
  if (!name) return { input: null, error: 'Name the supplier.' }
  const typed = form.leadTimeDays.trim()
  const days = typed === '' ? null : Number(typed)
  if (days !== null && (!Number.isInteger(days) || days < 0 || days > 365)) {
    return { input: null, error: 'Lead time is a whole number of days from 0 to 365.' }
  }
  return { input: { supplierName: name, contact: form.contact.trim() || null, leadTimeDays: days, note: form.note.trim() || null }, error: null }
}

/** The listed supplier with this name, ignoring case and surrounding spaces. */
export function findSupplier(suppliers: SupplierDto[], name: string): SupplierDto | undefined {
  const wanted = name.trim().toLowerCase()
  return wanted ? suppliers.find((one) => one.supplierName.toLowerCase() === wanted) : undefined
}

/** Names to offer on an order: the active listed suppliers once there is a list, else those already used (PO11, PO20). */
export function supplierChoices(suppliers: SupplierDto[], orders: PurchaseOrderDto[]): string[] {
  return suppliers.length > 0 ? suppliers.filter((one) => one.active).map((one) => one.supplierName) : knownSuppliers(orders)
}

/** Why an order's supplier would be refused, before saving: missing from the list or inactive; null when fine or unlisted (PO20). */
export function supplierProblem(suppliers: SupplierDto[], name: string): string | null {
  if (suppliers.length === 0 || !name.trim()) return null
  const found = findSupplier(suppliers, name)
  if (!found) return `${name.trim()} is not in the supplier list.`
  return found.active ? null : `${found.supplierName} is inactive.`
}

/** The day a supplier's lead time puts arrival at, ordering on {@code today} (PO21); null without a lead time. */
export function leadTimeDate(today: string, supplier: Pick<SupplierDto, 'leadTimeDays'> | undefined): string | null {
  if (!supplier || supplier.leadTimeDays == null) return null
  const date = new Date(`${today}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() + supplier.leadTimeDays)
  return date.toISOString().slice(0, 10)
}

/** How a supplier's purchase orders arrived against their dates (PO24). */
export interface SupplierDelivery {
  supplierName: string
  /** Fully received orders that had an expected date. */
  received: number
  /** Of those, all in by the first date the order was given. */
  onTimeFirst: number
  /** Of those, all in by the date last agreed. */
  onTimeLast: number
  /** Ordered, past the expected date and not all in yet. */
  lateNow: number
  /** Lines of those orders with a date (their own, or the order's), and of them all in by it (PO44). */
  linesReceived: number
  linesOnTime: number
  /** Orders in after the last date, and their days late added up (PO44). */
  lateArrivals: number
  daysLate: number
}

/**
 * Delivery by supplier from the orders themselves (PO24). An order counts once fully received with an expected date, and it
 * arrived on the project day of its last receipt. The first date is the order's own before any change (PO23), the last the one
 * it has now. Closed and cancelled orders do not count; suppliers are matched ignoring case. With {@code since} (a project day)
 * only orders that came in on or after it count; late now is always today's (PO44).
 */
export function supplierDelivery(orders: PurchaseOrderDto[], timeZone: string | undefined, today: string,
  since: string | null = null): SupplierDelivery[] {
  const rows = new Map<string, SupplierDelivery>()
  const row = (name: string) => {
    const key = name.trim().toLowerCase()
    const found = rows.get(key) ?? { supplierName: name.trim(), received: 0, onTimeFirst: 0, onTimeLast: 0, lateNow: 0, linesReceived: 0,
      linesOnTime: 0, lateArrivals: 0, daysLate: 0 }
    rows.set(key, found)
    return found
  }
  for (const order of orders) {
    if (isLate(order, today)) row(order.supplierName).lateNow += 1
    const last = order.expectedDate
    if (order.status !== 'received' || last === null || order.receipts.length === 0) continue
    const moves = (order.dateChanges ?? []).filter((change) => change.lineId === null)
    const first = moves.length === 0 ? last
      : moves[0].previousDate ?? moves.find((change) => change.expectedDate !== null)?.expectedDate ?? last
    const days = order.receipts.map((receipt) => projectToday(timeZone, new Date(receipt.receivedAt))).sort()
    const arrived = days[days.length - 1]
    if (since && arrived < since) continue
    const entry = row(order.supplierName)
    entry.received += 1
    if (arrived <= first) entry.onTimeFirst += 1
    if (arrived <= last) {
      entry.onTimeLast += 1
    } else {
      entry.lateArrivals += 1
      entry.daysLate += daysBetween(last, arrived)
    }
    // Each line against its own date, or the order's, by its own last receipt (PO44).
    for (const line of order.lines ?? []) {
      const lineDays = order.receipts.filter((receipt) => receipt.lineId === line.lineId)
        .map((receipt) => projectToday(timeZone, new Date(receipt.receivedAt))).sort()
      if (lineDays.length === 0) continue
      entry.linesReceived += 1
      if (lineDays[lineDays.length - 1] <= (line.expectedDate ?? last)) entry.linesOnTime += 1
    }
  }
  return [...rows.values()].sort((left, right) => left.supplierName.localeCompare(right.supplierName))
}

/** Whole days from one yyyy-MM-dd day to a later one. */
function daysBetween(from: string, to: string): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000)
}

/** "2.5": the average days late of orders in after the last date, to one decimal; "—" when none was late (PO44). */
export function averageDaysLateText(row: SupplierDelivery): string {
  return row.lateArrivals ? String(Math.round((row.daysLate / row.lateArrivals) * 10) / 10) : '—'
}

/** The supplier's own codes for items whose usual supplier is this one, by item id, ignoring case in the name (PO32). */
export function supplierCodesFor(supplierName: string, usual: SupplierItemDto[]): Map<string, string> {
  const wanted = supplierName.trim().toLowerCase()
  return new Map(usual.flatMap((one) => (one.supplierName.toLowerCase() === wanted && one.supplierItemCode
    ? [[one.itemId, one.supplierItemCode] as [string, string]] : [])))
}

/** A draft purchase order for one supplier (PO30). */
export interface DraftGroup {
  supplierName: string
  expectedDate: string | null
  lines: PurchaseOrderInput['lines']
}

/**
 * Draft lines split by their items' usual suppliers (PO30): one draft per active usual supplier, by name, expected after its
 * lead time from today, a line at the supplier's own price when it has one (PO38); lines whose item has none, or an inactive
 * one, are left for a supplier to be asked for.
 */
export function draftsBySupplier(
  lines: PurchaseOrderInput['lines'],
  usual: SupplierItemDto[],
  suppliers: SupplierDto[],
  today: string,
): { groups: DraftGroup[]; rest: PurchaseOrderInput['lines'] } {
  const usualOf = new Map(usual.map((one) => [one.itemId, one]))
  const byId = new Map(suppliers.map((one) => [one.supplierId, one]))
  const groups = new Map<string, DraftGroup>()
  const rest: PurchaseOrderInput['lines'] = []
  for (const line of lines) {
    const entry = usualOf.get(line.itemId)
    const supplier = byId.get(entry?.supplierId ?? '')
    if (!supplier?.active) {
      rest.push(line)
      continue
    }
    const group = groups.get(supplier.supplierId) ?? { supplierName: supplier.supplierName, expectedDate: leadTimeDate(today, supplier), lines: [] }
    group.lines.push(entry?.unitPrice != null ? { ...line, unitPrice: entry.unitPrice } : line)
    groups.set(supplier.supplierId, group)
  }
  return { groups: [...groups.values()].sort((left, right) => left.supplierName.localeCompare(right.supplierName)), rest }
}

const round4 = (value: number) => Math.round(value * 10000) / 10000

/** What was bought from a supplier in a period, at agreed prices (PO41). */
export interface SupplierSpend {
  supplierName: string
  /** Orders counted: ordered, received or closed, and ordered in the period. */
  orders: number
  ordered: number
  received: number
  returned: number
  /** Lines of those orders without a price, left out of the values. */
  unpriced: number
}

/** The day {@code days} before {@code today} (PO41). */
export function daysBefore(today: string, days: number): string {
  const date = new Date(`${today}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() - days)
  return date.toISOString().slice(0, 10)
}

/**
 * Spend by supplier from the orders themselves (PO41): orders ordered, received or closed, ordered on or after {@code since}
 * (a project day; every one when null). Ordered is the known line values; received and returned are what came and went back,
 * each at its line's price. Lines without a price are counted, not valued. Suppliers are matched ignoring case, most ordered
 * first.
 */
export function supplierSpend(orders: PurchaseOrderDto[], since: string | null, timeZone: string | undefined): SupplierSpend[] {
  const rows = new Map<string, SupplierSpend>()
  for (const order of orders) {
    if (!['ordered', 'received', 'closed'].includes(order.status) || !order.orderedAt) continue
    if (since && projectToday(timeZone, new Date(order.orderedAt)) < since) continue
    const key = order.supplierName.trim().toLowerCase()
    const row = rows.get(key) ?? { supplierName: order.supplierName.trim(), orders: 0, ordered: 0, received: 0, returned: 0, unpriced: 0 }
    rows.set(key, row)
    row.orders += 1
    const price = new Map(order.lines.map((line) => [line.lineId, line.unitPrice ?? null]))
    for (const line of order.lines) {
      if (line.unitPrice == null) row.unpriced += 1
      else row.ordered = round4(row.ordered + line.quantity * line.unitPrice)
    }
    for (const receipt of order.receipts) {
      const unit = price.get(receipt.lineId)
      if (unit != null) row.received = round4(row.received + receipt.quantity * unit)
    }
    for (const back of order.returns ?? []) {
      const unit = price.get(back.lineId)
      if (unit != null) row.returned = round4(row.returned + back.quantity * unit)
    }
  }
  return [...rows.values()].sort((left, right) => right.ordered - left.ordered || left.supplierName.localeCompare(right.supplierName))
}

/** What a supplier still owes back for returns (PO48). */
export interface SupplierCredit {
  supplierName: string
  /** Returns with something still owed. */
  returns: number
  /** Their worth at their lines' prices. */
  due: number
  /** Of those, returns on lines without a price, left out of due. */
  unpriced: number
}

/**
 * What each supplier still owes back, from the orders themselves (PO48): returns not sent to be replaced, and replacements a
 * closed order will not bring, until their credit is kept (PO47). Suppliers are matched ignoring case, most owed first.
 */
export function supplierCredits(orders: PurchaseOrderDto[]): SupplierCredit[] {
  const rows = new Map<string, SupplierCredit>()
  for (const order of orders) {
    for (const back of order.returns ?? []) {
      const owed = owedQuantity(order, back)
      if (owed <= 0) continue
      const key = order.supplierName.trim().toLowerCase()
      const row = rows.get(key) ?? { supplierName: order.supplierName.trim(), returns: 0, due: 0, unpriced: 0 }
      rows.set(key, row)
      row.returns += 1
      const value = returnValue(order, { lineId: back.lineId, quantity: owed })
      if (value == null) row.unpriced += 1
      else row.due = round4(row.due + value)
    }
  }
  return [...rows.values()].sort((left, right) => right.due - left.due || left.supplierName.localeCompare(right.supplierName))
}

/** An item's price on one order placed, as agreed on its line (PO49). */
export interface PricePoint {
  /** The project day the order was placed. */
  date: string
  supplierName: string
  orderNumber: string
  unitPrice: number
}

/** What a supplier was paid for an item (PO49). */
export interface ItemPrice {
  supplierName: string
  /** On its newest order with a price, placed that day as that number. */
  lastPrice: number
  lastDate: string
  lastOrder: string
  lowestPrice: number
  /** Its orders with a price for the item. */
  orders: number
}

const PLACED = ['ordered', 'received', 'closed']

/**
 * The item's prices on orders placed (ordered, received or closed), newest first (PO49): drafts and cancelled orders were never
 * bought on, and lines without a price say nothing.
 */
export function priceHistory(orders: PurchaseOrderDto[], itemId: string, timeZone: string | undefined): PricePoint[] {
  return orders.filter((order) => PLACED.includes(order.status) && order.orderedAt)
    .flatMap((order) => order.lines.filter((line) => line.itemId === itemId && line.unitPrice != null)
      .map((line) => ({ at: order.orderedAt as string, point: { date: projectToday(timeZone, new Date(order.orderedAt as string)),
        supplierName: order.supplierName.trim(), orderNumber: order.orderNumber, unitPrice: line.unitPrice as number } })))
    .sort((one, other) => other.at.localeCompare(one.at) || other.point.orderNumber.localeCompare(one.point.orderNumber))
    .map((one) => one.point)
}

/** The items with a price on an order placed, by code (PO49). */
export function pricedItems(orders: PurchaseOrderDto[]): { itemId: string; itemCode: string }[] {
  const found = new Map<string, string>()
  for (const order of orders) {
    if (!PLACED.includes(order.status) || !order.orderedAt) continue
    for (const line of order.lines) if (line.unitPrice != null) found.set(line.itemId, line.itemCode)
  }
  return [...found].map(([itemId, itemCode]) => ({ itemId, itemCode })).sort((one, other) => one.itemCode.localeCompare(other.itemCode))
}

/** The item's prices by supplier (matched ignoring case), the cheapest last price first (PO49). */
export function itemPrices(orders: PurchaseOrderDto[], itemId: string, timeZone: string | undefined): ItemPrice[] {
  const rows = new Map<string, ItemPrice>()
  for (const point of priceHistory(orders, itemId, timeZone)) {
    const key = point.supplierName.toLowerCase()
    const row = rows.get(key)
    if (!row) {
      rows.set(key, { supplierName: point.supplierName, lastPrice: point.unitPrice, lastDate: point.date, lastOrder: point.orderNumber,
        lowestPrice: point.unitPrice, orders: 1 })
      continue
    }
    row.orders += 1
    row.lowestPrice = Math.min(row.lowestPrice, point.unitPrice)
  }
  return [...rows.values()].sort((one, other) => one.lastPrice - other.lastPrice || one.supplierName.localeCompare(other.supplierName))
}

/** "Usually from Mill Co at 2.4 agreed.", "Usually from Mill Co; no price agreed."; null without a usual supplier (PO38, PO49). */
export function usualPriceText(usual: Pick<SupplierItemDto, 'supplierName' | 'unitPrice'> | undefined): string | null {
  if (!usual) return null
  return usual.unitPrice == null ? `Usually from ${usual.supplierName}; no price agreed.`
    : `Usually from ${usual.supplierName} at ${formatQty(usual.unitPrice)} agreed.`
}

/** "5 days", "1 day", "same day"; empty when not known. */
export function leadTimeText(days: number | null): string {
  if (days == null) return ''
  return days === 0 ? 'same day' : `${days} ${days === 1 ? 'day' : 'days'}`
}
