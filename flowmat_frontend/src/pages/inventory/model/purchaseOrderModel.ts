import type {
  PurchaseApprovalDto,
  PurchaseCreditChangeDto,
  PurchaseCreditChangeInput,
  PurchaseCreditInput,
  PurchaseOrderDateChangeDto,
  PurchaseOrderDatesInput,
  PurchaseOrderDto,
  PurchaseOrderInput,
  PurchaseOrderLineDto,
  PurchaseOrderStatus,
  PurchaseReceiptDto,
  PurchaseReceiptInput,
  PurchaseReturnDto,
  PurchaseReturnInput,
} from '../../../entities/purchasing/api/usePurchaseOrders'
import type { NonconformityDto } from '../../../entities/quality/api/useNonconformities'
import { formatQty } from '../../../shared/lib/formatQty'
import type { InventoryDto, ItemDto, MaterialRequirementDto } from '../../../shared/types/api'
import { isActiveItem } from './itemStatusModel'
import { escapeHtml } from './labelModel'
import { packsFor } from './stockAlertModel'
import { savedAtText } from './versionHistoryModel'

/** docs/domain/purchasing.md PO3-PO4, in the order they come. */
export const PURCHASE_STATUSES: PurchaseOrderStatus[] = ['draft', 'ordered', 'received', 'closed', 'cancelled']

/** The form as typed; a line is an item and a quantity in its stock unit. */
export interface OrderLineForm {
  itemId: string
  quantity: string
  /** Per stock unit; empty while not agreed (PO14). */
  unitPrice?: string
}

export interface OrderForm {
  supplierName: string
  expectedDate: string
  note: string
  lines: OrderLineForm[]
  /** Where it is to arrive (PO39); empty for none. */
  deliverTo?: string
}

export const EMPTY_LINE: OrderLineForm = { itemId: '', quantity: '' }
export const EMPTY_ORDER_FORM: OrderForm = { supplierName: '', expectedDate: '', note: '', lines: [EMPTY_LINE] }

export function orderForm(order: PurchaseOrderDto): OrderForm {
  return {
    supplierName: order.supplierName,
    expectedDate: order.expectedDate ?? '',
    note: order.note ?? '',
    ...(order.deliverTo ? { deliverTo: order.deliverTo } : {}),
    lines: order.lines.map((line) => ({ itemId: line.itemId, quantity: String(line.quantity),
      ...(line.unitPrice != null ? { unitPrice: String(line.unitPrice) } : {}) })),
  }
}

/** A quantity as typed: a number above 0 with at most four decimals, else null. */
export function typedQuantity(value: string): number | null {
  const trimmed = value.trim()
  if (!/^\d+(\.\d{1,4})?$/.test(trimmed)) return null
  const number = Number(trimmed)
  return number > 0 ? number : null
}

/** A unit price as typed: empty is none (null), else 0 or more with at most four decimals; undefined when it is neither. */
export function typedPrice(value: string | undefined): number | null | undefined {
  const trimmed = (value ?? '').trim()
  if (!trimmed) return null
  return /^\d+(\.\d{1,4})?$/.test(trimmed) ? Number(trimmed) : undefined
}

/** The draft to send, or what is wrong with the form; lines left empty are dropped. */
export function orderPayload(
  projectId: string,
  form: OrderForm,
  itemCode: (itemId: string) => string = (itemId) => itemId,
): { input: PurchaseOrderInput; error: null } | { input: null; error: string } {
  const supplier = form.supplierName.trim()
  if (!supplier) return { input: null, error: 'Name the supplier.' }
  if (supplier.length > 100) return { input: null, error: 'The supplier is longer than 100 characters.' }
  if (form.note.trim().length > 500) return { input: null, error: 'The note is longer than 500 characters.' }
  const deliverTo = form.deliverTo?.trim() ?? ''
  if (deliverTo.length > 100) return { input: null, error: 'Deliver to is longer than 100 characters.' }
  const filled = form.lines.filter((line) => line.itemId || line.quantity.trim())
  if (filled.length === 0) return { input: null, error: 'Add at least one line.' }
  const seen = new Set<string>()
  const lines: PurchaseOrderInput['lines'] = []
  for (const [index, line] of filled.entries()) {
    if (!line.itemId) return { input: null, error: `Choose the item on line ${index + 1}.` }
    const quantity = typedQuantity(line.quantity)
    if (quantity === null) return { input: null, error: `Line ${index + 1}: enter a quantity above 0 with at most four decimals.` }
    if (seen.has(line.itemId)) return { input: null, error: `${itemCode(line.itemId)} is on two lines; combine them.` }
    seen.add(line.itemId)
    const unitPrice = typedPrice(line.unitPrice)
    if (unitPrice === undefined) {
      return { input: null, error: `Line ${index + 1}: enter a unit price of 0 or more with at most four decimals, or leave it empty.` }
    }
    lines.push({ itemId: line.itemId, quantity, expectedDate: null, note: null, ...(unitPrice !== null ? { unitPrice } : {}) })
  }
  return {
    input: { projectId, supplierName: supplier, expectedDate: form.expectedDate || null, note: form.note.trim() || null, lines,
      ...(deliverTo ? { deliverTo } : {}) },
    error: null,
  }
}

