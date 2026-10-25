import type {
  StorageLocationCreateInput,
  StorageLocationDto,
  StorageLocationType,
  StorageLocationUpdateInput,
} from '../../../entities/inventory/api/useStorageLocations'
import type { InventoryDto } from '../../../shared/types/api'

/** From the outermost kind to the innermost; a place sits only inside a kind earlier in this list. */
export const LOCATION_TYPES: StorageLocationType[] = ['site', 'warehouse', 'zone', 'location', 'bin']

export const LOCATION_TYPE_LABELS: Record<StorageLocationType, string> = {
  site: 'Site',
  warehouse: 'Warehouse',
  zone: 'Zone',
  location: 'Location',
  bin: 'Bin',
}

/** The form as typed; the parent is a place id, empty for the top level. */
export interface LocationForm {
  locationCode: string
  locationName: string
  locationType: StorageLocationType
  parentLocationId: string
  note: string
  storageCondition: string
}

export const EMPTY_LOCATION_FORM: LocationForm = {
  locationCode: '',
  locationName: '',
  locationType: 'location',
  parentLocationId: '',
  note: '',
  storageCondition: '',
}

export function locationForm(location: StorageLocationDto): LocationForm {
  return {
    locationCode: location.locationCode,
    locationName: location.locationName ?? '',
    locationType: location.locationType,
    parentLocationId: location.parentLocationId ?? '',
    note: location.note ?? '',
    storageCondition: location.storageCondition ?? '',
  }
}

/** Ids of every place inside the given one, at any depth. */
export function placesInside(locations: StorageLocationDto[], locationId: string): Set<string> {
  const children = new Map<string, string[]>()
  for (const one of locations) {
    if (one.parentLocationId) children.set(one.parentLocationId, [...(children.get(one.parentLocationId) ?? []), one.locationId])
  }
  const inside = new Set<string>()
  const queue = [...(children.get(locationId) ?? [])]
  while (queue.length > 0) {
    const next = queue.shift() as string
    if (inside.has(next)) continue
    inside.add(next)
    queue.push(...(children.get(next) ?? []))
  }
  return inside
}

/** Lower-case codes of a place and every place inside it, to match stock records' places ignoring case. */
export function codesWithin(locations: StorageLocationDto[], locationId: string): Set<string> {
  const ids = placesInside(locations, locationId).add(locationId)
  return new Set(locations.filter((one) => ids.has(one.locationId)).map((one) => one.locationCode.trim().toLowerCase()))
}

/**
 * The places a place of this kind can sit in: active places of an outer kind, never the place itself or one inside it.
 * The place's current parent stays offered even when inactive, so editing other fields does not move it.
 */
export function parentOptions(
  locations: StorageLocationDto[],
  type: StorageLocationType,
  self: StorageLocationDto | null,
): StorageLocationDto[] {
  const rank = LOCATION_TYPES.indexOf(type)
  const inside = self ? placesInside(locations, self.locationId) : new Set<string>()
  return locations.filter(
    (one) =>
      LOCATION_TYPES.indexOf(one.locationType) < rank
      && one.locationId !== self?.locationId
      && !inside.has(one.locationId)
      && (one.active || one.locationId === self?.parentLocationId),
  )
}

/** The codes new stock can go to: active places in list order. Empty while the project keeps free-text locations. */
export function stockPlaceCodes(locations: StorageLocationDto[]): string[] {
  return locations.filter((one) => one.active).map((one) => one.locationCode)
}

/** Records holding stock and their different items. */
export interface StockHeld {
  records: number
  items: number
}

/**
 * Stock held at each place counting every place inside it (docs/domain/storage-location.md "화면"): records with stock
 * on hand or reserved, and their different items. Records are matched to places by code ignoring case, as the server
 * does; records at codes the list does not have count nowhere.
 */
