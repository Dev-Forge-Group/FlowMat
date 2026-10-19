import { describe, expect, it } from 'vitest'
import type { InventoryDto, LotDto, ProductionRunItemDto, RunCorrectionLineDto } from '../../../shared/types/api'
import {
  buildCorrectionRequest,
  describeCorrectionLine,
  inputLotOptions,
  orderStockForPick,
  voidableRecordings,
  type CorrectionDraft,
} from './correctionModel'

function row(overrides: Partial<ProductionRunItemDto>): ProductionRunItemDto {
  return {
    productionRunItemId: 'r',
    productionRunId: 'run',
    processId: null,
    processIoId: null,
    inventoryId: null,
    itemId: 'flour',
    direction: 'input',
    plannedQty: 5,
    actualQty: 5,
    unit: 'kg',
    quantitySource: 'manual',
    conversionRate: null,
    lotId: null,
    cancelled: false,
    cancelledBy: null,
    cancelledAt: null,
    cancelReason: null,
    ...overrides,
  }
}

const draft = (overrides: Partial<CorrectionDraft>): CorrectionDraft => ({
  reason: 'Wrong LOT',
  voidRunItemIds: [],
  adds: [],
  outputQty: '',
  ...overrides,
})

describe('building a correction request', () => {
  it('voids, adds and sets the output in that order', () => {
    const result = buildCorrectionRequest(
      draft({
        voidRunItemIds: ['a'],
        adds: [{ direction: 'input', itemId: 'flour', inventoryId: 'lot-b', qty: '5', unit: ' kg ' }],
        outputQty: '6',
      }),
      4,
    )
    expect(result).toEqual({
      ok: true,
      body: {
        reason: 'Wrong LOT',
        lines: [
          { kind: 'void_item', targetRunItemId: 'a' },
          { kind: 'add_item', direction: 'input', itemId: 'flour', inventoryId: 'lot-b', qty: 5, unit: 'kg' },
          { kind: 'set_output_qty', afterQty: 6 },
        ],
      },
    })
  })

  it('says what is missing', () => {
    expect(buildCorrectionRequest(draft({ reason: ' ' }), 1)).toEqual({ ok: false, error: 'Give a reason for the correction.' })
    expect(buildCorrectionRequest(draft({}), 1)).toEqual({ ok: false, error: 'Choose at least one change.' })
    expect(
      buildCorrectionRequest(draft({ adds: [{ direction: 'input', itemId: 'flour', inventoryId: '', qty: '0', unit: 'kg' }] }), 1),
    ).toEqual({ ok: false, error: 'Added recording 1: the quantity must be greater than 0.' })
    expect(buildCorrectionRequest(draft({ outputQty: '4' }), 4)).toEqual({ ok: false, error: "The run's output is already 4." })
  })

  it('sends no stock record when none was chosen', () => {
    const result = buildCorrectionRequest(
      draft({ adds: [{ direction: 'output', itemId: 'bread', inventoryId: '', qty: '2', unit: 'ea' }] }),
      null,
    )
    expect(result.ok && result.body.lines[0]).toMatchObject({ kind: 'add_item', inventoryId: null })
  })
})

describe('correction lines', () => {
  const items = [row({ productionRunItemId: 'a' }), row({ productionRunItemId: 'b', cancelled: true }), row({ productionRunItemId: 'p', quantitySource: 'bom' })]
  const label = (id: string) => id.toUpperCase()
  const qty = (value: number | null | undefined) => String(value ?? '-')
  const line = (overrides: Partial<RunCorrectionLineDto>): RunCorrectionLineDto => ({
    lineNo: 1,
    kind: 'void_item',
    targetRunItemId: null,
    direction: null,
    itemId: null,
    inventoryId: null,
    qty: null,
    unit: null,
    beforeQty: null,
    afterQty: null,
    createdRunItemId: null,
    ...overrides,
  })

  it('only offers standing recordings for voiding', () => {
    expect(voidableRecordings(items).map((item) => item.productionRunItemId)).toEqual(['a'])
  })

  it('describes each kind', () => {
    expect(describeCorrectionLine(line({ targetRunItemId: 'a' }), items, label, qty)).toBe('Void input FLOUR 5 kg')
    expect(describeCorrectionLine(line({ kind: 'add_item', direction: 'input', itemId: 'salt', qty: 1, unit: 'kg' }), items, label, qty))
      .toBe('Add input SALT 1 kg')
    expect(describeCorrectionLine(line({ kind: 'set_output_qty', beforeQty: 4, afterQty: 6 }), items, label, qty)).toBe('Output 4 → 6')
  })
})

describe('stock offered for an added recording', () => {
  const inv = (id: string, lotId: string | null) => ({ inventoryId: id, lotId }) as unknown as InventoryDto
  const lot = (lotId: string, expiryDate: string | null, expired = false) => ({ lotId, expiryDate, expired }) as unknown as LotDto

  it('puts the first-expiring LOT first, undated next and expired LOTs last', () => {
    const picks = orderStockForPick(
      [inv('none', null), inv('late', 'L2'), inv('gone', 'L3'), inv('soon', 'L1'), inv('undated', 'L4')],
      [lot('L1', '2026-10-01'), lot('L2', '2026-12-01'), lot('L3', '2026-09-01', true), lot('L4', null)],
    )
    expect(picks.map((pick) => pick.inventory.inventoryId)).toEqual(['soon', 'late', 'none', 'undated', 'gone'])
    expect(picks[4]).toMatchObject({ expired: true, expiryDate: '2026-09-01' })
  })
})

