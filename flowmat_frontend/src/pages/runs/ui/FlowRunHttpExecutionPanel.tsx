import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useHttpExecutions, useHttpExecutionMutations, useHttpRequestPreview, usePinnedHttpProfileVersion } from '../../../entities/connector/api/useHttpConnectors'
import { canApplyHttpExecution, executionSummary, isUnresolved, savedRequestId } from '../../../entities/connector/model/httpExecutionState'
import type { PinnedHttpBinding } from '../../../entities/connector/model/types'
import { useIsProjectOwner } from '../../../entities/project/api/useIsProjectOwner'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'
import { errorMessage } from '../../../shared/lib/errorMessage'

export function FlowRunHttpExecutionPanel({ projectId, runId, stepId, sequenceNo, runStatus, stepStatus, binding }: {
  projectId: string; runId: string; stepId: string; sequenceNo: number; runStatus: string; stepStatus: string; binding: PinnedHttpBinding
}) {
  const owner = useIsProjectOwner(projectId)
  const query = useHttpExecutions(runId, stepId)
  const profileVersion = usePinnedHttpProfileVersion(projectId, binding.profileVersionId)
  const { execute, apply } = useHttpExecutionMutations(runId, stepId)
  const attempts = useQuery({ queryKey: ['flow-run-attempts', runId, stepId],
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<{ attemptId: string; status: string }[]>>(
      `/flow-runs/${encodeURIComponent(runId)}/steps/${encodeURIComponent(stepId)}/attempts`)) })
  const storageKey = `flowmat.http-request:${projectId}:${runId}:${stepId}`
  const [requestId, setRequestId] = useState<string | null>(() => {
    try { return window.sessionStorage.getItem(storageKey) }
    catch { return null }
  })
  const [confirmed, setConfirmed] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const rows = query.data ?? []
  const latestAttempt = attempts.data?.[attempts.data.length - 1]
  const unresolved = rows.some(isUnresolved)
  const saved = rows.find((row) => row.requestId === requestId)
  const resumeRequest = Boolean(requestId && !saved)
  const currentClaim = latestAttempt && latestAttempt.status === 'running'
    ? rows.some((row) => row.attemptId === latestAttempt.attemptId) : false
  const active = runStatus === 'running' && (stepStatus === 'planned' || stepStatus === 'running')
  const operation = profileVersion.data?.profileVersionId === binding.profileVersionId
    ? profileVersion.data.operations.find((candidate) => candidate.operationId === binding.operationId) : undefined
  const maySend = owner && active && !unresolved && !currentClaim && !query.isPending && !query.isError
    && !attempts.isPending && !attempts.isError && Boolean(operation) && !profileVersion.isError
  const pending = execute.isPending || apply.isPending

  async function send() {
    if (!maySend || !confirmed || pending) return
    setProblem(null)
    try {
      // A new ID is possible only for an explicit action after the previous result was recovered.
      // A browser/network failure keeps the old ID, including across a page reload in this tab.
      if (saved) window.sessionStorage.removeItem(storageKey)
      const id = savedRequestId(window.sessionStorage, storageKey, () => window.crypto.randomUUID())
      setRequestId(id)
      await execute.mutateAsync(id)
      setConfirmed(false)
    } catch (error) {
      setProblem(`${errorMessage(error, 'Could not confirm the HTTP result.')} Retry only with the same request ID; do not start a second request.`)
    }
  }

  return <section aria-label={`HTTP execution for step ${sequenceNo}`} style={{ display: 'grid', gap: 8, marginTop: 12,
    borderTop: '1px solid var(--border)', paddingTop: 12 }}>
    <h4 style={{ margin: 0 }}>HTTP operation: {binding.operationId}</h4>
    <span style={{ overflowWrap: 'anywhere' }}>Pinned profile revision: {binding.profileVersionId}</span>
    {profileVersion.isPending && <p>Checking the pinned HTTP destination...</p>}
    {profileVersion.isError && <p role="alert">{errorMessage(profileVersion.error, 'Could not verify the HTTP destination. Sending is blocked.')}</p>}
    {operation && <strong style={{ overflowWrap: 'anywhere' }}>{operation.method} {profileVersion.data!.baseOrigin}{operation.relativePath}</strong>}
    {!profileVersion.isPending && !profileVersion.isError && !operation && <p role="alert">The pinned HTTP operation is unavailable. Sending is blocked.</p>}
    {query.isPending && <p>Loading HTTP results...</p>}
    {query.isError && <p role="alert">{errorMessage(query.error, 'Could not load HTTP results. Sending is blocked until results can be checked.')}</p>}
    {attempts.isError && <p role="alert">{errorMessage(attempts.error, 'Could not check the current attempt.')}</p>}
    {owner && active && <HttpRequestPreview key={JSON.stringify([projectId, runId, stepId, binding.processId,
      binding.profileVersionId, binding.operationId, runStatus, stepStatus, pending])}
      runId={runId} stepId={stepId} disabled={pending} />}
    {rows.map((row) => <article key={row.executionId} style={{ display: 'grid', gap: 4, minWidth: 0 }}>
      <p role="status" style={{ margin: 0 }}>{executionSummary(row)}</p>
      <small style={{ overflowWrap: 'anywhere' }}>Request ID: {row.requestId}</small>
      <small>Started: {new Date(row.startedAt).toLocaleString()}{row.completedAt ? ` · Ended: ${new Date(row.completedAt).toLocaleString()}` : ''}</small>
      {row.errorCode && <span>{row.errorCode}</span>}
      {row.outputJson && <details><summary>Mapped output</summary>
        <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{row.outputJson}</pre>
      </details>}
      {owner && active && canApplyHttpExecution(row) && <button type="button" disabled={pending}
        onClick={async () => {
          setProblem(null)
          try { await apply.mutateAsync(row.executionId) }
          catch (error) { setProblem(errorMessage(error, 'Could not apply the saved result. No HTTP request was resent.')) }
        }}>{apply.isPending ? 'Applying...' : 'Apply saved result'}</button>}
    </article>)}
    {!owner && <p>Only the project owner can send an HTTP request or apply a saved result.</p>}
    {owner && maySend && <>
      {resumeRequest && <p>A previous browser request has no confirmed result. Retrying uses its saved request ID.</p>}
      <label style={{ display: 'flex', gap: 8, alignItems: 'start' }}>
        <input type="checkbox" checked={confirmed} disabled={pending} onChange={(event) => setConfirmed(event.target.checked)} />
        I understand this sends an external HTTP request
      </label>
      <button type="button" disabled={pending || !confirmed} onClick={() => { void send() }}>
        {execute.isPending ? 'Waiting for HTTP result...' : resumeRequest ? 'Retry same request' : rows.length ? 'Execute next attempt' : 'Execute HTTP request'}
      </button>
    </>}
    <button type="button" disabled={query.isFetching || pending} onClick={() => { void query.refetch() }}>Refresh saved results</button>
    {problem && <p role="alert">{problem}</p>}
  </section>
}

