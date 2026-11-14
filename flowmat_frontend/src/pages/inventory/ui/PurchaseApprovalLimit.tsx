import { useState, type FormEvent } from 'react'
import { useIsProjectOwner } from '../../../entities/project/api/useIsProjectOwner'
import { useProjectMembersQuery } from '../../../entities/project/api/useProjectMembersQuery'
import { usePurchaseSettingsQuery, useSavePurchaseSettingsMutation } from '../../../entities/purchasing/api/usePurchaseSettings'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { approvalRulesText, approvalStepsPayload, typedPrice, type ApprovalStepRow } from '../model/purchaseOrderModel'

const MAX_STEPS = 5

/**
 * The project's purchase approval limit (docs/domain/purchasing.md PO33) and the editors who approve from a value up, in turn
 * (PO50): everyone reads them, the owner changes them.
 */
export function PurchaseApprovalLimit({ projectId }: { projectId: string }) {
  const query = usePurchaseSettingsQuery(projectId)
  const save = useSavePurchaseSettingsMutation(projectId)
  const owner = useIsProjectOwner(projectId)
  const [typed, setTyped] = useState<string | null>(null)
  const [limitVersion, setLimitVersion] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const limit = query.data?.approvalLimit ?? null
  const steps = query.data?.approvalSteps ?? []
  const editors = (useProjectMembersQuery(projectId).data ?? [])
    .filter((member) => member.memberStatus === 'active' && member.projectRole === 'editor')
  const [rows, setRows] = useState<ApprovalStepRow[] | null>(null)
  const [stepsVersion, setStepsVersion] = useState<number | null>(null)
  const [stepsError, setStepsError] = useState<string | null>(null)

  function setRow(index: number, patch: Partial<ApprovalStepRow>) {
    setRows((current) => current && current.map((row, at) => (at === index ? { ...row, ...patch } : row)))
  }

  function submitSteps(event: FormEvent) {
    event.preventDefault()
    if (!rows) return
    const payload = approvalStepsPayload(rows)
    setStepsError(payload.error)
    if (!payload.steps) return
    save.mutate({ approvalLimit: limit, approvalSteps: payload.steps, expectedVersion: stepsVersion },
      { onSuccess: () => setRows(null) })
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const value = typedPrice(typed ?? '')
    if (value === undefined) {
      setError('Enter 0 or more with at most four decimals, or leave it empty for no approval. Use at most 10 digits before the decimal.')
      return
    }
    setError(null)
    save.mutate({ approvalLimit: value, expectedVersion: limitVersion }, { onSuccess: () => setTyped(null) })
  }

  return <div role="group" aria-label="Purchase approval" style={{ fontSize: 12, margin: '6px 0' }}>
    <span className="inspector-hint">{approvalRulesText(limit, steps)}</span>
    {owner && typed === null && <button type="button" style={{ marginLeft: 6, fontSize: 11 }}
      onClick={() => { setTyped(limit === null ? '' : String(limit)); setLimitVersion(query.data?.version ?? null); save.reset() }}>Change approval limit</button>}
    {owner && rows === null && typed === null && <button type="button" style={{ marginLeft: 6, fontSize: 11 }}
      onClick={() => {
        setRows(steps.map((step) => ({ fromValue: String(step.fromValue), approverId: step.approverId })))
        setStepsVersion(query.data?.version ?? null)
        setStepsError(null)
        save.reset()
      }}>Change approval steps</button>}
    {owner && rows !== null && <form aria-label="Approval steps" onSubmit={submitSteps} style={{ display: 'grid', gap: 4, marginTop: 6, maxWidth: 440 }}>
      {rows.map((row, index) => <div key={index} style={{ display: 'flex', gap: 4 }}>
        <input aria-label={`Step ${index + 1} from`} type="number" min="0" step="any" value={row.fromValue} style={{ width: 100 }}
          onChange={(event) => setRow(index, { fromValue: event.target.value })} />
        <select aria-label={`Step ${index + 1} approver`} value={row.approverId} onChange={(event) => setRow(index, { approverId: event.target.value })}>
          <option value="">Choose who approves</option>
          {editors.map((member) => <option key={member.userId} value={member.userId}>{member.userId}</option>)}
          {row.approverId && !editors.some((member) => member.userId === row.approverId)
            && <option value={row.approverId}>{row.approverId} (cannot approve now)</option>}
        </select>
        <button type="button" aria-label={`Remove step ${index + 1}`}
          onClick={() => setRows(rows.filter((_, at) => at !== index))}>Remove</button>
      </div>)}
      <div style={{ display: 'flex', gap: 4 }}>
        <button type="button" disabled={rows.length >= MAX_STEPS} onClick={() => setRows([...rows, { fromValue: '', approverId: '' }])}>Add step</button>
        <button type="submit" disabled={save.isPending}>Save steps</button>
        <button type="button" onClick={() => { setRows(null); setStepsError(null) }}>Cancel steps</button>
      </div>
      <p className="inspector-hint" style={{ margin: 0 }}>Editors approve orders worth their value or more, lowest value first; the owner approves from the limit.</p>
    </form>}
    {stepsError && <p role="alert">{stepsError}</p>}
    {owner && typed !== null && <form aria-label="Approval limit" onSubmit={submit} style={{ display: 'inline-flex', gap: 4, marginLeft: 6 }}>
      <input aria-label="Limit" type="number" min="0" step="any" placeholder="no approval" value={typed} style={{ width: 100 }}
        onChange={(event) => setTyped(event.target.value)} />
      <button type="submit" disabled={save.isPending}>Save limit</button>
      <button type="button" onClick={() => { setTyped(null); setError(null) }}>Cancel</button>
    </form>}
    {error && <p role="alert">{error}</p>}
    {save.isError && <p role="alert">{errorMessage(save.error, 'The approval limit was not saved.')}</p>}
  </div>
}
