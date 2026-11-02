import type { BomDto, BomLineDto, BomLineImportRowDto, BomStatus } from '../../../shared/types/api'
import type { BomAction } from '../../../entities/bom/api/useBoms'
import { CsvParseError, parseCsv } from './itemCsvModel'

/**
 * Buttons each BOM status offers (docs/domain/inventory-bom-lot-contract.md §5). The server enforces the same rules and
 * the owner-only steps (approve / reject / retire); this only avoids offering moves that cannot succeed.
 */
export function bomActions(status: BomStatus): BomAction[] {
  switch (status) {
    case 'draft':
      return ['submit']
    case 'pending_approval':
      return ['approve', 'reject']
    case 'approved':
      return ['revisions', 'retire']
    case 'retired':
      return ['revisions']
  }
}

export const BOM_ACTION_LABELS: Record<BomAction, string> = {
  submit: 'Submit for approval',
  approve: 'Approve',
  reject: 'Reject',
  retire: 'Retire',
  revisions: 'New revision',
}

/** Only drafts accept line changes. */
export function isEditable(bom: BomDto): boolean {
  return bom.bomStatus === 'draft'
}

/** Revisions grouped by the item they produce, newest revision first. */
export function groupByTarget(boms: BomDto[]): Map<string, BomDto[]> {
  const groups = new Map<string, BomDto[]>()
  for (const bom of boms) {
    const list = groups.get(bom.targetItemId) ?? []
    list.push(bom)
    groups.set(bom.targetItemId, list)
  }
  for (const list of groups.values()) list.sort((a, b) => b.bomVersion - a.bomVersion)
  return groups
}

/** This browser's calendar day, YYYY-MM-DD. */
export function localDay(now: Date = new Date()): string {
  return new Date(now.getTime() - now.getTimezoneOffset() * 60_000).toISOString().slice(0, 10)
}

/**
 * Today in the project's business time zone, YYYY-MM-DD, as the server picks revisions (docs/domain/project-time-zone.md,
 * multi-level-bom.md M3); this browser's day when the zone is not known yet or not valid here.
 */
export function zoneDay(timeZone: string | null | undefined, now: Date = new Date()): string {
  if (timeZone) {
    try {
      return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(now)
    } catch {
      // An unknown zone falls back to this browser's day.
    }
  }
  return localDay(now)
}

/** Whether a revision's effective period includes the day (YYYY-MM-DD); both ends count and a missing end is open. */
export function coversDay(bom: Pick<BomDto, 'effectiveFrom' | 'effectiveTo'>, day: string): boolean {
  return (!bom.effectiveFrom || bom.effectiveFrom <= day) && (!bom.effectiveTo || day <= bom.effectiveTo)
}

/**
 * The approved revision production would use for an item on the day (default: today here). Approved revisions of one
 * item have separate periods (docs/domain/multi-level-bom.md M1); the highest one if legacy periods overlap.
 */
export function approvedRevision(boms: BomDto[], targetItemId: string, day: string = localDay()): BomDto | undefined {
  return boms
    .filter((bom) => bom.targetItemId === targetItemId && bom.bomStatus === 'approved' && coversDay(bom, day))
    .sort((a, b) => b.bomVersion - a.bomVersion)[0]
}

/** Approved revisions of the same product sharing a day with this one's period; approval refuses while there are any. */
export function overlappingApproved(bom: BomDto, revisions: BomDto[]): BomDto[] {
  return revisions.filter((other) => other.bomId !== bom.bomId && other.targetItemId === bom.targetItemId
    && other.bomStatus === 'approved'
    && (!bom.effectiveTo || !other.effectiveFrom || other.effectiveFrom <= bom.effectiveTo)
    && (!other.effectiveTo || !bom.effectiveFrom || bom.effectiveFrom <= other.effectiveTo))
}

/**
 * The day the replacement helper would end the overlapping approved revisions on, or null when it cannot: this revision
 * needs a start day, and each overlap must start before it and not run past this revision's end
 * (docs/domain/multi-level-bom.md M4-M6).
 */
