import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { useProjectTimeZone } from '../../../entities/project/api/useProjectTimeZone'
import { usePurchaseOrdersQuery } from '../../../entities/purchasing/api/usePurchaseOrders'
import { useDefectsQuery } from '../../../entities/quality/api/useQuality'
import {
  useNonconformitiesQuery,
  useNonconformityMutations,
  type NcrActionType,
  type NcrDisposition,
  type NcrStatus,
  type NcrVerification,
  type NonconformityDto,
} from '../../../entities/quality/api/useNonconformities'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { formatQty } from '../../../shared/lib/formatQty'
import type { DefectSeverity } from '../../../shared/types/api'
import { zoneDay } from '../model/bomModel'
import { lotOrigins, ncrReturnText, ncrReturns, originOrders, originSuppliers } from '../model/purchaseOrderModel'
import {
  ACTION_TYPES,
  ACTION_TYPE_LABELS,
  CHECK_AFTER_DAYS,
  DISPOSITIONS,
  DISPOSITION_LABELS,
  EMPTY_ACTION_FORM,
  EMPTY_NCR_FORM,
  actionPayload,
  actionProgress,
  addDays,
  checkDate,
  checkDueText,
  closeBlockers,
  followUpForm,
  ncrPayload,
  subjectText,
  verificationPayload,
} from '../model/nonconformityModel'

const STATUS_FILTERS: { value: NcrStatus | ''; label: string }[] = [
  { value: 'open', label: 'Open' },
  { value: '', label: 'All' },
  { value: 'closed', label: 'Closed' },
  { value: 'cancelled', label: 'Cancelled' },
]

/**
 * Nonconformity reports (docs/domain/nonconformity.md): raise one from open defects, record the root cause and what
 * happens to the product, track corrective actions, and close it once they are done.
 */
