import { describe, expect, it } from 'vitest'
import {
  applyItemDefaults, createDefaultPortFormState, hasValidPortSelection, portNeedsMeasure, toCreateProcessIoInput, toPortFormState,
  toUpdateProcessIoInput, needsMorePortFields,
} from './portPolicy'
import type { ItemDto } from '../../../shared/types/api'
import { toPortViewModel } from './toWorkflowCanvasViewModel'
import type { ProcessIoDto } from '../../../shared/types/api'

describe('port contract form', () => {
  it('needs a quantity and unit only on material, product and item ports (docs/domain/port-measurement.md)', () => {
    const data = { ...createDefaultPortFormState('output'), ioType: 'data', resourceType: 'data', quantity: '', unit: '' }
    expect(portNeedsMeasure(data)).toBe(false)
    expect(hasValidPortSelection(data)).toBe(true)
    expect(toCreateProcessIoInput('process-1', data)).toMatchObject({ quantity: undefined, unit: undefined })
    expect(hasValidPortSelection({ ...data, quantity: '3' })).toBe(false)
    expect(hasValidPortSelection({ ...data, unit: 'kWh' })).toBe(true)
    // A blank value is cleared on update; the other one is sent again.
    expect(toUpdateProcessIoInput({ ...data, processIoId: 'port-1', unit: 'kWh' }))
      .toMatchObject({ clearMeasure: true, quantity: undefined, unit: 'kWh' })
    expect(toUpdateProcessIoInput({ ...data, processIoId: 'port-1', quantity: '2', unit: 'kWh' }).clearMeasure).toBeUndefined()

    const material = { ...data, ioType: 'material', resourceType: 'material' }
    expect(portNeedsMeasure(material)).toBe(true)
    expect(hasValidPortSelection(material)).toBe(false)
    expect(hasValidPortSelection({ ...material, quantity: '0', unit: 'kg' })).toBe(true)
    expect(portNeedsMeasure({ ...data, itemId: 'item-1' })).toBe(true)
    expect(portNeedsMeasure({ ...data, resourceType: ' Product ' })).toBe(true)

    const stored = toPortViewModel({ processIoId: 'port-2', processId: 'process-1', itemId: null, ioName: 'Rows',
      direction: 'output', ioType: 'data', role: null, resourceType: 'data', schemaJson: null, validationRule: null,
      quantity: null, unit: null, formula: null, colorScheme: null, requiredYn: 'Y', allowShortageYn: 'N' } as unknown as ProcessIoDto)
    expect(toPortFormState(stored)).toMatchObject({ quantity: '', unit: '' })
  })

  it('can edit a stored port with nullable display fields', () => {
    const port: ProcessIoDto = {
      processIoId: 'port-1', processId: 'process-1', itemId: 'item-1',
      ioName: null, direction: 'input', ioType: null, role: null, resourceType: 'material',
      schemaJson: null, validationRule: null, quantity: 1, unit: 'kg', formula: null,
      colorScheme: null, requiredYn: 'Y', allowShortageYn: 'N',
    }

    const view = toPortViewModel(port)
    expect(view).toMatchObject({ name: '', ioType: 'material', colorScheme: 'sky' })
    const state = toPortFormState(view)
    expect(state).toMatchObject({ ioName: '', ioType: 'material', colorScheme: 'sky' })
    expect(hasValidPortSelection(state)).toBe(true)
    expect(toUpdateProcessIoInput(state)).toMatchObject({
      processIoId: 'port-1', ioName: '', ioType: 'material', colorScheme: 'sky',
    })
  })

  it('lets a port have no item: create leaves itemId out and update clears the binding', () => {
    const state = { ...createDefaultPortFormState('output'), ioName: 'Parsed rows', resourceType: 'data', unit: 'ea' }
    expect(hasValidPortSelection(state)).toBe(true)
    expect(toCreateProcessIoInput('process-1', state).itemId).toBeUndefined()
    const update = toUpdateProcessIoInput({ ...state, processIoId: 'port-1' })
    expect(update).toMatchObject({ processIoId: 'port-1', clearItem: true })
    expect(update).not.toHaveProperty('itemId')
    const bound = toUpdateProcessIoInput({ ...state, processIoId: 'port-1', itemId: 'item-1' })
    expect(bound).toMatchObject({ itemId: 'item-1' })
    expect(bound).not.toHaveProperty('clearItem')
  })

  it('sends a structured schema and resource metadata', () => {
    const state = {
      ...createDefaultPortFormState(),
      itemId: 'item-1',
      unit: 'kg',
      role: 'feed',
      resourceType: 'material',
      schemaJson: '{"type":"object","properties":{"lot":{"type":"string"}},"required":["lot"]}',
      validationRule: 'quantity > 0',
    }

    expect(hasValidPortSelection(state)).toBe(true)
    expect(toCreateProcessIoInput('process-1', state)).toMatchObject({
      role: 'feed',
      resourceType: 'material',
      schemaJson: { type: 'object', properties: { lot: { type: 'string' } }, required: ['lot'] },
      validationRule: 'quantity > 0',
    })
  })

  it('refuses malformed or non-object schemas before submit', () => {
    const base = { ...createDefaultPortFormState(), itemId: 'item-1', unit: 'kg' }
    expect(hasValidPortSelection({ ...base, schemaJson: '{invalid' })).toBe(false)
    expect(hasValidPortSelection({ ...base, schemaJson: '[1,2]' })).toBe(false)
    expect(hasValidPortSelection({ ...base, schemaJson: '{"type":"string"}' })).toBe(false)
    expect(hasValidPortSelection({ ...base, schemaJson: '{"type":"object","required":["lot"]}' })).toBe(false)
    expect(hasValidPortSelection({ ...base, schemaJson: '{"type":"object","properties":{"lot":{"type":"array"}}}' })).toBe(false)
    expect(hasValidPortSelection({ ...base, schemaJson: JSON.stringify({ type: 'object', note: 'x'.repeat(65536) }) })).toBe(false)
    expect(hasValidPortSelection({ ...base, schemaJson: '{"type":"object","custom":true}' })).toBe(true)
  })

  it('refuses negative and non-finite quantities', () => {
    const base = { ...createDefaultPortFormState(), itemId: 'item-1', unit: 'kg' }
    expect(hasValidPortSelection({ ...base, quantity: '-1' })).toBe(false)
    expect(hasValidPortSelection({ ...base, quantity: 'Infinity' })).toBe(false)
    expect(hasValidPortSelection({ ...base, quantity: '0.00001' })).toBe(false)
    expect(hasValidPortSelection({ ...base, quantity: '10000000000' })).toBe(false)
    expect(hasValidPortSelection({ ...base, quantity: '9999999999.9999' })).toBe(true)
    expect(hasValidPortSelection({ ...base, quantity: '1.5' })).toBe(true)
  })

  it('refuses port text that exceeds database field limits', () => {
    const base = { ...createDefaultPortFormState(), itemId: 'item-1', unit: 'kg' }
    expect(hasValidPortSelection({ ...base, ioName: 'x'.repeat(101) })).toBe(false)
    expect(hasValidPortSelection({ ...base, role: 'x'.repeat(51) })).toBe(false)
    expect(hasValidPortSelection({ ...base, resourceType: 'x'.repeat(51) })).toBe(false)
    expect(hasValidPortSelection({ ...base, unit: 'x'.repeat(21) })).toBe(false)
    expect(hasValidPortSelection({ ...base, validationRule: 'x'.repeat(2001) })).toBe(false)
  })

  it('explicitly clears a removed schema on update', () => {
    const state = { ...createDefaultPortFormState(), processIoId: 'port-1', itemId: 'item-1', unit: 'kg' }
    expect(toUpdateProcessIoInput(state)).toMatchObject({
      clearSchema: true,
      role: '',
      formula: '',
      validationRule: '',
    })
    expect(toUpdateProcessIoInput({ ...state, schemaJson: '{"type":"object"}' }))
      .toMatchObject({ clearSchema: false, schemaJson: { type: 'object' } })
  })

  it('takes an item\'s name and unit code, not its unit id, where the port has none yet', () => {
    const milk = { itemId: 'itm-milk', itemName: 'Raw milk', unitId: 'unit_kg' } as ItemDto
    const blank = createDefaultPortFormState('input')
    expect(applyItemDefaults(blank, milk, 'kg')).toMatchObject({ itemId: 'itm-milk', ioName: 'Raw milk', unit: 'kg' })
    // Without the code (units not loaded yet) the unit stays empty rather than taking the id.
    expect(applyItemDefaults(blank, milk, undefined).unit).toBe('')
    // What the user already typed stays.
    expect(applyItemDefaults({ ...blank, ioName: 'Milk in', unit: 'l' }, milk, 'kg')).toMatchObject({ ioName: 'Milk in', unit: 'l' })
  })

  it('folds the less common fields away for a plain material port with an item, and keeps them out otherwise', () => {
    const plain = { ...createDefaultPortFormState('input'), itemId: 'milk', ioName: 'Raw milk', quantity: '100', unit: 'kg' }
    expect(needsMorePortFields(plain)).toBe(false)
    expect(needsMorePortFields({ ...plain, itemId: '' })).toBe(true)
    expect(needsMorePortFields({ ...plain, validationRule: 'attrs.rows > 0' })).toBe(true)
    expect(needsMorePortFields({ ...plain, allowShortageYn: 'Y' })).toBe(true)
    expect(needsMorePortFields({ ...plain, colorScheme: 'rose' })).toBe(true)
  })
})