/** One row of the receipt form. */
export interface ReceiveLineForm {
  lineId: string
  quantity: string
  location: string
  lotNo: string
  expiryDate: string
}

/** A row for every line still open, starting at what is left to receive, at the place the order is to arrive (PO40). */
export function receiveForm(order: PurchaseOrderDto): ReceiveLineForm[] {
  return order.lines
    .filter((line) => line.remainingQuantity > 0)
    .map((line) => ({ lineId: line.lineId, quantity: String(line.remainingQuantity), location: order.deliverTo ?? '', lotNo: '', expiryDate: '' }))
}

/** The receipt to send, or what is wrong; rows left empty are not received this time. */
export function receivePayload(
  order: PurchaseOrderDto,
  rows: ReceiveLineForm[],
  requestId: string,
): { input: PurchaseReceiptInput; error: null } | { input: null; error: string } {
  const byId = new Map(order.lines.map((line) => [line.lineId, line]))
  const lines: PurchaseReceiptInput['lines'] = []
  for (const row of rows) {
    const line = byId.get(row.lineId)
    if (!line || !row.quantity.trim()) continue
    const quantity = typedQuantity(row.quantity)
    if (quantity === null) return { input: null, error: `${line.itemCode}: enter a quantity above 0 with at most four decimals.` }
    if (quantity > line.remainingQuantity) {
      return { input: null, error: `Only ${formatQty(line.remainingQuantity)} of ${line.itemCode} is left to receive.` }
    }
    if (line.lotTracked && !row.lotNo.trim()) return { input: null, error: `${line.itemCode} is LOT-tracked; give the LOT number.` }
    lines.push({
      lineId: line.lineId,
      quantity,
      location: row.location.trim() || null,
      lotNo: line.lotTracked ? row.lotNo.trim() : null,
      expiryDate: line.lotTracked && row.expiryDate ? row.expiryDate : null,
    })
  }
  if (lines.length === 0) return { input: null, error: 'Enter what arrived on at least one line.' }
  return { input: { requestId, lines }, error: null }
}

const round4 = (value: number) => Math.round(value * 10000) / 10000

/** A line that can still send stock back, with the stock records it can go from (PO26). */
export interface ReturnChoice {
  lineId: string
  itemCode: string
  /** Received less what went back already. */
  open: number
  records: InventoryDto[]
}

/**
 * The lines of an order with something to send back, and for each the stock records of its item with stock that is not
 * reserved; for a LOT item only the LOTs received on that line (PO26). Quarantined records count: they can go back.
 */
export function returnChoices(order: PurchaseOrderDto, stock: InventoryDto[]): ReturnChoice[] {
  return order.lines.map((line) => {
    const lots = new Set(order.receipts.filter((receipt) => receipt.lineId === line.lineId)
      .flatMap((receipt) => (receipt.lotNo ? [receipt.lotNo.toLowerCase()] : [])))
    return {
      lineId: line.lineId,
      itemCode: line.itemCode,
      open: round4(line.receivedQuantity - (line.returnedQuantity ?? 0)),
      records: stock.filter((row) => row.itemId === line.itemId && round4(row.quantity - row.reservedQuantity) > 0
        && (!line.lotTracked || (row.lotNo !== null && lots.has(row.lotNo.toLowerCase())))),
    }
  }).filter((choice) => choice.open > 0 && choice.records.length > 0)
}

/** "WH-A · LOT L-1 · 5 free · quarantined". */
export function stockChoiceText(row: InventoryDto): string {
  return `${row.location ?? 'no location'}${row.lotNo ? ` · LOT ${row.lotNo}` : ''} · ${formatQty(round4(row.quantity - row.reservedQuantity))} free`
    + `${row.inventoryStatus === 'quarantined' ? ' · quarantined' : ''}`
}

export interface ReturnForm {
  lineId: string
  inventoryId: string
  quantity: string
  reason: string
  /** The NCR the return is for (PO42); '' for none. */
  nonconformityId: string
  /** The supplier replaces it on the same order (PO46). */
  replacement: boolean
}

/** The first line and its first stock record, for the NCR given (PO42), replaced on the order unless said otherwise (PO46). */
export function returnForm(choices: ReturnChoice[], nonconformityId = '', replacement = true): ReturnForm {
  return { lineId: choices[0]?.lineId ?? '', inventoryId: choices[0]?.records[0]?.inventoryId ?? '', quantity: '', reason: '', nonconformityId,
    replacement }
}

/** The NCRs a return can name: open, with Return to supplier saved (PO42). */
export function returnNcrChoices(ncrs: NonconformityDto[]): NonconformityDto[] {
  return ncrs.filter((ncr) => ncr.status === 'open' && ncr.disposition === 'return_to_supplier')
}

/** "NCR-0001 · Sour yeast · YEAST LOT L-1". */
export function ncrChoiceText(ncr: NonconformityDto): string {
  return `${ncr.ncrNo} · ${ncr.title}${ncr.itemCode ? ` · ${ncr.itemCode}` : ''}${ncr.lotNo ? ` LOT ${ncr.lotNo}` : ''}`
}

