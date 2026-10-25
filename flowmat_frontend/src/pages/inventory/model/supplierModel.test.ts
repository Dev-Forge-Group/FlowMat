import { describe, expect, it } from 'vitest'
import type { PurchaseOrderDto } from '../../../entities/purchasing/api/usePurchaseOrders'
import type { SupplierDto, SupplierItemDto } from '../../../entities/purchasing/api/useSuppliers'
import {
  EMPTY_SUPPLIER_FORM,
  daysBefore,
  draftsBySupplier,
  findSupplier,
  itemPrices,
  leadTimeDate,
  leadTimeText,
  priceHistory,
  pricedItems,
  supplierChoices,
  supplierCodesFor,
  supplierCredits,
  averageDaysLateText,
  supplierDelivery,
  supplierForm,
  supplierPayload,
  supplierProblem,
  supplierSpend,
  usualPriceText,
} from './supplierModel'

const supplier = (overrides: Partial<SupplierDto> = {}): SupplierDto => ({
  supplierId: 's1', projectId: 'p', supplierName: 'Mill Co', contact: 'Kim 010-1234', leadTimeDays: 5, note: null, active: true,
  version: 0, createdBy: 'kim', createdAt: '2041-09-01T09:00:00', updatedBy: null, updatedAt: null, ...overrides,
})
const gone = supplier({ supplierId: 's2', supplierName: 'Gone Co', active: false })

