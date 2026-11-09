import { useMemo, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useInventoriesQuery } from '../../../entities/inventory/api/useInventoriesQuery'
import {
  useWarehouseTaskMutations,
  useWarehouseTasksQuery,
  type WarehouseTaskStatus,
} from '../../../entities/inventory/api/useWarehouseTasks'
import { useWorkOrdersQuery } from '../../../entities/production/api/useWorkOrders'
import { useCurrentUserQuery } from '../../../entities/auth/api/useCurrentUserQuery'
import { useProjectMembersQuery } from '../../../entities/project/api/useProjectMembersQuery'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { formatQty } from '../../../shared/lib/formatQty'
import type { ItemDto } from '../../../shared/types/api'
import {
  EMPTY_PICK,
  EMPTY_PUTAWAY,
  assigneeChoices,
  freeToMove,
  movableRecords,
  partQuantity,
  pickPayload,
  pickableOrders,
  putawayPayload,
  scannerTasks,
  shortageText,
  tasksFor,
  type AssigneeFilter,
} from '../model/warehouseTaskModel'
import { LOCATION_OPTIONS_ID, LocationOptions } from './LocationOptions'
import { TaskScan } from './TaskScan'

const STATUS_FILTERS: { value: WarehouseTaskStatus | ''; label: string }[] = [
  { value: 'open', label: 'Open' },
  { value: '', label: 'All' },
  { value: 'done', label: 'Done' },
  { value: 'cancelled', label: 'Cancelled' },
]

/**
 * Putaway and pick tasks (docs/domain/warehouse-task.md): plan moves of stock to a place, then do them one by one; doing
 * a task records an ordinary transfer. A pick list plans picks to a staging place for a work order's materials or for
 * given items, first-expiring LOTs first.
 */
