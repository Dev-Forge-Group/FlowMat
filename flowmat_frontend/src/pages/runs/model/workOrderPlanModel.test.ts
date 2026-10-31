import { describe, expect, it } from 'vitest'
import type { WorkOrderPlanSuggestionDto } from '../../../entities/production/api/useWorkOrderPlan'
import type { WorkOrderDto } from '../../../shared/types/api'
import {
  batchApproved, batchDrafts, batchPlannable, defaultPlanFrom, localInput, orderSummary, planInput, planSchedule, planStamp, planSummary,
  rescheduleInput,
} from './workOrderPlanModel'

function local(year: number, month: number, day: number, hour: number, minute: number): string {
  return new Date(year, month - 1, day, hour, minute).toISOString()
}

function plan(overrides: Partial<WorkOrderPlanSuggestionDto> = {}): WorkOrderPlanSuggestionDto {
  return {
    workOrderId: 'wo-1', equipmentId: 'eq-1', from: local(2030, 1, 7, 0, 0),
    plannedStartAt: local(2030, 1, 7, 9, 0), plannedEndAt: local(2030, 1, 8, 11, 0),
    remainingQuantity: 100, capacityPerHour: 10, productionHours: 10, changeoverHours: 0, neededHours: 10,
    changeoverFrom: null, movedPast: [], ...overrides,
  }
}

function order(overrides: Partial<WorkOrderDto> = {}): WorkOrderDto {
  return {
    workOrderId: 'wo-1', projectId: 'p-1', workflowId: 'wf-1', workOrderNumber: 'WO-1', workOrderTitle: 'Bake',
    workOrderStatus: 'draft', priority: 'high', targetItemId: 'bread', targetQuantity: 100,
    plannedStartAt: null, plannedEndAt: null, actualStartAt: null, actualEndAt: null, instruction: 'Preheat',
    assignedTo: 'kim', approvedBy: null, approvedAt: null, producedQuantity: 0, runCount: 0, bomId: 'bom-1',
    instructionUrl: 'https://example.com/sop', equipmentId: 'eq-1', ...overrides,
  }
}

describe('crew in a suggestion', () => {
  it('adds only the crew\'s problems to the summary', () => {
    expect(planSummary(plan({ crew: [{ userId: 'kim', calendarSet: true, hours: 10 }] })))
      .toBe('Mon 2030-01-07 09:00 → Tue 2030-01-08 11:00 · needs 10 h')
    expect(planSummary(plan({ crew: [{ userId: 'lee', calendarSet: true, hours: 0 }] })))
      .toBe('Mon 2030-01-07 09:00 → Tue 2030-01-08 11:00 · needs 10 h · lee does not work then')
    expect(planSummary(plan({ waitedForCrew: ['lee'], crewMovedPast: [{ workOrderNumber: 'WO-7', userIds: ['lee'] }] })))
      .toBe('Mon 2030-01-07 09:00 → Tue 2030-01-08 11:00 · needs 10 h · waits for lee to be on shift · after WO-7 (lee)')
  })

  it('says the labor the step plan leaves for the crew', () => {
    // Labor longer than production decides; without equipment it is all there is (LB25).
    expect(planSummary(plan({ laborMinutes: 420, laborHours: 7, neededHours: 10 })))
      .toBe('Mon 2030-01-07 09:00 → Tue 2030-01-08 11:00 · needs 10 h · labor 7 h for 420 worker min')
    expect(planSummary(plan({ equipmentId: null, capacityPerHour: null, productionHours: null, laborMinutes: 280, laborHours: 4.67,
      neededHours: 4.67 }))).toBe('Mon 2030-01-07 09:00 → Tue 2030-01-08 11:00 · needs 4.67 h · labor 4.67 h for 280 worker min')
    expect(planSummary(plan({ laborMinutes: null, laborHours: null }))).toBe('Mon 2030-01-07 09:00 → Tue 2030-01-08 11:00 · needs 10 h')
  })
})