/** The return to send, or what is wrong (PO26); the server checks again. */
export function returnPayload(
  choices: ReturnChoice[],
  form: ReturnForm,
  requestId: string,
): { input: PurchaseReturnInput; error: null } | { input: null; error: string } {
  const choice = choices.find((one) => one.lineId === form.lineId)
  if (!choice) return { input: null, error: 'Choose a line received on this order.' }
  const record = choice.records.find((row) => row.inventoryId === form.inventoryId)
  if (!record) return { input: null, error: `Choose the stock of ${choice.itemCode} to send back.` }
  const quantity = typedQuantity(form.quantity)
  if (quantity === null) return { input: null, error: `${choice.itemCode}: enter a quantity above 0 with at most four decimals.` }
  if (quantity > choice.open) return { input: null, error: `Only ${formatQty(choice.open)} of ${choice.itemCode} can go back.` }
  const free = round4(record.quantity - record.reservedQuantity)
  if (quantity > free) return { input: null, error: `Only ${formatQty(free)} of ${choice.itemCode} there is not reserved.` }
  const reason = form.reason.trim()
  if (!reason) return { input: null, error: 'Give a reason for the return.' }
  const input: PurchaseReturnInput = { requestId, lineId: choice.lineId, inventoryId: record.inventoryId, quantity, reason,
    replacement: form.replacement }
  return { input: form.nonconformityId ? { ...input, nonconformityId: form.nonconformityId } : input, error: null }
}

/**
 * "2041-09-05 10:00 · FLOUR 4 back from WH-A · LOT L-1 · Wet bags · for NCR-0001 · replacement on this order · by kim"
 * (PO28, PO42, PO46).
 */
export function returnText(back: PurchaseReturnDto): string {
  return `${savedAtText(back.returnedAt)} · ${back.itemCode} ${formatQty(back.quantity)} back${back.location ? ` from ${back.location}` : ''}`
    + `${back.lotNo ? ` · LOT ${back.lotNo}` : ''} · ${back.reason}${back.ncrNo ? ` · for ${back.ncrNo}` : ''}`
    + `${back.replacement ? ' · replacement on this order' : ''} · by ${back.returnedBy}`
}

/** The returns that name the NCR, oldest first, each with the order it went back on (PO43). */
export function ncrReturns(orders: PurchaseOrderDto[], nonconformityId: string): { order: PurchaseOrderDto; back: PurchaseReturnDto }[] {
  return orders.flatMap((order) => (order.returns ?? []).filter((back) => back.nonconformityId === nonconformityId)
    .map((back) => ({ order, back })))
    .sort((one, other) => one.back.returnedAt.localeCompare(other.back.returnedAt))
}

/** "2041-09-05 10:00 · YEAST 2 back on PO-0001 · LOT L-1 · replacement on the order · by kim" (PO43, PO46). */
export function ncrReturnText(order: PurchaseOrderDto, back: PurchaseReturnDto): string {
  return `${savedAtText(back.returnedAt)} · ${back.itemCode} ${formatQty(back.quantity)} back on ${order.orderNumber}`
    + `${back.lotNo ? ` · LOT ${back.lotNo}` : ''}${back.replacement ? ' · replacement on the order' : ''} · by ${back.returnedBy}`
}

/** What a return is worth at its line's price (PO47); null when the line has none. */
export function returnValue(order: Pick<PurchaseOrderDto, 'lines'>, back: Pick<PurchaseReturnDto, 'lineId' | 'quantity'>): number | null {
  const price = order.lines.find((line) => line.lineId === back.lineId)?.unitPrice
  return price == null ? null : round4(back.quantity * price)
}

/**
 * What of a return the supplier still owes back, in stock units (PO47): all of it unless it went back to be replaced; of a
 * replacement, what a closed order will not bring, the line's newest replacements first. Nothing once its credit is kept.
 */
export function owedQuantity(order: Pick<PurchaseOrderDto, 'status' | 'lines' | 'returns'>, back: PurchaseReturnDto): number {
  if (back.creditedAt) return 0
  if (!back.replacement) return back.quantity
  if (order.status !== 'closed') return 0
  const line = order.lines.find((one) => one.lineId === back.lineId)
  let left = Math.min(line?.replacementQuantity ?? 0, Math.max(0, line?.remainingQuantity ?? 0))
  const newestFirst = (order.returns ?? []).filter((one) => one.lineId === back.lineId && one.replacement)
    .sort((one, other) => other.returnedAt.localeCompare(one.returnedAt) || other.returnId.localeCompare(one.returnId))
  for (const one of newestFirst) {
    const owed = Math.min(one.quantity, left)
    if (one.returnId === back.returnId) return round4(owed)
    left = round4(left - owed)
  }
  return 0
}

const CREDIT_AMOUNT_PATTERN = /^\d+(\.\d{1,4})?$/

function typedCreditAmount(value: string): string | null {
  const amount = value.trim()
  if (!CREDIT_AMOUNT_PATTERN.test(amount)) return null
  const whole = amount.split('.')[0].replace(/^0+/, '')
  return whole.length <= 20 ? amount : null
}

/** Legacy numbers are usable only where separate four-decimal amounts cannot round to the same number. */
function readCreditAmount(text: string | null | undefined, amount: number | null | undefined): string | null {
  if (text !== undefined) return text === null ? null : typedCreditAmount(text)
  if (typeof amount !== 'number' || !Number.isFinite(amount) || amount < 0 || amount >= 2 ** 39) return null
  const value = String(amount)
  return CREDIT_AMOUNT_PATTERN.test(value) ? value : null
}

