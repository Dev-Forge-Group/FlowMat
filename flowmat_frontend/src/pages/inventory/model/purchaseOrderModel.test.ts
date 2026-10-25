import { describe, expect, it } from 'vitest'
import type {
  PurchaseApprovalDto,
  PurchaseOrderDto,
  PurchaseOrderLineDto,
  PurchaseReturnDto,
} from '../../../entities/purchasing/api/usePurchaseOrders'
import type { NonconformityDto } from '../../../entities/quality/api/useNonconformities'
import type { InventoryDto } from '../../../shared/types/api'
import {
  EMPTY_ORDER_FORM,
  approvalLimitText,
  approvalNote,
  approvalRulesText,
  approvalState,
  approvalStepsPayload,
  approvedText,
  canApprove,
  costSuggestion,
  creditChangeText,
  creditForm,
  creditPayload,
  creditText,
  dateChangeText,
  datesForm,
  datesPayload,
  isLate,
  knownSuppliers,
  linePriceText,
  lineText,
  lotOrigins,
  needsDraftLines,
  onOrderByItem,
  orderActions,
  orderCreditText,
  orderForm,
  orderPayload,
  orderSheetHtml,
  orderValueText,
  originOrders,
  originSuppliers,
  originText,
  owedQuantity,
  receiptLot,
  receiptText,
  receiveForm,
  receivePayload,
  reorderDraftLines,
  returnChoices,
  ncrChoiceText,
  ncrReturnText,
  ncrReturns,
  returnForm,
  returnNcrChoices,
  returnPayload,
  returnText,
  returnValue,
  stockChoiceText,
  typedPrice,
  typedQuantity,
  withdrawPayload,
} from './purchaseOrderModel'

const line = (overrides: Partial<PurchaseOrderLineDto> = {}): PurchaseOrderLineDto => ({
  lineId: 'l1', lineNo: 1, itemId: 'flour', itemCode: 'FLOUR', itemName: 'Flour', unitCode: 'kg', lotTracked: false, quantity: 10,
  receivedQuantity: 0, remainingQuantity: 10, expectedDate: null, note: null, ...overrides,
})
const yeast = line({ lineId: 'l2', lineNo: 2, itemId: 'yeast', itemCode: 'YEAST', lotTracked: true, quantity: 2, remainingQuantity: 2 })
const order = (overrides: Partial<PurchaseOrderDto> = {}): PurchaseOrderDto => ({
  purchaseOrderId: 'po1', projectId: 'p', orderNumber: 'PO-0001', supplierName: 'Mill Co', status: 'ordered', expectedDate: null,
  note: null, createdBy: 'kim', createdAt: '2041-09-01T09:00:00', orderedBy: 'kim', orderedAt: '2041-09-01T09:05:00', endedBy: null,
  endedAt: null, endReason: null, lines: [line(), yeast], receipts: [], ...overrides,
})
const receipt = { receiptId: 'r', lineId: 'l1', itemCode: 'FLOUR', quantity: 4, inventoryId: 'i', location: 'WH-A', lotNo: null,
  receivedBy: 'kim', receivedAt: '2041-09-02T10:00:00' }

