import { describe, expect, it } from 'vitest'
import type { WarehouseTaskDto } from '../../../entities/inventory/api/useWarehouseTasks'
import type { InventoryDto, WorkOrderDto } from '../../../shared/types/api'
import {
  EMPTY_PICK,
  EMPTY_PUTAWAY,
  assigneeChoices,
  freeToMove,
  movableRecords,
  partQuantity,
  pickPayload,
  pickableOrders,
  putawayPayload,
  scannedPlaceFits,
  scannerTasks,
  selectedScanTask,
  shortageText,
  stagingPlacesFor,
  taskCompletionInput,
  tasksFor,
  tasksForScan,
} from './warehouseTaskModel'

function record(inventoryId: string, patch: Partial<InventoryDto> = {}): InventoryDto {
  return {
    inventoryId, projectId: 'p', itemId: 'bolt', quantity: 10, reservedQuantity: 0, availableQuantity: 10, inventoryStatus: 'available',
    location: 'DOCK', minThreshold: null, maxThreshold: null, stockLevel: 'ok', version: 1, lotId: null, lotNo: null, ...patch,
  }
}

function task(inventoryId: string, quantity: number, status: WarehouseTaskDto['status'] = 'open'): WarehouseTaskDto {
  return {
    taskId: `t-${inventoryId}-${quantity}`, projectId: 'p', taskNo: 'WT-0001', taskType: 'putaway', status, inventoryId, itemId: 'bolt',
    itemCode: 'BOLT', itemName: 'Bolt', lotId: null, lotNo: null, quantity, fromLocation: 'DOCK', toLocation: 'SHELF', workOrderId: null,
    workOrderNumber: null, note: null, createdBy: 'u', createdAt: '2026-09-27T00:00:00Z', finishedBy: null, finishedAt: null,
    transferId: null, cancelReason: null, assignedTo: null,
  }
}

it('keeps the observed remaining quantity with a completion when the task later changes', () => {
  const observed = task('record', 6)
  const whole = taskCompletionInput(observed, { expectedToLocation: 'SHELF' })
  const part = taskCompletionInput(observed, { quantity: 2 })
  observed.quantity = 4
  expect(whole).toEqual({ taskId: observed.taskId, expectedQuantity: 6, expectedToLocation: 'SHELF' })
  expect(part).toEqual({ taskId: observed.taskId, expectedQuantity: 6, quantity: 2 })
  expect(taskCompletionInput(observed)).toEqual({ taskId: observed.taskId, expectedQuantity: 4 })
})

describe('freeToMove and movableRecords', () => {
  it('keeps reserved pick tasks separate from free stock already planned for putaway', () => {
    const reserved = { ...task('r1', 6), taskType: 'pick' as const, allocationId: 'allocation-1', workOrderId: 'order-1' }
    const stock = record('r1', { availableQuantity: 4, reservedQuantity: 6 })
    expect(freeToMove(stock, [reserved])).toBe(4)
    expect(freeToMove(stock, [reserved, task('r1', 2)])).toBe(2)
  })
  it('takes open tasks off what is available and leaves out held or fully planned records', () => {
    expect(freeToMove(record('r1'), [task('r1', 6), task('r1', 1, 'done'), task('r2', 3)])).toBe(4)
    const records = [record('r1'), record('r2', { inventoryStatus: 'hold' }), record('r3', { availableQuantity: 2 })]
    expect(movableRecords(records, [task('r3', 2)], () => 'BOLT').map((one) => one.inventoryId)).toEqual(['r1'])
  })
})

describe('stagingPlacesFor', () => {
  it('takes the places done picks of the order went to', () => {
    const task = (taskType: 'pick' | 'putaway', status: 'open' | 'done' | 'cancelled', workOrderId: string | null, toLocation: string) =>
      ({ taskType, status, workOrderId, toLocation }) as WarehouseTaskDto
    const tasks = [
      task('pick', 'done', 'wo-1', 'Line-1 '),
      task('pick', 'done', 'wo-1', 'LINE-1'),
      task('pick', 'open', 'wo-1', 'LINE-2'),
      task('putaway', 'done', 'wo-1', 'SHELF'),
      task('pick', 'done', 'wo-2', 'LINE-3'),
    ]
    expect([...stagingPlacesFor(tasks, 'wo-1')]).toEqual(['line-1'])
    expect(stagingPlacesFor(tasks, null).size).toBe(0)
  })
})

describe('partQuantity', () => {
  it('takes more than 0 and at most the task', () => {
    expect(partQuantity({ quantity: 6 }, '2')).toEqual({ quantity: 2, error: null })
    expect(partQuantity({ quantity: 6 }, '6')).toEqual({ quantity: 6, error: null })
    for (const text of ['', ' ', '0', '-1', '7', 'two']) {
      expect(partQuantity({ quantity: 6 }, text)).toEqual({ quantity: null, error: 'Enter a quantity above 0 and at most 6.' })
    }
  })
})

describe('pickableOrders', () => {
  it('offers approved or started orders with a BOM and a quantity', () => {
    const order = (workOrderId: string, patch: Partial<WorkOrderDto>) => ({ workOrderId, workOrderStatus: 'approved', bomId: 'b', targetQuantity: 1, ...patch }) as WorkOrderDto
    expect(pickableOrders([
      order('a', {}), order('b', { workOrderStatus: 'draft' }), order('c', { workOrderStatus: 'in_progress' }), order('d', { bomId: null }),
    ]).map((one) => one.workOrderId)).toEqual(['a', 'c'])
  })
})

