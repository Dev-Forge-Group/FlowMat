import { describe, expect, it } from 'vitest'
import type { PurchaseOrderDto } from '../../../entities/purchasing/api/usePurchaseOrders'
import type { WorkOrderDto } from '../../../shared/types/api'
import { arrivalsByItem, endDays, firstShort, startDays, type Arrival } from './needsTimingModel'

const need = (workOrderId: string | null, workOrderTitle: string | null, required: number, viaItemCode: string | null = null) =>
  ({ workOrderId, workOrderTitle, required, viaItemCode })
const starts = new Map<string, string | null>([['a', '2041-09-18'], ['b', '2041-09-20'], ['c', null]])
const flour = { usable: 5, plannedSupply: 0, orders: [need('a', 'Bread', 4), need('b', 'Buns', 4)] }
const arrival = (date: string | null, quantity = 4): Arrival => ({ date, quantity, orderNumber: 'PO-0002' })

describe('needsTimingModel', () => {
  it('runs short when an order needs more than there is by its start', () => {
    expect(firstShort(flour, starts, [arrival('2041-09-19')])).toBeNull()
    expect(firstShort(flour, starts, [arrival('2041-09-21')])).toEqual({ date: '2041-09-20', label: 'Buns', short: 3 })
    // Arriving on the day it is needed is in time.
    expect(firstShort(flour, starts, [arrival('2041-09-20')])).toBeNull()
    // Without a date, an arrival helps only the needs without one.
    expect(firstShort(flour, starts, [arrival(null)])).toEqual({ date: '2041-09-20', label: 'Buns', short: 3 })
    expect(firstShort({ ...flour, orders: [need('c', 'Rolls', 8)] }, starts, [arrival(null)])).toBeNull()
    // What is being made counts from the start.
    expect(firstShort({ ...flour, plannedSupply: 3 }, starts, [])).toBeNull()
  })

  it('puts what work orders are making on their planned end day', () => {
    const dough = { usable: 0, plannedSupply: 4, orders: [need('b', 'Buns', 4)], supplies: [{ workOrderId: 'm1', workOrderTitle: 'Dough', quantity: 4 }] }
    expect(firstShort(dough, starts, [], new Map([['m1', '2041-09-21']]))).toEqual({ date: '2041-09-20', label: 'Buns', short: 4 })
    expect(firstShort(dough, starts, [], new Map([['m1', '2041-09-20']]))).toBeNull()
    // Without a planned end it comes after the dated needs; without the breakdown it counts from the start.
    expect(firstShort(dough, starts, [], new Map())).toEqual({ date: '2041-09-20', label: 'Buns', short: 4 })
    expect(firstShort({ ...dough, supplies: undefined }, starts, [])).toBeNull()
    const ends = endDays([{ workOrderId: 'm1', plannedEndAt: '2041-09-20T16:00:00Z' }, { workOrderId: 'm2', plannedEndAt: null }] as WorkOrderDto[],
      'Asia/Seoul')
    expect(Object.fromEntries(ends)).toEqual({ m1: '2041-09-21', m2: null })
  })

  it('takes a sub-assembly share and orders without a start last', () => {
    const line = { usable: 5, plannedSupply: 0, orders: [need(null, null, 2, 'SPONGE'), need('c', 'Rolls', 2), need('a', 'Bread', 4)] }
    // Without a date, needs keep their order.
    expect(firstShort(line, starts, [])).toEqual({ date: null, label: 'via SPONGE', short: 1 })
    expect(firstShort({ ...line, usable: 8 }, starts, [])).toBeNull()
    expect(firstShort({ ...line, usable: 6.5 }, starts, [])).toEqual({ date: null, label: 'Rolls', short: 1.5 })
    expect(firstShort({ ...line, usable: 7.9999 }, starts, [])).toEqual({ date: null, label: 'Rolls', short: 0.0001 })
  })

  it('reads arrivals and start days from the orders', () => {
    const order = (status: string, expectedDate: string | null, lines: Record<string, unknown>[]) =>
      ({ orderNumber: `PO-${status}`, status, expectedDate, lines }) as unknown as PurchaseOrderDto
    const arrivals = arrivalsByItem([
      order('ordered', '2041-09-21', [{ itemId: 'flour', expectedDate: null, remainingQuantity: 4 },
        { itemId: 'salt', expectedDate: '2041-09-19', remainingQuantity: 1 }, { itemId: 'yeast', expectedDate: null, remainingQuantity: 0 }]),
      order('draft', '2041-09-22', [{ itemId: 'flour', expectedDate: null, remainingQuantity: 9 }]),
    ])
    expect(Object.fromEntries(arrivals)).toEqual({
      flour: [{ date: '2041-09-21', quantity: 4, orderNumber: 'PO-ordered' }],
      salt: [{ date: '2041-09-19', quantity: 1, orderNumber: 'PO-ordered' }],
    })
    const days = startDays([{ workOrderId: 'a', plannedStartAt: '2041-09-18T16:00:00Z' }, { workOrderId: 'b', plannedStartAt: null }] as WorkOrderDto[],
      'Asia/Seoul')
    expect(Object.fromEntries(days)).toEqual({ a: '2041-09-19', b: null })
  })
})
