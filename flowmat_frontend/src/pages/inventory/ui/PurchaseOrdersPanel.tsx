import { useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  usePurchaseOrderMutations,
  usePurchaseOrdersQuery,
  type PurchaseOrderDto,
  type PurchaseOrderStatus,
  type PurchaseReturnDto,
} from '../../../entities/purchasing/api/usePurchaseOrders'
import { useSupplierItemsQuery, useSuppliersQuery } from '../../../entities/purchasing/api/useSuppliers'
import { useUpdateItemMutation } from '../../../entities/catalog/api/useUpdateItemMutation'
import { useCurrentUserQuery } from '../../../entities/auth/api/useCurrentUserQuery'
import { useInventoriesQuery } from '../../../entities/inventory/api/useInventoriesQuery'
import { useIsProjectOwner } from '../../../entities/project/api/useIsProjectOwner'
import { useProjectTimeZone } from '../../../entities/project/api/useProjectTimeZone'
import { useInspectionStandardsQuery } from '../../../entities/quality/api/useInspectionStandards'
import { useNonconformitiesQuery } from '../../../entities/quality/api/useNonconformities'
import { receiptChecklist } from '../../../entities/quality/model/standardModel'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { formatQty } from '../../../shared/lib/formatQty'
import { newRequestId } from '../../../shared/lib/requestId'
import type { ItemDto } from '../../../shared/types/api'
import { projectToday } from '../model/equipmentScheduleModel'
import { pickableItems } from '../model/itemStatusModel'
import {
  EMPTY_LINE,
  EMPTY_ORDER_FORM,
  PURCHASE_STATUSES,
  approvalNote,
  approvalState,
  approvedText,
  canApprove,
  costSuggestion,
  creditChangeText,
  creditForm,
  creditPayload,
  creditText,
  dateChangeText,
  datesForm,
  datesPayload,
  isLate,
  linePriceText,
  lineText,
  ncrChoiceText,
  orderActions,
  orderCreditText,
  orderForm,
  orderPayload,
  orderSheetHtml,
  orderValueText,
  owedQuantity,
  receiptLot,
  receiptText,
  receiveForm,
  receivePayload,
  returnChoices,
  returnForm,
  returnNcrChoices,
  returnPayload,
  returnText,
  stockChoiceText,
  withdrawPayload,
  type CreditForm,
  type DatesForm,
  type OrderForm,
  type OrderLineForm,
  type ReceiveLineForm,
  type ReturnForm,
} from '../model/purchaseOrderModel'
import { findSupplier, leadTimeDate, leadTimeText, supplierChoices, supplierCodesFor, supplierProblem } from '../model/supplierModel'
import { LOCATION_OPTIONS_ID, LocationOptions } from './LocationOptions'
import { PurchaseApprovalLimit } from './PurchaseApprovalLimit'
import { ReceivedLotChecks } from './ReceivedLotChecks'

/**
 * Purchase orders (docs/architecture/adr/ADR-027-purchasing.md, docs/domain/purchasing.md): a draft lists what to buy from a
 * supplier, ordering fixes it, and what arrives is received into stock line by line. The rest of a partly received order can
 * be closed; one with nothing received is cancelled. An order past its expected date is marked late, a LOT received on it
 * opens its receipt checks, and suppliers already used are suggested (PO10-PO11). Once the project lists suppliers, the listed
 * active ones are offered and a supplier's lead time suggests the expected date (PO20-PO21).
 */
