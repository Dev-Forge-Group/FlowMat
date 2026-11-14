import { Fragment, useMemo, useRef, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  useSaveWorkOrderMutation,
  useWorkOrderTransitionMutation,
  useWorkOrdersQuery,
  type WorkOrderTransition,
} from '../../../entities/production/api/useWorkOrders'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { formatQty } from '../../../shared/lib/formatQty'
import type { ItemDto, WorkflowDto, WorkOrderDto } from '../../../shared/types/api'
import { availableWorkOrderActions, isWorkOrderEditable, safeHttpUrl, workOrderProgress } from '../model/workOrderActions'
import { useBomBuildableQuery, useBomsQuery } from '../../../entities/bom/api/useBoms'
import { useEffectiveBomQuery } from '../../../entities/bom/api/useBomEffectivity'
import { useProjectTimeZone } from '../../../entities/project/api/useProjectTimeZone'
import { projectDay } from '../model/workOrderBomModel'
import { approvedRevision, optionalMaterials, zoneDay } from '../../inventory/model/bomModel'
import { ItemScanInput } from '../../inventory/ui/ItemScanInput'
import { pickableItems } from '../../inventory/model/itemStatusModel'
import { WorkOrderReadiness } from './WorkOrderReadiness'
import { WorkOrderWasteEstimate } from './WorkOrderWasteEstimate'
import { WorkOrderSetupComparison } from './WorkOrderSetupComparison'
import { WorkOrderRunningComparison } from './WorkOrderRunningComparison'
import { WorkOrderLaborComparison } from './WorkOrderLaborComparison'
import { WorkOrderLaborCost } from './WorkOrderLaborCost'
import { plannedSupply } from '../model/readinessModel'
import { WorkOrderEquipmentPicker } from './WorkOrderEquipmentPicker'
import { WorkOrderCrewPicker } from './WorkOrderCrewPicker'
import { WorkOrderPlanSuggestion } from './WorkOrderPlanSuggestion'
import { WorkOrderBatchPlan } from './WorkOrderBatchPlan'
import { WorkOrderAllocations } from './WorkOrderAllocations'
import { WorkOrderReschedule } from './WorkOrderReschedule'
import { useEquipmentQuery } from '../../../entities/catalog/api/useEquipment'

const PRIORITIES = ['low', 'normal', 'high', 'urgent']

const STATUS_COLORS: Record<string, { fg: string; bg: string }> = {
  draft: { fg: '#475569', bg: '#e2e8f0' },
  approved: { fg: '#1d4ed8', bg: '#dbeafe' },
  in_progress: { fg: '#92400e', bg: '#fef3c7' },
  completed: { fg: '#047857', bg: '#d1fae5' },
  cancelled: { fg: '#b91c1c', bg: '#fee2e2' },
}

const ACTION_LABELS: Record<WorkOrderTransition, string> = {
  approve: 'Approve',
  cancel: 'Cancel',
  complete: 'Complete',
}

const ACTION_CONFIRM: Record<WorkOrderTransition, string> = {
  approve: 'Approve this work order? Draft fields are frozen; the owner can reschedule it with a reason. Runs can then start.',
  cancel: 'Cancel this work order? This cannot be undone.',
  complete: 'Mark this work order as completed?',
}

const cell = { padding: '8px 6px' } as const

interface OrderForm {
  workOrderTitle: string
  workflowId: string
  targetItemId: string
  bomId: string
  targetQuantity: string
  priority: string
  plannedStartAt: string
  plannedEndAt: string
  instruction: string
  instructionUrl: string
  /** Optional BOM materials the order takes, by item (ADR-033). */
  optionalItemIds: string[]
}

const EMPTY_FORM: OrderForm = {
  workOrderTitle: '',
  workflowId: '',
  targetItemId: '',
  bomId: '',
  targetQuantity: '',
  priority: 'normal',
  plannedStartAt: '',
  plannedEndAt: '',
  instruction: '',
  instructionUrl: '',
  optionalItemIds: [],
}

/** "2026-10-01T09:00" (datetime-local, local time) <-> ISO instant for the API. */
function toIso(local: string): string | undefined {
  return local ? new Date(local).toISOString() : undefined
}

