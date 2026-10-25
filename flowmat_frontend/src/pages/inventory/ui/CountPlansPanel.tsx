import { useState } from 'react'
import { useCurrentUserQuery } from '../../../entities/auth/api/useCurrentUserQuery'
import { useProjectMembersQuery } from '../../../entities/project/api/useProjectMembersQuery'
import { useInventoriesQuery } from '../../../entities/inventory/api/useInventoriesQuery'
import { useInventoryCountPlans, useCountPlanCommand, type CountPlanCommand, type CountPlanLine } from '../../../entities/inventory/api/useInventoryCountPlans'
import { errorMessage, errorStatus } from '../../../shared/lib/errorMessage'
import { formatQty } from '../../../shared/lib/formatQty'
import type { ItemDto } from '../../../shared/types/api'
import { countPlanQuantity } from '../model/countPlanModel'

export function CountPlansPanel({ projectId, items }: { projectId: string; items: ItemDto[] }) {
  const plans = useInventoryCountPlans(projectId)
  const stocks = useInventoriesQuery(projectId)
  const me = useCurrentUserQuery().data?.userId
  const members = useProjectMembersQuery(projectId)
  const role = members.data?.find((member) => member.userId === me && member.memberStatus === 'active')?.projectRole
  const canWrite = role === 'owner' || role === 'editor'
  const counters = (members.data ?? []).filter((member) => member.memberStatus === 'active')
  const command = useCountPlanCommand(projectId)
  const [selected, setSelected] = useState<string[]>([])
  const [blind, setBlind] = useState(true)
  const [note, setNote] = useState('')
  const [active, setActive] = useState<string | null>(null)
  const [reviewBaseline, setReviewBaseline] = useState(false)
  const [unconfirmed, setUnconfirmed] = useState<CountPlanCommand | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [drafts, setDrafts] = useState<Record<string, { value: string; version: number }>>({})
  const locked = command.isPending || Boolean(unconfirmed)
  const plan = plans.data?.find((entry) => entry.planId === active)
  const dirty = plan?.lines.some((line) => {
    const draft = drafts[line.lineId]
    if (!draft) return false
    if (draft.version !== line.entryVersion) return true
    if (!draft.value.trim()) return line.countedQuantity !== null
    try { return countPlanQuantity(draft.value) !== line.countedQuantity } catch { return true }
  }) ?? false
  const label = (itemId: string) => {
    const item = items.find((entry) => entry.itemId === itemId)
    return item ? `${item.itemCode} · ${item.itemName}` : itemId
  }
  async function run(input: CountPlanCommand) {
    if (command.isPending) return
    setMessage(null)
    try {
      const result = await command.mutateAsync(input)
      setUnconfirmed(null); setActive(result.planId)
      if (input.kind === 'record' || input.kind === 'recount') setDrafts((previous) => {
        const next = { ...previous }; delete next[input.lineId]; return next
      })
      if (input.kind === 'create') { setSelected([]); setNote(''); setReviewBaseline(false) }
      setMessage(result.status === 'submitted' ? 'Count plan submitted. Stock was updated once.' : 'Count plan saved.')
    } catch (error) {
      const status = errorStatus(error)
      setUnconfirmed(status === null || status >= 500 ? input : null)
      setMessage(errorMessage(error, 'The count plan could not be saved.'))
    }
  }
  return <section aria-label="Count plans">
    <h2>Count plans</h2>
    <p>Start a plan before counting. If stock changes, only the affected rows must be counted again.</p>
    {plans.isLoading && <p>Loading count plans...</p>}
    {plans.isError && <p role="alert">{errorMessage(plans.error)}</p>}
    {message && <p role={command.isError ? 'alert' : 'status'}>{message}</p>}
    {unconfirmed && <div role="alert">
      <p>The result is unconfirmed. Inputs are locked until the original request is retried.</p>
      <button disabled={command.isPending} onClick={() => void run(unconfirmed)}>Retry count plan request</button>
    </div>}
    {canWrite && <form onSubmit={(event) => {
      event.preventDefault()
      if (!locked && selected.length) void run({ kind: 'create', input: { projectId, requestId: crypto.randomUUID(), blind, note, inventoryIds: [...selected].sort() } })
    }}>
      <fieldset disabled={locked || dirty}>
        <legend>Start count plan</legend>
        <label><input type="checkbox" checked={blind} onChange={(event) => setBlind(event.target.checked)} />Blind count</label>
        <label>Plan note<input value={note} maxLength={500} onChange={(event) => setNote(event.target.value)} /></label>
        {stocks.isLoading && <p>Loading stock records...</p>}
        {stocks.isError && <p role="alert">{errorMessage(stocks.error)}</p>}
        {(stocks.data ?? []).map((stock) => <label key={stock.inventoryId} style={{ display: 'block' }}>
          <input type="checkbox" checked={selected.includes(stock.inventoryId)} disabled={!selected.includes(stock.inventoryId) && selected.length >= 500}
            onChange={(event) => setSelected((previous) => event.target.checked ? [...previous, stock.inventoryId] : previous.filter((id) => id !== stock.inventoryId))} />
          {label(stock.itemId)} · {stock.location ?? 'No location'}{stock.lotId ? ` · LOT ${stock.lotId}` : ''}
        </label>)}
        <button disabled={!selected.length || stocks.isError}>Start plan ({selected.length})</button>
      </fieldset>
    </form>}
    <label>Open count plan<select value={active ?? ''} disabled={locked || dirty} onChange={(event) => { setActive(event.target.value || null); setMessage(null); setDrafts({}); setReviewBaseline(false) }}>
      <option value="">Choose a plan</option>
      {(plans.data ?? []).map((entry) => <option key={entry.planId} value={entry.planId}>
        {entry.note || entry.planId} · {entry.status}{entry.assignedTo && entry.assignedTo === me ? ' · yours' : ''}
      </option>)}
    </select></label>
    {plan && <div role="region" aria-label="Selected count plan">
      <h3>{plan.note || plan.planId}</h3>
      <p>Status: {plan.status}. {plan.blind ? 'Blind count: baseline quantities are hidden.' : 'Only the project owner may view baseline quantities.'}</p>
      {/* Who should count it (P9); they hear of it in the app. */}
      {canWrite && plan.status !== 'submitted'
        ? <label>Assigned to<select value={plan.assignedTo ?? ''} disabled={locked}
          onChange={(event) => void run({ kind: 'assign', planId: plan.planId, input: { assignedTo: event.target.value || null } })}>
          <option value="">No one</option>
          {counters.map((member) => <option key={member.userId} value={member.userId}>{member.userId}{member.userId === me ? ' (me)' : ''}</option>)}
        </select></label>
        : <p>Assigned to {plan.assignedTo ?? 'no one'}.</p>}
      {plan.blind && role === 'owner' && <label><input type="checkbox" checked={reviewBaseline} onChange={(event) => setReviewBaseline(event.target.checked)} />Review baseline quantities (owner)</label>}
      {dirty && <p>Save each changed measurement before submitting. <button disabled={locked} onClick={() => setDrafts({})}>Discard unsaved measurements</button></p>}
      {plan.lines.map((line) => <CountPlanRow key={`${plan.planId}:${line.lineId}:${line.entryVersion}`} line={line}
        label={label(line.itemId)} baselineVisible={role === 'owner' && (!plan.blind || reviewBaseline)} disabled={locked || !canWrite || plan.status === 'submitted'}
        value={drafts[line.lineId]?.value ?? (line.countedQuantity === null ? '' : String(line.countedQuantity))}
        change={(value) => setDrafts((previous) => ({ ...previous, [line.lineId]: { value, version: previous[line.lineId]?.version ?? line.entryVersion } }))}
        record={(quantity) => void run({ kind: 'record', planId: plan.planId, lineId: line.lineId, input: { expectedEntryVersion: drafts[line.lineId]?.version ?? line.entryVersion, countedQuantity: quantity } })}
        recount={() => void run({ kind: 'recount', planId: plan.planId, lineId: line.lineId })} />)}
      {canWrite && plan.status !== 'submitted' && <button disabled={locked || dirty || plan.lines.some((line) => line.countedQuantity === null || line.requiresRecount)}
        onClick={() => void run({ kind: 'submit', planId: plan.planId })}>Submit count plan</button>}
      {plan.status === 'submitted' && <p>Submitted by {plan.submittedBy}. Count reference: {plan.countId}</p>}
    </div>}
  </section>
}