/** Format the whole part as an integer and keep the fractional digits as text. */
function formatCreditAmount(amount: string): string {
  const [whole, fraction = ''] = amount.split('.')
  const formatter = new Intl.NumberFormat(undefined, { maximumFractionDigits: 4 })
  const integer = formatter.format(BigInt(whole))
  const decimals = fraction.replace(/0+$/, '')
  if (!decimals) return integer
  const separator = formatter.formatToParts(0.1).find((part) => part.type === 'decimal')?.value ?? '.'
  return integer + separator + [...decimals].map((digit) => formatter.format(Number(digit))).join('')
}

/** " · credit due 10", " · credit due (no price)", " · credited 9.5 · CN-12 · by kim"; empty when nothing is owed (PO47). */
export function creditText(order: PurchaseOrderDto, back: PurchaseReturnDto): string {
  if (back.creditedAt) {
    const amount = readCreditAmount(back.creditAmountText, back.creditAmount)
    return ` · credited ${amount === null ? '(exact amount unavailable)' : formatCreditAmount(amount)}`
      + `${back.creditNote ? ` · ${back.creditNote}` : ''} · by ${back.creditedBy ?? ''}`
  }
  const owed = owedQuantity(order, back)
  if (owed <= 0) return ''
  const value = returnValue(order, { lineId: back.lineId, quantity: owed })
  return value == null ? ' · credit due (no price)' : ` · credit due ${formatQty(value)}`
}

/** "Credit due 10 (1 return without a price)": what the supplier still owes back for the order's returns (PO47); null for none. */
export function orderCreditText(order: PurchaseOrderDto): string | null {
  let owing = 0
  let unpriced = 0
  let due = 0
  for (const back of order.returns ?? []) {
    const owed = owedQuantity(order, back)
    if (owed <= 0) continue
    owing += 1
    const value = returnValue(order, { lineId: back.lineId, quantity: owed })
    if (value == null) unpriced += 1
    else due = round4(due + value)
  }
  if (owing === 0) return null
  const returns = (count: number) => `${count} ${count === 1 ? 'return' : 'returns'} without a price`
  if (unpriced === owing) return `Credit due on ${returns(unpriced)}`
  return `Credit due ${formatQty(due)}${unpriced ? ` (${returns(unpriced)})` : ''}`
}

/** The credit of a return as typed (PO47); a change, with its reason, once one was kept (PO52). */
export interface CreditForm {
  returnId: string
  amount: string
  creditNote: string
  reason: string
  /** A credit was kept for the return, so this changes it. */
  change: boolean
  /** An amount must be entered explicitly when the saved or suggested value cannot be read exactly. */
  amountWarning?: string
}

/**
 * The credit as it stands when one is kept; otherwise starting at what is owed at the line's price, else at the whole return's
 * worth, and empty without a price (PO47, PO52).
 */
export function creditForm(order: PurchaseOrderDto, back: PurchaseReturnDto): CreditForm {
  const change = Boolean(back.creditedAt) || (back.creditChanges ?? []).length > 0
  if (back.creditedAt) {
    const amount = readCreditAmount(back.creditAmountText, back.creditAmount)
    return { returnId: back.returnId, amount: amount ?? '', creditNote: back.creditNote ?? '', reason: '', change,
      ...(amount === null ? { amountWarning: 'The saved credit amount could not be read exactly. Enter the correct amount before saving.' } : {}) }
  }
  const value = returnValue(order, { lineId: back.lineId, quantity: owedQuantity(order, back) || back.quantity })
  const amount = readCreditAmount(undefined, value)
  return { returnId: back.returnId, amount: amount ?? '', creditNote: '', reason: '', change,
    ...(value != null && amount === null
      ? { amountWarning: 'The suggested credit amount could not be calculated exactly. Enter the correct amount before saving.' } : {}) }
}

/** The credit to send, with the reason for a change, or what is wrong (PO47, PO52); the server checks again. */
export function creditPayload(form: CreditForm):
  { input: PurchaseCreditInput & { reason?: string }; error: null } | { input: null; error: string } {
  const amount = form.amount.trim()
  if (!CREDIT_AMOUNT_PATTERN.test(amount)) {
    return { input: null, error: 'Enter the amount credited: 0 or more with at most four decimals.' }
  }
  if (typedCreditAmount(amount) === null) {
    return { input: null, error: 'The amount credited is too large: use at most 20 whole digits.' }
  }
  const reason = form.reason.trim()
  if (form.change && !reason) return { input: null, error: 'Give a reason for the change.' }
  const creditNote = form.creditNote.trim()
  const input = creditNote ? { amount, creditNote } : { amount }
  return { input: form.change ? { ...input, reason } : input, error: null }
}

/** Withdrawing a kept credit, which leaves the return owed again, or what is wrong (PO52). */
export function withdrawPayload(form: CreditForm): { input: PurchaseCreditChangeInput; error: null } | { input: null; error: string } {
  const reason = form.reason.trim()
  if (!reason) return { input: null, error: 'Give a reason for withdrawing the credit.' }
  return { input: { amount: null, reason }, error: null }
}

