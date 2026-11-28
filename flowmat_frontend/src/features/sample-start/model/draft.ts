import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'
import type { SampleSpec } from './samples'

/**
 * A first process drafted from a description (docs/architecture/adr/ADR-047-process-draft-from-a-description.md), checked
 * by the server: items, steps in order with what one batch takes and makes, and a target to plan for. Nothing is saved
 * until the person builds it.
 */
export interface ProcessDraft {
  title: string
  summary: string
  items: { code: string; name: string; type: 'material' | 'semi' | 'product'; unit: string }[]
  steps: { name: string; inputs: DraftFlow[]; outputs: DraftFlow[]; minutes: number }[]
  target: { item: string; quantity: number }
}

export interface DraftFlow {
  item: string
  quantity: number
}

const UNIT_IDS: Record<string, string> = {
  kg: 'unit_kg', g: 'unit_g', t: 'unit_t', l: 'unit_l', ml: 'unit_ml', ea: 'unit_ea', m: 'unit_m', cm: 'unit_cm', mm: 'unit_mm',
}

/** Whether this server drafts at all (ADR-047); the home screen shows the card only where it works. */
export async function fetchDraftAvailability(): Promise<boolean> {
  const answer = unwrapApiResponse(await httpClient.get<ApiEnvelope<{ available: boolean }>>('/process-drafts/availability'))
  return answer?.available === true
}

export async function requestDraft(description: string): Promise<ProcessDraft> {
  return unwrapApiResponse(await httpClient.post<ApiEnvelope<ProcessDraft>>('/process-drafts', { description }))
}

/**
 * The draft as a sample the sample builder makes through the public API: its items, each step a node with its ports, and a
 * connection wherever a step takes what an earlier step made. No stock or equipment: the factory adds its own.
 */
export function draftToSpec(draft: ProcessDraft): SampleSpec {
  const unitOf = new Map(draft.items.map((item) => [item.code, item.unit]))
  const madeBy = new Map<string, string>()
  const connections: SampleSpec['connections'] = []
  const nodes = draft.steps.map((step) => {
    for (const input of step.inputs) {
      const from = madeBy.get(input.item)
      if (from) connections.push({ from, to: step.name, item: input.item })
    }
    for (const output of step.outputs) madeBy.set(output.item, step.name)
    const ports: SampleSpec['nodes'][number]['ports'] = [
      ...step.inputs.map((flow) => ['input', flow.item, flow.quantity, unitOf.get(flow.item) ?? 'ea'] as const),
      ...step.outputs.map((flow) => ['output', flow.item, flow.quantity, unitOf.get(flow.item) ?? 'ea'] as const),
    ].map(([direction, code, quantity, unit]) => [direction, code, quantity, unit])
    return { name: step.name, ports, ...(step.minutes > 0 ? { requirements: [{ kind: 'time' as const, amount: step.minutes, unit: 'min' }] } : {}) }
  })
  const product = draft.items.find((item) => item.code === draft.target.item)
  return {
    key: 'draft',
    title: draft.title,
    summary: draft.summary,
    projectName: draft.title,
    workflowName: draft.title,
    target: `Ask the Plan panel for ${draft.target.quantity} ${product?.unit ?? ''} of ${product?.name ?? draft.target.item}.`.replace(/\s+/g, ' '),
    items: draft.items.map((item) => ({ code: item.code, name: item.name, type: item.type, unitId: UNIT_IDS[item.unit] ?? 'unit_ea' })),
    stock: [],
    equipment: [],
    nodes,
    connections,
  }
}
