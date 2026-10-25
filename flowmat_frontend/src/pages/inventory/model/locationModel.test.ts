import { describe, expect, it } from 'vitest'
import type { StorageLocationDto } from '../../../entities/inventory/api/useStorageLocations'
import {
  EMPTY_LOCATION_FORM,
  codesWithin,
  createPayload,
  filterLocations,
  locationForm,
  parentOptions,
  placesInside,
  stockPlaceCodes,
  stockText,
  stockWithin,
  unlistedPlaces,
  updatePayload,
} from './locationModel'

function place(
  locationId: string,
  locationType: StorageLocationDto['locationType'],
  parentLocationId: string | null,
  extra: Partial<StorageLocationDto> = {},
): StorageLocationDto {
  return {
    locationId, projectId: 'p', parentLocationId, locationCode: locationId.toUpperCase(), locationName: null, locationType,
    active: true, note: null, path: locationId.toUpperCase(), depth: 0, stockRecords: 0, itemCount: 0, ...extra,
  }
}

const site = place('s1', 'site', null)
const warehouse = place('wh1', 'warehouse', 's1')
const zone = place('z1', 'zone', 'wh1', { locationName: 'Cold room', path: 'S1 / WH1 / Z1' })
const bin = place('b1', 'bin', 'z1')
const oldZone = place('z0', 'zone', 'wh1', { active: false })
const all = [site, warehouse, zone, bin, oldZone]

describe('placesInside', () => {
  it('finds places at any depth below', () => {
    expect([...placesInside(all, 's1')].sort()).toEqual(['b1', 'wh1', 'z0', 'z1'])
    expect(placesInside(all, 'b1').size).toBe(0)
  })
})

describe('parentOptions', () => {
  it('offers active places of an outer kind only', () => {
    expect(parentOptions(all, 'bin', null).map((one) => one.locationId)).toEqual(['s1', 'wh1', 'z1'])
    expect(parentOptions(all, 'site', null)).toEqual([])
  })

  it('never offers the place itself or a place inside it, but keeps its inactive parent', () => {
    expect(parentOptions(all, 'zone', warehouse).map((one) => one.locationId)).toEqual(['s1'])
    const underOld = place('b9', 'bin', 'z0', { active: false })
    expect(parentOptions([...all, underOld], 'bin', underOld).map((one) => one.locationId)).toEqual(['s1', 'wh1', 'z1', 'z0'])
  })
})

describe('stockPlaceCodes and unlistedPlaces', () => {
  it('suggests active codes and finds places stock uses that the list lacks', () => {
    expect(stockPlaceCodes(all)).toEqual(['S1', 'WH1', 'Z1', 'B1'])
    expect(unlistedPlaces(all, ['wh1', ' Dock ', null, 'dock', 'attic', ''])).toEqual(['attic', 'Dock'])
  })
})

describe('codesWithin', () => {
  it('takes the place and every place inside it, as lower-case codes', () => {
    expect([...codesWithin([site, warehouse, zone, bin], 'wh1')].sort()).toEqual(['b1', 'wh1', 'z1'])
    expect([...codesWithin([site, warehouse, zone, bin], 'b1')]).toEqual(['b1'])
  })
})

describe('stockWithin', () => {
  it('counts the stock of every place inside, matching codes ignoring case', () => {
    const stock = [
      { itemId: 'flour', quantity: 5, reservedQuantity: 0, location: 'b1' },
      { itemId: 'sugar', quantity: 0, reservedQuantity: 2, location: 'Z1' },
      { itemId: 'flour', quantity: 3, reservedQuantity: 0, location: 'Z1' },
      { itemId: 'salt', quantity: 0, reservedQuantity: 0, location: 'B1' },
      { itemId: 'salt', quantity: 4, reservedQuantity: 0, location: 'elsewhere' },
      { itemId: 'salt', quantity: 4, reservedQuantity: 0, location: null },
    ]
    const held = stockWithin([site, warehouse, zone, bin], stock)
    expect(held.get('b1')).toEqual({ records: 1, items: 1 })
    expect(held.get('z1')).toEqual({ records: 3, items: 2 })
    expect(held.get('wh1')).toEqual({ records: 3, items: 2 })
    expect(held.get('s1')).toEqual({ records: 3, items: 2 })
    expect(stockText(held.get('b1')!)).toBe('1 record, 1 item')
    expect(stockText(held.get('s1')!)).toBe('3 records, 2 items')
    expect(stockText({ records: 0, items: 0 })).toBe('empty')
  })
})

describe('filterLocations', () => {
  it('matches code, name or path and hides inactive places unless asked', () => {
    expect(filterLocations(all, 'cold', false)).toEqual([zone])
    expect(filterLocations(all, '', false)).not.toContain(oldZone)
    expect(filterLocations(all, 'z0', true)).toEqual([oldZone])
  })
})

describe('payloads', () => {
  it('trims the form and names what is missing', () => {
    expect(createPayload('p', { ...EMPTY_LOCATION_FORM, locationCode: '  ' })).toEqual({ input: null, error: 'Enter a code.' })
    expect(createPayload('p', { ...EMPTY_LOCATION_FORM, locationCode: ' A-01 ', locationType: 'bin', parentLocationId: 'z1' })).toEqual({
      input: { projectId: 'p', locationCode: 'A-01', locationName: null, locationType: 'bin', parentLocationId: 'z1', note: null,
        storageCondition: null },
      error: null,
    })
  })

  it('sends the parent only when it changed and clears emptied text', () => {
    expect(updatePayload({ ...locationForm(zone), locationName: '' }, zone).input).toEqual({
      locationCode: 'Z1', locationName: '', locationType: 'zone', note: '', storageCondition: '',
    })
    expect(updatePayload({ ...locationForm(zone), parentLocationId: '' }, zone).input).toMatchObject({ clearParent: true })
    expect(updatePayload({ ...locationForm(zone), parentLocationId: 's1' }, zone).input).toMatchObject({ parentLocationId: 's1' })
  })
})
