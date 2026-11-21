import { create } from 'zustand'
import type { KernelWalk, KernelWalkStep } from '../../../shared/lib/flowKernel'

/** How a tried flow ended, in a line. */
export interface TryFlowSummary {
  tone: 'finished' | 'stopped' | 'failed' | 'unreadable'
  headline: string
}

/** One step of a tried flow as the panel lists it. */
export interface TryFlowRow {
  index: number
  nodeId: string
  name: string
  received: string
  status: KernelWalkStep['status']
  note: string | null
}

/** What reached a connection's far end, by connection: "6 kg". */
export type ConnectionFlows = Record<string, string>

/** A quantity with its unit as people read it, or "nothing measured". */
export function amount(snapshot: Record<string, unknown> | undefined): string {
  const quantity = snapshot?.quantity
  if (typeof quantity !== 'number') return 'nothing measured'
  const unit = typeof snapshot?.unit === 'string' ? ` ${snapshot.unit}` : ''
  return `${Number(quantity.toFixed(4))}${unit}`
}

export function summarizeWalk(walk: KernelWalk, nameOf: (id: string) => string): TryFlowSummary {
  if ('error' in walk) return { tone: 'unreadable', headline: `Cannot run: ${walk.error.message}` }
  if (walk.status === 'failed') {
    const failed = walk.steps.find((step) => step.status === 'failed' && step.error)
    return { tone: 'failed', headline: `Fails${failed ? ` at ${nameOf(failed.nodeId)}` : ''}: ${walk.failure?.message ?? ''}` }
  }
  if (walk.status === 'running') {
    const refused = walk.steps.find((step) => step.status === 'refused')
    if (refused) return { tone: 'stopped', headline: `Stops at ${nameOf(refused.nodeId)}: ${refused.refusal?.message ?? ''}` }
    const waiting = walk.steps.find((step) => step.status === 'planned')
    return { tone: 'stopped', headline: waiting ? `${nameOf(waiting.nodeId)} still waits for a branch` : 'Stops before the end' }
  }
  const lost = lostByUnit(walk)
  return {
    tone: 'finished',
    headline: `Reaches the end in ${walk.steps.length} ${walk.steps.length === 1 ? 'step' : 'steps'}`
      + (lost.length > 0 ? `, losing ${lost.join(' and ')} on the way` : ''),
  }
}

/** The losses on the way, added up per unit. */
function lostByUnit(walk: Extract<KernelWalk, { steps: unknown }>): string[] {
  const totals = new Map<string, number>()
  for (const event of walk.events) {
    if (event.type !== 'transfer_loss') continue
    const unit = typeof event.payload.unit === 'string' ? event.payload.unit : ''
    totals.set(unit, (totals.get(unit) ?? 0) + Number(event.payload.lost ?? 0))
  }
  return [...totals].map(([unit, lost]) => `${Number(lost.toFixed(4))}${unit ? ` ${unit}` : ''}`)
}

export function walkRows(walk: KernelWalk, nameOf: (id: string) => string): TryFlowRow[] {
  if ('error' in walk) return []
  return walk.steps.map((step, index) => ({
    index,
    nodeId: step.nodeId,
    name: nameOf(step.nodeId),
    received: amount(step.input),
    status: step.status,
    note: step.refusal?.message ?? step.error?.message ?? null,
  }))
}

/** What reached each connection's far end: a step's input, or a Join's arrival. */
export function connectionFlows(walk: KernelWalk): ConnectionFlows {
  if ('error' in walk) return {}
  const flows: ConnectionFlows = {}
  for (const step of walk.steps) {
    const arrivals = step.input.arrivals
    if (Array.isArray(arrivals)) {
      for (const arrival of arrivals as Record<string, unknown>[]) {
        if (typeof arrival.connectionId === 'string') flows[arrival.connectionId] = amount(arrival)
      }
    } else if (step.connectionId) {
      flows[step.connectionId] = amount(step.input)
    }
  }
  return flows
}

/** The input a try starts with: a quantity, a unit and attributes typed as JSON; null with the reason when not valid. */
export function tryInput(quantity: string, unit: string, attributes: string):
  { input: Record<string, unknown> } | { problem: string } {
  const input: Record<string, unknown> = {}
  if (quantity.trim()) {
    const number = Number(quantity)
    if (!Number.isFinite(number)) return { problem: 'The quantity is not a number.' }
    input.quantity = number
  }
  if (unit.trim()) input.unit = unit.trim()
  if (attributes.trim()) {
    let parsed: unknown
    try {
      parsed = JSON.parse(attributes)
    } catch {
      return { problem: 'The attributes are not JSON.' }
    }
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return { problem: 'The attributes are a JSON object, like {"lot": "A"}.' }
    }
    input.attrs = parsed
  }
  return { input }
}

/** What the canvas shows of the last try: the amount on each connection, until the drawing changes. */
export const useTryFlowStore = create<{ flows: ConnectionFlows; setFlows(flows: ConnectionFlows): void }>((set) => ({
  flows: {},
  setFlows: (flows) => set({ flows }),
}))
