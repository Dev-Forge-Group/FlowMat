import { create } from 'zustand'
import type { KernelTime, KernelWalk, KernelWalkStep } from '../../../shared/lib/flowKernel'
import type { WorkOrderWorkingTimeDto } from '../../../entities/production/api/workOrderWorkingTime'

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

/**
 * A `datetime-local` value (`2026-12-21T09:00`) as an ISO time with the browser's offset, `2026-12-21T09:00:00+09:00`, as
 * the working-time API and the kernel take it. `offsetMinutes` is east of UTC (the negative of `getTimezoneOffset`). Null
 * when it is not a date and time.
 */
export function startTime(local: string, offsetMinutes: number): string | null {
  const match = /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})(?::(\d{2}))?$/.exec(local.trim())
  if (!match) return null
  const sign = offsetMinutes < 0 ? '-' : '+'
  const total = Math.abs(offsetMinutes)
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${match[1]}T${match[2]}:${match[3] ?? '00'}${sign}${pad(Math.floor(total / 60))}:${pad(total % 60)}`
}

/** The kernel's time for a work order: its working stretches from the API, starting at `start`. */
export function kernelTime(start: string, working: WorkOrderWorkingTimeDto): KernelTime {
  return { start, stretches: working.allTime ? [] : working.stretches, equipmentId: working.equipmentId, crew: working.crew }
}

/** A time the kernel wrote, as the panel shows it: `2026-12-21 13:00`, seconds when there are some, in the start's offset. */
export function shownTime(time: string): string {
  const match = /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})(?::(\d{2}))?/.exec(time)
  if (!match) return time
  return `${match[1]} ${match[2]}${match[3] && match[3] !== '00' ? `:${match[3]}` : ''}`
}

/** How long from one time to another, as people say it: `4 h 30 min`, `2 min`, `45 s`. */
export function duration(from: string, to: string): string {
  const seconds = Math.max(0, Math.round((Date.parse(to) - Date.parse(from)) / 1000))
  if (seconds < 60) return `${seconds} s`
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes} min`
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  if (hours < 48) return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`
  const days = Math.floor(hours / 24)
  const restHours = hours % 24
  return restHours === 0 ? `${days} d` : `${days} d ${restHours} h`
}

/**
 * When each tried step could start, by place in the walk, as the production step screen says it: `from 2026-12-21 13:00
 * (waits for PRESS, kim)` when it waited for working time, `from …` when only a delay held it, nothing when it could start
 * at once. The equipment goes by its code.
 */
export function timeNotes(walk: KernelWalk, equipmentCode: string | null): (string | null)[] {
  if ('error' in walk || !walk.times) return []
  return walk.times.map((time) => {
    if (!time.scheduledAt) return null
    if (!time.held) return `from ${shownTime(time.scheduledAt)}`
    const names = [time.held.equipmentId ? equipmentCode ?? time.held.equipmentId : null, ...time.held.crew]
      .filter((name): name is string => Boolean(name))
    return `from ${shownTime(time.scheduledAt)}${names.length > 0 ? ` (waits for ${names.join(', ')})` : ''}`
  })
}

/** When the tried flow ends and how long it takes from `start`: the latest end of a step. Null without times. */
export function timeSummary(walk: KernelWalk, start: string): string | null {
  if ('error' in walk || !walk.times) return null
  let last: string | null = null
  for (const time of walk.times) {
    if (time.endedAt && (last === null || Date.parse(time.endedAt) > Date.parse(last))) last = time.endedAt
  }
  return last === null ? null : `Ends ${shownTime(last)}, ${duration(start, last)} after the start`
}

/** What the canvas shows of the last try: the amount on each connection, until the drawing changes. */
export const useTryFlowStore = create<{ flows: ConnectionFlows; setFlows(flows: ConnectionFlows): void }>((set) => ({
  flows: {},
  setFlows: (flows) => set({ flows }),
}))