/** "2041-09-10 10:00 · credit 8 · CN-13 · Supplier sent less · by kim", "… · credit withdrawn · Wrong return · by kim" (PO52). */
export function creditChangeText(change: PurchaseCreditChangeDto): string {
  const withdrawn = change.amountText === null || (change.amountText === undefined && change.amount == null)
  const amount = readCreditAmount(change.amountText, change.amount)
  const what = withdrawn ? 'credit withdrawn'
    : `credit ${amount === null ? '(exact amount unavailable)' : formatCreditAmount(amount)}${change.creditNote ? ` · ${change.creditNote}` : ''}`
  return `${savedAtText(change.changedAt)} · ${what} · ${change.reason} · by ${change.changedBy}`
}

/** The date form of an order being received: its own expected date and those of the lines still open (PO22). */
export interface DatesForm {
  expectedDate: string
  lines: { lineId: string; itemCode: string; expectedDate: string }[]
  reason: string
}

export function datesForm(order: PurchaseOrderDto): DatesForm {
  return {
    expectedDate: order.expectedDate ?? '',
    lines: order.lines.filter((line) => line.remainingQuantity > 0)
      .map((line) => ({ lineId: line.lineId, itemCode: line.itemCode, expectedDate: line.expectedDate ?? '' })),
    reason: '',
  }
}

/** The dates to send, with only the lines that move, or what is wrong (PO22). */
export function datesPayload(
  order: PurchaseOrderDto,
  form: DatesForm,
): { input: PurchaseOrderDatesInput; error: null } | { input: null; error: string } {
  const before = new Map(order.lines.map((line) => [line.lineId, line.expectedDate]))
  const lines = form.lines
    .filter((row) => (row.expectedDate || null) !== (before.get(row.lineId) ?? null))
    .map((row) => ({ lineId: row.lineId, expectedDate: row.expectedDate || null }))
  const expectedDate = form.expectedDate || null
  if (expectedDate === order.expectedDate && lines.length === 0) return { input: null, error: 'Change a date first.' }
  const reason = form.reason.trim()
  if (!reason) return { input: null, error: 'Give a reason for the new dates.' }
  return { input: { expectedDate, lines, reason }, error: null }
}

/** "2041-09-12 10:00 · order 2041-09-10 → 2041-09-15 · Supplier delayed · by kim"; a line shows its item, no date "none" (PO23). */
export function dateChangeText(change: PurchaseOrderDateChangeDto): string {
  return `${savedAtText(change.changedAt)} · ${change.itemCode ?? 'order'} ${change.previousDate ?? 'none'} → ${change.expectedDate ?? 'none'}`
    + ` · ${change.reason} · by ${change.changedBy}`
}

/**
 * "FLOUR · 10 kg", "FLOUR · 4 of 10 kg received", "FLOUR · 6 of 10 kg received · 4 returned, 4 for replacement": what was sent
 * back to be replaced does not count as received until its replacement came (PO46).
 */
export function lineText(line: PurchaseOrderLineDto): string {
  const unit = line.unitCode ? ` ${line.unitCode}` : ''
  const replacing = line.replacementQuantity ?? 0
  const kept = round4(line.receivedQuantity - replacing)
  const amount = kept > 0
    ? `${formatQty(kept)} of ${formatQty(line.quantity)}${unit} received`
    : `${formatQty(line.quantity)}${unit}`
  return `${line.itemCode} · ${amount}${line.returnedQuantity ? ` · ${formatQty(line.returnedQuantity)} returned` : ''}`
    + `${replacing ? `, ${formatQty(replacing)} for replacement` : ''}${line.expectedDate ? ` · expected ${line.expectedDate}` : ''}`
}

/** "2041-09-02 10:00 · FLOUR 4 at WH-A · LOT L-1 · by kim". */
export function receiptText(receipt: PurchaseReceiptDto): string {
  return `${savedAtText(receipt.receivedAt)} · ${receipt.itemCode} ${formatQty(receipt.quantity)}`
    + `${receipt.location ? ` at ${receipt.location}` : ''}${receipt.lotNo ? ` · LOT ${receipt.lotNo}` : ''} · by ${receipt.receivedBy}`
}

/** What is left to receive on ordered purchase orders, by item (PO8); drafts and ended orders do not count. */
export function onOrderByItem(orders: PurchaseOrderDto[], within: Set<string> | null = null): Map<string, number> {
  const result = new Map<string, number>()
  for (const order of orders) {
    if (order.status !== 'ordered') continue
    // For a place, only what is to arrive there or inside it (PO40).
    if (within && !(order.deliverTo && within.has(order.deliverTo.trim().toLowerCase()))) continue
    for (const line of order.lines) {
      if (line.remainingQuantity > 0) {
        result.set(line.itemId, Math.round(((result.get(line.itemId) ?? 0) + line.remainingQuantity) * 10000) / 10000)
      }
    }
  }
  return result
}

/**
 * Draft lines for what the reorder list suggests beyond what is already on order (PO9). Items that take no new stock are
 * left out, and so are those fully on order.
 */
export function reorderDraftLines(
  rows: { itemId: string; quantity: number; active: boolean; unitPrice?: number | null }[],
  onOrder: Map<string, number>,
): PurchaseOrderInput['lines'] {
  return rows
    .filter((row) => row.active)
    .map((row) => ({ ...row, quantity: Math.round(Math.max(0, row.quantity - (onOrder.get(row.itemId) ?? 0)) * 10000) / 10000 }))
    .filter((row) => row.quantity > 0)
    .map((row) => ({ itemId: row.itemId, quantity: row.quantity, expectedDate: null, note: null, ...startingPrice(row.unitPrice) }))
}

