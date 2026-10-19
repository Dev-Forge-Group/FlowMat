import type {
  InventoryDto,
  LotDto,
  ProductionRunItemDto,
  RunCorrectionLineDto,
  RunCorrectionLineRequest,
  RunCorrectionStatus,
} from '../../../shared/types/api'

/** A recording to add, as typed in the form (quantities stay strings until the request is built). */
export interface CorrectionAddDraft {
  direction: 'input' | 'output'
  itemId: string
  inventoryId: string
  qty: string
  unit: string
}

export interface CorrectionDraft {
  reason: string
  voidRunItemIds: string[]
  adds: CorrectionAddDraft[]
  /** Empty when the output quantity stays as it is. */
  outputQty: string
}

export type CorrectionRequestResult =
  | { ok: true; body: { reason: string; lines: RunCorrectionLineRequest[] } }
  | { ok: false; error: string }

/** Recordings a correction may void: they stand (not cancelled) and are not BOM plan lines. */
export function voidableRecordings(items: ProductionRunItemDto[]): ProductionRunItemDto[] {
  return items.filter((item) => item.quantitySource !== 'bom' && !item.cancelled)
}

/**
 * Turns the form into a correction request, or says what is missing. The server checks everything again; this only
 * catches what the form can know.
 */
export function buildCorrectionRequest(draft: CorrectionDraft, currentOutputQty: number | null): CorrectionRequestResult {
  const reason = draft.reason.trim()
  if (!reason) return { ok: false, error: 'Give a reason for the correction.' }

  const lines: RunCorrectionLineRequest[] = draft.voidRunItemIds.map((targetRunItemId) => ({
    kind: 'void_item',
    targetRunItemId,
  }))
  for (const [index, add] of draft.adds.entries()) {
    const qty = Number(add.qty)
    if (!add.itemId) return { ok: false, error: `Added recording ${index + 1}: choose the item.` }
    if (add.qty.trim() === '' || !Number.isFinite(qty) || qty <= 0) {
      return { ok: false, error: `Added recording ${index + 1}: the quantity must be greater than 0.` }
    }
    if (!add.unit.trim()) return { ok: false, error: `Added recording ${index + 1}: give the unit.` }
    lines.push({
      kind: 'add_item',
      direction: add.direction,
      itemId: add.itemId,
      inventoryId: add.inventoryId || null,
      qty,
      unit: add.unit.trim(),
    })
  }
  if (draft.outputQty.trim() !== '') {
    const afterQty = Number(draft.outputQty)
    if (!Number.isFinite(afterQty) || afterQty < 0) return { ok: false, error: 'The output quantity must be 0 or more.' }
    if (afterQty === Number(currentOutputQty ?? 0)) {
      return { ok: false, error: `The run's output is already ${afterQty}.` }
    }
    lines.push({ kind: 'set_output_qty', afterQty })
  }
  if (lines.length === 0) return { ok: false, error: 'Choose at least one change.' }
  return { ok: true, body: { reason, lines } }
}

/** One line of a correction in words, e.g. "Void input FLOUR · Flour 5 kg". */
export function describeCorrectionLine(
  line: RunCorrectionLineDto,
  runItems: ProductionRunItemDto[],
  itemLabel: (itemId: string) => string,
  formatQty: (value: number | null | undefined) => string,
  equipmentLabel?: (equipmentId: string) => string,
): string {
  if (line.kind === 'set_output_qty') {
    return `Output ${formatQty(line.beforeQty)} → ${formatQty(line.afterQty)}`
  }
  if (line.kind === 'cancel_setup') return 'Cancel a setup'
  if (line.kind === 'add_setup') {
    const on = line.equipmentId ? ` on ${equipmentLabel ? equipmentLabel(line.equipmentId) : line.equipmentId}` : ''
    return `Add a setup of ${line.setupMinutes ?? '?'} min${on}`
  }
  if (line.kind === 'cancel_running_time') return 'Cancel running time'
  if (line.kind === 'add_running_time') {
    const on = line.equipmentId ? ` on ${equipmentLabel ? equipmentLabel(line.equipmentId) : line.equipmentId}` : ''
    return `Add ${line.runningMinutes ?? '?'} min of running time${on}`
  }
  if (line.kind === 'cancel_labor') return 'Cancel worked time'
  if (line.kind === 'add_labor') {
    return `Add ${line.workedMinutes ?? '?'} min worked by ${line.userId ?? '?'}${line.stepId ? ' on a step' : ''}`
  }
  if (line.kind === 'void_item') {
    const target = runItems.find((item) => item.productionRunItemId === line.targetRunItemId)
    if (!target) return 'Void a recording'
    return `Void ${target.direction} ${itemLabel(target.itemId)} ${formatQty(target.actualQty ?? target.plannedQty)} ${target.unit}`
  }
  return `Add ${line.direction ?? ''} ${line.itemId ? itemLabel(line.itemId) : ''} ${formatQty(line.qty)} ${line.unit ?? ''}`
    .replace(/\s+/g, ' ')
    .trim()
}