function toLocalInput(iso: string | null): string {
  if (!iso) return ''
  const date = new Date(iso)
  const offsetMs = date.getTimezoneOffset() * 60_000
  return new Date(date.getTime() - offsetMs).toISOString().slice(0, 16)
}

function formatDate(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString(undefined, { dateStyle: 'short', timeStyle: 'short' }) : '-'
}

export function WorkOrdersPanel({
  projectId,
  workflows,
  items,
}: {
  projectId: string
  workflows: WorkflowDto[]
  items: ItemDto[]
}) {
  const [scheduleFor, setScheduleFor] = useState<string | null>(null)
  const ordersQuery = useWorkOrdersQuery(projectId)
  const saveMutation = useSaveWorkOrderMutation(projectId)
  const transitionMutation = useWorkOrderTransitionMutation(projectId)

  const [editing, setEditing] = useState<WorkOrderDto | null>(null)
  const [form, setForm] = useState<OrderForm>(EMPTY_FORM)
  // The form as last rendered, so a save that ends can tell whether anything was typed while it waited.
  const latestForm = useRef(form)
  latestForm.current = form
  // Whether the user chose the BOM in this form; one they did not choose follows the planned start.
  const [bomPicked, setBomPicked] = useState(false)
  const [readinessFor, setReadinessFor] = useState<string | null>(null)
  // Why the form was filled in for a short sub-assembly: an order's readiness, or open work order needs.
  const [madeFor, setMadeFor] = useState<string | null>(null)
  // Open work order needs (Stock tab) send a short sub-assembly as ?make=&quantity= (docs/domain/multi-level-bom.md).
  const [searchParams] = useSearchParams()
  const makeItemId = searchParams.get('make')
  const [madeFrom, setMadeFrom] = useState<string | null>(null)

  const orders = ordersQuery.data ?? []
  const itemLabel = useMemo(() => new Map(items.map((item) => [item.itemId, `${item.itemCode} · ${item.itemName}`])), [items])
  const itemCode = useMemo(() => new Map(items.map((item) => [item.itemId, item.itemCode])), [items])
  const workflowLabel = useMemo(() => new Map(workflows.map((wf) => [wf.workflowId, wf.workflowName])), [workflows])
  const equipmentList = useEquipmentQuery(projectId).data
  const equipmentCode = useMemo(
    () => new Map((equipmentList ?? []).map((one) => [one.equipmentId, one.equipmentCode ?? one.equipmentName])),
    [equipmentList],
  )
  const bomsQuery = useBomsQuery(projectId)
  // The same list from render to render until the query changes, so the makeable set is not redone each time.
  const boms = useMemo(() => bomsQuery.data ?? [], [bomsQuery.data])
  const bomsLoaded = bomsQuery.isSuccess
  const bomById = new Map(boms.map((bom) => [bom.bomId, bom]))
  // Retired revisions can no longer be chosen; draft / pending ones can, but must be approved before the order is.
  const bomChoices = boms.filter((bom) => bom.targetItemId === form.targetItemId && bom.bomStatus !== 'retired')
  const chosenBom = form.bomId ? bomById.get(form.bomId) : undefined
  // With approved revisions, a work order without a chosen BOM gets the one effective on its planned start's project day.
  const hasApproved = bomChoices.some((bom) => bom.bomStatus === 'approved')
  const timeZone = useProjectTimeZone(projectId).query.data?.timeZone
  const plannedDay = projectDay(form.plannedStartAt, timeZone)
  const effectiveBom = useEffectiveBomQuery(projectId, form.targetItemId, !form.bomId && hasApproved ? plannedDay : '')
  // The revision the order will plan from: the chosen one, else the one effective on the planned start (ADR-033 decision 9).
  const planningBom = chosenBom ?? (effectiveBom.data?.bomId ? bomById.get(effectiveBom.data.bomId) : undefined)
  const optionalChoices = optionalMaterials(planningBom)
  // What usable stock could make with the chosen BOM, so a quantity beyond it shows before approving.
  const canMake = useBomBuildableQuery(projectId, form.bomId || null).data

  if (makeItemId && makeItemId !== madeFrom && bomsLoaded) {
    const made = items.find((item) => item.itemId === makeItemId)
    if (made) {
      setMadeFrom(makeItemId)
      setEditing(null)
      setBomPicked(false)
      setForm({
        ...EMPTY_FORM,
        workOrderTitle: `${made.itemCode} for open work orders`,
        targetItemId: made.itemId,
        bomId: approvedRevision(boms, made.itemId, zoneDay(timeZone))?.bomId ?? '',
        targetQuantity: searchParams.get('quantity') ?? '',
      })
      setMadeFor('Filled in from open work order needs: what stock and open orders leave short. Check it, then create.')
    }
  }

  const makeable = useMemo(
    () => new Set(boms.filter((bom) => bom.bomStatus === 'approved').map((bom) => bom.targetItemId)),
    [boms],
  )

  /** A short sub-assembly as a new work order, done before the order it is for starts (docs/domain/multi-level-bom.md). */
  function makeSubAssembly(order: WorkOrderDto, material: { itemId: string; itemCode: string }, quantity: number) {
    setEditing(null)
    setBomPicked(false)
    saveMutation.reset()
    setForm({
      ...EMPTY_FORM,
      workOrderTitle: `${material.itemCode} for ${order.workOrderNumber}`,
      targetItemId: material.itemId,
      bomId: approvedRevision(boms, material.itemId, zoneDay(timeZone))?.bomId ?? '',
      targetQuantity: String(quantity),
      plannedEndAt: toLocalInput(order.plannedStartAt),
    })
    setMadeFor(`Filled in from ${order.workOrderNumber}'s readiness to make what it is short of before it starts. Check it, then create.`)
  }

  function selectTargetItem(targetItemId: string) {
    setBomPicked(false)
    setForm((f) => ({ ...f, targetItemId, bomId: f.plannedStartAt ? '' : approvedRevision(boms, targetItemId, zoneDay(timeZone))?.bomId ?? '' }))
  }

  /**
   * A BOM the user did not choose follows the planned start: with one, the server picks the revision effective on its
   * project day; without one, the approved revision is filled in as before (docs/domain/multi-level-bom.md).
   */
  function changePlannedStart(plannedStartAt: string) {
    setForm((f) => {
      const current = f.bomId ? bomById.get(f.bomId) : undefined
      if (bomPicked || !f.targetItemId || (current && current.bomStatus !== 'approved')) return { ...f, plannedStartAt }
      return { ...f, plannedStartAt, bomId: plannedStartAt ? '' : approvedRevision(boms, f.targetItemId, zoneDay(timeZone))?.bomId ?? '' }
    })
  }

  function resetForm() {
    setEditing(null)
    setBomPicked(false)
    setForm(EMPTY_FORM)
    setMadeFor(null)
    saveMutation.reset()
  }

  function startEdit(order: WorkOrderDto) {
    setEditing(order)
    setBomPicked(false)
    setForm({
      workOrderTitle: order.workOrderTitle,
      workflowId: order.workflowId ?? '',
      targetItemId: order.targetItemId ?? '',
      bomId: order.bomId ?? '',
      targetQuantity: order.targetQuantity !== null ? String(order.targetQuantity) : '',
      priority: order.priority,
      plannedStartAt: toLocalInput(order.plannedStartAt),
      plannedEndAt: toLocalInput(order.plannedEndAt),
      instruction: order.instruction ?? '',
      instructionUrl: order.instructionUrl ?? '',
      optionalItemIds: order.optionalItemIds ?? [],
    })
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    const submitted = form
    try {
      await saveMutation.mutateAsync({
        workOrderId: editing?.workOrderId,
        workOrderTitle: form.workOrderTitle.trim(),
        workflowId: form.workflowId || undefined,
        targetItemId: form.targetItemId || undefined,
        bomId: form.bomId || undefined,
        targetQuantity: form.targetQuantity === '' ? undefined : Number(form.targetQuantity),
        priority: form.priority,
        plannedStartAt: toIso(form.plannedStartAt),
        plannedEndAt: toIso(form.plannedEndAt),
        instruction: form.instruction.trim() || undefined,
        instructionUrl: form.instructionUrl.trim() || undefined,
        // Only picks the planning revision offers; without a known revision the picks are left as they are.
        optionalItemIds: planningBom
          ? form.optionalItemIds.filter((itemId) => optionalChoices.some((choice) => choice.childItemId === itemId))
          : undefined,
      })
      // What was typed while this saved is the next draft: it stays, for the same order when one was being edited.
      if (latestForm.current === submitted) resetForm()
    } catch {
      // Surfaced through saveMutation.error below the form.
    }
  }

  function runAction(order: WorkOrderDto, action: WorkOrderTransition) {
    if (!window.confirm(`${order.workOrderNumber}: ${ACTION_CONFIRM[action]}`)) return
    transitionMutation.mutate(
      { workOrderId: order.workOrderId, action },
      {
        onSuccess: () => {
          if (editing?.workOrderId === order.workOrderId) resetForm()
        },
      },
    )
  }

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 340px', gap: 24, alignItems: 'start' }}>
      <section>
        {ordersQuery.isLoading && <p>Loading work orders...</p>}
        {ordersQuery.isError && (
          <p style={{ color: '#dc2626' }}>{errorMessage(ordersQuery.error, 'Failed to load work orders.')}</p>
        )}
        {!ordersQuery.isLoading && orders.length === 0 && (
          <p className="inspector-hint">
            No work orders yet. Create a draft, have the project owner approve it, then start runs against it.
          </p>
        )}
        {orders.length > 0 && (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr style={{ borderBottom: '2px solid var(--border)', textAlign: 'left' }}>
                <th style={cell}>Order</th>
                <th style={cell}>Status</th>
                <th style={cell}>Target</th>
                <th style={cell}>Progress</th>
                <th style={cell}>Planned</th>
                <th style={cell}></th>
              </tr>
            </thead>
            <tbody>
              {orders.map((order) => {
                const colors = STATUS_COLORS[order.workOrderStatus] ?? STATUS_COLORS.draft
                const progress = workOrderProgress(order)
                return (
                  <Fragment key={order.workOrderId}>
                  <tr
                    style={{
                      borderBottom: '1px solid var(--border)',
                      background: editing?.workOrderId === order.workOrderId ? 'var(--accent-bg)' : undefined,
                    }}
                  >
                    <td style={cell}>
                      <div><code>{order.workOrderNumber}</code></div>
                      <div>{order.workOrderTitle}</div>
                      {safeHttpUrl(order.instructionUrl) && (
                        <a
                          href={safeHttpUrl(order.instructionUrl) ?? undefined}
                          target="_blank"
                          rel="noopener noreferrer"
                          style={{ fontSize: 11 }}
                        >
                          Work instruction ↗
                        </a>
                      )}
                      <div style={{ fontSize: 11, opacity: 0.6 }}>
                        {order.priority !== 'normal' && <strong>{order.priority} · </strong>}
                        {order.workflowId ? workflowLabel.get(order.workflowId) ?? order.workflowId : 'any workflow'}
                      </div>
                    </td>
                    <td style={cell}>
                      <span
                        style={{
                          padding: '2px 8px',
                          borderRadius: 999,
                          fontSize: 11,
                          fontWeight: 600,
                          color: colors.fg,
                          background: colors.bg,
                        }}
                      >
                        {order.workOrderStatus.replace('_', ' ')}
                      </span>
                    </td>
                    <td style={{ ...cell, opacity: 0.8 }}>
                      <div>{order.targetItemId ? itemLabel.get(order.targetItemId) ?? order.targetItemId : '-'}</div>
                      <div style={{ fontSize: 11, opacity: 0.7 }}>
                        {formatQty(order.targetQuantity)}
                        {order.bomId && ` · BOM ${bomById.get(order.bomId) ? `v${bomById.get(order.bomId)!.bomVersion}` : ''}`}
                        {(order.optionalItemIds ?? []).length > 0
                          && ` · with ${(order.optionalItemIds ?? []).map((id) => itemCode.get(id) ?? id).join(', ')}`}
                      </div>
                    </td>
                    <td style={{ ...cell, minWidth: 110 }}>
                      <div style={{ fontSize: 12 }}>
                        {formatQty(order.producedQuantity)} produced · {order.runCount} run(s)
                      </div>
                      {progress !== null && (
                        <div style={{ height: 6, borderRadius: 3, background: 'var(--border)', marginTop: 4 }}>
                          <div
                            style={{
                              width: `${progress}%`,
                              height: '100%',
                              borderRadius: 3,
                              background: progress >= 100 ? '#047857' : 'var(--accent)',
                            }}
                          />
                        </div>
                      )}
                    </td>
                    <td style={{ ...cell, fontSize: 11, opacity: 0.7, whiteSpace: 'nowrap' }}>
                      <div>{formatDate(order.plannedStartAt)}</div>
                      <div>→ {formatDate(order.plannedEndAt)}</div>
                      {order.equipmentId && <div>on {equipmentCode.get(order.equipmentId) ?? order.equipmentId}</div>}
                    </td>
                    <td style={{ ...cell, whiteSpace: 'nowrap' }}>
                      <button type="button" aria-expanded={scheduleFor === order.workOrderId}
                        onClick={() => setScheduleFor((current) => current === order.workOrderId ? null : order.workOrderId)}
                        style={{ marginRight: 4, fontSize: 12 }}>Schedule</button>
                      {isWorkOrderEditable(order.workOrderStatus) && (
                        <button type="button" onClick={() => startEdit(order)} style={{ marginRight: 4, fontSize: 12 }}>
                          Edit
                        </button>
                      )}
                      {availableWorkOrderActions(order.workOrderStatus).map((action) => (
                        <button
                          key={action}
                          type="button"
                          onClick={() => runAction(order, action)}
                          disabled={transitionMutation.isPending}
                          style={{
                            marginRight: 4,
                            fontSize: 12,
                            ...(action === 'cancel'
                              ? { color: '#dc2626', border: '1px solid #fca5a5', background: '#fef2f2' }
                              : {}),
                          }}
                        >
                          {ACTION_LABELS[action]}
                        </button>
                      ))}
                      {order.workOrderStatus !== 'completed' && order.workOrderStatus !== 'cancelled' && (
                        <button
                          type="button"
                          aria-expanded={readinessFor === order.workOrderId}
                          onClick={() => setReadinessFor((current) => (current === order.workOrderId ? null : order.workOrderId))}
                          style={{ fontSize: 12 }}
                        >
                          Readiness
                        </button>
                      )}
                    </td>
                  </tr>
                  {scheduleFor === order.workOrderId && (
                    <tr><td colSpan={6} style={{ ...cell, background: 'var(--accent-bg)' }}>
                      <WorkOrderReschedule key={order.workOrderId} order={order} projectId={projectId} />
                    </td></tr>
                  )}
                  {readinessFor === order.workOrderId && (
                    <tr style={{ borderBottom: '1px solid var(--border)' }}>
                      <td colSpan={6} style={{ ...cell, background: 'var(--accent-bg)' }}>
                        <WorkOrderEquipmentPicker order={order} projectId={projectId} />
                        <WorkOrderCrewPicker order={order} projectId={projectId} />
                        <WorkOrderPlanSuggestion order={order} projectId={projectId} />
                        <WorkOrderReadiness workOrderId={order.workOrderId} makeable={makeable}
                          planned={plannedSupply(orders, order.workOrderId)}
                          onMake={(material, quantity) => makeSubAssembly(order, material, quantity)} />
                        <WorkOrderAllocations order={order} projectId={projectId} />
                        <WorkOrderWasteEstimate workOrderId={order.workOrderId} />
                        <WorkOrderSetupComparison workOrderId={order.workOrderId} />
                        <WorkOrderRunningComparison workOrderId={order.workOrderId} />
                        <WorkOrderLaborComparison workOrderId={order.workOrderId} workflowId={order.workflowId ?? undefined}
                          planOpen={order.workOrderStatus !== 'completed' && order.workOrderStatus !== 'cancelled'} />
                        <WorkOrderLaborCost projectId={projectId} workOrderId={order.workOrderId} />
                      </td>
                    </tr>
                  )}
                  </Fragment>
                )
              })}
            </tbody>
          </table>
        )}
        {transitionMutation.isError && (
          <p style={{ color: '#dc2626', fontSize: 12 }}>
            {errorMessage(transitionMutation.error, 'Failed to update work order.')}
          </p>
        )}
        <WorkOrderBatchPlan projectId={projectId} orders={orders} />
      </section>

      <section style={{ border: '1px solid var(--border)', borderRadius: 12, padding: 18 }}>
        <h3 style={{ marginTop: 0 }}>{editing ? `Edit ${editing.workOrderNumber}` : 'New Work Order'}</h3>
        {madeFor && !editing && (
          <p role="note" className="inspector-hint" style={{ marginTop: 0 }}>
            {madeFor}
          </p>
        )}
        <form onSubmit={(e) => void handleSubmit(e)} style={{ display: 'grid', gap: 10 }}>
          <label style={{ display: 'grid', gap: 4 }}>
            <span>Title *</span>
            <input
              value={form.workOrderTitle}
              onChange={(e) => setForm((f) => ({ ...f, workOrderTitle: e.target.value }))}
              placeholder="e.g. October batch — widget A"
              required
            />
          </label>
          <label style={{ display: 'grid', gap: 4 }}>
            <span>Workflow</span>
            <select value={form.workflowId} onChange={(e) => setForm((f) => ({ ...f, workflowId: e.target.value }))}>
              <option value="">Any workflow</option>
              {workflows.map((wf) => <option key={wf.workflowId} value={wf.workflowId}>{wf.workflowName}</option>)}
            </select>
          </label>
          <ItemScanInput items={pickableItems(items)} onPick={(item) => selectTargetItem(item.itemId)} />
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 100px', gap: 8 }}>
            <label style={{ display: 'grid', gap: 4 }}>
              <span>Target item</span>
              <select aria-label="Target item" value={form.targetItemId} onChange={(e) => selectTargetItem(e.target.value)}>
                <option value="">None</option>
                {pickableItems(items, form.targetItemId).map((item) => <option key={item.itemId} value={item.itemId}>{item.itemCode} · {item.itemName}</option>)}
              </select>
            </label>
            <label style={{ display: 'grid', gap: 4 }}>
              <span>Quantity</span>
              <input
                type="number"
                min="0"
                step="any"
                value={form.targetQuantity}
                onChange={(e) => setForm((f) => ({ ...f, targetQuantity: e.target.value }))}
              />
            </label>
          </div>
          {form.targetItemId && (
            <label style={{ display: 'grid', gap: 4 }}>
              <span>BOM</span>
              <select
                aria-label="BOM"
                value={form.bomId}
                onChange={(e) => {
                  setBomPicked(true)
                  setForm((f) => ({ ...f, bomId: e.target.value }))
                }}
              >
                <option value="">{hasApproved ? 'Select by planned start' : 'None — record materials by hand'}</option>
                {bomChoices.map((bom) => (
                  <option key={bom.bomId} value={bom.bomId}>
                    {bom.bomName} v{bom.bomVersion} ({bom.bomStatus.replace('_', ' ')})
                  </option>
                ))}
              </select>
              {chosenBom && chosenBom.bomStatus !== 'approved' && (
                <span style={{ fontSize: 11, color: '#b45309' }}>
                  This BOM is {chosenBom.bomStatus.replace('_', ' ')}; approve it before approving the work order.
                </span>
              )}
              {!form.bomId && hasApproved && (
                <span style={{ fontSize: 11, opacity: 0.7 }}>
                  The server selects the approved revision for the planned start in the project time zone.
                  {!form.plannedStartAt && ' Set a planned start or pick a revision; without either the order has no BOM.'}
                </span>
              )}
              {!form.bomId && hasApproved && plannedDay && effectiveBom.data?.bomId && (
                <span data-testid="work-order-effective-bom" style={{ fontSize: 11 }}>
                  On {plannedDay} ({timeZone}) that is v{effectiveBom.data.bomVersion}.
                </span>
              )}
              {!form.bomId && hasApproved && plannedDay && effectiveBom.isError && (
                <span style={{ fontSize: 11, color: '#b45309' }}>
                  {errorMessage(effectiveBom.error, 'No approved revision covers this day.')}
                </span>
              )}
              {bomChoices.length === 0 && (
                <span style={{ fontSize: 11, opacity: 0.6 }}>No BOM for this item yet. Create one on the Inventory → BOMs tab.</span>
              )}
              {canMake && canMake.bomId === form.bomId && canMake.buildable != null && (
                <span
                  data-testid="work-order-can-make"
                  style={{
                    fontSize: 11,
                    color: Number(form.targetQuantity) > Number(canMake.buildable) ? '#b45309' : undefined,
                    opacity: Number(form.targetQuantity) > Number(canMake.buildable) ? 1 : 0.7,
                  }}
                >
                  Stock can make {formatQty(canMake.buildable)} {canMake.targetUnit} now
                  {canMake.limitingItemId ? ` (${itemLabel.get(canMake.limitingItemId) ?? canMake.limitingItemId} runs out first)` : ''}
                  {Number(form.targetQuantity) > Number(canMake.buildable) ? '; the rest needs more material.' : '.'}
                </span>
              )}
            </label>
          )}
          {optionalChoices.length > 0 && (
            <fieldset aria-label="Optional materials" style={{ display: 'grid', gap: 4, border: 'none', padding: 0, margin: 0 }}>
              <span>Optional materials</span>
              {optionalChoices.map((choice) => (
                <label key={choice.bomLineId} style={{ display: 'flex', gap: 6, alignItems: 'center', fontSize: 12 }}>
                  <input
                    type="checkbox"
                    checked={form.optionalItemIds.includes(choice.childItemId)}
                    onChange={(e) => setForm((f) => ({
                      ...f,
                      optionalItemIds: e.target.checked
                        ? [...f.optionalItemIds, choice.childItemId]
                        : f.optionalItemIds.filter((itemId) => itemId !== choice.childItemId),
                    }))}
                  />
                  Take {itemLabel.get(choice.childItemId) ?? choice.childItemId} ({formatQty(choice.quantity)} {choice.unit} a batch)
                </label>
              ))}
            </fieldset>
          )}
          <label style={{ display: 'grid', gap: 4 }}>
            <span>Priority</span>
            <select value={form.priority} onChange={(e) => setForm((f) => ({ ...f, priority: e.target.value }))}>
              {PRIORITIES.map((priority) => <option key={priority} value={priority}>{priority}</option>)}
            </select>
          </label>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
            <label style={{ display: 'grid', gap: 4 }}>
              <span>Planned start</span>
              <input
                type="datetime-local"
                value={form.plannedStartAt}
                onChange={(e) => changePlannedStart(e.target.value)}
              />
            </label>
            <label style={{ display: 'grid', gap: 4 }}>
              <span>Planned end</span>
              <input
                type="datetime-local"
                value={form.plannedEndAt}
                onChange={(e) => setForm((f) => ({ ...f, plannedEndAt: e.target.value }))}
              />
            </label>
          </div>
          <label style={{ display: 'grid', gap: 4 }}>
            <span>Instruction</span>
            <textarea
              rows={3}
              value={form.instruction}
              onChange={(e) => setForm((f) => ({ ...f, instruction: e.target.value }))}
            />
          </label>
          <label style={{ display: 'grid', gap: 4 }}>
            <span>Instruction link</span>
            <input
              type="url"
              value={form.instructionUrl}
              maxLength={255}
              placeholder="https://… (work instruction PDF or page)"
              onChange={(e) => setForm((f) => ({ ...f, instructionUrl: e.target.value }))}
            />
          </label>
          <div style={{ display: 'flex', gap: 8, marginTop: 4 }}>
            <button type="submit" disabled={saveMutation.isPending}>
              {saveMutation.isPending ? 'Saving...' : editing ? 'Save draft' : 'Create draft'}
            </button>
            {editing && (
              <button type="button" onClick={resetForm} style={{ background: 'transparent' }}>
                Cancel
              </button>
            )}
          </div>
          {saveMutation.isError && (
            <p style={{ color: '#dc2626', fontSize: 12, margin: 0 }}>
              {errorMessage(saveMutation.error, 'Failed to save work order.')}
            </p>
          )}
          <span style={{ fontSize: 11, opacity: 0.6 }}>
            Drafts are editable. The project owner approves; the first run started against an approved order puts it in
            progress.
          </span>
        </form>
      </section>
    </div>
  )
}