function CountPlanRow({ line, label, baselineVisible, disabled, value, change, record, recount }: {
  line: CountPlanLine; label: string; baselineVisible: boolean; disabled: boolean
  value: string; change: (value: string) => void; record: (quantity: number) => void; recount: () => void
}) {
  const [error, setError] = useState<string | null>(null)
  return <form aria-label={`Count ${label}`} onSubmit={(event) => {
    event.preventDefault()
    if (disabled || line.requiresRecount) return
    try { const quantity = countPlanQuantity(value); setError(null); record(quantity) }
    catch (failure) { setError(errorMessage(failure)) }
  }} style={{ borderTop: '1px solid var(--border)', padding: '12px 0', display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center' }}>
    <span>{label} · {line.location ?? 'No location'}{line.lotId ? ` · LOT ${line.lotId}` : ''}</span>
    {baselineVisible && line.baselineQuantity !== null && <span>Original baseline: {formatQty(line.baselineQuantity)} · Checkpoint: {formatQty(line.checkpointQuantity)}</span>}
    {line.requiresRecount && <strong>Stock changed. Recount required.</strong>}
    <label>Counted quantity<input type="text" inputMode="decimal" value={value} disabled={disabled || line.requiresRecount}
      onChange={(event) => change(event.target.value)} /></label>
    <button disabled={disabled || line.requiresRecount}>Save measurement</button>
    {line.requiresRecount && <button type="button" disabled={disabled} onClick={recount}>Start recount</button>}
    {line.countedQuantity !== null && <span>Saved: {formatQty(line.countedQuantity)} · {line.countedBy}</span>}
    {error && <span role="alert">{error}</span>}
  </form>
}