export function correctionStatusLabel(status: RunCorrectionStatus): string {
  switch (status) {
    case 'pending_approval':
      return 'waiting for approval'
    case 'applied':
      return 'applied'
    case 'rejected':
      return 'rejected'
  }
}

/** A stock record offered for an added recording, with what its LOT's expiry means today. */
export interface StockPick {
  inventory: InventoryDto
  expiryDate: string | null
  expired: boolean
}

/**
 * Stock records to offer for an added recording, first-expiring LOT first (FEFO): records whose LOT has no expiry date
 * come after, and expired LOTs last, since the server refuses them as inputs. Otherwise the given order is kept.
 */
export function orderStockForPick(stock: InventoryDto[], lots: LotDto[]): StockPick[] {
  const lotById = new Map(lots.map((lot) => [lot.lotId, lot]))
  const picks = stock.map((inventory, index) => {
    const lot = inventory.lotId ? lotById.get(inventory.lotId) : undefined
    return { inventory, expiryDate: lot?.expiryDate ?? null, expired: Boolean(lot?.expired), index }
  })
  const rank = (pick: (typeof picks)[number]) => (pick.expired ? 2 : pick.expiryDate ? 0 : 1)
  return picks
    .sort(
      (a, b) =>
        rank(a) - rank(b) || (a.expiryDate && b.expiryDate ? a.expiryDate.localeCompare(b.expiryDate) : 0) || a.index - b.index,
    )
    .map(({ inventory, expiryDate, expired }) => ({ inventory, expiryDate, expired }))
}

/** A stock record offered in a run's record form, with whether it can be picked and whether to use it first. */
export interface LotOption extends StockPick {
  disabled: boolean
  useFirst: boolean
  /** At a place the run's work order was picked to (docs/domain/warehouse-task.md); offered first. */
  staged: boolean
}

/**
 * The item's stock records for a run's record form (docs/domain/lot-expiry.md "먼저 만료되는 LOT 먼저"). For an input
 * they are in FEFO order: expired or quarantined records cannot be picked (the server refuses them), and the first one
 * that can, if it has an expiry date and there is a choice, is marked to use first. Records at a place in
 * {@code stagedAt} (lower-case codes the order was picked to) come first, FEFO within each group, and among records
 * expiring first a staged one is the one to use first. For an output the order is kept.
 */
export function inputLotOptions(
  stock: InventoryDto[],
  lots: LotDto[],
  direction: 'input' | 'output',
  stagedAt: ReadonlySet<string> = new Set(),
): LotOption[] {
  if (direction === 'output') {
    return orderStockForPick(stock, []).map((pick) => ({ ...pick, disabled: false, useFirst: false, staged: false }))
  }
  const options = orderStockForPick(stock, lots).map((pick) => ({
    ...pick,
    disabled: pick.expired || pick.inventory.inventoryStatus === 'quarantined',
    useFirst: false,
    staged: stagedAt.has(pick.inventory.location?.trim().toLowerCase() ?? ''),
  }))
  const usable = options.filter((option) => !option.disabled)
  const earliest = usable[0]?.expiryDate
  if (earliest && options.length > 1) {
    const first = usable.find((option) => option.expiryDate === earliest && option.staged) ?? usable[0]
    first.useFirst = true
  }
  return [...options.filter((option) => option.staged), ...options.filter((option) => !option.staged)]
}