/** A draft line starts at the item's unit cost when it has one above 0 (PO14); the price is agreed on the order. */
function startingPrice(unitCost: number | null | undefined): { unitPrice?: number } {
  return unitCost && unitCost > 0 ? { unitPrice: unitCost } : {}
}

/** "Order value 13.5", "Order value 12.5 (1 line without a price)"; null when no line has a price (PO14). */
export function orderValueText(order: Pick<PurchaseOrderDto, 'lines' | 'orderValue' | 'valueComplete'>): string | null {
  const unpriced = order.lines.filter((line) => line.unitPrice == null).length
  if (unpriced === order.lines.length) return null
  return `Order value ${formatQty(order.orderValue ?? 0)}${order.valueComplete || unpriced === 0 ? ''
    : ` (${unpriced} ${unpriced === 1 ? 'line' : 'lines'} without a price)`}`
}

/** " · 1.25 each = 12.5" for a priced line; empty without a price. */
export function linePriceText(line: Pick<PurchaseOrderLineDto, 'unitPrice' | 'lineValue'>): string {
  return line.unitPrice == null ? '' : ` · ${formatQty(line.unitPrice)} each = ${formatQty(line.lineValue ?? 0)}`
}

/**
 * Draft lines for what open work orders leave short (PO12): bought materials only, since one with its own approved BOM is
 * made (multi-level-bom.md), and only items that take new stock. The shortage already leaves out what is on order (PO8); it
 * is rounded up to whole purchase units when the item has them.
 */
export function needsDraftLines(
  lines: Pick<MaterialRequirementDto['lines'][number], 'itemId' | 'shortage' | 'madeHere'>[],
  items: Map<string, ItemDto>,
): PurchaseOrderInput['lines'] {
  const result: PurchaseOrderInput['lines'] = []
  for (const line of lines) {
    const item = items.get(line.itemId)
    if (line.shortage <= 0 || line.madeHere || !item || !isActiveItem(item)) continue
    const packs = item.purchaseUnit ? packsFor(line.shortage, item.purchaseUnitQty) : null
    const quantity = packs === null ? line.shortage : Math.round(packs * (item.purchaseUnitQty as number) * 10000) / 10000
    result.push({ itemId: line.itemId, quantity, expectedDate: null, note: null, ...startingPrice(item.unitCost) })
  }
  return result
}

/** Suppliers the project's orders already name, each once as first written, sorted: suggestions for a new order (PO10). */
export function knownSuppliers(orders: PurchaseOrderDto[]): string[] {
  const seen = new Map<string, string>()
  for (const order of orders) {
    const name = order.supplierName.trim()
    if (name && !seen.has(name.toLowerCase())) seen.set(name.toLowerCase(), name)
  }
  return [...seen.values()].sort((left, right) => left.localeCompare(right))
}

/** An ordered order whose expected date is before the project's today: not everything has arrived in time (PO10). */
export function isLate(order: Pick<PurchaseOrderDto, 'status' | 'expectedDate'>, today: string): boolean {
  return order.status === 'ordered' && order.expectedDate !== null && order.expectedDate < today
}

/** The LOT a receipt went into, read from the stock record it names, for its receipt checks (PO11); null without a LOT. */
export function receiptLot(
  receipt: Pick<PurchaseReceiptDto, 'inventoryId' | 'lotNo'>,
  line: Pick<PurchaseOrderLineDto, 'itemId'> | undefined,
  stock: Pick<InventoryDto, 'inventoryId' | 'lotId'>[],
): { lotId: string; lotNo: string; itemId: string } | null {
  if (!receipt.lotNo || !line) return null
  const lotId = stock.find((row) => row.inventoryId === receipt.inventoryId)?.lotId
  return lotId ? { lotId, lotNo: receipt.lotNo, itemId: line.itemId } : null
}

/** One receipt a LOT came in on. */
export interface LotOrigin {
  orderNumber: string
  supplierName: string
  quantity: number
  receivedAt: string
}

/**
 * Where a LOT came from: the receipts of purchase orders that brought it in, for the same item, oldest first (PO13). LOT
 * numbers are matched ignoring case, as the project keeps them unique.
 */
export function lotOrigins(orders: PurchaseOrderDto[], lot: { lotNo: string; itemId: string }): LotOrigin[] {
  const key = lot.lotNo.trim().toLowerCase()
  const found: LotOrigin[] = []
  for (const order of orders) {
    const itemOfLine = new Map(order.lines.map((line) => [line.lineId, line.itemId]))
    for (const receipt of order.receipts) {
      if (receipt.lotNo?.trim().toLowerCase() === key && itemOfLine.get(receipt.lineId) === lot.itemId) {
        found.push({ orderNumber: order.orderNumber, supplierName: order.supplierName, quantity: receipt.quantity, receivedAt: receipt.receivedAt })
      }
    }
  }
  return found.sort((left, right) => Date.parse(left.receivedAt) - Date.parse(right.receivedAt))
}

/** "Received on PO-0001 from Mill Co: 2 on 2041-09-02 10:00". */
export function originText(origin: LotOrigin): string {
  return `Received on ${origin.orderNumber} from ${origin.supplierName}: ${formatQty(origin.quantity)} on ${savedAtText(origin.receivedAt)}`
}