describe('supplierModel', () => {
  it('checks a supplier form before saving', () => {
    expect(supplierPayload(EMPTY_SUPPLIER_FORM).error).toBe('Name the supplier.')
    for (const days of ['-1', '366', '2.5', 'soon']) {
      expect(supplierPayload({ ...EMPTY_SUPPLIER_FORM, supplierName: 'Mill Co', leadTimeDays: days }).error)
        .toBe('Lead time is a whole number of days from 0 to 365.')
    }
    expect(supplierPayload({ supplierName: ' Mill Co ', contact: ' ', leadTimeDays: '0', note: ' gate 2 ', active: true }))
      .toEqual({ input: { supplierName: 'Mill Co', contact: null, leadTimeDays: 0, note: 'gate 2' }, error: null })
    expect(supplierForm(supplier({ leadTimeDays: null })))
      .toEqual({ supplierName: 'Mill Co', contact: 'Kim 010-1234', leadTimeDays: '', note: '', active: true })
  })

  it('offers the active listed suppliers once there is a list, else the ones already used', () => {
    const orders = [{ supplierName: 'Old Co' }] as PurchaseOrderDto[]
    expect(supplierChoices([], orders)).toEqual(['Old Co'])
    expect(supplierChoices([supplier(), gone], orders)).toEqual(['Mill Co'])
  })

  it('says why an order supplier would be refused', () => {
    const list = [supplier(), gone]
    expect(supplierProblem([], 'Anyone')).toBeNull()
    expect(supplierProblem(list, ' mill co ')).toBeNull()
    expect(findSupplier(list, ' mill co ')?.supplierId).toBe('s1')
    expect(findSupplier(list, ' ')).toBeUndefined()
    expect(supplierProblem(list, 'Anyone ')).toBe('Anyone is not in the supplier list.')
    expect(supplierProblem(list, 'gone co')).toBe('Gone Co is inactive.')
    expect(supplierProblem(list, ' ')).toBeNull()
  })

  it('counts how suppliers delivered against the first and the last date', () => {
    const base = { status: 'received', expectedDate: '2041-09-15', receipts: [{ receivedAt: '2041-09-12T23:30:00Z' }], dateChanges: [] }
    const orders = [
      // In on the 10th, before the 15th.
      { ...base, purchaseOrderId: 'a', supplierName: 'Mill Co', receipts: [{ receivedAt: '2041-09-10T09:00:00Z' }] },
      // Moved from the 10th to the 15th, last in on the 13th in Seoul (the 12th in UTC): late to the first date only.
      { ...base, purchaseOrderId: 'b', supplierName: 'mill co',
        receipts: [{ receivedAt: '2041-09-09T01:00:00Z' }, { receivedAt: '2041-09-12T23:30:00Z' }],
        dateChanges: [{ lineId: null, previousDate: '2041-09-10', expectedDate: '2041-09-15' }] },
      // A line's own date change is not the order's.
      { ...base, purchaseOrderId: 'c', supplierName: 'Salt Works', expectedDate: '2041-09-11',
        dateChanges: [{ lineId: 'l1', previousDate: null, expectedDate: '2041-09-20' }] },
      // Not counted: no date, closed, still ordered (but late now).
      { ...base, purchaseOrderId: 'd', supplierName: 'Salt Works', expectedDate: null },
      { ...base, purchaseOrderId: 'e', supplierName: 'Salt Works', status: 'closed' },
      { ...base, purchaseOrderId: 'f', supplierName: 'Salt Works', status: 'ordered', expectedDate: '2041-09-20', receipts: [] },
    ] as unknown as PurchaseOrderDto[]
    expect(supplierDelivery(orders, 'Asia/Seoul', '2041-09-25')).toEqual([
      { supplierName: 'Mill Co', received: 2, onTimeFirst: 1, onTimeLast: 2, lateNow: 0, linesReceived: 0, linesOnTime: 0, lateArrivals: 0, daysLate: 0 },
      // In on the 13th in Seoul against the 11th: two days late.
      { supplierName: 'Salt Works', received: 1, onTimeFirst: 0, onTimeLast: 0, lateNow: 1, linesReceived: 0, linesOnTime: 0, lateArrivals: 1,
        daysLate: 2 },
    ])
    // A date first given after ordering is the first date.
    const later = [{ ...base, purchaseOrderId: 'g', supplierName: 'Mill Co',
      dateChanges: [{ lineId: null, previousDate: null, expectedDate: '2041-09-11' }, { lineId: null, previousDate: '2041-09-11', expectedDate: '2041-09-15' }] }]
    expect(supplierDelivery(later as unknown as PurchaseOrderDto[], 'UTC', '2041-09-25'))
      .toEqual([{ supplierName: 'Mill Co', received: 1, onTimeFirst: 0, onTimeLast: 1, lateNow: 0, linesReceived: 0, linesOnTime: 0,
        lateArrivals: 0, daysLate: 0 }])
  })

  it('counts lines against their own dates, days late and a period', () => {
    const lines = [{ lineId: 'l1', expectedDate: null }, { lineId: 'l2', expectedDate: '2041-09-12' }]
    const orders = [
      // Due on the 10th, in on the 13th: 3 days late. Line 1 is due with the order (late), line 2 on its own 12th (on time).
      { purchaseOrderId: 'a', supplierName: 'Mill Co', status: 'received', expectedDate: '2041-09-10', dateChanges: [], lines,
        receipts: [{ lineId: 'l1', receivedAt: '2041-09-13T09:00:00Z' }, { lineId: 'l2', receivedAt: '2041-09-11T09:00:00Z' }] },
      // A month before: one on time, one a day late.
      { purchaseOrderId: 'b', supplierName: 'Mill Co', status: 'received', expectedDate: '2041-08-10', dateChanges: [], lines: [],
        receipts: [{ lineId: 'x', receivedAt: '2041-08-09T09:00:00Z' }] },
      { purchaseOrderId: 'c', supplierName: 'Mill Co', status: 'received', expectedDate: '2041-08-10', dateChanges: [], lines: [],
        receipts: [{ lineId: 'x', receivedAt: '2041-08-11T09:00:00Z' }] },
    ] as unknown as PurchaseOrderDto[]
    const [all] = supplierDelivery(orders, 'UTC', '2041-09-25')
    expect(all).toMatchObject({ received: 3, onTimeLast: 1, linesReceived: 2, linesOnTime: 1, lateArrivals: 2, daysLate: 4 })
    expect(averageDaysLateText(all)).toBe('2')
    // Only what came in on or after the 1st.
    const [recent] = supplierDelivery(orders, 'UTC', '2041-09-25', '2041-09-01')
    expect(recent).toMatchObject({ received: 1, linesReceived: 2, lateArrivals: 1, daysLate: 3 })
    expect(averageDaysLateText({ ...all, lateArrivals: 3 })).toBe('1.3')
    expect(averageDaysLateText({ ...all, lateArrivals: 0, daysLate: 0 })).toBe('—')
  })

  it('splits draft lines by their items\' usual suppliers', () => {
    const salt = supplier({ supplierId: 's3', supplierName: 'Salt Works', leadTimeDays: null })
    const usual = [{ itemId: 'flour', supplierId: 's1' }, { itemId: 'yeast', supplierId: 's1' }, { itemId: 'salt', supplierId: 's3' },
      { itemId: 'sugar', supplierId: 's2' }] as SupplierItemDto[]
    const line = (itemId: string) => ({ itemId, quantity: 1, expectedDate: null, note: null })
    const plan = draftsBySupplier([line('flour'), line('salt'), line('sugar'), line('yeast'), line('egg')], usual, [supplier(), gone, salt], '2041-09-28')
    expect(plan.groups).toEqual([
      { supplierName: 'Mill Co', expectedDate: '2041-10-03', lines: [line('flour'), line('yeast')] },
      { supplierName: 'Salt Works', expectedDate: null, lines: [line('salt')] },
    ])
    // Sugar's usual supplier is inactive and egg has none.
    expect(plan.rest).toEqual([line('sugar'), line('egg')])
    expect(draftsBySupplier([line('egg')], [], [], '2041-09-28')).toEqual({ groups: [], rest: [line('egg')] })
    // The supplier's own price goes on its lines (PO38).
    const priced = draftsBySupplier([{ ...line('flour'), unitPrice: 1.2 }], [{ itemId: 'flour', supplierId: 's1', unitPrice: 1.25 }] as SupplierItemDto[],
      [supplier()], '2041-09-28')
    expect(priced.groups[0].lines).toEqual([{ ...line('flour'), unitPrice: 1.25 }])
  })

  it('gives the supplier\'s own codes for the items it usually supplies', () => {
    const usual = [{ itemId: 'flour', supplierName: 'Mill Co', supplierItemCode: 'MC-77' }, { itemId: 'salt', supplierName: 'Mill Co', supplierItemCode: null },
      { itemId: 'yeast', supplierName: 'Salt Works', supplierItemCode: 'SW-1' }] as SupplierItemDto[]
    expect(Object.fromEntries(supplierCodesFor(' mill co ', usual))).toEqual({ flour: 'MC-77' })
    expect(supplierCodesFor('Other', usual).size).toBe(0)
  })

  it('adds up what was bought from each supplier in a period', () => {
    const base = { status: 'received', orderedAt: '2041-09-10T09:00:00Z', receipts: [], returns: [] }
    const ln = (lineId: string, quantity: number, unitPrice: number | null) => ({ lineId, quantity, unitPrice })
    const orders = [
      { ...base, supplierName: 'Mill Co', lines: [ln('a1', 10, 1.25), ln('a2', 3, null)],
        receipts: [{ lineId: 'a1', quantity: 10 }, { lineId: 'a2', quantity: 3 }], returns: [{ lineId: 'a1', quantity: 2 }] },
      { ...base, supplierName: 'mill co', status: 'ordered', lines: [ln('b1', 4, 0.5)], receipts: [{ lineId: 'b1', quantity: 1 }] },
      { ...base, supplierName: 'Salt Works', lines: [ln('c1', 1, 3)], orderedAt: '2041-08-01T09:00:00Z' },
      { ...base, supplierName: 'Gone Co', status: 'cancelled', lines: [ln('d1', 1, 100)] },
      { ...base, supplierName: 'Draft Co', status: 'draft', orderedAt: null, lines: [ln('e1', 1, 100)] },
    ] as unknown as PurchaseOrderDto[]
    expect(supplierSpend(orders, null, 'UTC')).toEqual([
      { supplierName: 'Mill Co', orders: 2, ordered: 14.5, received: 13, returned: 2.5, unpriced: 1 },
      { supplierName: 'Salt Works', orders: 1, ordered: 3, received: 0, returned: 0, unpriced: 0 },
    ])
    expect(supplierSpend(orders, '2041-09-01', 'UTC').map((one) => one.supplierName)).toEqual(['Mill Co'])
    expect(daysBefore('2041-09-30', 30)).toBe('2041-08-31')
  })

  it('compares what each supplier was paid for an item, and when', () => {
    const ln = (itemId: string, unitPrice: number | null) => ({ lineId: `${itemId}-l`, itemId, itemCode: itemId.toUpperCase(), unitPrice })
    const placed = (orderNumber: string, supplierName: string, orderedAt: string | null, lines: unknown[], status = 'received') =>
      ({ orderNumber, supplierName, status, orderedAt, lines })
    const orders = [
      placed('PO-0001', 'Mill Co', '2041-09-01T09:00:00Z', [ln('flour', 2.5), ln('salt', null)]),
      placed('PO-0002', 'Salt Works ', '2041-09-05T09:00:00Z', [ln('flour', 2.2)], 'ordered'),
      // Seoul's 10th; the newest from Mill Co, spelt in lower case.
      placed('PO-0003', 'mill co', '2041-09-09T20:00:00Z', [ln('flour', 2.7)], 'closed'),
      placed('PO-0004', 'Gone Co', '2041-09-06T09:00:00Z', [ln('flour', 1)], 'cancelled'),
      placed('PO-0005', 'Mill Co', null, [ln('flour', 1.5), ln('yeast', 3)], 'draft'),
    ] as unknown as PurchaseOrderDto[]
    // Drafts, cancelled orders and lines without a price are not prices paid (PO49).
    expect(pricedItems(orders)).toEqual([{ itemId: 'flour', itemCode: 'FLOUR' }])
    expect(priceHistory(orders, 'flour', 'Asia/Seoul')).toEqual([
      { date: '2041-09-10', supplierName: 'mill co', orderNumber: 'PO-0003', unitPrice: 2.7 },
      { date: '2041-09-05', supplierName: 'Salt Works', orderNumber: 'PO-0002', unitPrice: 2.2 },
      { date: '2041-09-01', supplierName: 'Mill Co', orderNumber: 'PO-0001', unitPrice: 2.5 },
    ])
    expect(itemPrices(orders, 'flour', 'Asia/Seoul')).toEqual([
      { supplierName: 'Salt Works', lastPrice: 2.2, lastDate: '2041-09-05', lastOrder: 'PO-0002', lowestPrice: 2.2, orders: 1 },
      { supplierName: 'mill co', lastPrice: 2.7, lastDate: '2041-09-10', lastOrder: 'PO-0003', lowestPrice: 2.5, orders: 2 },
    ])
    expect(itemPrices(orders, 'salt', 'UTC')).toEqual([])
    expect(usualPriceText({ supplierName: 'Mill Co', unitPrice: 2.4 })).toBe('Usually from Mill Co at 2.4 agreed.')
    expect(usualPriceText({ supplierName: 'Mill Co', unitPrice: null })).toBe('Usually from Mill Co; no price agreed.')
    expect(usualPriceText(undefined)).toBeNull()
  })

  it('adds up what each supplier still owes back for returns', () => {
    const back = (returnId: string, lineId: string, quantity: number, extra: Record<string, unknown> = {}) =>
      ({ returnId, lineId, quantity, returnedAt: '2041-09-05T10:00:00Z', ...extra })
    const ln = (lineId: string, unitPrice: number | null) => ({ lineId, unitPrice, replacementQuantity: 0, remainingQuantity: 0 })
    const orders = [
      { supplierName: 'Mill Co', status: 'received', lines: [ln('a1', 2.5), ln('a2', null)],
        returns: [back('r1', 'a1', 4), back('r2', 'a2', 1), back('r3', 'a1', 2, { creditedAt: '2041-09-09T10:00:00Z', creditAmount: 5 })] },
      { supplierName: 'mill co', status: 'ordered', lines: [ln('b1', 1)], returns: [back('r4', 'b1', 3, { replacement: true })] },
      { supplierName: 'Salt Works', status: 'received', lines: [ln('c1', 3)], returns: [back('r5', 'c1', 1)] },
    ] as unknown as PurchaseOrderDto[]
    // Credited returns and replacements still to come are not owed (PO48).
    expect(supplierCredits(orders)).toEqual([
      { supplierName: 'Mill Co', returns: 2, due: 10, unpriced: 1 },
      { supplierName: 'Salt Works', returns: 1, due: 3, unpriced: 0 },
    ])
  })

  it('puts arrival a lead time after today', () => {
    expect(leadTimeDate('2041-09-28', supplier())).toBe('2041-10-03')
    expect(leadTimeDate('2041-12-30', supplier({ leadTimeDays: 3 }))).toBe('2042-01-02')
    expect(leadTimeDate('2041-09-28', supplier({ leadTimeDays: 0 }))).toBe('2041-09-28')
    expect(leadTimeDate('2041-09-28', supplier({ leadTimeDays: null }))).toBeNull()
    expect(leadTimeDate('2041-09-28', undefined)).toBeNull()
    expect([leadTimeText(5), leadTimeText(1), leadTimeText(0), leadTimeText(null)]).toEqual(['5 days', '1 day', 'same day', ''])
  })
})
