import type { PickListInput, PickListLine, PutawayInput, WarehouseTaskCompletionInput, WarehouseTaskDto } from '../../../entities/inventory/api/useWarehouseTasks'
import type { InventoryDto, WorkOrderDto } from '../../../shared/types/api'

/** What of a record is not already planned in open tasks; the server checks the same. */
/** The places a work order's stock was picked to: done pick tasks of the order, lower-case codes. */
export function stagingPlacesFor(tasks: WarehouseTaskDto[], workOrderId: string | null): Set<string> {
  if (!workOrderId) return new Set()
  return new Set(tasks
    .filter((task) => task.taskType === 'pick' && task.status === 'done' && task.workOrderId === workOrderId)
    .map((task) => task.toLocation.trim().toLowerCase()))
}

/** How much of an open task to move now: above 0 and at most its quantity (docs/domain/warehouse-task.md W6). */
export function partQuantity(
  task: Pick<WarehouseTaskDto, 'quantity'>,
  text: string,
): { quantity: number; error: null } | { quantity: null; error: string } {
  const quantity = Number(text)
  if (!text.trim() || !Number.isFinite(quantity) || quantity <= 0 || quantity > task.quantity) {
    return { quantity: null, error: `Enter a quantity above 0 and at most ${task.quantity}.` }
  }
  return { quantity, error: null }
}

/** Keep the observed remaining quantity with this intention, including retries after an uncertain partial result. */
export function taskCompletionInput(
  task: Pick<WarehouseTaskDto, 'taskId' | 'quantity'>,
  options: Pick<WarehouseTaskCompletionInput, 'quantity' | 'expectedToLocation'> = {},
): WarehouseTaskCompletionInput {
  return { taskId: task.taskId, expectedQuantity: task.quantity, ...options }
}

export function freeToMove(record: InventoryDto, openTasks: WarehouseTaskDto[]): number {
  const planned = openTasks
    .filter((task) => task.status === 'open' && task.inventoryId === record.inventoryId && !task.allocationId)
    .reduce((sum, task) => sum + task.quantity, 0)
  return Math.max(0, Number(record.availableQuantity) - planned)
}

/** Records a putaway can take from: available status with something free, grouped by item then place. */
export function movableRecords(
  records: InventoryDto[],
  openTasks: WarehouseTaskDto[],
  itemLabel: (itemId: string) => string,
): InventoryDto[] {
  return records
    .filter((record) => record.inventoryStatus === 'available' && freeToMove(record, openTasks) > 0)
    .sort((left, right) => itemLabel(left.itemId).localeCompare(itemLabel(right.itemId))
      || (left.location ?? '').localeCompare(right.location ?? ''))
}

/** Orders a pick list can be made for: approved or started, with a BOM and a target quantity. */
export function pickableOrders(orders: WorkOrderDto[]): WorkOrderDto[] {
  return orders.filter((order) => (order.workOrderStatus === 'approved' || order.workOrderStatus === 'in_progress')
    && order.bomId !== null && order.targetQuantity !== null)
}

export interface PutawayForm {
  inventoryId: string
  quantity: string
  toLocation: string
  note: string
}

export const EMPTY_PUTAWAY: PutawayForm = { inventoryId: '', quantity: '', toLocation: '', note: '' }

export function putawayPayload(
  form: PutawayForm,
  record: InventoryDto | undefined,
  free: number,
): { input: PutawayInput; error: null } | { input: null; error: string } {
  if (!record) return { input: null, error: 'Pick a stock record.' }
  const quantity = Number(form.quantity)
  if (!form.quantity.trim() || !Number.isFinite(quantity) || quantity <= 0) return { input: null, error: 'Enter a quantity above 0.' }
  if (quantity > free) return { input: null, error: `Only ${free} of this record is free to move.` }
  const to = form.toLocation.trim()
  if (!to) return { input: null, error: 'Say where the stock goes.' }
  if (to.toLowerCase() === (record.location ?? '').trim().toLowerCase()) return { input: null, error: 'The stock is already there.' }
  return { input: { inventoryId: record.inventoryId, quantity, toLocation: to, note: form.note.trim() || null }, error: null }
}

export interface PickForm {
  mode: 'order' | 'items'
  stagingLocation: string
  workOrderId: string
  quantity: string
  lines: { itemId: string; quantity: string }[]
  note: string
}