export function replacementEndDay(bom: Pick<BomDto, 'effectiveFrom' | 'effectiveTo'>, overlaps: BomDto[]): string | null {
  const from = bom.effectiveFrom
  if (!from || overlaps.length === 0) return null
  const fits = overlaps.every((other) => (!other.effectiveFrom || other.effectiveFrom < from)
    && (!bom.effectiveTo || (other.effectiveTo != null && other.effectiveTo <= bom.effectiveTo)))
  if (!fits) return null
  const day = new Date(`${from}T00:00:00Z`)
  day.setUTCDate(day.getUTCDate() - 1)
  return day.toISOString().slice(0, 10)
}

/** A revision's effective period for display. */
export function periodLabel(bom: Pick<BomDto, 'effectiveFrom' | 'effectiveTo'>): string {
  return `${bom.effectiveFrom ?? 'open'} → ${bom.effectiveTo ?? 'open'}`
}

export type BomLineChange = 'added' | 'removed' | 'changed' | 'same'

/** A side of a comparison; scrapRate (0 to 1) is there only when the material plans scrap (ADR-031). */
export interface BomLineSide {
  quantity: number
  unit: string
  scrapRate?: number
  /** The item of the main material this one stands in for (ADR-032). */
  substituteFor?: string
  /** Planned only for a work order that picks it (ADR-033). */
  optional?: boolean
}

export interface BomLineComparison {
  childItemId: string
  before: BomLineSide | null
  after: BomLineSide | null
  change: BomLineChange
}

interface MaterialTotal {
  childItemId: string
  quantity: number
  unit: string
  /** The quantity with each line's scrap on top. */
  planned: number
  /** The item of the main line this material stands in for (ADR-032). */
  substituteFor?: string
  /** Planned only for a work order that picks it (ADR-033). */
  optional?: boolean
}

/** Materials keyed by item; lines of one item in one unit are added up, another unit gets its own entry. */
function materials(bom: BomDto): Map<string, MaterialTotal> {
  const byKey = new Map<string, MaterialTotal>()
  const itemOfLine = new Map(bom.lines.map((line) => [line.bomLineId, line.childItemId]))
  for (const line of bom.lines) {
    const planned = line.quantity * (1 + (line.scrapRate ?? 0))
    const substituteFor = line.substituteFor ? itemOfLine.get(line.substituteFor) : undefined
    const existing = byKey.get(line.childItemId)
    if (existing && existing.unit === line.unit) {
      existing.quantity += line.quantity
      existing.planned += planned
    } else {
      const total: MaterialTotal = { childItemId: line.childItemId, quantity: line.quantity, unit: line.unit, planned }
      if (substituteFor) total.substituteFor = substituteFor
      if (line.optionalYn === 'Y') total.optional = true
      byKey.set(existing ? `${line.childItemId}|${line.unit}` : line.childItemId, total)
    }
  }
  return byKey
}

/** The scrap of added-up lines as one rate, 4 decimals like the server keeps it. */
function totalScrapRate(total: MaterialTotal): number {
  return total.quantity === 0 ? 0 : Math.round((total.planned / total.quantity - 1) * 10000) / 10000
}

function side(total: MaterialTotal): BomLineSide {
  const scrapRate = totalScrapRate(total)
  const result: BomLineSide = scrapRate === 0 ? { quantity: total.quantity, unit: total.unit }
    : { quantity: total.quantity, unit: total.unit, scrapRate }
  if (total.substituteFor) result.substituteFor = total.substituteFor
  if (total.optional) result.optional = true
  return result
}

/**
 * A scrap percent typed on a BOM line as the 0-1 rate the server keeps (ADR-031): blank or 0 is none, otherwise 0 to 100
 * with at most 2 decimals.
 */
export function scrapRateFromPercent(text: string): { ok: true; rate: number | undefined } | { ok: false; error: string } {
  const trimmed = text.trim()
  if (!trimmed) return { ok: true, rate: undefined }
  if (!/^\d+(\.\d+)?$/.test(trimmed)) return { ok: false, error: 'Give scrap as a percent from 0 to 100.' }
  const percent = Number(trimmed)
  if (percent > 100) return { ok: false, error: 'Scrap must be from 0% to 100%.' }
  if (!/^\d+(\.\d{1,2})?$/.test(trimmed)) return { ok: false, error: 'Scrap takes at most 2 decimals (0.01%).' }
  return { ok: true, rate: percent === 0 ? undefined : Math.round(percent * 100) / 10000 }
}

