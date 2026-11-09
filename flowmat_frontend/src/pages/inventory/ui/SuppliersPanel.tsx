import { useState, type FormEvent } from 'react'
import { useProjectTimeZone } from '../../../entities/project/api/useProjectTimeZone'
import { usePurchaseOrdersQuery } from '../../../entities/purchasing/api/usePurchaseOrders'
import {
  useSetSupplierItemMutation,
  useSupplierItemsQuery,
  useSupplierMutations,
  useSuppliersQuery,
  type SupplierDto,
} from '../../../entities/purchasing/api/useSuppliers'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { formatQty } from '../../../shared/lib/formatQty'
import type { ItemDto } from '../../../shared/types/api'
import { projectToday } from '../model/equipmentScheduleModel'
import { pickableItems } from '../model/itemStatusModel'
import { typedPrice } from '../model/purchaseOrderModel'
import {
  EMPTY_SUPPLIER_FORM,
  daysBefore,
  itemPrices,
  leadTimeText,
  averageDaysLateText,
  priceHistory,
  pricedItems,
  supplierDelivery,
  supplierForm,
  supplierCredits,
  supplierPayload,
  supplierSpend,
  usualPriceText,
  type SupplierForm,
} from '../model/supplierModel'

/**
 * The suppliers a project buys from (docs/domain/purchasing.md PO18-PO21). Once one is listed, purchase orders name an active
 * supplier from the list; a supplier is switched off, never deleted, and orders keep the name they were given. Below, how each
 * supplier's orders arrived against their dates (PO24), what was spent with each (PO41), and which supplier each item usually
 * comes from (PO29-PO31).
 */