/** "Mill Co (PO-0001)", "Mill Co (PO-0001, PO-0003), Salt Ltd (PO-0002)": who a LOT came from; empty when no order brought it. */
/** The purchase orders a LOT came on, once each and oldest first, to open them (PO37); ones not in the list are left out. */
export function originOrders(
  origins: LotOrigin[],
  orders: Pick<PurchaseOrderDto, 'purchaseOrderId' | 'orderNumber'>[],
): { purchaseOrderId: string; orderNumber: string }[] {
  const seen = new Set<string>()
  return origins.flatMap((origin) => {
    const order = orders.find((one) => one.orderNumber === origin.orderNumber)
    if (!order || seen.has(order.purchaseOrderId)) return []
    seen.add(order.purchaseOrderId)
    return [{ purchaseOrderId: order.purchaseOrderId, orderNumber: order.orderNumber }]
  })
}

export function originSuppliers(origins: LotOrigin[]): string {
  const bySupplier = new Map<string, string[]>()
  for (const origin of origins) {
    const numbers = bySupplier.get(origin.supplierName) ?? []
    if (!numbers.includes(origin.orderNumber)) numbers.push(origin.orderNumber)
    bySupplier.set(origin.supplierName, numbers)
  }
  return [...bySupplier.entries()].map(([supplier, numbers]) => `${supplier} (${numbers.join(', ')})`).join(', ')
}

/**
 * A line whose agreed price differs from its item's unit cost, once something was received at that price: it can be taken
 * as the unit cost by hand (PO15). Null otherwise; the unit cost never changes by itself (ADR-027 decision 9).
 */
export function costSuggestion(
  line: Pick<PurchaseOrderLineDto, 'itemId' | 'itemCode' | 'unitPrice' | 'receivedQuantity'>,
  item: Pick<ItemDto, 'unitCost'> | undefined,
): { itemId: string; itemCode: string; from: number | null; to: number } | null {
  if (line.unitPrice == null || line.receivedQuantity <= 0 || !item) return null
  const from = item.unitCost ?? null
  return from === line.unitPrice ? null : { itemId: line.itemId, itemCode: line.itemCode, from, to: line.unitPrice }
}

/**
 * A purchase order as a page to print or hand to the supplier (PO16): its number, supplier, dates, lines with quantities,
 * prices and values, the order value and the note. Every value is escaped. With the supplier's own codes for items, by item
 * id, a "Their code" column shows them (PO32).
 */
export function orderSheetHtml(order: PurchaseOrderDto, supplierCodes: Map<string, string> = new Map()): string {
  // The code kept on the line as it was ordered; where none was kept, the usual supplier's code now (PO32, PO45).
  const codeOf = (line: PurchaseOrderLineDto) => line.supplierItemCode ?? supplierCodes.get(line.itemId)
  const coded = order.lines.some((line) => codeOf(line) !== undefined)
  const cell = (value: string, number = false) => `<td${number ? ' class="num"' : ''}>${escapeHtml(value)}</td>`
  const rows = order.lines.map((line) => `<tr>${cell(String(line.lineNo))}${cell(line.itemCode)}${cell(line.itemName ?? '')}`
    + `${coded ? cell(codeOf(line) ?? '') : ''}`
    + `${cell(`${formatQty(line.quantity)}${line.unitCode ? ` ${line.unitCode}` : ''}`, true)}`
    + `${cell(line.unitPrice == null ? '' : formatQty(line.unitPrice), true)}${cell(line.lineValue == null ? '' : formatQty(line.lineValue), true)}</tr>`)
  const facts = [`Supplier: ${order.supplierName}`, `Status: ${order.status}`,
    order.expectedDate ? `Expected: ${order.expectedDate}` : null, order.deliverTo ? `Deliver to: ${order.deliverTo}` : null, order.orderedAt ? `Ordered: ${savedAtText(order.orderedAt)}` : null]
    .filter((fact): fact is string => fact !== null)
  const value = orderValueText(order)
  return '<!doctype html><html><head><meta charset="utf-8"><title>' + escapeHtml(order.orderNumber) + '</title><style>'
    + 'body{font-family:system-ui,sans-serif;margin:15mm;color:#000;background:#fff}h1{font-size:18pt;margin:0 0 4mm}'
    + 'table{width:100%;border-collapse:collapse;margin-top:6mm}th,td{border-bottom:1px solid #ccc;padding:2mm;text-align:left}'
    + '.num{text-align:right}.facts p{margin:1mm 0}@media print{.noprint{display:none}}</style></head><body>'
    + '<p class="noprint"><button type="button" onclick="window.print()">Print</button></p>'
    + `<h1>Purchase order ${escapeHtml(order.orderNumber)}</h1>`
    + `<div class="facts">${facts.map((fact) => `<p>${escapeHtml(fact)}</p>`).join('')}</div>`
    + `<table><thead><tr><th>#</th><th>Item</th><th>Name</th>${coded ? '<th>Their code</th>' : ''}<th class="num">Quantity</th>`
    + '<th class="num">Unit price</th>'
    + `<th class="num">Value</th></tr></thead><tbody>${rows.join('')}</tbody></table>`
    + (value ? `<p><strong>${escapeHtml(value)}</strong></p>` : '')
    + (order.note ? `<p>Note: ${escapeHtml(order.note)}</p>` : '')
    + '</body></html>'
}

