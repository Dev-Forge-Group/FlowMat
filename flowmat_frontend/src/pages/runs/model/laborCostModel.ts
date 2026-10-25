import type { LaborCostPerson } from '../../../entities/production/api/useLaborCost'
import type { WorkerLaborRateLineDto } from '../../../entities/workforce/api/useWorkerLaborRates'
import { formatQty } from '../../../shared/lib/formatQty'

/** "Labor cost: 45", or "Known labor cost: 45 (some worked time has no rate)" (docs/architecture/adr/ADR-025-worker-labor-cost.md). */
export function laborCostText(cost: { cost: number; costComplete: boolean }): string {
  return cost.costComplete ? `Labor cost: ${formatQty(cost.cost)}`
    : `Known labor cost: ${formatQty(cost.cost)} (some worked time has no rate)`
}

/** "kim: 90 min · 45", "lee: 30 min · no rate". */
export function laborCostPersonText(person: LaborCostPerson): string {
  const cost = person.costComplete ? formatQty(person.cost)
    : Number(person.cost) > 0 ? `${formatQty(person.cost)} known, some without a rate` : 'no rate'
  return `${person.userId}: ${person.workedMinutes} min · ${cost}`
}

/** One saved rate, e.g. "30/h from 2041-09-01 (current) · Base · set by demo-owner". */
export function rateLineText(line: WorkerLaborRateLineDto): string {
  const rate = line.hourlyRate == null ? 'unknown rate' : `${formatQty(line.hourlyRate)}/h`
  const from = line.effectiveFrom ? `from ${line.effectiveFrom}` : 'from the start'
  const state = line.state === 'removed' ? `removed by ${line.removedBy ?? 'someone'}` : line.state
  return `${rate} ${from} (${state})${line.reason ? ` · ${line.reason}` : ''} · set by ${line.setBy}`
}