export function NonconformityPanel({ projectId }: { projectId: string }) {
  const [status, setStatus] = useState<NcrStatus | ''>('open')
  const listQuery = useNonconformitiesQuery(projectId, status || null)
  const openDefectsQuery = useDefectsQuery(projectId, {}, true)
  const { create } = useNonconformityMutations(projectId)
  const [raising, setRaising] = useState(false)
  const [form, setForm] = useState(EMPTY_NCR_FORM)
  const [formError, setFormError] = useState<string | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const ncrs = listQuery.data ?? []
  const selected = ncrs.find((ncr) => ncr.nonconformityId === selectedId) ?? null
  const raiseError = formError ?? (create.isError ? errorMessage(create.error) : null)

  function raise(event: FormEvent) {
    event.preventDefault()
    const payload = ncrPayload(form)
    setFormError(payload.error)
    if (!payload.input) return
    create.mutate(payload.input, {
      onSuccess: (ncr) => {
        setRaising(false)
        setForm(EMPTY_NCR_FORM)
        setStatus('open')
        setSelectedId(ncr.nonconformityId)
      },
    })
  }

  return (
    <section aria-label="Nonconformities" style={{ marginTop: 24 }}>
      <h3 style={{ marginBottom: 4 }}>Nonconformities</h3>
      <p className="inspector-hint" style={{ marginTop: 0 }}>
        Gather defects into a report, find the root cause, decide what happens to the product and track the actions until it
        can be closed.
      </p>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', marginBottom: 8, fontSize: 12 }}>
        <select aria-label="Nonconformity status" value={status} onChange={(event) => setStatus(event.target.value as NcrStatus | '')}>
          {STATUS_FILTERS.map((filter) => <option key={filter.label} value={filter.value}>{filter.label}</option>)}
        </select>
        {!raising && <button type="button" onClick={() => { setRaising(true); create.reset() }}>Raise nonconformity</button>}
      </div>
      {raising && (
        <form aria-label="Raise nonconformity" onSubmit={raise} style={{ display: 'grid', gap: 8, fontSize: 13, maxWidth: 520, marginBottom: 12 }}>
          {form.followUp && (
            <p className="inspector-hint" style={{ margin: 0 }}>
              Follow-up to {form.followUp.ncrNo}{form.followUp.about ? `, about ${form.followUp.about}` : ''}.
            </p>
          )}
          <label style={{ display: 'grid', gap: 4 }}>Title *
            <input value={form.title} maxLength={200} onChange={(event) => setForm({ ...form, title: event.target.value })} />
          </label>
          <label style={{ display: 'grid', gap: 4 }}>Severity
            <select value={form.severity} onChange={(event) => setForm({ ...form, severity: event.target.value as DefectSeverity | '' })}>
              <option value="">From the defects</option>
              <option value="minor">Minor</option>
              <option value="major">Major</option>
              <option value="critical">Critical</option>
            </select>
          </label>
          <label style={{ display: 'grid', gap: 4 }}>Description
            <textarea rows={2} value={form.description} maxLength={2000}
              onChange={(event) => setForm({ ...form, description: event.target.value })} />
          </label>
          <fieldset style={{ border: '1px solid var(--border)', padding: 8 }}>
            <legend style={{ fontSize: 12 }}>Open defects to gather</legend>
            {(openDefectsQuery.data ?? []).length === 0 && <span className="inspector-hint">No open defects.</span>}
            {(openDefectsQuery.data ?? []).map((defect) => (
              <label key={defect.defectLogId} style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                <input
                  type="checkbox"
                  checked={form.defectLogIds.includes(defect.defectLogId)}
                  onChange={(event) => setForm({
                    ...form,
                    defectLogIds: event.target.checked
                      ? [...form.defectLogIds, defect.defectLogId]
                      : form.defectLogIds.filter((id) => id !== defect.defectLogId),
                  })}
                />
                {defect.defectType} · {defect.severity} · {formatQty(defect.quantity)} {defect.itemCode ?? ''}{defect.lotNo ? ` · LOT ${defect.lotNo}` : ''}
              </label>
            ))}
          </fieldset>
          {raiseError && <p role="alert" style={{ color: '#dc2626', margin: 0 }}>{raiseError}</p>}
          <div style={{ display: 'flex', gap: 8 }}>
            <button type="submit" disabled={create.isPending}>{create.isPending ? 'Saving...' : 'Raise'}</button>
            <button type="button" onClick={() => { setRaising(false); setFormError(null) }}>Cancel</button>
          </div>
        </form>
      )}
      {listQuery.isError && <p role="alert">{errorMessage(listQuery.error)}</p>}
      {ncrs.length === 0 && !listQuery.isPending && <p className="inspector-hint">No nonconformities{status ? ` ${status}` : ''}.</p>}
      {ncrs.length > 0 && (
        <table aria-label="Nonconformity list" style={{ width: '100%', textAlign: 'left', fontSize: 13, marginBottom: 12 }}>
          <thead><tr><th>No.</th><th>Title</th><th>Severity</th><th>About</th><th>Actions</th><th>Status</th><th /></tr></thead>
          <tbody>{ncrs.map((ncr) => (
            <tr key={ncr.nonconformityId} style={{ fontWeight: ncr.nonconformityId === selectedId ? 600 : undefined }}>
              <td>{ncr.ncrNo}</td>
              <td>{ncr.title}</td>
              <td>{ncr.severity}</td>
              <td>{subjectText(ncr) || '-'}</td>
              <td>
                {actionProgress(ncr)}
                {ncr.overdueActions > 0 && <span style={{ color: '#b91c1c' }}> · {ncr.overdueActions} overdue</span>}
              </td>
              <td>
                {ncr.status}
                {ncr.status === 'closed' && !ncr.verificationResult && (checkDueText(ncr)
                  ? <span className={ncr.verificationOverdue ? undefined : 'inspector-hint'}
                    style={{ color: ncr.verificationOverdue ? '#b91c1c' : undefined }}> · {checkDueText(ncr)}</span>
                  : <span className="inspector-hint"> · not checked</span>)}
                {ncr.verificationResult === 'not_effective' && <span style={{ color: '#b91c1c' }}> · not effective</span>}
              </td>
              <td><button type="button" onClick={() => setSelectedId(ncr.nonconformityId === selectedId ? null : ncr.nonconformityId)}>
                {ncr.nonconformityId === selectedId ? 'Hide' : 'Open'}
              </button></td>
            </tr>
          ))}</tbody>
        </table>
      )}
      {selected && <NonconformityDetail key={selected.nonconformityId} projectId={projectId} ncr={selected}
        onFollowUp={(ncr) => {
          create.reset()
          setFormError(null)
          setForm(followUpForm(ncr))
          setRaising(true)
        }} />}
    </section>
  )
}