/** Where a draft stands with the owner's approval (PO34): not needed, needed, or given. */
export function approvalState(order: Pick<PurchaseOrderDto, 'status' | 'needsApproval' | 'approvedAt'>): 'none' | 'needed' | 'approved' {
  if (order.status !== 'draft' || !order.needsApproval) return 'none'
  return order.approvedAt ? 'approved' : 'needed'
}

/** "Approved by kim 2041-09-02 10:00"; after approvals in turn, "Approved by kim 2041-09-02 10:00, then lee 2041-09-03 09:00" (PO50). */
export function approvedText(order: Pick<PurchaseOrderDto, 'approvedBy' | 'approvedAt' | 'approvals'>): string {
  const given = (order.approvals ?? []).filter((step): step is PurchaseApprovalDto & { approvedAt: string } => step.approvedAt !== null)
  if (given.length > 1) return `Approved by ${given.map((step) => `${step.approverId} ${savedAtText(step.approvedAt)}`).join(', then ')}`
  return `Approved by ${order.approvedBy ?? 'the owner'}${order.approvedAt ? ` ${savedAtText(order.approvedAt)}` : ''}`
}

/**
 * What a draft that needs approval waits for (PO34, PO50): the owner alone as before; with member steps, every approver in turn,
 * "kim ✓ 2041-09-02 10:00 → lee → the project owner".
 */
export function approvalNote(order: Pick<PurchaseOrderDto, 'approvals'>): string {
  const steps = order.approvals ?? []
  if (steps.length === 0 || (steps.length === 1 && steps[0].projectOwner && !steps[0].approvedAt)) {
    return "Needs the project owner's approval before it is ordered."
  }
  return `Needs approval in turn before it is ordered: ${steps.map((step) => `${step.projectOwner ? 'the project owner' : step.approverId}`
    + `${step.approvedAt ? ` ✓ ${savedAtText(step.approvedAt)}` : ''}`).join(' → ')}`
}

/** Whether the signed-in user approves the draft now: the first approver it still waits for, or the owner when it names none (PO50). */
export function canApprove(order: Pick<PurchaseOrderDto, 'approvals'>, me: string | undefined, owner: boolean): boolean {
  const waiting = (order.approvals ?? []).filter((step) => !step.approvedAt)
  if (waiting.length === 0) return owner
  return Boolean(me) && waiting[0].approverId === me
}

/** A member approval step as typed (PO50). */
export interface ApprovalStepRow {
  fromValue: string
  approverId: string
}

/** The steps to send, lowest value first, or what is wrong; the server checks again (PO50). */
export function approvalStepsPayload(rows: ApprovalStepRow[]):
  { steps: { fromValue: number; approverId: string }[]; error: null } | { steps: null; error: string } {
  const steps: { fromValue: number; approverId: string }[] = []
  for (const [index, row] of rows.entries()) {
    const value = typedPrice(row.fromValue)
    if (value == null) return { steps: null, error: `Step ${index + 1}: enter the value it starts from, 0 or more with at most four decimals.` }
    if (!row.approverId) return { steps: null, error: `Step ${index + 1}: choose who approves.` }
    if (steps.some((one) => one.approverId === row.approverId)) return { steps: null, error: `${row.approverId} is on two steps.` }
    steps.push({ fromValue: value, approverId: row.approverId })
  }
  return { steps: steps.sort((one, other) => one.fromValue - other.fromValue), error: null }
}

/**
 * What the project's approvals mean (PO33, PO50): the owner's limit alone as before; with member steps, every approver by value,
 * the owner last among equal values.
 */
export function approvalRulesText(limit: number | null, steps: { fromValue: number; approverId: string; canApprove?: boolean }[] = []): string {
  if (steps.length === 0) return approvalLimitText(limit)
  const rules = [
    ...steps.map((step) => ({ from: step.fromValue, owner: false,
      who: `${step.approverId}${step.canApprove === false ? ' (cannot approve now)' : ''}` })),
    ...(limit === null ? [] : [{ from: limit, owner: true, who: 'the project owner' }]),
  ].sort((one, other) => one.from - other.from || Number(one.owner) - Number(other.owner))
  return `Purchase orders are approved in turn: ${rules.map((rule) => `from ${formatQty(rule.from)} by ${rule.who}`).join(', ')}`
    + '; a line not priced yet needs every approval.'
}

/** What the project's approval limit means (PO33). */
export function approvalLimitText(limit: number | null): string {
  return limit === null
    ? 'Purchase orders do not need approval.'
    : `Purchase orders worth ${formatQty(limit)} or more, or with a line not priced yet, need the project owner's approval.`
}

/** What can be done with an order now (PO3-PO4). */
export function orderActions(order: PurchaseOrderDto): { edit: boolean; order: boolean; receive: boolean; close: boolean; cancel: boolean } {
  const received = order.receipts.length > 0
  return {
    edit: order.status === 'draft',
    order: order.status === 'draft',
    receive: order.status === 'ordered',
    close: order.status === 'ordered' && received,
    cancel: (order.status === 'draft' || order.status === 'ordered') && !received,
  }
}