export function PurchaseOrdersPanel({ projectId, items }: { projectId: string; items: ItemDto[] }) {
  const query = usePurchaseOrdersQuery(projectId)
  const { create, update, order, cancel, close, receive, changeDates, giveBack, approve, credit, changeCredit } = usePurchaseOrderMutations(projectId)
  const owner = useIsProjectOwner(projectId)
  const me = useCurrentUserQuery().data?.userId
  const [status, setStatus] = useState<'' | PurchaseOrderStatus>('')
  const [searchParams] = useSearchParams()
  // Opened from elsewhere, such as a nonconformity to send back (PO37), with that order chosen.
  const [selectedId, setSelectedId] = useState<string | null>(() => searchParams.get('order'))
  // A return can name the NCR that decided it; the NCR's link names it in the address (PO42).
  const ncrParam = searchParams.get('ncr')
  const allNcrs = useNonconformitiesQuery(projectId, null).data ?? []
  const ncrChoices = returnNcrChoices(allNcrs)
  const linkedNcr = ncrParam ? allNcrs.find((one) => one.nonconformityId === ncrParam) ?? null : null
  const linkedNcrId = linkedNcr && ncrChoices.includes(linkedNcr) ? linkedNcr.nonconformityId : ''
  const [editing, setEditing] = useState<PurchaseOrderDto | null>(null)
  const [form, setForm] = useState<OrderForm>(EMPTY_ORDER_FORM)
  const [formError, setFormError] = useState<string | null>(null)
  const [receiving, setReceiving] = useState<ReceiveLineForm[] | null>(null)
  const [receiveError, setReceiveError] = useState<string | null>(null)
  const [requestId, setRequestId] = useState(newRequestId)
  const [checking, setChecking] = useState<{ lotId: string; lotNo: string; itemId: string } | null>(null)
  const stock = useInventoriesQuery(projectId).data ?? []
  const standards = useInspectionStandardsQuery(projectId).data ?? []
  const today = projectToday(useProjectTimeZone(projectId).query.data?.timeZone, new Date())
  const all = query.data ?? []
  const shown = status ? all.filter((one) => one.status === status) : all
  const selected = all.find((one) => one.purchaseOrderId === selectedId) ?? null
  const actions = selected ? orderActions(selected) : null
  const approval = selected ? approvalState(selected) : 'none'
  const returnable = selected ? returnChoices(selected, stock) : []
  const codes = new Map(items.map((item) => [item.itemId, item.itemCode]))
  const choices = pickableItems(items)
  const listed = useSuppliersQuery(projectId).data ?? []
  const usualItems = useSupplierItemsQuery(projectId).data ?? []
  const suppliers = supplierChoices(listed, all)
  const chosen = findSupplier(listed, form.supplierName)
  const supplierNote = supplierProblem(listed, form.supplierName)
  const arrival = chosen?.active ? leadTimeDate(today, chosen) : null
  const contact = selected ? findSupplier(listed, selected.supplierName)?.contact : null
  const itemById = new Map(items.map((item) => [item.itemId, item]))
  const updateItem = useUpdateItemMutation()
  const [costNote, setCostNote] = useState<{ error: boolean; text: string } | null>(null)
  const [printError, setPrintError] = useState<string | null>(null)
  const [dates, setDates] = useState<DatesForm | null>(null)
  const [datesError, setDatesError] = useState<string | null>(null)
  const [returning, setReturning] = useState<ReturnForm | null>(null)
  const [returnError, setReturnError] = useState<string | null>(null)
  const [returnRequestId, setReturnRequestId] = useState(newRequestId)
  // The NCR list can arrive after the return form opened; until the user picks, the linked NCR is the choice (PO42).
  const [returnNcrPicked, setReturnNcrPicked] = useState(false)
  const returnNcrId = returning && !returnNcrPicked && !returning.nonconformityId ? linkedNcrId : returning?.nonconformityId ?? ''
  const [crediting, setCrediting] = useState<CreditForm | null>(null)
  const [creditError, setCreditError] = useState<string | null>(null)
  const actionError = order.error ?? cancel.error ?? close.error ?? approve.error
  const saveError = formError
    ?? (create.isError ? errorMessage(create.error) : null)
    ?? (update.isError ? errorMessage(update.error) : null)

  function startNew() {
    setEditing(null)
    setForm(EMPTY_ORDER_FORM)
    setFormError(null)
    create.reset()
    update.reset()
  }

  function select(one: PurchaseOrderDto) {
    setSelectedId(one.purchaseOrderId)
    setReceiving(null)
    setChecking(null)
    setCostNote(null)
    setDates(null)
    setReturning(null)
    setCrediting(null)
    order.reset()
    cancel.reset()
    close.reset()
    approve.reset()
  }

  function setLine(index: number, patch: Partial<OrderLineForm>) {
    setForm((current) => ({ ...current, lines: current.lines.map((line, at) => (at === index ? { ...line, ...patch } : line)) }))
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const payload = orderPayload(projectId, form, (itemId) => codes.get(itemId) ?? itemId)
    setFormError(payload.error)
    if (!payload.input) return
    const onSuccess = (saved: PurchaseOrderDto) => {
      setSelectedId(saved.purchaseOrderId)
      startNew()
    }
    if (editing) update.mutate({ purchaseOrderId: editing.purchaseOrderId, input: payload.input }, { onSuccess })
    else create.mutate(payload.input, { onSuccess })
  }

  function end(kind: 'cancel' | 'close', one: PurchaseOrderDto) {
    const reason = window.prompt(kind === 'cancel' ? `Why is ${one.orderNumber} cancelled?` : `Why is the rest of ${one.orderNumber} not coming?`)
    if (!reason?.trim()) return
    const mutation = kind === 'cancel' ? cancel : close
    mutation.mutate({ purchaseOrderId: one.purchaseOrderId, reason: reason.trim() })
  }

  /** Takes a received line's price as the item's unit cost, by hand (PO15); finished runs keep the costs they had. */
  function takeAsCost(cost: { itemId: string; itemCode: string; from: number | null; to: number }) {
    const was = cost.from == null ? 'no unit cost' : formatQty(cost.from)
    if (!window.confirm(`Set ${cost.itemCode}'s unit cost from ${was} to ${formatQty(cost.to)}? Finished runs keep the costs they had.`)) return
    setCostNote(null)
    updateItem.mutate({ itemId: cost.itemId, projectId, unitCost: cost.to }, {
      onSuccess: () => setCostNote({ error: false, text: `${cost.itemCode}'s unit cost is now ${formatQty(cost.to)}.` }),
      onError: (error) => setCostNote({ error: true, text: errorMessage(error, 'The unit cost was not changed.') }),
    })
  }

  /** The order as a page to print or hand to the supplier, in a new window (PO16). */
  function printOrder(one: PurchaseOrderDto) {
    const sheet = window.open('', '_blank')
    if (!sheet) {
      setPrintError('Allow pop-ups for this site to print the purchase order.')
      return
    }
    setPrintError(null)
    sheet.document.write(orderSheetHtml(one, supplierCodesFor(one.supplierName, usualItems)))
    sheet.document.close()
  }

  /** Stock received on the order goes back to the supplier (PO26), replaced on it unless it is closed (PO46). */
  function startReturn() {
    setReturning(returnForm(returnable, linkedNcrId, selected?.status !== 'closed'))
    setReturnNcrPicked(false)
    setReturnError(null)
    setReturnRequestId(newRequestId())
    giveBack.reset()
  }

  function submitReturn(event: FormEvent) {
    event.preventDefault()
    if (!selected || !returning) return
    const submitted = returning
    const submittedRequestId = returnRequestId
    const payload = returnPayload(returnable, { ...submitted, nonconformityId: returnNcrId }, submittedRequestId)
    setReturnError(payload.error)
    // The same requestId goes again after an unconfirmed answer, so a retry cannot send stock back twice.
    if (payload.input) giveBack.mutate({ purchaseOrderId: selected.purchaseOrderId, input: payload.input }, { onSuccess: () => {
      setReturning((current) => current === submitted ? null : current)
      const nextRequestId = newRequestId()
      setReturnRequestId((current) => current === submittedRequestId ? nextRequestId : current)
    } })
  }

  /** What the supplier credited for a return is kept once (PO47). */
  function startCredit(one: PurchaseReturnDto) {
    if (!selected) return
    setCrediting(creditForm(selected, one))
    setCreditError(null)
    credit.reset()
    changeCredit.reset()
  }

  function submitCredit(event: FormEvent) {
    event.preventDefault()
    if (!selected || !crediting) return
    const payload = creditPayload(crediting)
    setCreditError(payload.error)
    if (!payload.input) return
    const target = { purchaseOrderId: selected.purchaseOrderId, returnId: crediting.returnId }
    // A kept credit changes with its reason (PO52); the same credit sent again after an unconfirmed answer is kept once.
    if (crediting.change) {
      changeCredit.mutate({ ...target, input: { ...payload.input, reason: payload.input.reason ?? '' } }, { onSuccess: () => setCrediting(null) })
    } else credit.mutate({ ...target, input: payload.input }, { onSuccess: () => setCrediting(null) })
  }

  /** A kept credit that was not given after all is withdrawn, so the return is owed again (PO52). */
  function withdrawCredit() {
    if (!selected || !crediting) return
    const payload = withdrawPayload(crediting)
    setCreditError(payload.error)
    if (payload.input) {
      changeCredit.mutate({ purchaseOrderId: selected.purchaseOrderId, returnId: crediting.returnId, input: payload.input },
        { onSuccess: () => setCrediting(null) })
    }
  }

  /** The expected dates of an order being received move with a reason (PO22). */
  function startDates(one: PurchaseOrderDto) {
    setDates(datesForm(one))
    setDatesError(null)
    changeDates.reset()
  }

  function submitDates(event: FormEvent) {
    event.preventDefault()
    if (!selected || !dates) return
    const submitted = dates
    const payload = datesPayload(selected, submitted)
    setDatesError(payload.error)
    if (payload.input) changeDates.mutate({ purchaseOrderId: selected.purchaseOrderId, input: payload.input }, {
      onSuccess: () => setDates((current) => current === submitted ? null : current),
    })
  }

  function startReceive(one: PurchaseOrderDto) {
    setReceiving(receiveForm(one))
    setReceiveError(null)
    setRequestId(newRequestId())
    receive.reset()
  }

  function setReceiveRow(index: number, patch: Partial<ReceiveLineForm>) {
    setReceiving((current) => current && current.map((row, at) => (at === index ? { ...row, ...patch } : row)))
  }

  function submitReceive(event: FormEvent) {
    event.preventDefault()
    if (!selected || !receiving) return
    const submitted = receiving
    const submittedRequestId = requestId
    const payload = receivePayload(selected, submitted, submittedRequestId)
    setReceiveError(payload.error)
    // The same requestId goes again after an unconfirmed answer, so a retry cannot receive twice.
    if (payload.input) receive.mutate({ purchaseOrderId: selected.purchaseOrderId, input: payload.input }, { onSuccess: () => {
      setReceiving((current) => current === submitted ? null : current)
      const nextRequestId = newRequestId()
      setRequestId((current) => current === submittedRequestId ? nextRequestId : current)
    } })
  }

  return <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 320px', gap: 24 }}>
    <section style={{ minWidth: 0, overflowX: 'auto' }}>
      <LocationOptions projectId={projectId} />
      <h2>Purchase orders</h2>
      <p className="inspector-hint">What is bought from suppliers. Ordering fixes the lines; what arrives is received into stock line by line.</p>
      <PurchaseApprovalLimit projectId={projectId} />
      <label style={{ fontSize: 12 }}>Status{' '}
        <select aria-label="Purchase order status" value={status} onChange={(event) => setStatus(event.target.value as '' | PurchaseOrderStatus)}>
          <option value="">all</option>
          {PURCHASE_STATUSES.map((one) => <option key={one} value={one}>{one}</option>)}
        </select>
      </label>
      {query.isPending && <p>Loading purchase orders...</p>}
      {query.isError && <p role="alert">{errorMessage(query.error, 'Could not load the purchase orders.')}</p>}
      {query.isSuccess && shown.length === 0 && <p className="inspector-hint">No purchase orders{status ? ` ${status}` : ''}.</p>}
      {shown.length > 0 && <table aria-label="Purchase orders" style={{ width: '100%', textAlign: 'left' }}>
        <thead><tr><th>Number</th><th>Supplier</th><th>Status</th><th>Lines</th><th>Expected</th></tr></thead>
        <tbody>{shown.map((one) => <tr key={one.purchaseOrderId} style={{ fontWeight: one.purchaseOrderId === selectedId ? 600 : 400 }}>
          <td><button type="button" onClick={() => select(one)}>{one.orderNumber}</button></td>
          <td>{one.supplierName}</td>
          <td>{one.status}</td>
          <td>{one.lines.length}</td>
          <td>{one.expectedDate ?? '—'}{isLate(one, today) && <span style={{ color: '#b45309' }}> · late</span>}</td>
        </tr>)}</tbody>
      </table>}

      {selected && actions && <section aria-label={`Purchase order ${selected.orderNumber}`} style={{ marginTop: 16 }}>
        <h3>{selected.orderNumber} · {selected.supplierName} · {selected.status}</h3>
        {contact && <p className="inspector-hint">Contact: {contact}</p>}
        {selected.deliverTo && <p className="inspector-hint">Deliver to {selected.deliverTo}</p>}
        {approval === 'needed' && <p role="note" style={{ color: '#b45309', fontSize: 12 }}>{approvalNote(selected)}</p>}
        {approval === 'approved' && <p className="inspector-hint">{approvedText(selected)}</p>}
        {selected.endReason && <p className="inspector-hint">{selected.status} by {selected.endedBy}: {selected.endReason}</p>}
        {isLate(selected, today) && <p role="note" style={{ color: '#b45309', fontSize: 12 }}>
          Expected {selected.expectedDate}; not everything has arrived.
        </p>}
        <ul aria-label="Order lines" style={{ margin: '4px 0', paddingLeft: 18 }}>
          {selected.lines.map((line) => {
            const cost = costSuggestion(line, itemById.get(line.itemId))
            return <li key={line.lineId}>{lineText(line)}{linePriceText(line)}
              {cost && <>{' '}
                <button type="button" disabled={updateItem.isPending} aria-label={`Use ${formatQty(cost.to)} as ${cost.itemCode}'s unit cost`}
                  onClick={() => takeAsCost(cost)}>Use as unit cost</button>
              </>}
            </li>
          })}
        </ul>
        {costNote && <p role={costNote.error ? 'alert' : 'status'} style={{ margin: '0 0 6px', fontSize: 12 }}>{costNote.text}</p>}
        {orderValueText(selected) && <p aria-label="Order value" style={{ margin: '0 0 6px', fontSize: 12 }}>{orderValueText(selected)}</p>}
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          {actions.edit && <button type="button" onClick={() => { setEditing(selected); setForm(orderForm(selected)); setFormError(null) }}>Edit</button>}
          {approval === 'needed' && canApprove(selected, me, owner) && <button type="button" disabled={approve.isPending}
            onClick={() => approve.mutate(selected.purchaseOrderId)}>Approve</button>}
          {actions.order && approval !== 'needed' && <button type="button" disabled={order.isPending}
            onClick={() => order.mutate(selected.purchaseOrderId)}>Order</button>}
          {actions.receive && <button type="button" onClick={() => startReceive(selected)}>Receive</button>}
          {actions.receive && <button type="button" onClick={() => startDates(selected)}>Change dates</button>}
          {returnable.length > 0 && <button type="button" onClick={startReturn}>Return to supplier</button>}
          {actions.close && <button type="button" disabled={close.isPending} onClick={() => end('close', selected)}>Close</button>}
          {actions.cancel && <button type="button" disabled={cancel.isPending} onClick={() => end('cancel', selected)}>Cancel order</button>}
          <button type="button" onClick={() => printOrder(selected)}>Print</button>
        </div>
        {printError && <p role="alert">{printError}</p>}
        {actionError && <p role="alert">{errorMessage(actionError)}</p>}
        {receiving && <form aria-label="Receive" onSubmit={submitReceive} style={{ marginTop: 8 }}>
          <table style={{ width: '100%', textAlign: 'left' }}>
            <thead><tr><th>Item</th><th>Receive</th><th>Location</th><th>LOT</th><th>Expiry</th></tr></thead>
            <tbody>{receiving.map((row, index) => {
              const line = selected.lines.find((one) => one.lineId === row.lineId)
              if (!line) return null
              return <tr key={row.lineId}>
                <td>{line.itemCode} ({formatQty(line.remainingQuantity)} left)</td>
                <td><input aria-label={`Receive ${line.itemCode}`} type="number" min="0" step="any" value={row.quantity} style={{ width: 80 }}
                  onChange={(event) => setReceiveRow(index, { quantity: event.target.value })} /></td>
                <td><input aria-label={`Location for ${line.itemCode}`} list={LOCATION_OPTIONS_ID} maxLength={100} value={row.location}
                  onChange={(event) => setReceiveRow(index, { location: event.target.value })} /></td>
                <td>{line.lotTracked ? <input aria-label={`LOT for ${line.itemCode}`} maxLength={100} value={row.lotNo}
                  onChange={(event) => setReceiveRow(index, { lotNo: event.target.value })} /> : '—'}</td>
                <td>{line.lotTracked ? <input aria-label={`Expiry for ${line.itemCode}`} type="date" value={row.expiryDate}
                  onChange={(event) => setReceiveRow(index, { expiryDate: event.target.value })} /> : '—'}</td>
              </tr>
            })}</tbody>
          </table>
          <div style={{ display: 'flex', gap: 8, marginTop: 6 }}>
            <button type="submit" disabled={receive.isPending}>{receive.isPending ? 'Saving...' : 'Save receipt'}</button>
            <button type="button" onClick={() => setReceiving(null)}>Close form</button>
          </div>
          {receiveError && <p role="alert">{receiveError}</p>}
          {receive.isError && <p role="alert">{errorMessage(receive.error, 'The receipt was refused.')}</p>}
        </form>}
        {returning && <form aria-label="Return to supplier" onSubmit={submitReturn} style={{ display: 'grid', gap: 6, marginTop: 8, maxWidth: 420 }}>
          <label>Line<select value={returning.lineId} onChange={(event) => {
            const choice = returnable.find((one) => one.lineId === event.target.value)
            setReturning({ ...returning, lineId: event.target.value, inventoryId: choice?.records[0]?.inventoryId ?? '' })
          }}>
            {returnable.map((one) => <option key={one.lineId} value={one.lineId}>{one.itemCode} ({formatQty(one.open)} can go back)</option>)}
          </select></label>
          <label>From stock<select value={returning.inventoryId} onChange={(event) => setReturning({ ...returning, inventoryId: event.target.value })}>
            {(returnable.find((one) => one.lineId === returning.lineId)?.records ?? [])
              .map((row) => <option key={row.inventoryId} value={row.inventoryId}>{stockChoiceText(row)}</option>)}
          </select></label>
          <label>Return quantity<input type="number" min="0" step="any" value={returning.quantity}
            onChange={(event) => setReturning({ ...returning, quantity: event.target.value })} /></label>
          <label>Return reason<input value={returning.reason} maxLength={500}
            onChange={(event) => setReturning({ ...returning, reason: event.target.value })} /></label>
          {ncrChoices.length > 0 && <label>For NCR<select value={returnNcrId}
            onChange={(event) => { setReturnNcrPicked(true); setReturning({ ...returning, nonconformityId: event.target.value }) }}>
            <option value="">None</option>
            {ncrChoices.map((one) => <option key={one.nonconformityId} value={one.nonconformityId}>{ncrChoiceText(one)}</option>)}
          </select></label>}
          {linkedNcr && !ncrChoices.includes(linkedNcr) && <p role="note" aria-label="NCR" className="inspector-hint" style={{ margin: 0 }}>
            {`${linkedNcr.ncrNo} is not an open NCR with Return to supplier saved, so this return will not name it.`}
          </p>}
          <label><input type="checkbox" checked={returning.replacement} disabled={selected.status === 'closed'}
            onChange={(event) => setReturning({ ...returning, replacement: event.target.checked })} /> Replace on this order</label>
          {selected.status === 'closed'
            ? <p role="note" aria-label="Replacement" className="inspector-hint" style={{ margin: 0 }}>
              {`${selected.orderNumber} is closed, so a replacement is ordered anew.`}
            </p>
            : returning.replacement && <p className="inspector-hint" style={{ margin: 0 }}>
              It is to come again on {selected.orderNumber} and counts as on order until it has.
            </p>}
          <div style={{ display: 'flex', gap: 8 }}>
            <button type="submit" disabled={giveBack.isPending}>{giveBack.isPending ? 'Saving...' : 'Save return'}</button>
            <button type="button" onClick={() => setReturning(null)}>Close return</button>
          </div>
          {returnError && <p role="alert">{returnError}</p>}
          {giveBack.isError && <p role="alert">{errorMessage(giveBack.error, 'The return was refused.')}</p>}
        </form>}
        {(selected.returns ?? []).length > 0 && <ul aria-label="Returns" style={{ margin: '8px 0', paddingLeft: 18, fontSize: 12 }}>
          {(selected.returns ?? []).map((one) => <li key={one.returnId}>{returnText(one)}{creditText(selected, one)}
            {owedQuantity(selected, one) > 0 && <>{' '}
              <button type="button" onClick={() => startCredit(one)}>Record credit</button>
            </>}
            {one.creditedAt && <>{' '}
              <button type="button" onClick={() => startCredit(one)}>Change credit</button>
            </>}
            {(one.creditChanges ?? []).length > 0 && <ul aria-label="Credit changes" style={{ margin: '2px 0', paddingLeft: 16 }}>
              {(one.creditChanges ?? []).map((change) => <li key={change.changedAt}>{creditChangeText(change)}</li>)}
            </ul>}
          </li>)}
        </ul>}
        {orderCreditText(selected) && <p aria-label="Credit due" style={{ margin: '0 0 6px', fontSize: 12 }}>{orderCreditText(selected)}</p>}
        {crediting && <form aria-label={crediting.change ? 'Change credit' : 'Record credit'} onSubmit={submitCredit}
          style={{ display: 'grid', gap: 6, marginTop: 8, maxWidth: 360 }}>
          <label>Amount credited<input type="text" inputMode="decimal" value={crediting.amount}
            onChange={(event) => setCrediting({ ...crediting, amount: event.target.value, amountWarning: undefined })} /></label>
          {crediting.amountWarning && <p role="alert" style={{ margin: 0 }}>{crediting.amountWarning}</p>}
          <label>Credit note<input value={crediting.creditNote} maxLength={100}
            onChange={(event) => setCrediting({ ...crediting, creditNote: event.target.value })} /></label>
          {crediting.change && <label>Change reason<input value={crediting.reason} maxLength={500}
            onChange={(event) => setCrediting({ ...crediting, reason: event.target.value })} /></label>}
          <div style={{ display: 'flex', gap: 8 }}>
            <button type="submit" disabled={credit.isPending || changeCredit.isPending}>
              {credit.isPending || changeCredit.isPending ? 'Saving...' : 'Save credit'}
            </button>
            {(selected.returns ?? []).some((one) => one.returnId === crediting.returnId && one.creditedAt) && <button type="button"
              disabled={changeCredit.isPending} onClick={withdrawCredit}>Withdraw credit</button>}
            <button type="button" onClick={() => setCrediting(null)}>Close credit</button>
          </div>
          {creditError && <p role="alert">{creditError}</p>}
          {credit.isError && <p role="alert">{errorMessage(credit.error, 'The credit was refused.')}</p>}
          {changeCredit.isError && <p role="alert">{errorMessage(changeCredit.error, 'The change was refused.')}</p>}
        </form>}
        {dates && <form aria-label="Change dates" onSubmit={submitDates} style={{ display: 'grid', gap: 6, marginTop: 8, maxWidth: 360 }}>
          <label>Order expected<input type="date" value={dates.expectedDate}
            onChange={(event) => setDates({ ...dates, expectedDate: event.target.value })} /></label>
          {dates.lines.map((row, index) => <label key={row.lineId}>{row.itemCode} expected<input type="date" value={row.expectedDate}
            onChange={(event) => setDates({ ...dates, lines: dates.lines.map((one, at) => (at === index ? { ...one, expectedDate: event.target.value } : one)) })} />
          </label>)}
          <label>Reason<input value={dates.reason} maxLength={500} onChange={(event) => setDates({ ...dates, reason: event.target.value })} /></label>
          <div style={{ display: 'flex', gap: 8 }}>
            <button type="submit" disabled={changeDates.isPending}>{changeDates.isPending ? 'Saving...' : 'Save dates'}</button>
            <button type="button" onClick={() => setDates(null)}>Close dates</button>
          </div>
          {datesError && <p role="alert">{datesError}</p>}
          {changeDates.isError && <p role="alert">{errorMessage(changeDates.error, 'The dates were not changed.')}</p>}
        </form>}
        {(selected.dateChanges ?? []).length > 0 && <ul aria-label="Date changes" style={{ margin: '8px 0', paddingLeft: 18, fontSize: 12 }}>
          {(selected.dateChanges ?? []).map((one) => <li key={one.changeId}>{dateChangeText(one)}</li>)}
        </ul>}
        {selected.receipts.length > 0 && <ul aria-label="Receipts" style={{ margin: '8px 0', paddingLeft: 18 }}>
          {selected.receipts.map((one) => {
            // A LOT with receipt checks to do opens them here, as Add Stock does (docs/domain/inspection-standard.md).
            const lot = receiptLot(one, selected.lines.find((line) => line.lineId === one.lineId), stock)
            return <li key={one.receiptId}>{receiptText(one)}
              {lot && receiptChecklist(standards, lot.itemId, []).length > 0 && <>{' '}
                <button type="button" aria-label={`Receipt checks for LOT ${lot.lotNo}`} onClick={() => setChecking(lot)}>Receipt checks</button>
              </>}
            </li>
          })}
        </ul>}
        {checking && <ReceivedLotChecks key={checking.lotId} projectId={projectId} lot={checking}
          itemLabel={(itemId) => codes.get(itemId) ?? itemId} stock={stock} onClose={() => setChecking(null)} />}
      </section>}
    </section>

    <form onSubmit={submit} aria-label={editing ? `Edit ${editing.orderNumber}` : 'New purchase order'}
      style={{ display: 'grid', gap: 10, alignContent: 'start' }}>
      <h2>{editing ? `Edit ${editing.orderNumber}` : 'New purchase order'}</h2>
      <label>Supplier<input value={form.supplierName} maxLength={100} list="purchase-supplier-options"
        onChange={(event) => setForm({ ...form, supplierName: event.target.value })} /></label>
      <datalist id="purchase-supplier-options">{suppliers.map((one) => <option key={one} value={one} />)}</datalist>
      {supplierNote && <p role="note" style={{ margin: 0, fontSize: 12, color: '#b45309' }}>{supplierNote}</p>}
      {arrival && chosen && arrival !== form.expectedDate && <button type="button" onClick={() => setForm({ ...form, expectedDate: arrival })}>
        Expect by {arrival} (lead time {leadTimeText(chosen.leadTimeDays)})
      </button>}
      <label>Expected<input type="date" value={form.expectedDate} onChange={(event) => setForm({ ...form, expectedDate: event.target.value })} /></label>
      <label>Deliver to<input value={form.deliverTo ?? ''} maxLength={100} list={LOCATION_OPTIONS_ID} placeholder="where it is to arrive"
        onChange={(event) => setForm({ ...form, deliverTo: event.target.value })} /></label>
      <fieldset style={{ display: 'grid', gap: 6 }}>
        <legend>Lines (quantity and price per each item's stock unit)</legend>
        {form.lines.map((line, index) => <div key={index}
          style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 70px 70px auto', gap: 4 }}>
          <select aria-label={`Line ${index + 1} item`} value={line.itemId} onChange={(event) => setLine(index, { itemId: event.target.value })}>
            <option value="">Choose an item</option>
            {choices.map((item) => <option key={item.itemId} value={item.itemId}>{item.itemCode} · {item.itemName}</option>)}
          </select>
          <input aria-label={`Line ${index + 1} quantity`} type="number" min="0" step="any" value={line.quantity}
            onChange={(event) => setLine(index, { quantity: event.target.value })} />
          <input aria-label={`Line ${index + 1} unit price`} type="number" min="0" step="any" placeholder="price" value={line.unitPrice ?? ''}
            onChange={(event) => setLine(index, { unitPrice: event.target.value })} />
          <button type="button" aria-label={`Remove line ${index + 1}`} disabled={form.lines.length === 1}
            onClick={() => setForm((current) => ({ ...current, lines: current.lines.filter((_, at) => at !== index) }))}>×</button>
        </div>)}
        <button type="button" onClick={() => setForm((current) => ({ ...current, lines: [...current.lines, EMPTY_LINE] }))}>Add line</button>
      </fieldset>
      <label>Note<input value={form.note} maxLength={500} onChange={(event) => setForm({ ...form, note: event.target.value })} /></label>
      {saveError && <p role="alert">{saveError}</p>}
      <button type="submit" disabled={create.isPending || update.isPending}>{editing ? 'Save draft' : 'Save as draft'}</button>
      {editing && <button type="button" onClick={startNew}>Stop editing</button>}
    </form>
  </div>
}
