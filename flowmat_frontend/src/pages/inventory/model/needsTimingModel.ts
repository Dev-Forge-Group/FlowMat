import type { PurchaseOrderDto } from '../../../entities/purchasing/api/usePurchaseOrders'
import type { MaterialRequirementDto, WorkOrderDto } from '../../../shared/types/api'
import { zoneDay } from './bomModel'

type NeedLine = MaterialRequirementDto['lines'][number]

/** What an open purchase order line still brings, on its expected date: the line's own, else the order's (PO25). */
export interface Arrival {
  date: string | null
  quantity: number
  orderNumber: string
}

/** What is still to come on ordered purchase orders, by item, as the needs count it on order (PO8, PO25). */
export function arrivalsByItem(orders: PurchaseOrderDto[]): Map<string, Arrival[]> {
  const result = new Map<string, Arrival[]>()
  for (const order of orders) {
    if (order.status !== 'ordered') continue
    for (const line of order.lines) {
      if (line.remainingQuantity <= 0) continue
      const list = result.get(line.itemId) ?? []
      list.push({ date: line.expectedDate ?? order.expectedDate, quantity: line.remainingQuantity, orderNumber: order.orderNumber })
      result.set(line.itemId, list)
    }
  }
  return result
}

/** The project day each work order is planned to start; null without a planned start (PO25). */
export function startDays(workOrders: WorkOrderDto[], timeZone: string | null | undefined): Map<string, string | null> {
  return new Map(workOrders.map((order) => [order.workOrderId,
    order.plannedStartAt ? zoneDay(timeZone, new Date(order.plannedStartAt)) : null]))
}

/** The project day each work order is planned to end; null without a planned end (PO25). */
export function endDays(workOrders: WorkOrderDto[], timeZone: string | null | undefined): Map<string, string | null> {
  return new Map(workOrders.map((order) => [order.workOrderId,
    order.plannedEndAt ? zoneDay(timeZone, new Date(order.plannedEndAt)) : null]))
}

/** The day a material first runs short, the need that does it, and by how much. */
export interface ShortFrom {
  date: string | null
  label: string
  short: number
}

const round = (value: number) => Math.round(value * 10000) / 10000

/**
 * When a material first runs short as the open orders take it in planned start order (docs/domain/purchasing.md PO25).
 * Usable stock counts from the start. What open work orders are making comes on each one's planned end day, and an arrival on
 * its expected day, both before that day's needs; without the work orders' breakdown, what is being made counts from the start,
 * as Left after does. Supplies without a date come after every dated need, and needs without a date (no planned start, or a
 * sub-assembly's share) last. Null when it never runs short.
 */
export function firstShort(
  line: Pick<NeedLine, 'usable' | 'plannedSupply' | 'orders' | 'supplies'>,
  starts: Map<string, string | null>,
  arrivals: Arrival[],
  ends: Map<string, string | null> = new Map(),
): ShortFrom | null {
  const made = line.supplies ?? null
  const events = [
    ...arrivals.map((arrival) => ({ date: arrival.date, rank: 0, change: arrival.quantity, label: arrival.orderNumber })),
    ...(made ?? []).map((supply) => ({ date: ends.get(supply.workOrderId) ?? null, rank: 0, change: supply.quantity,
      label: supply.workOrderTitle ?? 'a work order' })),
    ...line.orders.map((need) => ({
      date: need.workOrderId ? starts.get(need.workOrderId) ?? null : null,
      rank: 1,
      change: -need.required,
      label: need.viaItemCode ? `via ${need.viaItemCode}` : need.workOrderTitle ?? 'a work order',
    })),
  ].sort((left, right) => Number(left.date === null) - Number(right.date === null)
    || (left.date ?? '').localeCompare(right.date ?? '') || left.rank - right.rank)
  let balance = round(line.usable + (made ? 0 : (line.plannedSupply ?? 0)))
  for (const event of events) {
    balance = round(balance + event.change)
    if (event.change < 0 && balance < 0) return { date: event.date, label: event.label, short: -balance }
  }
  return null
}