describe('purchase orders', () => {
  it('reads quantities as typed', () => {
    expect(typedQuantity(' 2.5 ')).toBe(2.5)
    expect(typedQuantity('1.0001')).toBe(1.0001)
    expect(typedQuantity('1.00001')).toBeNull()
    expect(typedQuantity('0')).toBeNull()
    expect(typedQuantity('-1')).toBeNull()
    expect(typedQuantity('ten')).toBeNull()
  })

  it('makes a draft from the form and names what is wrong', () => {
    const form = { ...EMPTY_ORDER_FORM, supplierName: ' Mill Co ', lines: [{ itemId: 'flour', quantity: '10' }, { itemId: '', quantity: '' }] }
    expect(orderPayload('p', form)).toEqual({ input: { projectId: 'p', supplierName: 'Mill Co', expectedDate: null, note: null,
      lines: [{ itemId: 'flour', quantity: 10, expectedDate: null, note: null }] }, error: null })
    expect(orderPayload('p', { ...form, supplierName: ' ' }).error).toBe('Name the supplier.')
    expect(orderPayload('p', { ...form, lines: [] }).error).toBe('Add at least one line.')
    expect(orderPayload('p', { ...form, lines: [{ itemId: '', quantity: '3' }] }).error).toBe('Choose the item on line 1.')
    expect(orderPayload('p', { ...form, lines: [{ itemId: 'flour', quantity: '0' }] }).error)
      .toBe('Line 1: enter a quantity above 0 with at most four decimals.')
    expect(orderPayload('p', { ...form, lines: [{ itemId: 'flour', quantity: '1' }, { itemId: 'flour', quantity: '2' }] },
      () => 'FLOUR').error).toBe('FLOUR is on two lines; combine them.')
    expect(orderForm(order()).lines).toEqual([{ itemId: 'flour', quantity: '10' }, { itemId: 'yeast', quantity: '2' }])
  })

  it('receives what arrived, within what is left and with a LOT where needed', () => {
    const po = order({ lines: [line({ receivedQuantity: 4, remainingQuantity: 6 }), yeast] })
    const rows = receiveForm(po)
    expect(rows.map((row) => row.quantity)).toEqual(['6', '2'])
    expect(receivePayload(po, rows, 'r1').error).toBe('YEAST is LOT-tracked; give the LOT number.')
    const filled = [{ ...rows[0], location: ' WH-A ' }, { ...rows[1], lotNo: ' L-1 ', expiryDate: '2041-12-31' }]
    expect(receivePayload(po, filled, 'r1')).toEqual({ input: { requestId: 'r1', lines: [
      { lineId: 'l1', quantity: 6, location: 'WH-A', lotNo: null, expiryDate: null },
      { lineId: 'l2', quantity: 2, location: null, lotNo: 'L-1', expiryDate: '2041-12-31' }] }, error: null })
    expect(receivePayload(po, [{ ...rows[0], quantity: '7' }], 'r1').error).toBe('Only 6 of FLOUR is left to receive.')
    expect(receivePayload(po, [{ ...rows[0], quantity: '' }], 'r1').error).toBe('Enter what arrived on at least one line.')
    expect(receiveForm(order({ lines: [line({ receivedQuantity: 10, remainingQuantity: 0 })] }))).toEqual([])
  })

  it('says what a line and a receipt are', () => {
    expect(lineText(line())).toBe('FLOUR · 10 kg')
    expect(lineText(line({ receivedQuantity: 4, expectedDate: '2041-09-10' }))).toBe('FLOUR · 4 of 10 kg received · expected 2041-09-10')
    expect(receiptText(receipt)).toBe('2041-09-02 10:00 · FLOUR 4 at WH-A · by kim')
    expect(receiptText({ ...receipt, location: null, lotNo: 'L-1' })).toBe('2041-09-02 10:00 · FLOUR 4 · LOT L-1 · by kim')
  })

  it('offers what an order allows now', () => {
    expect(orderActions(order({ status: 'draft' }))).toEqual({ edit: true, order: true, receive: false, close: false, cancel: true })
    expect(orderActions(order())).toEqual({ edit: false, order: false, receive: true, close: false, cancel: true })
    expect(orderActions(order({ receipts: [receipt] }))).toEqual({ edit: false, order: false, receive: true, close: true, cancel: false })
    expect(orderActions(order({ status: 'received' }))).toEqual({ edit: false, order: false, receive: false, close: false, cancel: false })
  })

  it('suggests the suppliers already used, marks a late order and finds the LOT a receipt went into', () => {
    expect(knownSuppliers([order({ supplierName: ' Mill Co ' }), order({ supplierName: 'mill co' }), order({ supplierName: 'Salt Ltd' })]))
      .toEqual(['Mill Co', 'Salt Ltd'])
    expect(isLate({ status: 'ordered', expectedDate: '2041-09-09' }, '2041-09-10')).toBe(true)
    expect(isLate({ status: 'ordered', expectedDate: '2041-09-10' }, '2041-09-10')).toBe(false)
    expect(isLate({ status: 'ordered', expectedDate: null }, '2041-09-10')).toBe(false)
    expect(isLate({ status: 'received', expectedDate: '2041-09-01' }, '2041-09-10')).toBe(false)
    const stock = [{ inventoryId: 'i2', lotId: 'lot-1' }, { inventoryId: 'i1', lotId: null }]
    expect(receiptLot({ inventoryId: 'i2', lotNo: 'L-1' }, yeast, stock)).toEqual({ lotId: 'lot-1', lotNo: 'L-1', itemId: 'yeast' })
    expect(receiptLot({ inventoryId: 'i1', lotNo: null }, line(), stock)).toBeNull()
    expect(receiptLot({ inventoryId: 'gone', lotNo: 'L-2' }, yeast, stock)).toBeNull()
  })

  it('tells where a LOT came from', () => {
    const received = (receiptId: string, lineId: string, lotNo: string | null, at: string) => ({ ...receipt, receiptId, lineId, lotNo,
      quantity: 2, receivedAt: at })
    const orders = [
      order({ orderNumber: 'PO-0003', receipts: [received('r3', 'l2', 'l-1', '2041-09-05T10:00:00')] }),
      order({ receipts: [received('r1', 'l2', 'L-1', '2041-09-02T10:00:00'), received('r2', 'l1', 'L-1', '2041-09-02T10:00:00')] }),
      order({ orderNumber: 'PO-0002', supplierName: 'Salt Ltd', receipts: [received('r4', 'l2', 'L-2', '2041-09-03T10:00:00')] }),
    ]
    const origins = lotOrigins(orders, { lotNo: 'L-1', itemId: 'yeast' })
    expect(origins.map((one) => one.orderNumber)).toEqual(['PO-0001', 'PO-0003'])
    expect(originText(origins[0])).toBe('Received on PO-0001 from Mill Co: 2 on 2041-09-02 10:00')
    expect(originSuppliers(origins)).toBe('Mill Co (PO-0001, PO-0003)')
    expect(originSuppliers([])).toBe('')
    expect(lotOrigins(orders, { lotNo: 'L-2', itemId: 'flour' })).toEqual([])
  })

  it('takes an optional unit price and adds up what the order is worth', () => {
    expect(typedPrice('')).toBeNull()
    expect(typedPrice(' 1.25 ')).toBe(1.25)
    expect(typedPrice('0')).toBe(0)
    expect(typedPrice('1.00001')).toBeUndefined()
    expect(typedPrice('-1')).toBeUndefined()
    const form = { ...EMPTY_ORDER_FORM, supplierName: 'Mill Co', lines: [{ itemId: 'flour', quantity: '10', unitPrice: '1.25' },
      { itemId: 'salt', quantity: '3', unitPrice: '' }] }
    expect(orderPayload('p', form).input?.lines).toEqual([
      { itemId: 'flour', quantity: 10, expectedDate: null, note: null, unitPrice: 1.25 },
      { itemId: 'salt', quantity: 3, expectedDate: null, note: null },
    ])
    expect(orderPayload('p', { ...form, lines: [{ itemId: 'flour', quantity: '10', unitPrice: 'x' }] }).error)
      .toBe('Line 1: enter a unit price of 0 or more with at most four decimals, or leave it empty.')
    const priced = order({ lines: [line({ unitPrice: 1.25, lineValue: 12.5 }), yeast], orderValue: 12.5, valueComplete: false })
    expect(orderValueText(priced)).toBe('Order value 12.5 (1 line without a price)')
    expect(orderValueText(order({ lines: [line({ unitPrice: 1.25, lineValue: 12.5 })], orderValue: 12.5, valueComplete: true })))
      .toBe('Order value 12.5')
    expect(orderValueText(order())).toBeNull()
    expect(linePriceText(priced.lines[0])).toBe(' · 1.25 each = 12.5')
    expect(linePriceText(yeast)).toBe('')
    expect(orderForm(priced).lines).toEqual([{ itemId: 'flour', quantity: '10', unitPrice: '1.25' }, { itemId: 'yeast', quantity: '2' }])
    expect(reorderDraftLines([{ itemId: 'flour', quantity: 4, active: true, unitPrice: 1.2 },
      { itemId: 'salt', quantity: 1, active: true, unitPrice: 0 }], new Map())).toEqual([
      { itemId: 'flour', quantity: 4, expectedDate: null, note: null, unitPrice: 1.2 },
      { itemId: 'salt', quantity: 1, expectedDate: null, note: null },
    ])
  })

  it('offers a received price as the unit cost only when it differs', () => {
    const priced = line({ unitPrice: 1.25, receivedQuantity: 4 })
    expect(costSuggestion(priced, { unitCost: 1.2 })).toEqual({ itemId: 'flour', itemCode: 'FLOUR', from: 1.2, to: 1.25 })
    expect(costSuggestion(priced, { unitCost: null })).toEqual({ itemId: 'flour', itemCode: 'FLOUR', from: null, to: 1.25 })
    expect(costSuggestion(priced, { unitCost: 1.25 })).toBeNull()
    expect(costSuggestion(line({ unitPrice: 1.25 }), { unitCost: 1.2 })).toBeNull()
    expect(costSuggestion(line({ receivedQuantity: 4 }), { unitCost: 1.2 })).toBeNull()
    expect(costSuggestion(priced, undefined)).toBeNull()
  })

  it('lays an order out as a page to print, escaping what it shows', () => {
    const html = orderSheetHtml(order({ supplierName: 'Mill & <Co>', note: 'Deliver "before" 9', expectedDate: '2041-09-10',
      lines: [line({ unitPrice: 1.25, lineValue: 12.5 }), yeast], orderValue: 12.5, valueComplete: false }))
    expect(html).toContain('<title>PO-0001</title>')
    expect(html).toContain('<h1>Purchase order PO-0001</h1>')
    expect(html).toContain('<p>Supplier: Mill &amp; &lt;Co&gt;</p>')
    expect(html).toContain('<p>Expected: 2041-09-10</p>')
    expect(html).toContain('<tr><td>1</td><td>FLOUR</td><td>Flour</td><td class="num">10 kg</td><td class="num">1.25</td><td class="num">12.5</td></tr>')
    expect(html).toContain('<td class="num">2 kg</td><td class="num"></td><td class="num"></td>')
    expect(html).toContain('<strong>Order value 12.5 (1 line without a price)</strong>')
    expect(html).toContain('<p>Note: Deliver &quot;before&quot; 9</p>')
    expect(html).not.toContain('<Co>')
    expect(html).not.toContain('Their code')
    // The supplier's own codes, where it has one (PO32).
    const coded = orderSheetHtml(order({ lines: [line(), yeast] }), new Map([['flour', 'MC-<77>']]))
    expect(coded).toContain('<th>Name</th><th>Their code</th><th class="num">Quantity</th>')
    expect(coded).toContain('<td>Flour</td><td>MC-&lt;77&gt;</td><td class="num">10 kg</td>')
    expect(coded).toContain('<td>YEAST</td><td>Flour</td><td></td><td class="num">2 kg</td>')
    // An ordered line keeps the code it was ordered with, whatever the usual code is now (PO45).
    const kept = orderSheetHtml(order({ status: 'ordered', lines: [line({ supplierItemCode: 'MC-12' }), yeast] }), new Map([['flour', 'MC-<77>']]))
    expect(kept).toContain('<td>Flour</td><td>MC-12</td><td class="num">10 kg</td>')
    expect(orderSheetHtml(order({ lines: [line(), { ...yeast, supplierItemCode: 'YC-1' }] }))).toContain('<th>Their code</th>')
  })

  it('sends only the dates that move, with a reason, and tells each change', () => {
    const salt = line({ lineId: 'l2', lineNo: 2, itemId: 'salt', itemCode: 'SALT', quantity: 5, receivedQuantity: 5, remainingQuantity: 0 })
    const ordered = order({ expectedDate: '2041-09-10', lines: [line(), salt] })
    const form = datesForm(ordered)
    expect(form).toEqual({ expectedDate: '2041-09-10', lines: [{ lineId: 'l1', itemCode: 'FLOUR', expectedDate: '' }], reason: '' })
    expect(datesPayload(ordered, form).error).toBe('Change a date first.')
    expect(datesPayload(ordered, { ...form, expectedDate: '2041-09-15' }).error).toBe('Give a reason for the new dates.')
    expect(datesPayload(ordered, { ...form, expectedDate: '2041-09-15', reason: ' Supplier delayed ' }).input)
      .toEqual({ expectedDate: '2041-09-15', lines: [], reason: 'Supplier delayed' })
    expect(datesPayload(ordered, { expectedDate: '', lines: [{ lineId: 'l1', itemCode: 'FLOUR', expectedDate: '2041-09-14' }], reason: 'Split' }).input)
      .toEqual({ expectedDate: null, lines: [{ lineId: 'l1', expectedDate: '2041-09-14' }], reason: 'Split' })
    const change = { changeId: 'c1', lineId: null, itemCode: null, previousDate: '2041-09-10', expectedDate: '2041-09-15',
      reason: 'Supplier delayed', changedBy: 'kim', changedAt: '2041-09-02T10:00:00' }
    expect(dateChangeText(change)).toBe('2041-09-02 10:00 · order 2041-09-10 → 2041-09-15 · Supplier delayed · by kim')
    expect(dateChangeText({ ...change, lineId: 'l1', itemCode: 'FLOUR', previousDate: null, expectedDate: '2041-09-14', reason: 'Split' }))
      .toBe('2041-09-02 10:00 · FLOUR none → 2041-09-14 · Split · by kim')
  })

  it('offers the received lines and the stock they can go back from', () => {
    const received = order({ status: 'received',
      lines: [line({ receivedQuantity: 10, remainingQuantity: 0, returnedQuantity: 4 }),
        yeast, line({ lineId: 'l3', lineNo: 3, itemId: 'salt', itemCode: 'SALT', receivedQuantity: 2, remainingQuantity: 0, returnedQuantity: 2 })],
      receipts: [{ ...receipt, quantity: 10 }, { ...receipt, receiptId: 'r2', lineId: 'l2', itemCode: 'YEAST', quantity: 2, lotNo: 'L-1' }] })
    const yeastLine = { ...received.lines[1], receivedQuantity: 2 }
    const withYeast = { ...received, lines: [received.lines[0], yeastLine, received.lines[2]] }
    const row = (inventoryId: string, itemId: string, quantity: number, extra: Partial<InventoryDto> = {}) =>
      ({ inventoryId, itemId, quantity, reservedQuantity: 0, location: 'WH-A', lotNo: null, inventoryStatus: 'available', ...extra }) as InventoryDto
    const stock = [row('f1', 'flour', 6), row('f2', 'flour', 3, { reservedQuantity: 3 }), row('y1', 'yeast', 2, { lotNo: 'l-1', inventoryStatus: 'quarantined' }),
      row('y9', 'yeast', 3, { lotNo: 'L-9' }), row('s1', 'salt', 5)]
    const choices = returnChoices(withYeast, stock)
    // Salt went back whole; fully reserved flour and a LOT that came on another order are not offered.
    expect(choices.map((one) => [one.itemCode, one.open, one.records.map((record) => record.inventoryId)]))
      .toEqual([['FLOUR', 6, ['f1']], ['YEAST', 2, ['y1']]])
    expect(stockChoiceText(choices[1].records[0])).toBe('WH-A · LOT l-1 · 2 free · quarantined')
    const form = returnForm(choices)
    expect(form).toEqual({ lineId: 'l1', inventoryId: 'f1', quantity: '', reason: '', nonconformityId: '', replacement: true })
    expect(returnPayload(choices, form, 'k').error).toBe('FLOUR: enter a quantity above 0 with at most four decimals.')
    expect(returnPayload(choices, { ...form, quantity: '7' }, 'k').error).toBe('Only 6 of FLOUR can go back.')
    expect(returnPayload(choices, { ...form, quantity: '4' }, 'k').error).toBe('Give a reason for the return.')
    expect(returnPayload(choices, { ...form, quantity: '4', reason: ' Wet bags ' }, 'k').input)
      .toEqual({ requestId: 'k', lineId: 'l1', inventoryId: 'f1', quantity: 4, reason: 'Wet bags', replacement: true })
    expect(returnPayload(choices, { ...form, inventoryId: 'y1' }, 'k').error).toBe('Choose the stock of FLOUR to send back.')
    expect(lineText(withYeast.lines[0])).toBe('FLOUR · 10 of 10 kg received · 4 returned')
    // Sent back to be replaced, it counts as received again once the replacement came (PO46).
    expect(returnForm(choices, '', false).replacement).toBe(false)
    expect(returnPayload(choices, { ...form, quantity: '4', reason: 'Wet bags', replacement: false }, 'k').input?.replacement).toBe(false)
    expect(lineText({ ...withYeast.lines[0], replacementQuantity: 4, remainingQuantity: 4 }))
      .toBe('FLOUR · 6 of 10 kg received · 4 returned, 4 for replacement')
    expect(lineText({ ...withYeast.lines[0], receivedQuantity: 14, replacementQuantity: 4 }))
      .toBe('FLOUR · 10 of 10 kg received · 4 returned, 4 for replacement')
    expect(lineText({ ...withYeast.lines[0], receivedQuantity: 4, returnedQuantity: 4, replacementQuantity: 4, remainingQuantity: 10 }))
      .toBe('FLOUR · 10 kg · 4 returned, 4 for replacement')
    expect(returnText({ returnId: 'b1', lineId: 'l2', itemCode: 'YEAST', quantity: 2, inventoryId: 'y1', location: 'WH-A', lotNo: 'L-1',
      reason: 'Dead yeast', returnedBy: 'kim', returnedAt: '2041-09-05T10:00:00' }))
      .toBe('2041-09-05 10:00 · YEAST 2 back from WH-A · LOT L-1 · Dead yeast · by kim')
    expect(returnText({ returnId: 'b1', lineId: 'l1', itemCode: 'FLOUR', quantity: 4, inventoryId: 'f1', location: null, lotNo: null,
      reason: 'Wet bags', returnedBy: 'kim', returnedAt: '2041-09-05T10:00:00', replacement: true }))
      .toBe('2041-09-05 10:00 · FLOUR 4 back · Wet bags · replacement on this order · by kim')
  })

  it('names the NCR a return goes back for, and the NCR finds its returns', () => {
    const ncr = (extra: Partial<NonconformityDto>) => ({ nonconformityId: 'n1', ncrNo: 'NCR-0001', title: 'Sour yeast', status: 'open',
      disposition: 'return_to_supplier', itemCode: 'YEAST', lotNo: 'L-1', ...extra }) as NonconformityDto
    // Only open NCRs with Return to supplier saved can be named.
    const choices = returnNcrChoices([ncr({}), ncr({ nonconformityId: 'n2', disposition: 'pending' }),
      ncr({ nonconformityId: 'n3', status: 'closed' })])
    expect(choices.map((one) => one.nonconformityId)).toEqual(['n1'])
    expect(ncrChoiceText(choices[0])).toBe('NCR-0001 · Sour yeast · YEAST LOT L-1')
    expect(ncrChoiceText(ncr({ itemCode: null, lotNo: null }))).toBe('NCR-0001 · Sour yeast')

    const stock = [{ inventoryId: 'y1', itemId: 'yeast', quantity: 2, reservedQuantity: 0, location: null, lotNo: 'L-1',
      inventoryStatus: 'quarantined' } as InventoryDto]
    const yeastOrder = order({ status: 'received', lines: [line({ itemId: 'yeast', itemCode: 'YEAST', lotTracked: true, receivedQuantity: 2,
      remainingQuantity: 0 })], receipts: [{ ...receipt, itemCode: 'YEAST', quantity: 2, inventoryId: 'y1', lotNo: 'L-1' }] })
    const returnable = returnChoices(yeastOrder, stock)
    const form = { ...returnForm(returnable, 'n1'), quantity: '2', reason: 'Sour' }
    expect(returnPayload(returnable, form, 'k').input).toEqual({ requestId: 'k', lineId: 'l1', inventoryId: 'y1', quantity: 2,
      reason: 'Sour', nonconformityId: 'n1', replacement: true })

    const back = { returnId: 'b1', lineId: 'l1', itemCode: 'YEAST', quantity: 2, inventoryId: 'y1', location: null, lotNo: 'L-1',
      reason: 'Sour', returnedBy: 'kim', returnedAt: '2041-09-05T10:00:00', nonconformityId: 'n1', ncrNo: 'NCR-0001' }
    expect(returnText(back)).toBe('2041-09-05 10:00 · YEAST 2 back · LOT L-1 · Sour · for NCR-0001 · by kim')
    const earlier = { ...back, returnId: 'b0', quantity: 1, returnedAt: '2041-09-04T08:00:00' }
    const other = order({ purchaseOrderId: 'po2', orderNumber: 'PO-0002', returns: [earlier, { ...back, returnId: 'b2', nonconformityId: null }] })
    const found = ncrReturns([{ ...yeastOrder, returns: [back] }, other], 'n1')
    expect(found.map(({ order: one, back: sent }) => [one.orderNumber, sent.returnId])).toEqual([['PO-0002', 'b0'], ['PO-0001', 'b1']])
    expect(ncrReturnText(found[1].order, found[1].back)).toBe('2041-09-05 10:00 · YEAST 2 back on PO-0001 · LOT L-1 · by kim')
    expect(ncrReturnText(found[1].order, { ...found[1].back, replacement: true }))
      .toBe('2041-09-05 10:00 · YEAST 2 back on PO-0001 · LOT L-1 · replacement on the order · by kim')
  })

  it('waits for approvals in turn and says whose turn it is', () => {
    const step = (approverId: string, extra: Partial<PurchaseApprovalDto> = {}): PurchaseApprovalDto =>
      ({ approverId, fromValue: 100, projectOwner: false, approvedAt: null, ...extra })
    // The owner alone reads as before (PO34).
    expect(approvalNote({ approvals: [step('boss', { projectOwner: true })] })).toBe("Needs the project owner's approval before it is ordered.")
    expect(approvalNote({})).toBe("Needs the project owner's approval before it is ordered.")
    const turns = { approvals: [step('kim', { approvedAt: '2041-09-02T10:00:00' }), step('lee', { fromValue: 500 }),
      step('boss', { fromValue: 1000, projectOwner: true })] }
    expect(approvalNote(turns)).toBe('Needs approval in turn before it is ordered: kim ✓ 2041-09-02 10:00 → lee → the project owner')
    expect([canApprove(turns, 'kim', false), canApprove(turns, 'lee', false), canApprove(turns, 'boss', true)]).toEqual([false, true, false])
    expect([canApprove({}, 'boss', true), canApprove({}, 'kim', false), canApprove(turns, undefined, false)]).toEqual([true, false, false])
    expect(approvedText({ approvedBy: 'lee', approvedAt: '2041-09-03T09:00:00',
      approvals: [step('kim', { approvedAt: '2041-09-02T10:00:00' }), step('lee', { approvedAt: '2041-09-03T09:00:00' })] }))
      .toBe('Approved by kim 2041-09-02 10:00, then lee 2041-09-03 09:00')
    // The rules by value, the owner last among equal values (PO50).
    expect(approvalRulesText(1000, [])).toBe(approvalLimitText(1000))
    expect(approvalRulesText(500, [{ fromValue: 500, approverId: 'lee', canApprove: false }, { fromValue: 100, approverId: 'kim', canApprove: true }]))
      .toBe('Purchase orders are approved in turn: from 100 by kim, from 500 by lee (cannot approve now), from 500 by the project owner;'
        + ' a line not priced yet needs every approval.')
    expect(approvalRulesText(null, [{ fromValue: 100, approverId: 'kim' }]))
      .toBe('Purchase orders are approved in turn: from 100 by kim; a line not priced yet needs every approval.')
    // Steps as typed: a value and an approver each, each approver once, lowest value first.
    expect(approvalStepsPayload([{ fromValue: '500', approverId: 'lee' }, { fromValue: '100', approverId: 'kim' }]))
      .toEqual({ steps: [{ fromValue: 100, approverId: 'kim' }, { fromValue: 500, approverId: 'lee' }], error: null })
    expect(approvalStepsPayload([{ fromValue: '', approverId: 'kim' }]).error)
      .toBe('Step 1: enter the value it starts from, 0 or more with at most four decimals.')
    expect(approvalStepsPayload([{ fromValue: '1', approverId: '' }]).error).toBe('Step 1: choose who approves.')
    expect(approvalStepsPayload([{ fromValue: '1', approverId: 'kim' }, { fromValue: '2', approverId: 'kim' }]).error).toBe('kim is on two steps.')
    expect(approvalStepsPayload([])).toEqual({ steps: [], error: null })
  })

  it('says what the supplier owes back for returns until the credit is kept', () => {
    const back = (extra: Partial<PurchaseReturnDto>): PurchaseReturnDto => ({ returnId: 'b1', lineId: 'l1', itemCode: 'FLOUR', quantity: 4,
      inventoryId: 'f1', location: null, lotNo: null, reason: 'Wet bags', returnedBy: 'kim', returnedAt: '2041-09-05T10:00:00', ...extra })
    const priced = line({ unitPrice: 2.5, receivedQuantity: 10, remainingQuantity: 0, returnedQuantity: 4 })
    const credit = order({ status: 'received', lines: [priced, yeast], returns: [back({})] })
    // Not sent to be replaced, all of it is owed at the line's price (PO47).
    expect(returnValue(credit, back({}))).toBe(10)
    expect(owedQuantity(credit, back({}))).toBe(4)
    expect(creditText(credit, back({}))).toBe(' · credit due 10')
    expect(orderCreditText(credit)).toBe('Credit due 10')
    expect(creditText(order({ returns: [back({})] }), back({}))).toBe(' · credit due (no price)')
    expect(orderCreditText(order({ returns: [back({}), back({ returnId: 'b2' })] }))).toBe('Credit due on 2 returns without a price')
    expect(orderCreditText({ ...credit, returns: [back({}), back({ returnId: 'b2', lineId: 'l2', itemCode: 'YEAST' })] }))
      .toBe('Credit due 10 (1 return without a price)')
    // Credited, nothing is owed.
    const kept = back({ creditAmount: 9.5, creditNote: 'CN-12', creditedBy: 'kim', creditedAt: '2041-09-09T10:00:00' })
    expect(creditText(credit, kept)).toBe(' · credited 9.5 · CN-12 · by kim')
    expect(orderCreditText({ ...credit, returns: [kept] })).toBeNull()
    // A replacement is owed only once a closed order will not bring it, the newest return first.
    const first = back({ replacement: true, quantity: 3 })
    const second = back({ returnId: 'b2', replacement: true, quantity: 2, returnedAt: '2041-09-06T10:00:00' })
    const waiting = { ...priced, replacementQuantity: 5, receivedQuantity: 11, remainingQuantity: 4 }
    expect(owedQuantity(order({ lines: [waiting], returns: [first, second] }), first)).toBe(0)
    const closed = order({ status: 'closed', lines: [waiting], returns: [first, second] })
    expect([owedQuantity(closed, second), owedQuantity(closed, first)]).toEqual([2, 2])
    expect(orderCreditText(closed)).toBe('Credit due 10')
    // The form starts at what is owed; the amount is 0 or more with four decimals at most.
    expect(creditForm(credit, back({}))).toEqual({ returnId: 'b1', amount: '10', creditNote: '', reason: '', change: false })
    expect(creditForm(order({ returns: [back({})] }), back({})).amount).toBe('')
    const typed = { returnId: 'b1', amount: '', creditNote: '', reason: '', change: false }
    expect(creditPayload(typed).error).toBe('Enter the amount credited: 0 or more with at most four decimals.')
    expect(creditPayload({ ...typed, amount: '1.00001' }).input).toBeNull()
    expect(creditPayload({ ...typed, amount: '9.5', creditNote: ' CN-12 ' }).input).toEqual({ amount: 9.5, creditNote: 'CN-12' })
    expect(creditPayload({ ...typed, amount: '0' }).input).toEqual({ amount: 0 })
    // A kept credit changes from where it stands, with a reason; withdrawn, it is owed again (PO52).
    expect(creditForm(credit, kept)).toEqual({ returnId: 'b1', amount: '9.5', creditNote: 'CN-12', reason: '', change: true })
    expect(creditPayload({ ...typed, amount: '8', change: true }).error).toBe('Give a reason for the change.')
    expect(creditPayload({ ...typed, amount: '8', change: true, reason: ' Sent less ' }).input).toEqual({ amount: 8, reason: 'Sent less' })
    expect(withdrawPayload({ ...typed, change: true }).error).toBe('Give a reason for withdrawing the credit.')
    expect(withdrawPayload({ ...typed, change: true, reason: 'Wrong return' }).input).toEqual({ amount: null, reason: 'Wrong return' })
    const changes = [{ amount: 8, creditNote: 'CN-13', reason: 'Sent less', changedBy: 'kim', changedAt: '2041-09-10T10:00:00' },
      { amount: null, creditNote: null, reason: 'Wrong return', changedBy: 'kim', changedAt: '2041-09-11T10:00:00' }]
    const withdrawn = back({ creditChanges: changes })
    expect(changes.map(creditChangeText)).toEqual(['2041-09-10 10:00 · credit 8 · CN-13 · Sent less · by kim',
      '2041-09-11 10:00 · credit withdrawn · Wrong return · by kim'])
    expect(owedQuantity(credit, withdrawn)).toBe(4)
    expect(creditForm(credit, withdrawn)).toEqual({ returnId: 'b1', amount: '10', creditNote: '', reason: '', change: true })
  })

  it('tells where a draft stands with the owner\'s approval', () => {
    expect(approvalState(order({ status: 'draft' }))).toBe('none')
    expect(approvalState(order({ status: 'draft', needsApproval: true }))).toBe('needed')
    expect(approvalState(order({ status: 'draft', needsApproval: true, approvedBy: 'kim', approvedAt: '2041-09-02T10:00:00' }))).toBe('approved')
    expect(approvalState(order({ needsApproval: false }))).toBe('none')
    expect(approvedText({ approvedBy: 'kim', approvedAt: '2041-09-02T10:00:00' })).toBe('Approved by kim 2041-09-02 10:00')
    expect(approvalLimitText(null)).toBe('Purchase orders do not need approval.')
    expect(approvalLimitText(100)).toBe("Purchase orders worth 100 or more, or with a line not priced yet, need the project owner's approval.")
  })

  it('finds the purchase orders a LOT came on, once each', () => {
    const origin = (orderNumber: string) => ({ orderNumber, supplierName: 'Mill Co', quantity: 1, receivedAt: '2041-09-02T10:00:00' })
    expect(originOrders([origin('PO-0001'), origin('PO-0002'), origin('PO-0001'), origin('PO-0009')],
      [{ purchaseOrderId: 'po1', orderNumber: 'PO-0001' }, { purchaseOrderId: 'po2', orderNumber: 'PO-0002' }]))
      .toEqual([{ purchaseOrderId: 'po1', orderNumber: 'PO-0001' }, { purchaseOrderId: 'po2', orderNumber: 'PO-0002' }])
  })

  it('says where an order is to arrive and counts it for that place', () => {
    const form = { ...EMPTY_ORDER_FORM, supplierName: 'Mill Co', lines: [{ itemId: 'flour', quantity: '10' }] }
    expect(orderPayload('p', { ...form, deliverTo: ' WH-A ' }).input).toEqual({ projectId: 'p', supplierName: 'Mill Co', expectedDate: null,
      note: null, lines: [{ itemId: 'flour', quantity: 10, expectedDate: null, note: null }], deliverTo: 'WH-A' })
    expect(orderPayload('p', form).input).not.toHaveProperty('deliverTo')
    expect(orderPayload('p', { ...form, deliverTo: 'x'.repeat(101) }).error).toBe('Deliver to is longer than 100 characters.')
    expect(orderForm(order({ deliverTo: 'WH-A' })).deliverTo).toBe('WH-A')
    expect(receiveForm(order({ deliverTo: 'WH-A' }))[0].location).toBe('WH-A')
    const there = order({ purchaseOrderId: 'a', deliverTo: 'BIN-1' })
    const elsewhere = order({ purchaseOrderId: 'b', deliverTo: 'WH-B' })
    const nowhere = order({ purchaseOrderId: 'c', deliverTo: null })
    expect(onOrderByItem([there, elsewhere, nowhere]).get('flour')).toBe(30)
    expect(onOrderByItem([there, elsewhere, nowhere], new Set(['wh-a', 'bin-1'])).get('flour')).toBe(10)
    expect(orderSheetHtml(there)).toContain('<p>Deliver to: BIN-1</p>')
  })

  it('counts what is still to come on ordered orders only', () => {
    const onOrder = onOrderByItem([
      order({ lines: [line({ receivedQuantity: 4, remainingQuantity: 6 }), yeast] }),
      order({ purchaseOrderId: 'po2', lines: [line({ remainingQuantity: 0.0001 })] }),
      order({ purchaseOrderId: 'po3', status: 'draft' }),
      order({ purchaseOrderId: 'po4', status: 'closed' }),
    ])
    expect([...onOrder.entries()]).toEqual([['flour', 6.0001], ['yeast', 2]])
  })

  it('drafts the bought materials open work orders leave short, in whole purchase units', () => {
    const item = (itemId: string, extra: Record<string, unknown> = {}) => [itemId, { itemId, projectId: 'p', itemCode: itemId.toUpperCase(),
      itemName: itemId, itemType: 'material', unitId: 'unit_kg', itemStatus: 'active', lotManageYn: 'N', ...extra }] as const
    const items = new Map([item('flour', { purchaseUnit: 'bag', purchaseUnitQty: 5 }), item('salt'), item('dough'),
      item('old', { itemStatus: 'discontinued' })]) as unknown as Parameters<typeof needsDraftLines>[1]
    expect(needsDraftLines([
      { itemId: 'flour', shortage: 7, madeHere: false },
      { itemId: 'salt', shortage: 1.5 },
      { itemId: 'dough', shortage: 2, madeHere: true },
      { itemId: 'old', shortage: 3 },
      { itemId: 'sugar', shortage: 0 },
    ], items)).toEqual([
      { itemId: 'flour', quantity: 10, expectedDate: null, note: null },
      { itemId: 'salt', quantity: 1.5, expectedDate: null, note: null },
    ])
  })

  it('drafts what the reorder list suggests beyond what is on order', () => {
    const onOrder = new Map([['flour', 10], ['salt', 5]])
    expect(reorderDraftLines([
      { itemId: 'flour', quantity: 15, active: true },
      { itemId: 'salt', quantity: 4, active: true },
      { itemId: 'sugar', quantity: 3, active: false },
      { itemId: 'oil', quantity: 2.5, active: true },
    ], onOrder)).toEqual([
      { itemId: 'flour', quantity: 5, expectedDate: null, note: null },
      { itemId: 'oil', quantity: 2.5, expectedDate: null, note: null },
    ])
  })
})