/** "5% scrap" for a line that plans scrap; empty when it plans none. */
export function scrapLabel(rate: number | null | undefined): string {
  return rate ? `${Math.round(rate * 10000) / 100}% scrap` : ''
}

/**
 * How a material's actual loss compares with its planned scrap, in percentage points (ADR-034): "over" when it lost more
 * than half a point beyond the plan, "under" when half a point less, otherwise "even". No rate, no comparison.
 */
export function lossGap(planned: number, actual: number | null): { points: number; tone: 'over' | 'under' | 'even' } | null {
  if (actual == null) return null
  const points = Math.round((actual - planned) * 10000) / 100
  return { points, tone: points > 0.5 ? 'over' : points < -0.5 ? 'under' : 'even' }
}

/** "5%" for a 0–1 rate, two decimals at most. */
export function percentLabel(rate: number): string {
  return `${Math.round(rate * 10000) / 100}%`
}

/** Lines a new material can stand in for (ADR-032 decision 3): main materials that are neither phantoms nor optional. */
export function substituteTargets(lines: BomLineDto[]): BomLineDto[] {
  return lines.filter((line) => (line.lineType ?? 'material') === 'material' && !line.phantom && !line.substituteFor
    && line.optionalYn !== 'Y')
}

/** The optional materials of a revision that a work order may pick (ADR-033 decision 9); none without a revision. */
export function optionalMaterials(bom: Pick<BomDto, 'lines'> | undefined): BomLineDto[] {
  return (bom?.lines ?? []).filter((line) => (line.lineType ?? 'material') === 'material' && line.optionalYn === 'Y')
}

/** "substitute for FLOUR", or "substitute 2 for FLOUR" when the main material has several; empty for any other line. */
export function substituteLabel(line: BomLineDto, lines: BomLineDto[], label: (itemId: string) => string): string {
  if (!line.substituteFor) return ''
  const main = lines.find((other) => other.bomLineId === line.substituteFor)
  const group = lines.filter((other) => other.substituteFor === line.substituteFor)
    .sort((a, b) => (a.priority ?? 0) - (b.priority ?? 0))
  const rank = group.length > 1 ? ` ${group.indexOf(line) + 1}` : ''
  return `substitute${rank} for ${main ? label(main.childItemId) : 'another material'}`
}

/**
 * What changed from one revision to another, material by material, in the newer revision's order and then what was
 * dropped. Quantities are compared per unit of product, so halving the base and every quantity is no change; when the
 * base unit itself changed they are compared as written. Another scrap rate is a change too (ADR-031).
 */
export function compareBoms(from: BomDto, to: BomDto): { baseChanged: boolean; lines: BomLineComparison[] } {
  const sameBaseUnit = from.baseUnit === to.baseUnit
  const perBase = (quantity: number, bom: BomDto) => (sameBaseUnit ? quantity / bom.baseQuantity : quantity)
  const before = materials(from)
  const after = materials(to)
  const lines: BomLineComparison[] = []
  for (const [key, now] of after) {
    const was = before.get(key)
    const change: BomLineChange = !was
      ? 'added'
      : was.unit !== now.unit || Math.abs(perBase(was.quantity, from) - perBase(now.quantity, to)) > 1e-9
          || totalScrapRate(was) !== totalScrapRate(now) || was.substituteFor !== now.substituteFor
          || Boolean(was.optional) !== Boolean(now.optional)
        ? 'changed'
        : 'same'
    lines.push({ childItemId: now.childItemId, before: was ? side(was) : null, after: side(now), change })
  }
  for (const [key, was] of before) {
    if (!after.has(key)) {
      lines.push({ childItemId: was.childItemId, before: side(was), after: null, change: 'removed' })
    }
  }
  return { baseChanged: from.baseQuantity !== to.baseQuantity || !sameBaseUnit, lines }
}

