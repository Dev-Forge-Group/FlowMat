import type { HttpProfile } from '../../connector/model/types'
import type { IntegrationJob, IntegrationKind, IntegrationRun } from './types'

export interface IntegrationJobForm {
  jobId?: string
  version?: number
  name: string
  kind: IntegrationKind
  profileVersionId: string
  operationId: string
  verifyOperationId: string
  note: string
  mapping: string
}

export const KIND_LABELS: Record<IntegrationKind, string> = {
  pull_items: 'Pull items',
  pull_stock: 'Pull stock',
  push_stock: 'Push stock',
}

/** A starting mapping for each kind: where the records are and which fields each one gives. */
export function exampleMapping(kind: IntegrationKind): string {
  const row = kind === 'pull_items'
    ? { itemCode: { from: '/sku' }, itemName: { from: '/name' }, unitCode: { literal: 'KG' } }
    : kind === 'pull_stock'
      ? { itemCode: { from: '/sku' }, quantity: { from: '/quantity' }, location: { from: '/place', required: false } }
      : { itemCode: { from: '/sku' }, quantity: { from: '/onHand' } }
  return JSON.stringify({ rowsAt: kind === 'push_stock' ? '/stock' : '/items', row: { object: row } }, null, 2)
}

export function emptyJobForm(profile?: HttpProfile): IntegrationJobForm {
  const read = profile?.operations.find((operation) => operation.method === 'GET')
  return { name: '', kind: 'pull_items', profileVersionId: profile?.profileVersionId ?? '', operationId: read?.operationId ?? '',
    verifyOperationId: '', note: '', mapping: exampleMapping('pull_items') }
}

export function jobForm(job: IntegrationJob): IntegrationJobForm {
  return { jobId: job.jobId, version: job.version, name: job.name, kind: job.kind, profileVersionId: job.profileVersionId,
    operationId: job.operationId, verifyOperationId: job.verifyOperationId ?? '', note: job.note ?? '',
    mapping: JSON.stringify(job.mapping, null, 2) }
}

/** The request body, or an error that says which field to fix. The server checks the rest. */
export function jobBody(form: IntegrationJobForm): string {
  let mapping: unknown
  try { mapping = JSON.parse(form.mapping) }
  catch { throw new Error('The mapping must be valid JSON.') }
  const push = form.kind === 'push_stock'
  return JSON.stringify({
    name: form.name.trim(), kind: form.kind, profileVersionId: form.profileVersionId, operationId: form.operationId,
    verifyOperationId: push ? form.verifyOperationId || null : null,
    note: form.kind === 'pull_stock' ? form.note.trim() || null : null,
    mapping,
    ...(form.version === undefined ? {} : { version: form.version }),
  })
}

/** The operations of the chosen profile version that the kind can use: GET reads, POST/PUT/PATCH sends. */
export function operationsFor(profile: HttpProfile | undefined, role: 'read' | 'send') {
  return (profile?.operations ?? []).filter((operation) => role === 'read'
    ? operation.method === 'GET' : ['POST', 'PUT', 'PATCH'].includes(operation.method))
}

const STATUS_LABELS: Record<string, string> = {
  running: 'Running', succeeded: 'Succeeded', partial: 'Partly done', failed: 'Failed', outcome_unknown: 'Outcome unknown',
}

/** One line for a run: what happened, the counts and the message. */
export function runSummary(run: IntegrationRun): string {
  const parts = [STATUS_LABELS[run.status] ?? run.status]
  if (run.rowsTotal !== null && run.rowsTotal !== undefined) {
    parts.push(`${run.rowsApplied ?? 0} of ${run.rowsTotal} taken`)
    if (run.rowsLeftOut) parts.push(`${run.rowsLeftOut} left out`)
  }
  if (run.message) parts.push(run.message)
  return parts.join(' · ')
}

const RESULT_LABELS: Record<string, string> = {
  create: 'New', update: 'Changed', unchanged: 'No change', receive: 'Received', left_out: 'Left out',
  same: 'Already matches', send: 'Would send', matched: 'Matches', different: 'Does not match', not_listed: 'Not listed after',
  not_in_flowmat: 'Not in FlowMat', not_sent: 'Not sent', unknown: 'Unknown',
}

export function resultLabel(result: string): string {
  return RESULT_LABELS[result] ?? result
}