describe('LOTs offered in a run record form', () => {
  const inv = (id: string, lotId: string | null, inventoryStatus = 'available') => ({ inventoryId: id, lotId, inventoryStatus }) as unknown as InventoryDto
  const lot = (lotId: string, expiryDate: string | null, expired = false) => ({ lotId, expiryDate, expired }) as unknown as LotDto
  const lots = [lot('L1', '2026-10-01'), lot('L2', '2026-12-01'), lot('L3', '2026-09-01', true)]

  it('orders inputs FEFO, blocks expired and quarantined records and marks the first usable one', () => {
    const options = inputLotOptions([inv('late', 'L2'), inv('gone', 'L3'), inv('held', 'L1', 'quarantined'), inv('soon', 'L1')], lots, 'input')
    expect(options.map((option) => [option.inventory.inventoryId, option.disabled, option.useFirst])).toEqual([
      ['held', true, false],
      ['soon', false, true],
      ['late', false, false],
      ['gone', true, false],
    ])
  })

  it('offers stock already picked to the order first, and prefers it among the LOTs expiring first', () => {
    const at = (id: string, lotId: string, location: string) =>
      ({ inventoryId: id, lotId, inventoryStatus: 'available', location }) as unknown as InventoryDto
    const stock = [at('shelf-soon', 'L1', 'A-1'), at('staged-late', 'L2', 'line-1'), at('staged-soon', 'L1', 'LINE-1 ')]
    expect(inputLotOptions(stock, lots, 'input', new Set(['line-1']))
      .map((option) => [option.inventory.inventoryId, option.staged, option.useFirst])).toEqual([
      ['staged-soon', true, true],
      ['staged-late', true, false],
      ['shelf-soon', false, false],
    ])
    // Without a staging place nothing moves, and the first-expiring record in list order is to use first.
    expect(inputLotOptions(stock, lots, 'input').map((option) => [option.inventory.inventoryId, option.useFirst])).toEqual([
      ['shelf-soon', true],
      ['staged-soon', false],
      ['staged-late', false],
    ])
  })

  it('marks nothing when there is no choice or no expiry date, and keeps outputs as they are', () => {
    expect(inputLotOptions([inv('soon', 'L1')], lots, 'input')[0].useFirst).toBe(false)
    expect(inputLotOptions([inv('a', null), inv('b', null)], lots, 'input').some((option) => option.useFirst)).toBe(false)
    const outputs = inputLotOptions([inv('gone', 'L3'), inv('late', 'L2')], lots, 'output')
    expect(outputs.map((option) => [option.inventory.inventoryId, option.disabled])).toEqual([['gone', false], ['late', false]])
  })
})

describe('setup correction lines', () => {
  it('are described by what they change', () => {
    const base = { lineNo: 1, targetRunItemId: null, direction: null, itemId: null, inventoryId: null, qty: null, unit: null,
      beforeQty: null, afterQty: null, createdRunItemId: null }
    expect(describeCorrectionLine({ ...base, kind: 'cancel_setup', targetRunSetupId: 's1' }, [], String, String)).toBe('Cancel a setup')
    expect(describeCorrectionLine({ ...base, kind: 'add_setup', equipmentId: 'press', setupMinutes: 20 }, [], String, String))
      .toBe('Add a setup of 20 min on press')
    expect(describeCorrectionLine({ ...base, kind: 'add_setup', equipmentId: 'press', setupMinutes: 20 }, [], String, String,
      () => 'PRESS')).toBe('Add a setup of 20 min on PRESS')
    expect(describeCorrectionLine({ ...base, kind: 'cancel_running_time', targetRunEquipmentTimeId: 't1' }, [], String, String))
      .toBe('Cancel running time')
    expect(describeCorrectionLine({ ...base, kind: 'add_running_time', equipmentId: 'oven', runningMinutes: 30 }, [], String, String,
      () => 'OVEN-1')).toBe('Add 30 min of running time on OVEN-1')
    expect(describeCorrectionLine({ ...base, kind: 'add_running_time', equipmentId: null, runningMinutes: null }, [], String, String))
      .toBe('Add ? min of running time')
    expect(describeCorrectionLine({ ...base, kind: 'cancel_labor', targetRunLaborId: 'l1' }, [], String, String))
      .toBe('Cancel worked time')
    expect(describeCorrectionLine({ ...base, kind: 'add_labor', userId: 'kim', workedMinutes: 30 }, [], String, String))
      .toBe('Add 30 min worked by kim')
    expect(describeCorrectionLine({ ...base, kind: 'add_labor', userId: 'kim', workedMinutes: 30, stepId: 's1' }, [], String, String))
      .toBe('Add 30 min worked by kim on a step')
  })
})
