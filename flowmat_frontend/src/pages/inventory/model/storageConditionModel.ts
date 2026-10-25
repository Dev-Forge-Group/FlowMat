import type { StorageLocationDto } from '../../../entities/inventory/api/useStorageLocations'
import type { InventoryDto, ItemDto } from '../../../shared/types/api'

/**
 * Storage conditions of places and items (docs/domain/storage-location.md SL1-SL4). Both are free text; two are the same when
 * they match trimmed, with inner spaces collapsed and ignoring case. Differences are pointed out, never enforced.
 */
export function conditionKey(condition: string | null | undefined): string | null {
  const key = (condition ?? '').trim().replace(/\s+/g, ' ').toLowerCase()
  return key === '' ? null : key
}

/** A place's condition: its own, else that of the nearest place it sits in that has one (SL1). */
export function effectiveCondition(
  locations: StorageLocationDto[],
  location: StorageLocationDto,
): { condition: string | null; from: StorageLocationDto | null } {
  const byId = new Map(locations.map((one) => [one.locationId, one]))
  const seen = new Set<string>()
  let place: StorageLocationDto | undefined = location
  while (place && !seen.has(place.locationId)) {
    seen.add(place.locationId)
    if (conditionKey(place.storageCondition)) {
      return { condition: (place.storageCondition ?? '').trim(), from: place === location ? null : place }
    }
    place = place.parentLocationId ? byId.get(place.parentLocationId) : undefined
  }
  return { condition: null, from: null }
}

/** "Chilled", "Chilled (from WH-A)" or "—". */
export function conditionText(locations: StorageLocationDto[], location: StorageLocationDto): string {
  const { condition, from } = effectiveCondition(locations, location)
  if (!condition) return '—'
  return from ? `${condition} (from ${from.locationCode})` : condition
}

/**
 * Why an item should not go to a place, e.g. `FISH is kept "Frozen"; B1 is "Chilled".`; null when the place is not listed,
 * either has no condition, or they match (SL3).
 */
export function conditionWarning(
  locations: StorageLocationDto[],
  code: string,
  itemCode?: string,
  itemCondition?: string | null,
): string | null {
  const wanted = conditionKey(itemCondition)
  const key = code.trim().toLowerCase()
  if (!wanted || !key) return null
  const place = locations.find((one) => one.locationCode.trim().toLowerCase() === key)
  if (!place) return null
  const { condition } = effectiveCondition(locations, place)
  if (!conditionKey(condition) || conditionKey(condition) === wanted) return null
  return `${itemCode ?? 'This item'} is kept "${(itemCondition ?? '').trim()}"; ${place.locationCode} is "${condition}".`
}

export interface ConditionMismatch {
  inventoryId: string
  itemCode: string
  itemCondition: string
  placeCondition: string
}

/**
 * Stock records holding stock at a listed place whose condition differs from their item's, by the place they are at (SL2).
 * Records are matched to places by code ignoring case, as the server does.
 */
export function conditionMismatches(
  locations: StorageLocationDto[],
  stock: Pick<InventoryDto, 'inventoryId' | 'itemId' | 'quantity' | 'reservedQuantity' | 'location'>[],
  items: Pick<ItemDto, 'itemId' | 'itemCode' | 'details'>[],
): Map<string, ConditionMismatch[]> {
  const byCode = new Map(locations.map((one) => [one.locationCode.trim().toLowerCase(), one]))
  const byItem = new Map(items.map((item) => [item.itemId, item]))
  const result = new Map<string, ConditionMismatch[]>()
  for (const row of stock) {
    if (row.quantity === 0 && row.reservedQuantity === 0) continue
    const place = row.location ? byCode.get(row.location.trim().toLowerCase()) : undefined
    const item = byItem.get(row.itemId)
    const wanted = conditionKey(item?.details?.storageCondition)
    if (!place || !item || !wanted) continue
    const { condition } = effectiveCondition(locations, place)
    if (!condition || conditionKey(condition) === wanted) continue
    result.set(place.locationId, [...(result.get(place.locationId) ?? []), { inventoryId: row.inventoryId, itemCode: item.itemCode,
      itemCondition: (item.details?.storageCondition ?? '').trim(), placeCondition: condition }])
  }
  return result
}

/** "1 record needs another condition: FISH (Frozen)". */
export function mismatchText(mismatches: ConditionMismatch[]): string {
  const items = [...new Map(mismatches.map((one) => [one.itemCode, `${one.itemCode} (${one.itemCondition})`])).values()]
  return `${mismatches.length} ${mismatches.length === 1 ? 'record needs' : 'records need'} another condition: ${items.join(', ')}`
}

/** Conditions places and items use, each once as first written, sorted: suggestions for a place's condition. */
export function knownConditions(
  locations: StorageLocationDto[],
  items: Pick<ItemDto, 'details'>[],
): string[] {
  const seen = new Map<string, string>()
  for (const value of [...locations.map((one) => one.storageCondition), ...items.map((item) => item.details?.storageCondition)]) {
    const key = conditionKey(value)
    if (key && !seen.has(key)) seen.set(key, (value ?? '').trim().replace(/\s+/g, ' '))
  }
  return [...seen.values()].sort((left, right) => left.localeCompare(right))
}