export function stockWithin(
  locations: StorageLocationDto[],
  stock: Pick<InventoryDto, 'itemId' | 'quantity' | 'reservedQuantity' | 'location'>[],
): Map<string, StockHeld> {
  const byCode = new Map(locations.map((one) => [one.locationCode.trim().toLowerCase(), one]))
  const byId = new Map(locations.map((one) => [one.locationId, one]))
  const records = new Map<string, number>()
  const items = new Map<string, Set<string>>()
  for (const row of stock) {
    if (row.quantity === 0 && row.reservedQuantity === 0) continue
    let place = row.location ? byCode.get(row.location.trim().toLowerCase()) : undefined
    const seen = new Set<string>()
    while (place && !seen.has(place.locationId)) {
      seen.add(place.locationId)
      records.set(place.locationId, (records.get(place.locationId) ?? 0) + 1)
      items.set(place.locationId, (items.get(place.locationId) ?? new Set<string>()).add(row.itemId))
      place = place.parentLocationId ? byId.get(place.parentLocationId) : undefined
    }
  }
  return new Map(locations.map((one) => [one.locationId, {
    records: records.get(one.locationId) ?? 0,
    items: items.get(one.locationId)?.size ?? 0,
  }]))
}

/** "empty" or "3 records, 2 items". */
export function stockText(held: StockHeld): string {
  if (held.records === 0) return 'empty'
  return `${held.records} ${held.records === 1 ? 'record' : 'records'}, ${held.items} ${held.items === 1 ? 'item' : 'items'}`
}

/** Places stock records name that the list does not have, compared ignoring case as the server does, sorted. */
export function unlistedPlaces(locations: StorageLocationDto[], used: (string | null | undefined)[]): string[] {
  const listed = new Set(locations.map((one) => one.locationCode.trim().toLowerCase()))
  const seen = new Set<string>()
  const result: string[] = []
  for (const raw of used) {
    const code = raw?.trim()
    if (!code) continue
    const key = code.toLowerCase()
    if (listed.has(key) || seen.has(key)) continue
    seen.add(key)
    result.push(code)
  }
  return result.sort((left, right) => left.localeCompare(right))
}

/** Places whose code, name or path contains the text, ignoring case; inactive ones only when asked for. */
export function filterLocations(locations: StorageLocationDto[], text: string, showInactive: boolean): StorageLocationDto[] {
  const needle = text.trim().toLowerCase()
  return locations.filter(
    (one) =>
      (showInactive || one.active)
      && (!needle || [one.locationCode, one.locationName, one.path].some((value) => (value ?? '').toLowerCase().includes(needle))),
  )
}

function formError(form: LocationForm): string | null {
  if (!form.locationCode.trim()) return 'Enter a code.'
  if (form.locationCode.trim().length > 100) return 'The code is longer than 100 characters.'
  if (form.locationName.trim().length > 100) return 'The name is longer than 100 characters.'
  if (form.note.trim().length > 500) return 'The note is longer than 500 characters.'
  if (form.storageCondition.trim().length > 100) return 'The storage condition is longer than 100 characters.'
  return null
}

export function createPayload(
  projectId: string,
  form: LocationForm,
): { input: StorageLocationCreateInput; error: null } | { input: null; error: string } {
  const error = formError(form)
  if (error) return { input: null, error }
  return {
    input: {
      projectId,
      locationCode: form.locationCode.trim(),
      locationName: form.locationName.trim() || null,
      locationType: form.locationType,
      parentLocationId: form.parentLocationId || null,
      note: form.note.trim() || null,
      storageCondition: form.storageCondition.trim() || null,
    },
    error: null,
  }
}

/** Every field the form shows, so an emptied name, note or storage condition clears it; the parent only when it changed. */
export function updatePayload(
  form: LocationForm,
  location: StorageLocationDto,
): { input: StorageLocationUpdateInput; error: null } | { input: null; error: string } {
  const error = formError(form)
  if (error) return { input: null, error }
  const input: StorageLocationUpdateInput = {
    locationCode: form.locationCode.trim(),
    locationName: form.locationName.trim(),
    locationType: form.locationType,
    note: form.note.trim(),
    storageCondition: form.storageCondition.trim(),
  }
  if (form.parentLocationId !== (location.parentLocationId ?? '')) {
    if (form.parentLocationId) input.parentLocationId = form.parentLocationId
    else input.clearParent = true
  }
  return { input, error: null }
}
