import type {
  WorkOrderBatchPlanDto, WorkOrderPlanOrderDto, WorkOrderPlanSuggestionDto,
} from '../../../entities/production/api/useWorkOrderPlan'
import type { WorkOrderInput } from '../../../entities/production/api/useWorkOrders'
import type { WorkOrderRescheduleInput } from '../../../entities/production/api/useWorkOrderReschedule'
import type { WorkOrderDto } from '../../../shared/types/api'
import { crewDelayNote, crewNote } from './crewModel'

const DAY_NAMES = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

/** "2030-01-07T09:00" for a datetime-local field, in local time. */
export function localInput(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** "Mon 2030-01-07 09:00", in local time. */
export function planStamp(iso: string): string {
  const date = new Date(iso)
  return `${DAY_NAMES[date.getDay()]} ${localInput(date).replace('T', ' ')}`
}

/** Where a suggestion starts unless the user picks a time: the planned start if still ahead, else the next minute. */
export function defaultPlanFrom(order: Pick<WorkOrderDto, 'plannedStartAt'>, now: Date): string {
  const planned = order.plannedStartAt ? new Date(order.plannedStartAt) : null
  if (planned && planned > now) return localInput(planned)
  return localInput(new Date(now.getFullYear(), now.getMonth(), now.getDate(), now.getHours(), now.getMinutes() + 1))
}

function hours(value: number): string {
  return `${Number(value.toFixed(2))} h`
}

/**
 * One line, e.g. "Mon 2030-01-07 09:00 → Tue 2030-01-08 11:00 · needs 10 h", with the changeover, the labor the step plan
 * leaves for the crew (docs/domain/worker-calendar.md LB25) and the orders the suggestion was moved after when there are any.
 */
export function planSummary(plan: WorkOrderPlanSuggestionDto): string {
  const changeover = plan.changeoverHours > 0
    ? ` (with ${hours(plan.changeoverHours)} changeover${plan.changeoverFrom ? ` after ${plan.changeoverFrom}` : ''})`
    : ''
  const labor = plan.laborHours != null ? ` · labor ${hours(plan.laborHours)} for ${plan.laborMinutes ?? 0} worker min` : ''
  const after = plan.movedPast.length > 0 ? ` · after ${plan.movedPast.join(', ')}` : ''
  return `${planStamp(plan.plannedStartAt)} → ${planStamp(plan.plannedEndAt)} · needs ${hours(plan.neededHours)}${changeover}${labor}${after}`
    + crewDelayNote(plan.waitedForCrew, plan.crewMovedPast) + crewNote(plan.crew)
}

/** Orders a batch can plan: drafts and approved orders with equipment (docs/domain/equipment-schedule.md P8, P12). */
export function batchPlannable(orders: WorkOrderDto[]): WorkOrderDto[] {
  return orders.filter((order) => Boolean(order.equipmentId)
    && (order.workOrderStatus === 'draft' || order.workOrderStatus === 'approved'))
}

function batchOfStatus(
  lines: WorkOrderBatchPlanDto['lines'],
  orders: WorkOrderDto[],
  status: WorkOrderDto['workOrderStatus'],
): { order: WorkOrderDto; plan: WorkOrderPlanSuggestionDto }[] {
  return lines.flatMap((line) => {
    const order = orders.find((one) => one.workOrderId === line.workOrderId)
    return order && order.workOrderStatus === status && line.suggestion ? [{ order, plan: line.suggestion }] : []
  })
}

/** The drafts of a batch answer that take their dates now; an approved order waits for the owner's reschedule (P12). */
export function batchDrafts(lines: WorkOrderBatchPlanDto['lines'], orders: WorkOrderDto[]) {
  return batchOfStatus(lines, orders, 'draft')
}

/** The approved orders of a batch answer that the project owner can reschedule to their suggestion (P17). */
export function batchApproved(lines: WorkOrderBatchPlanDto['lines'], orders: WorkOrderDto[]) {
  return batchOfStatus(lines, orders, 'approved')
}

/** A reschedule of an approved order to its suggestion, checked against the dates it has now (P17, policy C). */
export function rescheduleInput(
  order: Pick<WorkOrderDto, 'plannedStartAt' | 'plannedEndAt'>,
  plan: Pick<WorkOrderPlanSuggestionDto, 'plannedStartAt' | 'plannedEndAt'>,
  reason: string,
): WorkOrderRescheduleInput {
  return {
    expectedPlannedStartAt: order.plannedStartAt,
    expectedPlannedEndAt: order.plannedEndAt,
    plannedStartAt: plan.plannedStartAt,
    plannedEndAt: plan.plannedEndAt,
    reason,
  }
}

/** What a suggested order saves, e.g. "Reordered for fewer changeovers: 20 min instead of 120 min." (P16). */
export function orderSummary(order: Pick<WorkOrderPlanOrderDto, 'changeoverMinutes' | 'givenChangeoverMinutes'>): string {
  return order.changeoverMinutes < order.givenChangeoverMinutes
    ? `Reordered for fewer changeovers: ${order.changeoverMinutes} min instead of ${order.givenChangeoverMinutes} min.`
    : `The ticked order already has the fewest changeovers: ${order.changeoverMinutes} min.`
}

/** The draft as it is with the suggested dates; the update replaces every editable field, so all of them are sent. */
export function planInput(
  order: WorkOrderDto,
  plan: Pick<WorkOrderPlanSuggestionDto, 'plannedStartAt' | 'plannedEndAt'>,
): WorkOrderInput {
  return {
    workOrderTitle: order.workOrderTitle,
    workflowId: order.workflowId ?? undefined,
    targetItemId: order.targetItemId ?? undefined,
    bomId: order.bomId ?? undefined,
    targetQuantity: order.targetQuantity ?? undefined,
    priority: order.priority,
    plannedStartAt: plan.plannedStartAt,
    plannedEndAt: plan.plannedEndAt,
    instruction: order.instruction ?? undefined,
    assignedTo: order.assignedTo ?? undefined,
    instructionUrl: order.instructionUrl ?? undefined,
  }
}
