import type { HttpExecution, PinnedHttpBinding } from './types'

type RequestStorage = Pick<Storage, 'getItem' | 'setItem'>

/** Persist before transmission. Storage failures must prevent a send rather than lose its idempotency key. */
export function savedRequestId(storage: RequestStorage, key: string, create: () => string): string {
  const existing = storage.getItem(key)
  if (existing) return existing
  const requestId = create()
  storage.setItem(key, requestId)
  return requestId
}

export const isUnresolved = (execution: Pick<HttpExecution, 'transportStatus'>): boolean =>
  execution.transportStatus === 'PENDING' || execution.transportStatus === 'OUTCOME_UNKNOWN'

type ExecutionState = Pick<HttpExecution, 'transportStatus' | 'applyStatus'>
  & Partial<Pick<HttpExecution, 'httpStatus' | 'errorCode' | 'outputJson'>>

/** The server also blocks a transmitted result that has not been applied, including a late result from an earlier attempt. */
export function blocksHttpTransmission(execution: ExecutionState): boolean {
  return isUnresolved(execution) || (['SUCCEEDED', 'HTTP_REJECTED', 'RESPONSE_REJECTED'].includes(execution.transportStatus)
    && execution.applyStatus !== 'APPLIED')
}

export function canApplyHttpExecution(execution: ExecutionState): boolean {
  if (execution.applyStatus !== 'APPLY_FAILED') return false
  return execution.transportStatus === 'SUCCEEDED' ? execution.outputJson != null
    : ['HTTP_REJECTED', 'RESPONSE_REJECTED', 'TRANSPORT_FAILED'].includes(execution.transportStatus)
}

export function executionSummary(execution: ExecutionState): string {
  if (execution.transportStatus === 'PENDING') return 'HTTP request pending. Another transmission is blocked.'
  if (execution.transportStatus === 'OUTCOME_UNKNOWN') return 'The remote outcome is unknown. Do not resend; check the external system first.'
  const status = execution.httpStatus === null || execution.httpStatus === undefined ? 'HTTP response' : `HTTP ${execution.httpStatus}`
  if (execution.applyStatus === 'LATE') return `${status} recorded after the step ended. It was not applied.`
  if (execution.transportStatus === 'SUCCEEDED') {
    if (execution.applyStatus === 'APPLY_FAILED') {
      if (execution.outputJson == null) {
        return execution.errorCode === 'RESPONSE_MAPPING_FAILED'
          ? `${status} accepted, but its response could not be mapped. No output was saved, so it cannot be applied again.`
          : `${status} accepted. No output was saved, so it cannot be applied again.`
      }
      return `${status} accepted. Applying the result failed; it can be applied again without another HTTP request.`
    }
    return execution.applyStatus === 'APPLIED' ? `${status} accepted. Result applied.` : `${status} accepted. Applying the result is pending.`
  }
  if (execution.transportStatus === 'TRANSPORT_FAILED') return 'The HTTP request was not sent.'
  if (execution.transportStatus === 'HTTP_REJECTED') return `${status} was not an approved success status.`
  if (execution.transportStatus === 'RESPONSE_REJECTED') return 'The external response could not be accepted.'
  return 'HTTP result recorded.'
}

export function pinnedHttpBinding(snapshot: unknown, processId: string): PinnedHttpBinding | null {
  if (!snapshot || typeof snapshot !== 'object' || !('nodeBindings' in snapshot) || !Array.isArray(snapshot.nodeBindings)) return null
  const binding: unknown = snapshot.nodeBindings.find((entry: unknown) => entry && typeof entry === 'object'
    && 'processId' in entry && entry.processId === processId)
  if (!binding || typeof binding !== 'object' || !('profileVersionId' in binding) || typeof binding.profileVersionId !== 'string'
    || !('operationId' in binding) || typeof binding.operationId !== 'string') return null
  return { processId, profileVersionId: binding.profileVersionId, operationId: binding.operationId }
}