function NonconformityDetail({ projectId, ncr, onFollowUp }: {
  projectId: string
  ncr: NonconformityDto
  /** Raise a new nonconformity for what this one's actions did not fix. */
  onFollowUp: (ncr: NonconformityDto) => void
}) {
  const { update, addAction, finishAction, close, cancel, verify, setVerificationDue } = useNonconformityMutations(projectId)
  const today = zoneDay(useProjectTimeZone(projectId).query.data?.timeZone)
  const purchaseOrders = usePurchaseOrdersQuery(projectId).data ?? []
  // Returned to the supplier: the purchase orders the LOT came on say who that is (docs/domain/purchasing.md PO13).
  const origins = ncr.lotNo && ncr.itemId ? lotOrigins(purchaseOrders, { lotNo: ncr.lotNo, itemId: ncr.itemId }) : []
  const supplier = originSuppliers(origins)
  // It goes back from the order it came on (PO26, PO37).
  const backFrom = originOrders(origins, purchaseOrders)
  // What went back naming this NCR (PO43).
  const sentBack = ncrReturns(purchaseOrders, ncr.nonconformityId)
  // Null until changed: the suggested day follows the project's today once its time zone is known.
  const [checkBy, setCheckBy] = useState<string | null>(null)
  const closeCheckBy = checkBy ?? addDays(today, CHECK_AFTER_DAYS)
  const [dueDraft, setDueDraft] = useState(ncr.verificationDueDate ?? '')
  const [checkDateError, setCheckDateError] = useState<string | null>(null)
  const [verification, setVerification] = useState<NcrVerification | ''>('')
  const [verificationNote, setVerificationNote] = useState('')
  const [verificationError, setVerificationError] = useState<string | null>(null)
  const [rootCause, setRootCause] = useState(ncr.rootCause ?? '')
  const [disposition, setDisposition] = useState<NcrDisposition>(ncr.disposition)
  const [actionForm, setActionForm] = useState(EMPTY_ACTION_FORM)
  const [actionError, setActionError] = useState<string | null>(null)
  const [finishing, setFinishing] = useState<{ actionId: string; done: boolean } | null>(null)
  const [finishNote, setFinishNote] = useState('')
  const [closeNote, setCloseNote] = useState('')
  const open = ncr.status === 'open'
  const blockers = closeBlockers(ncr)
  const failure = [update, addAction, finishAction, close, cancel, verify, setVerificationDue].find((mutation) => mutation.isError)

  function closeWithCheckDate() {
    const checked = checkDate(closeCheckBy, today)
    setCheckDateError(checked.error)
    if (!checked.error) close.mutate({ id: ncr.nonconformityId, note: closeNote, verificationDueDate: checked.day })
  }

  function saveCheckDate(event: FormEvent) {
    event.preventDefault()
    const checked = checkDate(dueDraft, today)
    setCheckDateError(checked.error)
    if (!checked.error) setVerificationDue.mutate({ id: ncr.nonconformityId, verificationDueDate: checked.day })
  }

  function saveVerification(event: FormEvent) {
    event.preventDefault()
    const payload = verificationPayload(verification, verificationNote)
    setVerificationError(payload.error)
    if (payload.input) verify.mutate({ id: ncr.nonconformityId, ...payload.input })
  }

  function saveAction(event: FormEvent) {
    event.preventDefault()
    const payload = actionPayload(actionForm)
    setActionError(payload.error)
    if (payload.input) addAction.mutate({ id: ncr.nonconformityId, input: payload.input }, { onSuccess: () => setActionForm(EMPTY_ACTION_FORM) })
  }

  function saveFinish(event: FormEvent) {
    event.preventDefault()
    if (!finishing) return
    finishAction.mutate(
      { id: ncr.nonconformityId, actionId: finishing.actionId, done: finishing.done, note: finishNote },
      { onSuccess: () => { setFinishing(null); setFinishNote('') } },
    )
  }

  return (
    <article aria-label={`Nonconformity ${ncr.ncrNo}`} style={{ border: '1px solid var(--border)', borderRadius: 8, padding: 12, fontSize: 13 }}>
      <h4 style={{ margin: '0 0 4px' }}>{ncr.ncrNo} · {ncr.title} <span className="inspector-hint">({ncr.status})</span></h4>
      <p className="inspector-hint" style={{ margin: '0 0 8px' }}>
        {ncr.severity}{subjectText(ncr) ? ` · ${subjectText(ncr)}` : ''} · raised by {ncr.raisedBy} {new Date(ncr.raisedAt).toLocaleString()}
        {ncr.closedAt && ` · ${ncr.status} by ${ncr.closedBy} ${new Date(ncr.closedAt).toLocaleString()}`}
      </p>
      {ncr.description && <p style={{ margin: '0 0 8px' }}>{ncr.description}</p>}
      {ncr.closureNote && <p style={{ margin: '0 0 8px' }}>Note: {ncr.closureNote}</p>}
      {ncr.status === 'closed' && (ncr.verificationResult ? (
        <section aria-label="Effectiveness" style={{ margin: '0 0 8px' }}>
          <strong style={{ color: ncr.verificationResult === 'effective' ? '#047857' : '#b91c1c' }}>
            {ncr.verificationResult === 'effective' ? 'The actions worked' : 'The actions did not work'}
          </strong>
          <span className="inspector-hint">
            {' '}· checked by {ncr.verifiedBy}{ncr.verifiedAt ? ` ${new Date(ncr.verifiedAt).toLocaleString()}` : ''}
          </span>
          {ncr.verificationNote && <div>{ncr.verificationNote}</div>}
          {ncr.verificationResult === 'not_effective' && (
            <button type="button" style={{ marginTop: 4 }} onClick={() => onFollowUp(ncr)}>Raise a follow-up</button>
          )}
        </section>
      ) : (<>
        <form aria-label="Check date" onSubmit={saveCheckDate}
          style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center', margin: '0 0 8px' }}>
          <span style={{ color: ncr.verificationOverdue ? '#b91c1c' : undefined }}>
            {!ncr.verificationDueDate ? 'No check date'
              : ncr.verificationOverdue ? `The check was due ${ncr.verificationDueDate}` : `Check by ${ncr.verificationDueDate}`}
          </span>
          <input type="date" aria-label="New check date" value={dueDraft} onChange={(event) => setDueDraft(event.target.value)} />
          <button type="submit" disabled={setVerificationDue.isPending || dueDraft === (ncr.verificationDueDate ?? '')}>
            {dueDraft ? 'Save check date' : 'Clear check date'}
          </button>
          {checkDateError && <p role="alert" style={{ color: '#dc2626', margin: 0, flexBasis: '100%' }}>{checkDateError}</p>}
        </form>
        <form aria-label="Check the actions" onSubmit={saveVerification}
          style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center', margin: '0 0 8px' }}>
          <span>Did the actions work?</span>
          <label><input type="radio" name={`verify-${ncr.nonconformityId}`} checked={verification === 'effective'}
            onChange={() => setVerification('effective')} /> Yes</label>
          <label><input type="radio" name={`verify-${ncr.nonconformityId}`} checked={verification === 'not_effective'}
            onChange={() => setVerification('not_effective')} /> No</label>
          <input aria-label="What was checked" value={verificationNote} maxLength={1000} style={{ minWidth: 260 }}
            placeholder="What was checked, or what still goes wrong" onChange={(event) => setVerificationNote(event.target.value)} />
          <button type="submit" disabled={verify.isPending}>{verify.isPending ? 'Saving...' : 'Record check'}</button>
          {verificationError && <p role="alert" style={{ color: '#dc2626', margin: 0, flexBasis: '100%' }}>{verificationError}</p>}
        </form>
      </>))}

      {ncr.defects.length > 0 && (
        <ul aria-label="Gathered defects" style={{ margin: '0 0 8px', paddingLeft: 18 }}>
          {ncr.defects.map((defect) => (
            <li key={defect.defectLogId}>
              {defect.defectType} · {defect.severity} · {formatQty(defect.quantity)} {defect.itemCode ?? ''}
              {defect.lotNo ? ` · LOT ${defect.lotNo}` : ''}{defect.resolved ? ' · resolved' : ''}
            </li>
          ))}
        </ul>
      )}

      <div style={{ display: 'grid', gap: 8, maxWidth: 520, marginBottom: 8 }}>
        <label style={{ display: 'grid', gap: 4 }}>Root cause
          <textarea rows={2} value={rootCause} maxLength={2000} disabled={!open} onChange={(event) => setRootCause(event.target.value)} />
        </label>
        <label style={{ display: 'grid', gap: 4 }}>Disposition
          <select value={disposition} disabled={!open} onChange={(event) => setDisposition(event.target.value as NcrDisposition)}>
            {DISPOSITIONS.map((value) => <option key={value} value={value}>{DISPOSITION_LABELS[value]}</option>)}
          </select>
        </label>
        {disposition === 'return_to_supplier' && (
          <p role="note" aria-label="Supplier" className="inspector-hint" style={{ margin: 0 }}>
            {supplier ? `LOT ${ncr.lotNo} came from ${supplier}.` : 'No purchase order brought this in; name the supplier in an action.'}
          </p>
        )}
        {disposition === 'return_to_supplier' && backFrom.length > 0 && (
          <p className="inspector-hint" style={{ margin: 0 }}>
            Send it back from{' '}
            {backFrom.map((one, index) => <span key={one.purchaseOrderId}>
              {index > 0 ? ', ' : ''}
              <Link to={`/projects/${encodeURIComponent(projectId)}/inventory?tab=purchasing&order=${encodeURIComponent(one.purchaseOrderId)}&ncr=${encodeURIComponent(ncr.nonconformityId)}`}>
                {one.orderNumber}
              </Link>
            </span>)}
          </p>
        )}
        {sentBack.length > 0 && <ul aria-label="Sent back" style={{ margin: 0, paddingLeft: 18, fontSize: 12 }}>
          {sentBack.map(({ order, back }) => <li key={back.returnId}>{ncrReturnText(order, back)}</li>)}
        </ul>}
        {open && (
          <div>
            <button type="button" disabled={update.isPending}
              onClick={() => update.mutate({ id: ncr.nonconformityId, input: { rootCause, disposition } })}>
              {update.isPending ? 'Saving...' : 'Save cause and disposition'}
            </button>
          </div>
        )}
      </div>

      <table aria-label="Corrective actions" style={{ width: '100%', textAlign: 'left', marginBottom: 8 }}>
        <thead><tr><th>#</th><th>Kind</th><th>Action</th><th>Owner</th><th>Due</th><th>Status</th><th /></tr></thead>
        <tbody>
          {ncr.actions.length === 0 && <tr><td colSpan={7} className="inspector-hint">No actions yet.</td></tr>}
          {ncr.actions.map((action) => (
            <tr key={action.correctiveActionId}>
              <td>{action.actionNo}</td>
              <td>{action.actionType}</td>
              <td>{action.description}{action.resultNote && <div className="inspector-hint">{action.resultNote}</div>}</td>
              <td>{action.ownerId ?? '-'}</td>
              <td style={{ color: action.overdue ? '#b91c1c' : undefined }}>{action.dueDate ?? '-'}{action.overdue && ' (overdue)'}</td>
              <td>{action.status}</td>
              <td style={{ whiteSpace: 'nowrap' }}>
                {open && action.status === 'open' && (
                  <>
                    <button type="button" onClick={() => { setFinishing({ actionId: action.correctiveActionId, done: true }); setFinishNote('') }}>Done</button>{' '}
                    <button type="button" onClick={() => { setFinishing({ actionId: action.correctiveActionId, done: false }); setFinishNote('') }}>Cancel</button>
                  </>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {finishing && (
        <form aria-label="Finish action" onSubmit={saveFinish} style={{ display: 'flex', gap: 8, marginBottom: 8, flexWrap: 'wrap' }}>
          <input aria-label={finishing.done ? 'What was done' : 'Why cancel'} value={finishNote} maxLength={1000} style={{ minWidth: 280 }}
            placeholder={finishing.done ? 'What was done' : 'Why it is cancelled'} onChange={(event) => setFinishNote(event.target.value)} />
          <button type="submit" disabled={finishAction.isPending}>{finishing.done ? 'Mark done' : 'Cancel action'}</button>
          <button type="button" onClick={() => setFinishing(null)}>Back</button>
        </form>
      )}

      {open && (
        <form aria-label="Add action" onSubmit={saveAction} style={{ display: 'grid', gridTemplateColumns: '180px minmax(0, 1fr) 120px 140px auto', gap: 6, alignItems: 'end', marginBottom: 8 }}>
          <label style={{ display: 'grid', gap: 4 }}>Kind
            <select value={actionForm.actionType} onChange={(event) => setActionForm({ ...actionForm, actionType: event.target.value as NcrActionType })}>
              {ACTION_TYPES.map((type) => <option key={type} value={type}>{ACTION_TYPE_LABELS[type]}</option>)}
            </select>
          </label>
          <label style={{ display: 'grid', gap: 4 }}>Action
            <input value={actionForm.description} maxLength={1000} onChange={(event) => setActionForm({ ...actionForm, description: event.target.value })} />
          </label>
          <label style={{ display: 'grid', gap: 4 }}>Owner
            <input value={actionForm.ownerId} maxLength={50} placeholder="user id" onChange={(event) => setActionForm({ ...actionForm, ownerId: event.target.value })} />
          </label>
          <label style={{ display: 'grid', gap: 4 }}>Due
            <input type="date" value={actionForm.dueDate} onChange={(event) => setActionForm({ ...actionForm, dueDate: event.target.value })} />
          </label>
          <button type="submit" disabled={addAction.isPending}>Add action</button>
        </form>
      )}
      {actionError && <p role="alert" style={{ color: '#dc2626', margin: '0 0 8px' }}>{actionError}</p>}

      {open && (
        <div style={{ display: 'grid', gap: 6, maxWidth: 520 }}>
          {blockers.length > 0 && (
            <ul aria-label="Before closing" className="inspector-hint" style={{ margin: 0, paddingLeft: 18 }}>
              {blockers.map((blocker) => <li key={blocker}>{blocker}</li>)}
            </ul>
          )}
          <input aria-label="Closing note" value={closeNote} maxLength={1000} placeholder="How it was verified, or why it is cancelled"
            onChange={(event) => setCloseNote(event.target.value)} />
          <label style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>Check the actions by
            <input type="date" value={closeCheckBy} onChange={(event) => setCheckBy(event.target.value)} />
            <span className="inspector-hint">Empty: no check date.</span>
          </label>
          {checkDateError && <p role="alert" style={{ color: '#dc2626', margin: 0 }}>{checkDateError}</p>}
          <div style={{ display: 'flex', gap: 8 }}>
            <button type="button" disabled={blockers.length > 0 || close.isPending}
              onClick={closeWithCheckDate}>Close nonconformity</button>
            <button type="button" disabled={!closeNote.trim() || cancel.isPending}
              onClick={() => cancel.mutate({ id: ncr.nonconformityId, note: closeNote })}>Cancel nonconformity</button>
          </div>
        </div>
      )}
      {failure && <p role="alert" style={{ color: '#dc2626', marginBottom: 0 }}>{errorMessage(failure.error)}</p>}
    </article>
  )
}
