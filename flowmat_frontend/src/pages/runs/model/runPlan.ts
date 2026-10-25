import type { ProductionRunDto, ProductionRunItemDto } from '../../../shared/types/api'

/**
 * How much of a BOM-planned input has been recorded by hand: the sum of manual input rows for the same item in the
 * same unit. Rows in another unit are not converted here (the server converts when it moves stock), so they do not
 * count towards this figure.
 */
export function recordedAgainstPlan(planned: ProductionRunItemDto, items: ProductionRunItemDto[]): number {
  return items
    .filter(
      (item) =>
        item.quantitySource !== 'bom'
        && !item.cancelled
        && item.direction === planned.direction
        && item.itemId === planned.itemId
        && item.unit === planned.unit,
    )
    .reduce((sum, item) => sum + Number(item.actualQty ?? item.plannedQty ?? 0), 0)
}

/** Who cancelled a recording, when and why, for its row on the run screen; null when it was not cancelled. */
export function cancelSummary(
  item: ProductionRunItemDto,
  formatTime: (iso: string) => string = (iso) => new Date(iso).toLocaleString(),
): string | null {
  if (!item.cancelled) return null
  const when = item.cancelledAt ? ` on ${formatTime(item.cancelledAt)}` : ''
  return `Cancelled by ${item.cancelledBy ?? '?'}${when}: ${item.cancelReason ?? ''}`
}

/**
 * Whether cancelling a recording can give its stock back to the run's work order (docs/domain/stock-allocation.md R4): an
 * input taken from a stock record by a run of an order that moves real stock.
 */
export function canAllocateAgain(item: ProductionRunItemDto, run: Pick<ProductionRunDto, 'workOrderId' | 'runType'>): boolean {
  const physical = !['simulation', 'test', 'dry_run'].includes((run.runType ?? '').toLowerCase())
  return item.direction === 'input' && Boolean(item.inventoryId) && Boolean(run.workOrderId) && physical
}

/** What is still to record for a planned line; never negative. */
export function remainingOfPlan(planned: ProductionRunItemDto, items: ProductionRunItemDto[]): number {
  return Math.max(0, Number(planned.plannedQty) - recordedAgainstPlan(planned, items))
}