export function WarehouseTasksPanel({ projectId, items }: { projectId: string; items: ItemDto[] }) {
  const [status, setStatus] = useState<WarehouseTaskStatus | ''>('open')
  const tasksQuery = useWarehouseTasksQuery(projectId, status || null)
  const openQuery = useWarehouseTasksQuery(projectId, 'open')
  const inventoriesQuery = useInventoriesQuery(projectId)
  const workOrdersQuery = useWorkOrdersQuery(projectId)
  const { create, pickList, complete, cancel, assign } = useWarehouseTaskMutations(projectId)
  // Who does a task (W7): the signed-in user and the project's members can be given open tasks.
  const me = useCurrentUserQuery().data?.userId ?? null
  const members = useProjectMembersQuery(projectId).data ?? []
  const [assignee, setAssignee] = useState<AssigneeFilter>('anyone')
  const [putaway, setPutaway] = useState(EMPTY_PUTAWAY)
  const [putawayError, setPutawayError] = useState<string | null>(null)
  const [pick, setPick] = useState(EMPTY_PICK)
  const [pickError, setPickError] = useState<string | null>(null)
  const [cancelling, setCancelling] = useState<string | null>(null)
  const [reason, setReason] = useState('')
  // The open task being done in part, and how much of it moves now.
  const [parting, setParting] = useState<string | null>(null)
  const [partText, setPartText] = useState('')
  const [partError, setPartError] = useState<string | null>(null)
  // A handheld's view (W9) is ?view=scanner on the Tasks tab, so a device can keep it as a bookmark.
  const [searchParams, setSearchParams] = useSearchParams()
  const scanner = searchParams.get('view') === 'scanner'

  const itemLabel = useMemo(() => {
    const byId = new Map(items.map((item) => [item.itemId, item.itemCode]))
    return (itemId: string) => byId.get(itemId) ?? itemId
  }, [items])
  const openTasks = openQuery.data ?? []
  const records = movableRecords(inventoriesQuery.data ?? [], openTasks, itemLabel)
  const selected = records.find((record) => record.inventoryId === putaway.inventoryId)
  const free = selected ? freeToMove(selected, openTasks) : 0
  const orders = pickableOrders(workOrdersQuery.data ?? [])
  const tasks = tasksFor(tasksQuery.data ?? [], assignee, me)
  const rowError = complete.isError ? errorMessage(complete.error) : cancel.isError ? errorMessage(cancel.error)
    : assign.isError ? errorMessage(assign.error) : null

  function submitPutaway(event: FormEvent) {
    event.preventDefault()
    const payload = putawayPayload(putaway, selected, free)
    setPutawayError(payload.error)
    const submitted = putaway
    if (payload.input) create.mutate(payload.input,
      { onSuccess: () => setPutaway((current) => current === submitted ? EMPTY_PUTAWAY : current) })
  }

  function submitPick(event: FormEvent) {
    event.preventDefault()
    const payload = pickPayload(pick)
    setPickError(payload.error)
    if (payload.input) pickList.mutate(payload.input)
  }

  function showScanner(on: boolean) {
    setSearchParams((params) => {
      const next = new URLSearchParams(params)
      if (on) next.set('view', 'scanner')
      else next.delete('view')
      return next
    })
  }

  if (scanner) {
    const mine = scannerTasks(openTasks, me)
    return (
      <section aria-label="Scanner view" style={{ display: 'grid', gap: 12, maxWidth: 520 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <h2 style={{ margin: 0 }}>Tasks</h2>
          <button type="button" style={{ marginLeft: 'auto', minHeight: 44 }} onClick={() => showScanner(false)}>Full view</button>
        </div>
        <TaskScan large tasks={openTasks} items={items} me={me} complete={(taskId, expectedToLocation) => complete.mutateAsync({ taskId, expectedToLocation })} />
        {openQuery.isError && <p role="alert">{errorMessage(openQuery.error)}</p>}
        <h3 style={{ margin: 0, fontSize: 16 }}>My open tasks ({mine.length})</h3>
        {mine.length === 0 ? (
          <p className="inspector-hint" style={{ margin: 0 }}>No open task is given to you or to nobody.</p>
        ) : (
          <ul aria-label="My open tasks" style={{ listStyle: 'none', padding: 0, margin: 0, display: 'grid', gap: 8 }}>
            {mine.map((task) => (
              <li key={task.taskId} style={{ border: '1px solid var(--border)', borderRadius: 8, padding: 10, fontSize: 16 }}>
                <strong>{task.taskNo}</strong> · {task.taskType}{task.assignedTo ? '' : ' · not assigned'}
                <div>{formatQty(task.quantity)} {task.itemCode ?? task.itemId}{task.lotNo ? ` · LOT ${task.lotNo}` : ''}{task.allocationId && <div className="inspector-hint">Reserved for work order</div>}</div>
                <div>{task.fromLocation ?? '-'} → <strong>{task.toLocation}</strong></div>
              </li>
            ))}
          </ul>
        )}
      </section>
    )
  }

  return (
    <div style={{ display: 'grid', gap: 20 }}>
      <LocationOptions projectId={projectId} />
      <section aria-label="Warehouse tasks">
        <h2 style={{ marginBottom: 4 }}>Tasks</h2>
        <p className="inspector-hint" style={{ marginTop: 0 }}>
          Plan putaways and picks, then do them one by one; doing a task moves the stock like Move to another place.
        </p>
        <select aria-label="Task status" value={status} onChange={(event) => setStatus(event.target.value as WarehouseTaskStatus | '')}
          style={{ marginBottom: 8, fontSize: 12 }}>
          {STATUS_FILTERS.map((filter) => <option key={filter.label} value={filter.value}>{filter.label}</option>)}
        </select>{' '}
        <select aria-label="Assigned to" value={assignee} onChange={(event) => setAssignee(event.target.value as AssigneeFilter)}
          style={{ marginBottom: 8, fontSize: 12 }}>
          <option value="anyone">Anyone&apos;s</option>
          <option value="me">Mine</option>
          <option value="nobody">Not assigned</option>
        </select>{' '}
        <button type="button" style={{ marginBottom: 8, fontSize: 12 }} onClick={() => showScanner(true)}>Scanner view</button>
        <TaskScan tasks={openTasks} items={items} me={me} complete={(taskId, expectedToLocation) => complete.mutateAsync({ taskId, expectedToLocation })} />
        {tasksQuery.isError && <p role="alert">{errorMessage(tasksQuery.error)}</p>}
        {rowError && <p role="alert" style={{ color: '#dc2626' }}>{rowError}</p>}
        {tasks.length === 0 && !tasksQuery.isPending && <p className="inspector-hint">No tasks{status ? ` ${status}` : ''}.</p>}
        {tasks.length > 0 && (
          <table aria-label="Task list" style={{ width: '100%', textAlign: 'left', fontSize: 13 }}>
            <thead><tr><th>No.</th><th>Kind</th><th>Item</th><th>Qty</th><th>From</th><th>To</th><th>Work order</th><th>Assigned</th><th>Status</th><th /></tr></thead>
            <tbody>{tasks.map((task) => (
              <tr key={task.taskId}>
                <td>{task.taskNo}</td>
                <td>{task.taskType}</td>
                <td>{task.itemCode ?? itemLabel(task.itemId)}{task.lotNo && <div className="inspector-hint">LOT {task.lotNo}</div>}</td>
                <td>{formatQty(task.quantity)}{task.allocationId && <div className="inspector-hint">Reserved for work order</div>}</td>
                <td>{task.fromLocation ?? '-'}</td>
                <td>{task.toLocation}</td>
                <td>{task.workOrderNumber ?? '-'}</td>
                <td>
                  {task.status === 'open' ? (
                    <select aria-label={`Assignee of ${task.taskNo}`} value={task.assignedTo ?? ''} disabled={assign.isPending}
                      onChange={(event) => assign.mutate({ taskId: task.taskId, assignedTo: event.target.value || null })}>
                      <option value="">—</option>
                      {assigneeChoices(members, me, task.assignedTo).map((userId) => <option key={userId} value={userId}>{userId}</option>)}
                    </select>
                  ) : task.assignedTo ?? '-'}
                </td>
                <td>
                  {task.status}
                  {task.cancelReason && <div className="inspector-hint">{task.cancelReason}</div>}
                  {task.note && <div className="inspector-hint">{task.note}</div>}
                </td>
                <td style={{ whiteSpace: 'nowrap' }}>
                  {task.status === 'open' && cancelling !== task.taskId && parting !== task.taskId && (
                    <>
                      <button type="button" disabled={complete.isPending} onClick={() => complete.mutate({ taskId: task.taskId })}>Done</button>{' '}
                      <button type="button" onClick={() => { setParting(task.taskId); setPartText(''); setPartError(null) }}>Part…</button>{' '}
                      <button type="button" onClick={() => { setCancelling(task.taskId); setReason('') }}>Cancel</button>
                    </>
                  )}
                  {parting === task.taskId && (
                    <form aria-label={`Move part of ${task.taskNo}`} style={{ display: 'flex', gap: 4, alignItems: 'center' }} onSubmit={(event) => {
                      event.preventDefault()
                      const part = partQuantity(task, partText)
                      setPartError(part.error)
                      if (part.quantity !== null) {
                        complete.mutate({ taskId: task.taskId, quantity: part.quantity }, { onSuccess: () => setParting(null) })
                      }
                    }}>
                      <input aria-label="Quantity moved" type="number" min="0" step="any" value={partText} style={{ width: 70 }}
                        onChange={(event) => setPartText(event.target.value)} />
                      <button type="submit" disabled={complete.isPending}>Move</button>
                      <button type="button" onClick={() => setParting(null)}>Back</button>
                      {partError && <span role="alert" style={{ color: '#dc2626' }}>{partError}</span>}
                    </form>
                  )}
                  {cancelling === task.taskId && (
                    <form aria-label={`Cancel ${task.taskNo}`} style={{ display: 'flex', gap: 4 }} onSubmit={(event) => {
                      event.preventDefault()
                      cancel.mutate({ taskId: task.taskId, reason }, { onSuccess: () => setCancelling(null) })
                    }}>
                      <input aria-label="Why cancel" value={reason} maxLength={500} placeholder="why" onChange={(event) => setReason(event.target.value)} />
                      <button type="submit" disabled={!reason.trim() || cancel.isPending}>Cancel task</button>
                      <button type="button" onClick={() => setCancelling(null)}>Back</button>
                    </form>
                  )}
                </td>
              </tr>
            ))}</tbody>
          </table>
        )}
      </section>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: 24, alignItems: 'start' }}>
        <form aria-label="New putaway" onSubmit={submitPutaway} style={{ display: 'grid', gap: 8, fontSize: 13 }}>
          <h3 style={{ margin: 0 }}>Putaway</h3>
          <label style={{ display: 'grid', gap: 4 }}>Stock record
            <select value={putaway.inventoryId} onChange={(event) => setPutaway({ ...putaway, inventoryId: event.target.value })}>
              <option value="">Choose...</option>
              {records.map((record) => (
                <option key={record.inventoryId} value={record.inventoryId}>
                  {itemLabel(record.itemId)}{record.lotNo ? ` · LOT ${record.lotNo}` : ''} · {record.location ?? 'no location'} · {formatQty(freeToMove(record, openTasks))} free
                </option>
              ))}
            </select>
          </label>
          <label style={{ display: 'grid', gap: 4 }}>Quantity
            <input inputMode="decimal" value={putaway.quantity} onChange={(event) => setPutaway({ ...putaway, quantity: event.target.value })} />
          </label>
          <label style={{ display: 'grid', gap: 4 }}>To
            <input list={LOCATION_OPTIONS_ID} maxLength={100} value={putaway.toLocation}
              onChange={(event) => setPutaway({ ...putaway, toLocation: event.target.value })} />
          </label>
          <label style={{ display: 'grid', gap: 4 }}>Note
            <input maxLength={500} value={putaway.note} onChange={(event) => setPutaway({ ...putaway, note: event.target.value })} />
          </label>
          {(putawayError ?? (create.isError ? errorMessage(create.error) : null)) && (
            <p role="alert" style={{ color: '#dc2626', margin: 0 }}>{putawayError ?? errorMessage(create.error)}</p>
          )}
          <button type="submit" disabled={create.isPending}>{create.isPending ? 'Saving...' : 'Plan putaway'}</button>
        </form>

        <form aria-label="Pick list" onSubmit={submitPick} style={{ display: 'grid', gap: 8, fontSize: 13 }}>
          <h3 style={{ margin: 0 }}>Pick list</h3>
          <div role="radiogroup" aria-label="Pick for" style={{ display: 'flex', gap: 12 }}>
            <label><input type="radio" checked={pick.mode === 'order'} onChange={() => setPick({ ...pick, mode: 'order' })} /> A work order</label>
            <label><input type="radio" checked={pick.mode === 'items'} onChange={() => setPick({ ...pick, mode: 'items' })} /> Items</label>
          </div>
          {pick.mode === 'order' ? (
            <>
              <label style={{ display: 'grid', gap: 4 }}>Work order
                <select value={pick.workOrderId} onChange={(event) => setPick({ ...pick, workOrderId: event.target.value })}>
                  <option value="">Choose...</option>
                  {orders.map((order) => (
                    <option key={order.workOrderId} value={order.workOrderId}>
                      {order.workOrderNumber} · {order.workOrderTitle} · {formatQty(order.targetQuantity ?? 0)}
                    </option>
                  ))}
                </select>
              </label>
              <label style={{ display: 'grid', gap: 4 }}>Quantity to pick for (empty: the whole order)
                <input inputMode="decimal" value={pick.quantity} onChange={(event) => setPick({ ...pick, quantity: event.target.value })} />
              </label>
            </>
          ) : (
            <fieldset style={{ border: '1px solid var(--border)', padding: 8, display: 'grid', gap: 6 }}>
              <legend style={{ fontSize: 12 }}>Items, in each item's stock unit</legend>
              {pick.lines.map((line, index) => (
                <div key={index} style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 90px', gap: 6 }}>
                  <select aria-label={`Item ${index + 1}`} value={line.itemId} onChange={(event) => setPick({
                    ...pick, lines: pick.lines.map((one, at) => (at === index ? { ...one, itemId: event.target.value } : one)),
                  })}>
                    <option value="">Choose...</option>
                    {items.map((item) => <option key={item.itemId} value={item.itemId}>{item.itemCode} · {item.itemName}</option>)}
                  </select>
                  <input aria-label={`Quantity ${index + 1}`} inputMode="decimal" value={line.quantity} onChange={(event) => setPick({
                    ...pick, lines: pick.lines.map((one, at) => (at === index ? { ...one, quantity: event.target.value } : one)),
                  })} />
                </div>
              ))}
              <button type="button" onClick={() => setPick({ ...pick, lines: [...pick.lines, { itemId: '', quantity: '' }] })}>Add item</button>
            </fieldset>
          )}
          <label style={{ display: 'grid', gap: 4 }}>Pick to
            <input list={LOCATION_OPTIONS_ID} maxLength={100} value={pick.stagingLocation}
              onChange={(event) => setPick({ ...pick, stagingLocation: event.target.value })} />
          </label>
          {(pickError ?? (pickList.isError ? errorMessage(pickList.error) : null)) && (
            <p role="alert" style={{ color: '#dc2626', margin: 0 }}>{pickError ?? errorMessage(pickList.error)}</p>
          )}
          <button type="submit" disabled={pickList.isPending}>{pickList.isPending ? 'Planning...' : 'Plan picks'}</button>
          {pickList.data && (
            <div role="status">
              <p style={{ margin: '4px 0' }}>
                {pickList.data.tasks.length} {pickList.data.tasks.length === 1 ? 'pick' : 'picks'} planned.
                {shortageText(pickList.data.lines) && <span style={{ color: '#b45309' }}> {shortageText(pickList.data.lines)}</span>}
              </p>
              <table aria-label="Pick plan" style={{ width: '100%', textAlign: 'left', fontSize: 12 }}>
                <thead><tr><th>Item</th><th>Needed</th><th>At place</th><th>Planned before</th><th>Planned now</th><th>Short</th></tr></thead>
                <tbody>{pickList.data.lines.map((line) => (
                  <tr key={line.itemId}>
                    <td>{line.itemCode}</td><td>{formatQty(line.required)}</td><td>{formatQty(line.atStaging)}</td>
                    <td>{formatQty(line.alreadyPlanned)}</td><td>{formatQty(line.plannedNow)}</td>
                    <td style={{ color: line.shortage > 0 ? '#b45309' : undefined }}>{formatQty(line.shortage)}</td>
                  </tr>
                ))}</tbody>
              </table>
            </div>
          )}
        </form>
      </div>
    </div>
  )
}