export function SuppliersPanel({ projectId, items }: { projectId: string; items: ItemDto[] }) {
  const query = useSuppliersQuery(projectId)
  const { create, update } = useSupplierMutations(projectId)
  const [editing, setEditing] = useState<SupplierDto | null>(null)
  const [form, setForm] = useState<SupplierForm>(EMPTY_SUPPLIER_FORM)
  const [formError, setFormError] = useState<string | null>(null)
  const all = query.data ?? []
  const timeZone = useProjectTimeZone(projectId).query.data?.timeZone
  const orders = usePurchaseOrdersQuery(projectId).data ?? []
  const today = projectToday(timeZone, new Date())
  // Delivery over a period by when orders came in (PO44); all time unless chosen.
  const [deliveryPeriod, setDeliveryPeriod] = useState(0)
  const deliveredEver = supplierDelivery(orders, timeZone, today)
  const delivery = deliveryPeriod ? supplierDelivery(orders, timeZone, today, daysBefore(today, deliveryPeriod)) : deliveredEver
  // Spend over a period (PO41); the section shows once anything was ordered at all.
  const [period, setPeriod] = useState(90)
  const spentEver = supplierSpend(orders, null, timeZone)
  const credits = supplierCredits(orders)
  const spend = period ? supplierSpend(orders, daysBefore(today, period), timeZone) : spentEver
  const usual = useSupplierItemsQuery(projectId).data ?? []
  // One item's prices across suppliers and over time, from the orders placed (PO49).
  const priced = pricedItems(orders)
  const [priceItem, setPriceItem] = useState('')
  const prices = priceItem ? itemPrices(orders, priceItem, timeZone) : []
  const history = priceItem ? priceHistory(orders, priceItem, timeZone).slice(0, 10) : []
  const usualOf = usual.find((one) => one.itemId === priceItem)
  const setUsual = useSetSupplierItemMutation(projectId)
  const [usualForm, setUsualForm] = useState({ itemId: '', supplierId: '', supplierItemCode: '', unitPrice: '' })
  const [usualError, setUsualError] = useState<string | null>(null)
  const choices = pickableItems(items)
  const saveError = formError
    ?? (create.isError ? errorMessage(create.error) : null)
    ?? (update.isError ? errorMessage(update.error) : null)

  function start(one: SupplierDto | null) {
    setEditing(one)
    setForm(one ? supplierForm(one) : EMPTY_SUPPLIER_FORM)
    setFormError(null)
    create.reset()
    update.reset()
  }

  /** Picks an item and starts from its usual supplier, if it has one. */
  function pickItem(itemId: string) {
    const current = usual.find((one) => one.itemId === itemId)
    setUsualForm({ itemId, supplierId: current?.supplierId ?? '', supplierItemCode: current?.supplierItemCode ?? '',
      unitPrice: current?.unitPrice == null ? '' : String(current.unitPrice) })
    setUsualError(null)
    setUsual.reset()
  }

  function submitUsual(event: FormEvent) {
    event.preventDefault()
    if (!usualForm.itemId) return
    const current = usual.find((one) => one.itemId === usualForm.itemId)
    const unitPrice = typedPrice(usualForm.unitPrice)
    if (unitPrice === undefined) {
      setUsualError('Their price is 0 or more with at most four decimals, or empty. Use at most 10 digits before the decimal.')
      return
    }
    setUsualError(null)
    setUsual.mutate({ projectId, itemId: usualForm.itemId, supplierId: usualForm.supplierId || null,
      supplierItemCode: usualForm.supplierItemCode.trim() || null, unitPrice, expectedVersion: current?.version ?? null })
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const payload = supplierPayload(form)
    setFormError(payload.error)
    if (!payload.input) return
    if (editing) {
      update.mutate({ supplierId: editing.supplierId, input: payload.input, active: form.active, expectedVersion: editing.version },
        { onSuccess: () => start(null) })
    } else create.mutate(payload.input, { onSuccess: () => start(null) })
  }

  return <section aria-label="Suppliers" style={{ marginTop: 24, display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 320px', gap: 24 }}>
    <div style={{ minWidth: 0, overflowX: 'auto' }}>
      <h2>Suppliers</h2>
      <p className="inspector-hint">Once a supplier is listed, purchase orders name an active one from this list. A lead time suggests when an order arrives.</p>
      {query.isPending && <p>Loading suppliers...</p>}
      {query.isError && <p role="alert">{errorMessage(query.error, 'Could not load the suppliers.')}</p>}
      {query.isSuccess && all.length === 0 && <p className="inspector-hint">No suppliers listed; purchase orders name any supplier.</p>}
      {all.length > 0 && <table aria-label="Supplier list" style={{ width: '100%', textAlign: 'left' }}>
        <thead><tr><th>Name</th><th>Contact</th><th>Lead time</th><th>Status</th><th /></tr></thead>
        <tbody>{all.map((one) => <tr key={one.supplierId} style={{ color: one.active ? undefined : '#6b7280' }}>
          <td>{one.supplierName}</td>
          <td>{one.contact ?? '—'}</td>
          <td>{leadTimeText(one.leadTimeDays) || '—'}</td>
          <td>{one.active ? 'active' : 'inactive'}</td>
          <td><button type="button" aria-label={`Edit ${one.supplierName}`} onClick={() => start(one)}>Edit</button></td>
        </tr>)}</tbody>
      </table>}
      {deliveredEver.length > 0 && <>
        <h3>Delivery</h3>
        <label style={{ fontSize: 12 }}>Period{' '}
          <select aria-label="Delivery period" value={deliveryPeriod} onChange={(event) => setDeliveryPeriod(Number(event.target.value))}>
            {[0, 30, 90, 365].map((days) => <option key={days} value={days}>{days ? `last ${days} days` : 'all time'}</option>)}
          </select>
        </label>
        <table aria-label="Supplier delivery" style={{ width: '100%', textAlign: 'left' }}>
          <thead><tr><th>Supplier</th><th>Received</th><th>On time (first date)</th><th>On time (last date)</th><th>Lines on time</th>
            <th>Avg days late</th><th>Late now</th></tr></thead>
          <tbody>{delivery.map((one) => <tr key={one.supplierName}>
            <td>{one.supplierName}</td>
            <td>{one.received}</td>
            <td>{one.received ? `${one.onTimeFirst} of ${one.received}` : '—'}</td>
            <td>{one.received ? `${one.onTimeLast} of ${one.received}` : '—'}</td>
            <td>{one.linesReceived ? `${one.linesOnTime} of ${one.linesReceived}` : '—'}</td>
            <td>{averageDaysLateText(one)}</td>
            <td>{one.lateNow}</td>
          </tr>)}</tbody>
        </table>
        <p className="inspector-hint">Fully received orders with an expected date, by the project day of their last receipt; the period counts
          those that came in within it. The first date is the order&apos;s own before any change. A line counts against its own date, or the
          order&apos;s; days late are averaged over orders in after the last date.</p>
      </>}

      {spentEver.length > 0 && <>
        <h3>Spend</h3>
        <label style={{ fontSize: 12 }}>Period{' '}
          <select aria-label="Spend period" value={period} onChange={(event) => setPeriod(Number(event.target.value))}>
            {[30, 90, 365, 0].map((days) => <option key={days} value={days}>{days ? `last ${days} days` : 'all time'}</option>)}
          </select>
        </label>
        {spend.length === 0 ? <p className="inspector-hint">Nothing was ordered in this period.</p>
          : <table aria-label="Supplier spend" style={{ width: '100%', textAlign: 'left' }}>
            <thead><tr><th>Supplier</th><th>Orders</th><th>Ordered</th><th>Received</th><th>Returned</th><th>Lines without a price</th></tr></thead>
            <tbody>{spend.map((one) => <tr key={one.supplierName}>
              <td>{one.supplierName}</td>
              <td>{one.orders}</td>
              <td>{formatQty(one.ordered)}</td>
              <td>{formatQty(one.received)}</td>
              <td>{formatQty(one.returned)}</td>
              <td>{one.unpriced}</td>
            </tr>)}</tbody>
          </table>}
        <p className="inspector-hint">Orders ordered, received or closed in the period, at their agreed unit prices; lines without a price are only counted.</p>
      </>}

      {credits.length > 0 && <>
        <h3>Credit due</h3>
        <table aria-label="Supplier credit due" style={{ width: '100%', textAlign: 'left' }}>
          <thead><tr><th>Supplier</th><th>Returns</th><th>Due</th><th>Returns without a price</th></tr></thead>
          <tbody>{credits.map((one) => <tr key={one.supplierName}>
            <td>{one.supplierName}</td>
            <td>{one.returns}</td>
            <td>{formatQty(one.due)}</td>
            <td>{one.unpriced}</td>
          </tr>)}</tbody>
        </table>
        <p className="inspector-hint">Returns not sent to be replaced, and replacements a closed order will not bring, until their credit is recorded on the order.</p>
      </>}

      {priced.length > 0 && <>
        <h3>Item prices</h3>
        <label style={{ fontSize: 12 }}>Prices for{' '}
          <select aria-label="Prices for" value={priceItem} onChange={(event) => setPriceItem(event.target.value)}>
            <option value="">Choose an item</option>
            {priced.map((one) => <option key={one.itemId} value={one.itemId}>{one.itemCode}</option>)}
          </select>
        </label>
        {priceItem && <>
          {prices.length === 0 ? <p className="inspector-hint">No order placed for it has a price yet.</p>
            : <table aria-label="Item prices by supplier" style={{ width: '100%', textAlign: 'left' }}>
              <thead><tr><th>Supplier</th><th>Last price</th><th>Last ordered</th><th>Lowest</th><th>Orders</th></tr></thead>
              <tbody>{prices.map((one) => <tr key={one.supplierName.toLowerCase()}>
                <td>{one.supplierName}{usualOf && usualOf.supplierName.trim().toLowerCase() === one.supplierName.toLowerCase() ? ' (usual)' : ''}</td>
                <td>{formatQty(one.lastPrice)}</td>
                <td>{one.lastDate} · {one.lastOrder}</td>
                <td>{formatQty(one.lowestPrice)}</td>
                <td>{one.orders}</td>
              </tr>)}</tbody>
            </table>}
          {usualPriceText(usualOf) && <p className="inspector-hint">{usualPriceText(usualOf)}</p>}
          {history.length > 0 && <ul aria-label="Price history" style={{ margin: '4px 0', paddingLeft: 18, fontSize: 12 }}>
            {history.map((one) => <li key={one.orderNumber}>{`${one.date} · ${one.supplierName} · ${one.orderNumber} · ${formatQty(one.unitPrice)}`}</li>)}
          </ul>}
        </>}
        <p className="inspector-hint">Unit prices agreed on orders placed (ordered, received or closed), newest first; the last ten are listed.</p>
      </>}

      <h3>Usual suppliers</h3>
      <p className="inspector-hint">Drafts from the reorder list and from work order needs go to each item&apos;s usual supplier, expected after its lead time.</p>
      {usual.length > 0 && <table aria-label="Usual suppliers" style={{ width: '100%', textAlign: 'left' }}>
        <thead><tr><th>Item</th><th>Supplier</th><th>Their code</th><th>Their price</th></tr></thead>
        <tbody>{usual.map((one) => <tr key={one.supplierItemId} style={{ color: one.supplierActive ? undefined : '#6b7280' }}>
          <td>{one.itemCode}</td>
          <td>{one.supplierName}{one.supplierActive ? '' : ' (inactive)'}</td>
          <td>{one.supplierItemCode ?? '—'}</td>
          <td>{one.unitPrice == null ? '—' : formatQty(one.unitPrice)}</td>
        </tr>)}</tbody>
      </table>}
      <form aria-label="Usual supplier" onSubmit={submitUsual} style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'end', marginTop: 6 }}>
        <label>Item<select value={usualForm.itemId} onChange={(event) => pickItem(event.target.value)}>
          <option value="">Choose an item</option>
          {choices.map((item) => <option key={item.itemId} value={item.itemId}>{item.itemCode} · {item.itemName}</option>)}
        </select></label>
        <label>Usual supplier<select value={usualForm.supplierId} onChange={(event) => setUsualForm({ ...usualForm, supplierId: event.target.value })}>
          <option value="">none</option>
          {all.filter((one) => one.active).map((one) => <option key={one.supplierId} value={one.supplierId}>{one.supplierName}</option>)}
        </select></label>
        <label>Their code<input value={usualForm.supplierItemCode} maxLength={100}
          onChange={(event) => setUsualForm({ ...usualForm, supplierItemCode: event.target.value })} /></label>
        <label>Their price<input type="number" min="0" step="any" value={usualForm.unitPrice} style={{ width: 90 }}
          onChange={(event) => setUsualForm({ ...usualForm, unitPrice: event.target.value })} /></label>
        <button type="submit" disabled={!usualForm.itemId || setUsual.isPending}>Save usual supplier</button>
      </form>
      {usualError && <p role="alert">{usualError}</p>}
      {setUsual.isError && <p role="alert">{errorMessage(setUsual.error, 'The usual supplier was not saved.')}</p>}
      {setUsual.isSuccess && <p role="status" style={{ fontSize: 12 }}>Saved.</p>}
    </div>

    <form onSubmit={submit} aria-label={editing ? `Edit supplier ${editing.supplierName}` : 'New supplier'}
      style={{ display: 'grid', gap: 10, alignContent: 'start' }}>
      <h3>{editing ? `Edit ${editing.supplierName}` : 'New supplier'}</h3>
      <label>Name<input value={form.supplierName} maxLength={100} onChange={(event) => setForm({ ...form, supplierName: event.target.value })} /></label>
      <label>Contact<input value={form.contact} maxLength={200} placeholder="person, phone or e-mail"
        onChange={(event) => setForm({ ...form, contact: event.target.value })} /></label>
      <label>Lead time (days)<input type="number" min="0" max="365" step="1" value={form.leadTimeDays}
        onChange={(event) => setForm({ ...form, leadTimeDays: event.target.value })} /></label>
      <label>Note<input value={form.note} maxLength={500} onChange={(event) => setForm({ ...form, note: event.target.value })} /></label>
      {editing && <label><input type="checkbox" checked={form.active} onChange={(event) => setForm({ ...form, active: event.target.checked })} /> Active</label>}
      {saveError && <p role="alert">{saveError}</p>}
      <button type="submit" disabled={create.isPending || update.isPending}>{editing ? 'Save supplier' : 'Add supplier'}</button>
      {editing && <button type="button" onClick={() => start(null)}>Stop editing</button>}
    </form>
  </section>
}