describe('several orders', () => {
  it('offers drafts and approved orders that have equipment', () => {
    const orders = [
      order({ workOrderId: 'd' }), order({ workOrderId: 'a', workOrderStatus: 'approved' }),
      order({ workOrderId: 'run', workOrderStatus: 'in_progress' }), order({ workOrderId: 'loose', equipmentId: null }),
      order({ workOrderId: 'gone', workOrderStatus: 'cancelled' }),
    ]
    expect(batchPlannable(orders).map((one) => one.workOrderId)).toEqual(['d', 'a'])
  })

  it('says what a suggested order saves', () => {
    expect(orderSummary({ changeoverMinutes: 20, givenChangeoverMinutes: 120 }))
      .toBe('Reordered for fewer changeovers: 20 min instead of 120 min.')
    expect(orderSummary({ changeoverMinutes: 0, givenChangeoverMinutes: 0 }))
      .toBe('The ticked order already has the fewest changeovers: 0 min.')
  })

  it('lets only drafts with a suggestion take their dates', () => {
    const orders = [order({ workOrderId: 'd' }), order({ workOrderId: 'a', workOrderStatus: 'approved' }), order({ workOrderId: 'p' })]
    const line = (workOrderId: string, status: string, suggestion: WorkOrderPlanSuggestionDto | null) => ({
      workOrderId, workOrderNumber: workOrderId, workOrderStatus: status, suggestion, problem: suggestion ? null : 'Set a target quantity first.',
    })
    const drafts = batchDrafts([line('a', 'approved', plan()), line('p', 'draft', null), line('d', 'draft', plan()),
      line('missing', 'draft', plan())], orders)
    expect(drafts.map((one) => one.order.workOrderId)).toEqual(['d'])
    expect(drafts[0].plan.plannedStartAt).toBe(local(2030, 1, 7, 9, 0))
    expect(batchApproved([line('a', 'approved', plan()), line('d', 'draft', plan())], orders).map((one) => one.order.workOrderId))
      .toEqual(['a'])
  })

  it('reschedules an approved order to its suggestion against the dates it has now', () => {
    const now = order({ workOrderStatus: 'approved', plannedStartAt: local(2030, 1, 6, 9, 0), plannedEndAt: local(2030, 1, 6, 17, 0) })
    expect(rescheduleInput(now, plan(), 'Planned together')).toEqual({
      expectedPlannedStartAt: local(2030, 1, 6, 9, 0), expectedPlannedEndAt: local(2030, 1, 6, 17, 0),
      plannedStartAt: local(2030, 1, 7, 9, 0), plannedEndAt: local(2030, 1, 8, 11, 0), reason: 'Planned together',
    })
  })
})

describe('dates', () => {
  it('writes local times for the field and the summary', () => {
    expect(localInput(new Date(2030, 0, 7, 9, 5))).toBe('2030-01-07T09:05')
    expect(planStamp(local(2030, 1, 7, 9, 0))).toBe('Mon 2030-01-07 09:00')
    expect(planStamp(local(2030, 1, 13, 23, 30))).toBe('Sun 2030-01-13 23:30')
  })

  it('starts from the planned start while it is ahead, otherwise from the next minute', () => {
    const now = new Date(2026, 9, 3, 6, 59, 30)
    expect(defaultPlanFrom({ plannedStartAt: local(2030, 1, 7, 9, 0) }, now)).toBe('2030-01-07T09:00')
    expect(defaultPlanFrom({ plannedStartAt: local(2026, 10, 1, 9, 0) }, now)).toBe('2026-10-03T07:00')
    expect(defaultPlanFrom({ plannedStartAt: null }, new Date(2026, 9, 3, 23, 59, 0))).toBe('2026-10-04T00:00')
  })
})

describe('planSummary', () => {
  it('names the start, the end and the hours, then any changeover and orders it was moved after', () => {
    expect(planSummary(plan())).toBe('Mon 2030-01-07 09:00 → Tue 2030-01-08 11:00 · needs 10 h')
    expect(planSummary(plan({ changeoverHours: 1.5, neededHours: 11.5, changeoverFrom: 'WO-7', movedPast: ['WO-7', 'WO-9'] })))
      .toBe('Mon 2030-01-07 09:00 → Tue 2030-01-08 11:00 · needs 11.5 h (with 1.5 h changeover after WO-7) · after WO-7, WO-9')
  })
})

describe('planInput', () => {
  it('sends every editable field as it is with the suggested dates', () => {
    const suggestion = plan()
    expect(planInput(order(), suggestion)).toEqual({
      workOrderTitle: 'Bake', workflowId: 'wf-1', targetItemId: 'bread', bomId: 'bom-1', targetQuantity: 100,
      priority: 'high', plannedStartAt: suggestion.plannedStartAt, plannedEndAt: suggestion.plannedEndAt,
      instruction: 'Preheat', assignedTo: 'kim', instructionUrl: 'https://example.com/sop',
    })
    const bare = planInput(order({ workflowId: null, targetItemId: null, bomId: null, targetQuantity: null, instruction: null,
      assignedTo: null, instructionUrl: null }), suggestion)
    expect(bare.workflowId).toBeUndefined()
    expect(bare.targetQuantity).toBeUndefined()
    expect(bare.instructionUrl).toBeUndefined()
  })
})

describe('who works when', () => {
  it('puts each process with its people and their times', () => {
    const at = (hour: number) => new Date(2041, 4, 6, hour).toISOString()
    expect(planSchedule({ schedule: [
      { nodeId: 'p1', nodeName: 'Mix', userId: 'kim', from: at(9), to: at(14) },
      { nodeId: 'p1', nodeName: 'Mix', userId: 'lee', from: at(9), to: at(10) },
      { nodeId: 'p2', nodeName: 'Bake', userId: 'lee', from: at(14), to: at(15) }] }))
      .toEqual(['Mix: kim Mon 2041-05-06 09:00–14:00, lee Mon 2041-05-06 09:00–10:00', 'Bake: lee Mon 2041-05-06 14:00–15:00'])
    expect(planSchedule({ schedule: [{ nodeId: 'p1', nodeName: null, userId: 'kim', from: at(22), to: new Date(2041, 4, 7, 2).toISOString() }] }))
      .toEqual(['p1: kim Mon 2041-05-06 22:00–Tue 2041-05-07 02:00'])
    expect(planSchedule({ schedule: [] })).toEqual([])
    expect(planSchedule({})).toEqual([])
  })
})
