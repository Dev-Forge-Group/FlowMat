import { describe, expect, it } from 'vitest'
import type { StorageLocationDto } from '../../../entities/inventory/api/useStorageLocations'
import {
  conditionKey,
  conditionMismatches,
  conditionText,
  conditionWarning,
  effectiveCondition,
  knownConditions,
  mismatchText,
} from './storageConditionModel'

const place = (locationId: string, parentLocationId: string | null, storageCondition: string | null = null): StorageLocationDto => ({
  locationId, projectId: 'p', parentLocationId, locationCode: locationId.toUpperCase(), locationName: null, locationType: 'bin',
  active: true, note: null, path: locationId.toUpperCase(), depth: 0, stockRecords: 0, itemCount: 0, storageCondition,
})
const cold = place('cold', null, 'Chilled')
const shelf = place('shelf', 'cold')
const freezer = place('freezer', 'cold', ' frozen ')
const dry = place('dry', null)
const all = [cold, shelf, freezer, dry]
const item = (itemId: string, storageCondition: string | null) => ({ itemId, itemCode: itemId.toUpperCase(),
  details: { itemGroup: null, spec: null, barcode: null, sku: null, storageCondition, description: null } })

describe('storage conditions', () => {
  it('compares conditions however they are typed', () => {
    expect(conditionKey('  Chilled   2-8 C ')).toBe('chilled 2-8 c')
    expect(conditionKey('   ')).toBeNull()
    expect(conditionKey(null)).toBeNull()
  })

  it('takes a place\'s own condition, else the nearest outer one', () => {
    expect(effectiveCondition(all, freezer)).toEqual({ condition: 'frozen', from: null })
    expect(effectiveCondition(all, shelf)).toEqual({ condition: 'Chilled', from: cold })
    expect(effectiveCondition(all, dry)).toEqual({ condition: null, from: null })
    expect(conditionText(all, shelf)).toBe('Chilled (from COLD)')
    expect(conditionText(all, cold)).toBe('Chilled')
    expect(conditionText(all, dry)).toBe('—')
  })

  it('points out an item going to a place kept differently', () => {
    expect(conditionWarning(all, 'shelf', 'FISH', 'Frozen')).toBe('FISH is kept "Frozen"; SHELF is "Chilled".')
    expect(conditionWarning(all, 'FREEZER', 'FISH', 'FROZEN')).toBeNull()
    expect(conditionWarning(all, 'dry', 'FISH', 'Frozen')).toBeNull()
    expect(conditionWarning(all, 'nowhere', 'FISH', 'Frozen')).toBeNull()
    expect(conditionWarning(all, 'shelf', 'SALT', null)).toBeNull()
  })

  it('lists stock records kept where their item needs another condition', () => {
    const stock = [
      { inventoryId: 'i1', itemId: 'fish', quantity: 5, reservedQuantity: 0, location: 'shelf' },
      { inventoryId: 'i2', itemId: 'fish', quantity: 0, reservedQuantity: 0, location: 'shelf' },
      { inventoryId: 'i3', itemId: 'fish', quantity: 2, reservedQuantity: 0, location: 'freezer' },
      { inventoryId: 'i4', itemId: 'salt', quantity: 2, reservedQuantity: 0, location: 'shelf' },
      { inventoryId: 'i5', itemId: 'fish', quantity: 1, reservedQuantity: 0, location: 'unlisted' },
      { inventoryId: 'i6', itemId: 'fish', quantity: 0, reservedQuantity: 1, location: 'Shelf' },
    ]
    const found = conditionMismatches(all, stock, [item('fish', 'Frozen'), item('salt', null)])
    expect([...found.keys()]).toEqual(['shelf'])
    expect(found.get('shelf')?.map((one) => one.inventoryId)).toEqual(['i1', 'i6'])
    expect(mismatchText(found.get('shelf')!)).toBe('2 records need another condition: FISH (Frozen)')
    expect(mismatchText(found.get('shelf')!.slice(0, 1))).toBe('1 record needs another condition: FISH (Frozen)')
  })

  it('suggests the conditions in use once each', () => {
    expect(knownConditions(all, [item('fish', 'Frozen'), item('salt', null), item('milk', 'chilled')])).toEqual(['Chilled', 'frozen'])
  })
})