describe('payloads', () => {
  it('checks a putaway against the record', () => {
    expect(putawayPayload(EMPTY_PUTAWAY, undefined, 0).error).toBe('Pick a stock record.')
    const form = { ...EMPTY_PUTAWAY, inventoryId: 'r1', quantity: '5', toLocation: 'dock' }
    expect(putawayPayload(form, record('r1'), 4).error).toBe('Only 4 of this record is free to move.')
    expect(putawayPayload({ ...form, quantity: '3' }, record('r1'), 4).error).toBe('The stock is already there.')
    expect(putawayPayload({ ...form, quantity: '3', toLocation: ' SHELF ' }, record('r1'), 4).input)
      .toEqual({ inventoryId: 'r1', quantity: 3, toLocation: 'SHELF', note: null })
  })

  it('builds a pick list from an order or from lines', () => {
    expect(pickPayload(EMPTY_PICK).error).toBe('Say where to pick to.')
    expect(pickPayload({ ...EMPTY_PICK, stagingLocation: 'LINE', workOrderId: 'wo' }).input)
      .toEqual({ stagingLocation: 'LINE', workOrderId: 'wo', note: null })
    expect(pickPayload({ ...EMPTY_PICK, stagingLocation: 'LINE', workOrderId: 'wo', quantity: '-1' }).error)
      .toBe('The quantity to pick for must be above 0.')
    const items = { ...EMPTY_PICK, mode: 'items' as const, stagingLocation: 'LINE' }
    expect(pickPayload(items).error).toBe('Add an item to pick.')
    expect(pickPayload({ ...items, lines: [{ itemId: 'salt', quantity: '' }] }).error).toBe('Every line needs an item and a quantity above 0.')
    expect(pickPayload({ ...items, lines: [{ itemId: 'salt', quantity: '5' }, { itemId: '', quantity: '' }] }).input)
      .toEqual({ stagingLocation: 'LINE', lines: [{ itemId: 'salt', quantity: 5 }], note: null })
  })

  it('names what could not be planned', () => {
    const line = { itemId: 'f', itemCode: 'FLOUR', required: 10, atStaging: 0, alreadyPlanned: 0, plannedNow: 6, shortage: 4 }
    expect(shortageText([line, { ...line, itemCode: 'SALT', shortage: 0 }])).toBe('Short: FLOUR 4')
    expect(shortageText([{ ...line, shortage: 0 }])).toBe('')
  })
})

describe('who does a task', () => {
  it('offers me and the active members, and keeps whoever has it now', () => {
    const members = [
      { userId: 'lee', memberStatus: 'active' }, { userId: 'park', memberStatus: 'removed' }, { userId: 'kim', memberStatus: 'active' },
    ]
    expect(assigneeChoices(members, 'demo-owner', null)).toEqual(['demo-owner', 'kim', 'lee'])
    expect(assigneeChoices(members, 'kim', 'park')).toEqual(['kim', 'lee', 'park'])
    expect(assigneeChoices([], null, null)).toEqual([])
  })

  it('lists the tasks given to me or to no one', () => {
    const mine = { ...task('r1', 1), assignedTo: 'kim' }
    const theirs = { ...task('r2', 2), assignedTo: 'lee' }
    const nobodys = task('r3', 3)
    expect(tasksFor([mine, theirs, nobodys], 'me', 'kim')).toEqual([mine])
    expect(tasksFor([mine, theirs, nobodys], 'nobody', 'kim')).toEqual([nobodys])
    expect(tasksFor([mine, theirs, nobodys], 'anyone', 'kim')).toHaveLength(3)
    expect(tasksFor([mine], 'me', null)).toEqual([])
  })
})

describe('doing a task by scanning', () => {
  it('finds the open tasks of the scanned item, mine first, and checks the place', () => {
    const theirs = { ...task('r1', 1), assignedTo: 'lee' }
    const mine = { ...task('r2', 2), assignedTo: 'kim' }
    const done = task('r3', 3, 'done')
    const otherItem = { ...task('r4', 4), itemId: 'nut' }
    expect(tasksForScan([theirs, mine, done, otherItem], { itemId: 'bolt' }, 'kim')).toEqual([mine, theirs])
    expect(tasksForScan([theirs], null, 'kim')).toEqual([])
    expect(scannedPlaceFits(mine, ' shelf ')).toBe(true)
    expect(scannedPlaceFits(mine, 'DOCK')).toBe(false)
  })
})

describe('scanner view', () => {
  it('lists my open tasks, then the ones given to nobody, and leaves out the rest', () => {
    const free = task('r1', 1)
    const mine = { ...task('r2', 2), assignedTo: 'kim' }
    const theirs = { ...task('r3', 3), assignedTo: 'lee' }
    const doneMine = { ...task('r4', 4, 'done'), assignedTo: 'kim' }
    expect(scannerTasks([free, mine, theirs, doneMine], 'kim')).toEqual([mine, free])
    expect(scannerTasks([free, mine], null)).toEqual([free])
  })
})

describe('selectedScanTask', () => {
  it('uses the latest destination and remaining quantity for the selected task id', () => {
    const old = task('r1', 4)
    const current = { ...old, quantity: 2, toLocation: 'NEW-LINE' }
    expect(selectedScanTask([current], old.taskId)).toBe(current)
    expect(selectedScanTask([current], old.taskId)?.quantity).toBe(2)
    expect(selectedScanTask([current], old.taskId)?.toLocation).toBe('NEW-LINE')
  })

  it.each(['done', 'cancelled'] as const)('does not allow a %s task to stay selected', (status) => {
    const closed = task('r1', 4, status)
    expect(selectedScanTask([closed], closed.taskId)).toBeNull()
  })

  it('does not select missing tasks or a task before its item has been scanned', () => {
    expect(selectedScanTask([], 'missing')).toBeNull()
    expect(selectedScanTask([task('r1', 4)], null)).toBeNull()
  })
})