function HttpRequestPreview({ runId, stepId, disabled }: { runId: string; stepId: string; disabled: boolean }) {
  // Its keyed parent unmounts this mutation observer when the run, binding or status changes.
  // A late response from that context cannot populate the current step's preview.
  const preview = useHttpRequestPreview(runId, stepId)
  const result = preview.isSuccess ? preview.data : undefined
  return <div style={{ display: 'grid', gap: 6, minWidth: 0 }}>
    <button type="button" disabled={disabled || preview.isPending} onClick={() => { preview.mutate() }}>
      {preview.isPending ? 'Preparing HTTP request preview...' : 'Preview HTTP request'}
    </button>
    <small>This preview does not send a request or reserve an attempt. Execution checks permissions, configuration and the current step again.</small>
    {preview.isError && <p role="alert">{errorMessage(preview.error, 'Could not preview the HTTP request.')}</p>}
    {result && <div aria-label="HTTP request preview" style={{ display: 'grid', gap: 4, minWidth: 0 }}>
      <strong style={{ overflowWrap: 'anywhere' }}>{result.method} {result.target}</strong>
      <small style={{ overflowWrap: 'anywhere' }}>Profile revision: {result.profileVersionId} · Operation: {result.operationId}</small>
      {result.hasRequestBody
        ? <pre aria-label="HTTP request preview JSON" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', margin: 0 }}>{result.requestJson}</pre>
        : <p style={{ margin: 0 }}>No request body (GET).</p>}
    </div>}
  </div>
}