const BOM_LINE_COLUMNS: Record<string, keyof BomLineImportRowDto> = {
  item_code: 'itemCode',
  code: 'itemCode',
  material: 'itemCode',
  quantity: 'quantity',
  qty: 'quantity',
  unit: 'unit',
  unit_code: 'unit',
  note: 'note',
  type: 'lineType',
  line_type: 'lineType',
  kind: 'lineType',
  phantom: 'phantom',
  is_phantom: 'phantom',
  scrap: 'scrapPercent',
  scrap_percent: 'scrapPercent',
  scrap_pct: 'scrapPercent',
  'scrap_%': 'scrapPercent',
}

/** Substitutes are set on screen; a CSV column for them is refused rather than skipped (ADR-032 decision 3). */
const SUBSTITUTE_COLUMN = /^(substitute|alternate|alternative)/

/** Optional materials are marked on screen; a CSV column for them is refused rather than skipped (ADR-033 decision 10). */
const OPTIONAL_COLUMN = /^(is_)?optional(_yn)?$/

/** Scrap columns that could hold a 0–1 rate: refused, so 0.05 is never read as 0.05% (ADR-031). */
const SCRAP_RATE_COLUMNS = new Set(['scrap_rate', 'scrap_factor'])

function columnKey(name: string): string {
  return name.trim().toLowerCase().replace(/[\s-]+/g, '_')
}

/**
 * A draft's lines from CSV text: item_code, quantity and unit columns by name, in any order; an optional type column says
 * material (blank), by_product or waste, an optional phantom column Y or N, and an optional scrap_percent column the
 * planned scrap as on screen (5 or 5% for 5%).
 */
export function bomLinesFromCsv(text: string): { ok: true; rows: BomLineImportRowDto[] } | { ok: false; error: string } {
  let parsed: string[][]
  try {
    parsed = parseCsv(text)
  } catch (error) {
    if (error instanceof CsvParseError) return { ok: false, error: error.message }
    throw error
  }
  const [header, ...data] = parsed
  if (!header) return { ok: false, error: 'The file is empty.' }
  const rateColumn = header.find((name) => SCRAP_RATE_COLUMNS.has(columnKey(name)))
  if (rateColumn) {
    return { ok: false, error: `Give scrap as a percent in a scrap_percent column (5 means 5%), not ${rateColumn.trim()}.` }
  }
  const substituteColumn = header.find((name) => SUBSTITUTE_COLUMN.test(columnKey(name)))
  if (substituteColumn) {
    return { ok: false, error: `Substitute materials are added on screen, not by CSV yet; remove the ${substituteColumn.trim()} column.` }
  }
  const optionalColumn = header.find((name) => OPTIONAL_COLUMN.test(columnKey(name)))
  if (optionalColumn) {
    return { ok: false, error: `Optional materials are marked on screen, not by CSV yet; remove the ${optionalColumn.trim()} column.` }
  }
  const fields = header.map((name) => BOM_LINE_COLUMNS[columnKey(name)] ?? null)
  const duplicate = fields.find((field, index) => field !== null && fields.indexOf(field) !== index)
  if (duplicate) {
    return { ok: false, error: `Use only one ${header[fields.indexOf(duplicate)].trim()} column, including aliases.` }
  }
  for (const [needed, label] of [['itemCode', 'item_code'], ['quantity', 'quantity'], ['unit', 'unit']] as const) {
    if (!fields.includes(needed)) return { ok: false, error: `The first line must name the columns, with a ${label} column.` }
  }
  if (data.length === 0) return { ok: false, error: 'The file has no materials under its header.' }
  return {
    ok: true,
    rows: data.map((cells) => {
      const row: BomLineImportRowDto = { itemCode: '' }
      fields.forEach((field, index) => {
        if (field) row[field] = (cells[index] ?? '').trim()
      })
      return row
    }),
  }
}

/** How much a revision's material cost moved: the difference and, when there was a cost before, the percentage. */
export function costChange(before: number, after: number): { delta: number; percent: number | null } {
  const delta = Math.round((after - before) * 10000) / 10000
  return { delta, percent: before === 0 ? null : Math.round((delta / before) * 1000) / 10 }
}