export const EMPTY_PICK: PickForm = {
  mode: 'order', stagingLocation: '', workOrderId: '', quantity: '', lines: [{ itemId: '', quantity: '' }], note: '',
}

export function pickPayload(form: PickForm): { input: PickListInput; error: null } | { input: null; error: string } {
  const staging = form.stagingLocation.trim()
  if (!staging) return { input: null, error: 'Say where to pick to.' }
  const note = form.note.trim() || null
  if (form.mode === 'order') {
    if (!form.workOrderId) return { input: null, error: 'Pick a work order.' }
    if (!form.quantity.trim()) return { input: { stagingLocation: staging, workOrderId: form.workOrderId, note }, error: null }
    const quantity = Number(form.quantity)
    if (!Number.isFinite(quantity) || quantity <= 0) return { input: null, error: 'The quantity to pick for must be above 0.' }
    return { input: { stagingLocation: staging, workOrderId: form.workOrderId, quantity, note }, error: null }
  }
  const lines = form.lines.filter((line) => line.itemId || line.quantity.trim())
  if (lines.length === 0) return { input: null, error: 'Add an item to pick.' }
  const parsed: { itemId: string; quantity: number }[] = []
  for (const line of lines) {
    const quantity = Number(line.quantity)
    if (!line.itemId || !line.quantity.trim() || !Number.isFinite(quantity) || quantity <= 0) {
      return { input: null, error: 'Every line needs an item and a quantity above 0.' }
    }
    parsed.push({ itemId: line.itemId, quantity })
  }
  return { input: { stagingLocation: staging, lines: parsed, note }, error: null }
}

/** e.g. "Short: FLOUR 4, SALT 0.2"; empty when everything could be planned. */
export function shortageText(lines: PickListLine[]): string {
  const short = lines.filter((line) => line.shortage > 0)
  return short.length === 0 ? '' : `Short: ${short.map((line) => `${line.itemCode} ${line.shortage}`).join(', ')}`
}

/** Who an open task can be given to (W7): the signed-in user and the project's active members, and whoever has it now. */
export function assigneeChoices(
  members: { userId: string; memberStatus: string }[],
  me: string | null,
  current: string | null,
): string[] {
  const ids = new Set(members.filter((member) => member.memberStatus === 'active').map((member) => member.userId))
  if (me) ids.add(me)
  if (current) ids.add(current)
  return [...ids].sort((left, right) => left.localeCompare(right))
}

export type AssigneeFilter = 'anyone' | 'me' | 'nobody'

/** The tasks given to me, or to no one yet; all of them for anyone. */
export function tasksFor(tasks: WarehouseTaskDto[], filter: AssigneeFilter, me: string | null): WarehouseTaskDto[] {
  if (filter === 'me') return tasks.filter((task) => me !== null && task.assignedTo === me)
  if (filter === 'nobody') return tasks.filter((task) => !task.assignedTo)
  return tasks
}

/** Resolve a scanner selection against current open tasks, so renamed places and partial moves stay current. */
export function selectedScanTask(tasks: WarehouseTaskDto[], taskId: string | null): WarehouseTaskDto | null {
  if (!taskId) return null
  return tasks.find((task) => task.taskId === taskId && task.status === 'open') ?? null
}

/** The open tasks of the item a scan named, the ones given to me first (W8). */
export function tasksForScan(tasks: WarehouseTaskDto[], item: { itemId: string } | null, me: string | null): WarehouseTaskDto[] {
  if (!item) return []
  return tasks
    .filter((task) => task.status === 'open' && task.itemId === item.itemId)
    .sort((left, right) => Number(me !== null && right.assignedTo === me) - Number(me !== null && left.assignedTo === me))
}

/** Whether a scanned place is where the task goes, ignoring case and spaces around it (W8). */
export function scannedPlaceFits(task: Pick<WarehouseTaskDto, 'toLocation'>, scanned: string): boolean {
  return scanned.trim().toLowerCase() === task.toLocation.trim().toLowerCase()
}

/** A handheld's list (W9): the open tasks given to me, then the ones given to nobody; others' tasks are left out. */
export function scannerTasks(tasks: WarehouseTaskDto[], me: string | null): WarehouseTaskDto[] {
  const open = tasks.filter((task) => task.status === 'open')
  return [...open.filter((task) => me !== null && task.assignedTo === me), ...open.filter((task) => !task.assignedTo)]
}
