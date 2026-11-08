import { useMemo, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useItemsQuery } from '../../../entities/catalog/api/useItemsQuery'
import { useProductionRunQuery } from '../../../entities/production/api/useProductionRunQuery'
import { useWorkflowRevisionsQuery } from '../../../entities/workflow/api/useWorkflowRevisions'
import { useProductionRunItemsQuery } from '../../../entities/production/api/useProductionRunItemsQuery'
import { useRecordRunItemMutation } from '../../../entities/production/api/useRecordRunItemMutation'
import { useAllocateRunInputMutation } from '../../../entities/production/api/useAllocateRunInputMutation'
import { useCancelRunItemMutation } from '../../../entities/production/api/useCancelRunItemMutation'
import { useFinishProductionRunMutation } from '../../../entities/production/api/useFinishProductionRunMutation'
import { useInventoriesQuery } from '../../../entities/inventory/api/useInventoriesQuery'
import { useLotsQuery } from '../../../entities/inventory/api/useLots'
import { useWarehouseTasksQuery } from '../../../entities/inventory/api/useWarehouseTasks'
import { useUnitsQuery } from '../../../entities/catalog/api/useUnitsQuery'
import { useRunQualityChecklistQuery } from '../../../entities/quality/api/useInspectionStandards'
import { finishQualityWarning } from '../../../entities/quality/model/standardModel'
import { errorMessage } from '../../../shared/lib/errorMessage'
import type { BomOutputDto, ProductionRunItemDto } from '../../../shared/types/api'
import { canAllocateAgain, cancelSummary, recordedAgainstPlan, remainingOfPlan } from '../model/runPlan'
import { inputLotOptions } from '../model/correctionModel'
import { RunCorrectionsPanel } from './RunCorrectionsPanel'
import { RunQualityPanel } from './RunQualityPanel'
import { RunCostPanel } from './RunCostPanel'
import { RunSetupPanel } from './RunSetupPanel'
import { RunEquipmentTimePanel } from './RunEquipmentTimePanel'
import { RunLaborCost } from './RunLaborCost'
import { RunLaborPanel } from './RunLaborPanel'
import { RunExpectedOutputs } from './RunExpectedOutputs'
import { useBomRequirementsQuery } from '../../../entities/bom/api/useBoms'
import { finishByProductNote, finishOutput } from '../../inventory/model/bomLineTypeModel'
import { ChecklistFinishNote, RunInstructionChecklist } from './RunInstructionChecklist'
import { RunStepsPanel } from './RunStepsPanel'
import { RunStepTiming } from './RunStepTiming'
import { RunStatusBadge, formatQty, isRunOpen } from './runDisplay'
import { ItemScanInput } from '../../inventory/ui/ItemScanInput'
import { stagingPlacesFor } from '../../inventory/model/warehouseTaskModel'

const cell = { padding: '8px 6px' } as const

const EMPTY_ITEM_FORM = {
  itemId: '',
  direction: 'input' as 'input' | 'output',
  plannedQty: '',
  actualQty: '',
  unit: 'ea',
  inventoryId: '',
}

export function RunDetailRoute() {
  const { projectId = '', runId = '' } = useParams<{ projectId: string; runId: string }>()

  const runQuery = useProductionRunQuery(runId)
  const runItemsQuery = useProductionRunItemsQuery(runId)
  const itemsQuery = useItemsQuery(projectId)
  const allUnits = useUnitsQuery(true).data ?? []
  const unitCodeById = new Map(allUnits.map((unit) => [unit.unitId, unit.unitCode]))

  /** Selecting an item pre-fills its catalog unit; items with a unit only accept units of the same type. */
  function selectItem(itemId: string) {
    const item = (itemsQuery.data ?? []).find((candidate) => candidate.itemId === itemId)
    const unitCode = item?.unitId ? unitCodeById.get(item.unitId) : undefined
    setItemForm((f) => ({ ...f, itemId, inventoryId: '', unit: unitCode ?? f.unit }))
  }
  const inventoriesQuery = useInventoriesQuery(projectId)
  const lots = useLotsQuery(projectId).data ?? []

  /** Copies a BOM plan line into the record form: what is still unrecorded becomes the actual quantity. */
  function fillFromPlan(planned: ProductionRunItemDto) {
    const remaining = remainingOfPlan(planned, runItemsQuery.data ?? [])
    setItemForm({
      itemId: planned.itemId,
      direction: 'input',
      plannedQty: String(planned.plannedQty),
      actualQty: String(remaining > 0 ? remaining : planned.plannedQty),
      unit: planned.unit,
      inventoryId: '',
    })
    document.getElementById('record-run-item')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  /** Copies an expected by-product or waste into the record form as an output (docs/domain/bom-by-products.md). */
  function fillFromOutput(output: BomOutputDto, remaining: number) {
    setItemForm({
      itemId: output.itemId,
      direction: 'output',
      plannedQty: String(output.itemQuantity),
      actualQty: String(remaining > 0 ? remaining : output.itemQuantity),
      unit: output.itemUnit,
      inventoryId: '',
    })
    document.getElementById('record-run-item')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  const recordMutation = useRecordRunItemMutation(runId, projectId)
  const allocateMutation = useAllocateRunInputMutation(runId, projectId)
  const [allocation, setAllocation] = useState<string | null>(null)
  const cancelMutation = useCancelRunItemMutation(runId, projectId)
  const [cancelNote, setCancelNote] = useState<string | null>(null)
  const finishMutation = useFinishProductionRunMutation()
  // The same query the run's quality checklist uses, so finishing can warn about checks not done.
  const qualityWarning = finishQualityWarning(useRunQualityChecklistQuery(projectId, runId).data)

  const [itemForm, setItemForm] = useState(EMPTY_ITEM_FORM)
  const [actualOutputQty, setActualOutputQty] = useState('')

  const run = runQuery.data
  const revisionsQuery = useWorkflowRevisionsQuery(run?.workflowId ?? '')
  // Done picks are only read for a run with a work order; their places are offered first as inputs.
  const doneTasks = useWarehouseTasksQuery(run?.workOrderId ? projectId : '', 'done').data
  const stagedAt = useMemo(() => stagingPlacesFor(doneTasks ?? [], run?.workOrderId ?? null), [doneTasks, run?.workOrderId])
  const workflowRevision = revisionsQuery.data?.find(
    (revision) => revision.workflowRevisionId === run?.workflowRevisionId,
  )
  // The same lists from render to render until their queries change, so the totals and labels are not redone each time.
  const runItems = useMemo(() => runItemsQuery.data ?? [], [runItemsQuery.data])
  const inventories = useMemo(() => inventoriesQuery.data ?? [], [inventoriesQuery.data])
  const itemLabel = useMemo(
    () => new Map((itemsQuery.data ?? []).map((item) => [item.itemId, `${item.itemCode} · ${item.itemName}`])),
    [itemsQuery.data],
  )
  const inventoryLabel = useMemo(
    () =>
      new Map(
        inventories.map((inv) => [inv.inventoryId, `${inv.location ?? inv.inventoryId}${inv.lotNo ? ` · LOT ${inv.lotNo}` : ''}`]),
      ),
    [inventories],
  )
  const inventoriesForItem = inventories.filter((inv) => inv.itemId === itemForm.itemId)
  // LOT-tracked items are only recorded against a LOT (its stock record); the server refuses anything else.
  const lotTracked = (itemsQuery.data ?? []).find((item) => item.itemId === itemForm.itemId)?.lotManageYn === 'Y'
  const selectedItemUnit = allUnits.find(
    (unit) => unit.unitId === (itemsQuery.data ?? []).find((item) => item.itemId === itemForm.itemId)?.unitId,
  )
  const compatibleUnits = selectedItemUnit
    ? allUnits.filter(
        (unit) =>
          unit.unitType === selectedItemUnit.unitType && (unit.activeYn === 'Y' || unit.unitId === selectedItemUnit.unitId),
      )
    : []

  const totals = useMemo(() => {
    const sum = (direction: string) =>
      runItems
        // BOM rows are the plan, not something that was recorded.
        .filter((item) => item.direction === direction && item.quantitySource !== 'bom' && !item.cancelled)
        .reduce((acc, item) => acc + Number(item.actualQty ?? item.plannedQty ?? 0), 0)
    return { input: sum('input'), output: sum('output') }
  }, [runItems])

  // The query behind "Also comes out": finishing says which by-products and waste are not recorded yet.
  const expectedOutputs = useBomRequirementsQuery(run?.bomId ?? null, Number(run?.plannedOutputQty ?? 0)).data?.outputs
  const byProductNote = run && expectedOutputs
    ? finishByProductNote(expectedOutputs, runItems, (itemId) => itemLabel.get(itemId) ?? itemId, Number(run.plannedOutputQty),
      finishOutput(actualOutputQty, run.targetItemId, runItems, Number(run.plannedOutputQty)))
    : null

  async function handleRecord(e: FormEvent) {
    e.preventDefault()
    try {
      await recordMutation.mutateAsync({
        productionRunId: runId,
        itemId: itemForm.itemId,
        direction: itemForm.direction,
        plannedQty: Number(itemForm.plannedQty),
        actualQty: itemForm.actualQty === '' ? undefined : Number(itemForm.actualQty),
        unit: itemForm.unit.trim(),
        inventoryId: itemForm.inventoryId || undefined,
      })
      setItemForm((f) => ({ ...EMPTY_ITEM_FORM, direction: f.direction, unit: f.unit }))
    } catch {
      // Surfaced through the mutation error state below the form (includes rule-engine rejections).
    }
  }

  function handleFinish(e: FormEvent) {
    e.preventDefault()
    const question = 'Finish this run? Items can no longer be recorded afterwards.'
    if (!window.confirm([question, qualityWarning, byProductNote].filter(Boolean).join('\n\n'))) return
    finishMutation.mutate({
      productionRunId: runId,
      actualOutputQty: actualOutputQty === '' ? undefined : Number(actualOutputQty),
    })
  }

  const backLink = run ? `/projects/${projectId}/runs?workflowId=${run.workflowId}` : `/projects/${projectId}/runs`

  return (
    <div style={{ padding: 32, maxWidth: 1120, margin: '0 auto' }}>
      <Link to={backLink} style={{ fontSize: 13, color: 'var(--accent)' }}>Back to runs</Link>

      {runQuery.isLoading && <p>Loading run...</p>}
      {runQuery.isError && <p style={{ color: '#dc2626' }}>{errorMessage(runQuery.error, 'Failed to load run.')}</p>}

      {run && (
        <>
          <h1 style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <code>{run.runNumber}</code>
            <RunStatusBadge status={run.runStatus} />
          </h1>

          <dl
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))',
              gap: 12,
              margin: '0 0 24px',
              fontSize: 13,
            }}
          >
            {[
              ['Type', run.runType ?? '-'],
              ['Workflow revision', run.workflowRevisionId
                ? `v${workflowRevision?.revisionNo ?? '?'} (fixed at start)`
                : 'Legacy draft'],
              ['Target item', run.targetItemId ? itemLabel.get(run.targetItemId) ?? run.targetItemId : '-'],
              ['Planned output', formatQty(run.plannedOutputQty)],
              ['Actual output', formatQty(run.actualOutputQty)],
              ['Recorded inputs', formatQty(totals.input)],
              ['Recorded outputs', formatQty(totals.output)],
              ...(run.bomId ? [['BOM', `v${run.bomVersion} (fixed at start)`]] : []),
            ].map(([label, value]) => (
              <div key={label} style={{ border: '1px solid var(--border)', borderRadius: 10, padding: '10px 12px' }}>
                <dt style={{ fontSize: 11, opacity: 0.6 }}>{label}</dt>
                <dd style={{ margin: 0, fontWeight: 600 }}>{value}</dd>
              </div>
            ))}
          </dl>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 320px', gap: 24, alignItems: 'start' }}>
            <section>
              <h3 style={{ marginTop: 0 }}>Recorded items</h3>
              {cancelMutation.isError && (
                <p style={{ color: '#dc2626', fontSize: 12 }}>
                  {errorMessage(cancelMutation.error, 'The item could not be cancelled.')}
                </p>
              )}
              {cancelNote && <p role="status" style={{ fontSize: 12 }}>{cancelNote}</p>}
              {runItemsQuery.isLoading && <p>Loading items...</p>}
              {runItemsQuery.isError && (
                <p style={{ color: '#dc2626' }}>{errorMessage(runItemsQuery.error, 'Failed to load run items.')}</p>
              )}
              {!runItemsQuery.isLoading && runItems.length === 0 && (
                <p className="inspector-hint">No material movements recorded yet.</p>
              )}
              {runItems.length > 0 && (
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                  <thead>
                    <tr style={{ borderBottom: '2px solid var(--border)', textAlign: 'left' }}>
                      <th style={cell}>Direction</th>
                      <th style={cell}>Item</th>
                      <th style={{ ...cell, textAlign: 'right' }}>Planned</th>
                      <th style={{ ...cell, textAlign: 'right' }}>Actual</th>
                      <th style={cell}>Unit</th>
                      <th style={cell}>Inventory</th>
                    </tr>
                  </thead>
                  <tbody>
                    {runItems.map((item) => (
                      <tr
                        key={item.productionRunItemId}
                        title={cancelSummary(item) ?? undefined}
                        style={{
                          borderBottom: '1px solid var(--border)',
                          ...(item.cancelled ? { opacity: 0.45, textDecoration: 'line-through' } : {}),
                        }}
                      >
                        <td style={{ ...cell, color: item.direction === 'input' ? '#0369a1' : '#047857' }}>
                          {item.direction === 'input' ? '↓ input' : '↑ output'}
                        </td>
                        <td style={cell}>
                          {itemLabel.get(item.itemId) ?? item.itemId}
                          {item.quantitySource === 'bom' && (
                            <span
                              title="Planned from the BOM when the run started"
                              style={{ marginLeft: 6, fontSize: 10, padding: '1px 6px', borderRadius: 999, background: 'var(--accent-bg)' }}
                            >
                              BOM
                            </span>
                          )}
                          {item.viaItemId && (
                            <span
                              title="Expanded through this phantom sub-assembly"
                              style={{ marginLeft: 6, fontSize: 10, padding: '1px 6px', borderRadius: 999, background: 'var(--accent-bg)' }}
                            >
                              via {itemLabel.get(item.viaItemId) ?? item.viaItemId}
                            </span>
                          )}
                          {item.quantitySource === 'correction' && (
                            <span
                              title="Added by a correction of this finished run"
                              style={{ marginLeft: 6, fontSize: 10, padding: '1px 6px', borderRadius: 999, background: 'var(--accent-bg)' }}
                            >
                              correction
                            </span>
                          )}
                        </td>
                        <td style={{ ...cell, textAlign: 'right' }}>{formatQty(item.plannedQty)}</td>
                        <td
                          style={{ ...cell, textAlign: 'right' }}
                          title={item.quantitySource === 'bom' ? 'Recorded so far against this plan line (same item and unit)' : undefined}
                        >
                          {item.quantitySource === 'bom'
                            ? <span style={{ opacity: 0.7 }}>{formatQty(recordedAgainstPlan(item, runItems))}</span>
                            : formatQty(item.actualQty)}
                        </td>
                        <td style={{ ...cell, opacity: 0.7 }}>{item.unit}</td>
                        <td style={{ ...cell, opacity: 0.7 }}>
                          {item.inventoryId ? inventoryLabel.get(item.inventoryId) ?? item.inventoryId : '-'}
                          {item.quantitySource === 'bom' && isRunOpen(run.runStatus) && (
                            <button type="button" onClick={() => fillFromPlan(item)} style={{ marginLeft: 6, fontSize: 11 }}>
                              Record
                            </button>
                          )}
                          {item.cancelled && (
                            <span style={{ marginLeft: 6, fontSize: 10, textDecoration: 'none', display: 'inline-block' }}>cancelled</span>
                          )}
                          {item.quantitySource !== 'bom' && !item.cancelled && isRunOpen(run.runStatus) && (
                            <button
                              type="button"
                              disabled={cancelMutation.isPending}
                              onClick={() => {
                                const reason = window.prompt('Why is this recording being cancelled? Its stock movement will be reversed.')
                                if (reason?.trim()) {
                                  // An input of a work order's run may go back to the order instead of free stock (R4).
                                  const reallocate = canAllocateAgain(item, run) && window.confirm(
                                    'Allocate the returned stock to the work order again?\n\nOK keeps it for the order. Cancel returns it to free stock.')
                                  setCancelNote(null)
                                  cancelMutation.mutate({ productionRunItemId: item.productionRunItemId, reason: reason.trim(), reallocate },
                                    { onSuccess: () => setCancelNote(reallocate ? 'Cancelled. The returned stock is allocated to the work order again.' : null) })
                                }
                              }}
                              style={{ marginLeft: 6, fontSize: 11 }}
                            >
                              Cancel
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
              <RunExpectedOutputs
                bomId={run.bomId}
                plannedOutputQty={Number(run.plannedOutputQty)}
                runItems={runItems}
                items={itemsQuery.data ?? []}
                open={isRunOpen(run.runStatus)}
                madeQty={run.runStatus.toLowerCase() === 'finished' ? Number(run.actualOutputQty ?? 0) : null}
                onRecord={fillFromOutput}
              />
              <RunStepsPanel runId={runId} open={isRunOpen(run.runStatus)} />
              <RunStepTiming runId={runId} />
              <RunInstructionChecklist projectId={projectId} runId={runId} />
            </section>

            <div style={{ display: 'grid', gap: 16 }}>
              {isRunOpen(run.runStatus) ? (
                <>
                  <section style={{ border: '1px solid var(--border)', borderRadius: 12, padding: 18 }}>
                    <h3 style={{ marginTop: 0 }}>Record Item</h3>
                    <form id="record-run-item" onSubmit={(e) => void handleRecord(e)} style={{ display: 'grid', gap: 10 }}>
                      <div style={{ display: 'flex', gap: 12 }}>
                        {(['input', 'output'] as const).map((direction) => (
                          <label key={direction} style={{ display: 'flex', gap: 4, alignItems: 'center' }}>
                            <input
                              type="radio"
                              name="direction"
                              checked={itemForm.direction === direction}
                              onChange={() => setItemForm((f) => ({ ...f, direction }))}
                            />
                            {direction}
                          </label>
                        ))}
                      </div>
                      <ItemScanInput items={itemsQuery.data ?? []} onPick={(item) => selectItem(item.itemId)} />
                      <label style={{ display: 'grid', gap: 4 }}>
                        <span>Item *</span>
                        <select
                          value={itemForm.itemId}
                          onChange={(e) => selectItem(e.target.value)}
                          required
                        >
                          <option value="" disabled>Select item</option>
                          {(itemsQuery.data ?? []).map((item) => (
                            <option key={item.itemId} value={item.itemId}>{item.itemCode} · {item.itemName}</option>
                          ))}
                        </select>
                      </label>
                      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 70px', gap: 8 }}>
                        <label style={{ display: 'grid', gap: 4 }}>
                          <span>Planned *</span>
                          <input
                            type="number"
                            min="0"
                            step="any"
                            value={itemForm.plannedQty}
                            onChange={(e) => setItemForm((f) => ({ ...f, plannedQty: e.target.value }))}
                            required
                          />
                        </label>
                        <label style={{ display: 'grid', gap: 4 }}>
                          <span>Actual</span>
                          <input
                            type="number"
                            min="0"
                            step="any"
                            value={itemForm.actualQty}
                            onChange={(e) => setItemForm((f) => ({ ...f, actualQty: e.target.value }))}
                          />
                        </label>
                        <label style={{ display: 'grid', gap: 4 }}>
                          <span>Unit *</span>
                          {compatibleUnits.length > 0 ? (
                            // The server converts to the item's unit and rejects other unit types.
                            <select
                              value={itemForm.unit}
                              onChange={(e) => setItemForm((f) => ({ ...f, unit: e.target.value }))}
                              required
                            >
                              {compatibleUnits.map((unit) => (
                                <option key={unit.unitId} value={unit.unitCode}>{unit.unitCode}</option>
                              ))}
                            </select>
                          ) : (
                            <input
                              value={itemForm.unit}
                              onChange={(e) => setItemForm((f) => ({ ...f, unit: e.target.value }))}
                              required
                            />
                          )}
                        </label>
                      </div>
                      <label style={{ display: 'grid', gap: 4 }}>
                        <span>{lotTracked ? 'LOT *' : 'Inventory'}</span>
                        <select
                          value={itemForm.inventoryId}
                          onChange={(e) => setItemForm((f) => ({ ...f, inventoryId: e.target.value }))}
                          disabled={!itemForm.itemId || inventoriesForItem.length === 0}
                          required={lotTracked}
                        >
                          <option value="" disabled={lotTracked}>{lotTracked ? 'Select LOT' : "Don't adjust stock"}</option>
                          {inputLotOptions(inventoriesForItem, lots, itemForm.direction, stagedAt).map(({ inventory: inv, expiryDate, expired, disabled, useFirst, staged }) => (
                            <option key={inv.inventoryId} value={inv.inventoryId} disabled={disabled}>
                              {inventoryLabel.get(inv.inventoryId)} (available {formatQty(inv.availableQuantity)})
                              {expiryDate ? ` · expires ${expiryDate}` : ''}
                              {expired ? ' — expired' : ''}
                              {inv.inventoryStatus === 'quarantined' ? ' — quarantined' : ''}
                              {useFirst ? ' · use first' : ''}
                              {staged ? ' · picked for this order' : ''}
                            </option>
                          ))}
                        </select>
                        {lotTracked && inventoriesForItem.length === 0 && (
                          <span style={{ fontSize: 11, color: '#b45309' }}>
                            This item tracks LOTs. Register a LOT and its stock record on the Inventory page first.
                          </span>
                        )}
                        {itemForm.inventoryId && (
                          <span style={{ fontSize: 11, opacity: 0.6 }}>
                            Stock will be {itemForm.direction === 'input' ? 'decreased' : 'increased'} by the actual
                            (or planned) quantity.
                          </span>
                        )}
                      </label>
                      <button type="submit" disabled={recordMutation.isPending}>
                        {recordMutation.isPending ? 'Recording...' : 'Record'}
                      </button>
                      {itemForm.direction === 'input' && lotTracked && (
                        <button
                          type="button"
                          disabled={allocateMutation.isPending || !(Number(itemForm.actualQty || itemForm.plannedQty) > 0)}
                          title="Take the quantity from the LOTs that expire first, one recording per LOT"
                          onClick={() => {
                            setAllocation(null)
                            allocateMutation.mutate(
                              {
                                productionRunId: runId,
                                itemId: itemForm.itemId,
                                quantity: Number(itemForm.actualQty || itemForm.plannedQty),
                                unit: itemForm.unit,
                              },
                              {
                                onSuccess: (recorded) => {
                                  const lotNo = new Map(lots.map((lot) => [lot.lotId, lot.lotNo]))
                                  setAllocation(
                                    `Recorded from ${recorded.length} LOT${recorded.length === 1 ? '' : 's'}: `
                                      + recorded.map((row) => `${lotNo.get(row.lotId ?? '') ?? row.lotId} ${formatQty(row.actualQty)} ${row.unit}`).join(', '),
                                  )
                                  setItemForm(EMPTY_ITEM_FORM)
                                },
                              },
                            )
                          }}
                        >
                          {allocateMutation.isPending ? 'Splitting...' : 'Split over LOTs (FEFO)'}
                        </button>
                      )}
                      {allocation && (
                        <p role="status" style={{ color: '#047857', fontSize: 12, margin: 0 }}>
                          {allocation}
                        </p>
                      )}
                      {allocateMutation.isError && (
                        <p style={{ color: '#dc2626', fontSize: 12, margin: 0 }}>
                          {errorMessage(allocateMutation.error, 'The LOTs could not be split.')}
                        </p>
                      )}
                      {recordMutation.isError && (
                        <p style={{ color: '#dc2626', fontSize: 12, margin: 0 }}>
                          {errorMessage(recordMutation.error, 'Failed to record item.')}
                        </p>
                      )}
                    </form>
                  </section>

                  <section style={{ border: '1px solid var(--border)', borderRadius: 12, padding: 18 }}>
                    <h3 style={{ marginTop: 0 }}>Finish Run</h3>
                    <form onSubmit={handleFinish} style={{ display: 'grid', gap: 10 }}>
                      <label style={{ display: 'grid', gap: 4 }}>
                        <span>Actual output qty</span>
                        <input
                          type="number"
                          min="0"
                          step="any"
                          value={actualOutputQty}
                          placeholder={totals.output > 0 ? String(totals.output) : formatQty(run.plannedOutputQty)}
                          onChange={(e) => setActualOutputQty(e.target.value)}
                        />
                      </label>
                      <ChecklistFinishNote runId={runId} />
                      {qualityWarning && (
                        <p role="note" aria-label="Quality before finishing" style={{ color: '#b45309', fontSize: 12, margin: 0 }}>
                          {qualityWarning}
                        </p>
                      )}
                      {byProductNote && (
                        <p role="note" aria-label="By-products before finishing" style={{ color: '#b45309', fontSize: 12, margin: 0 }}>
                          {byProductNote}
                        </p>
                      )}
                      <button type="submit" disabled={finishMutation.isPending}>
                        {finishMutation.isPending ? 'Finishing...' : 'Finish'}
                      </button>
                      {finishMutation.isError && (
                        <p style={{ color: '#dc2626', fontSize: 12, margin: 0 }}>
                          {errorMessage(finishMutation.error, 'Failed to finish run.')}
                        </p>
                      )}
                    </form>
                  </section>
                </>
              ) : (
                <>
                  <p className="inspector-hint">This run is {run.runStatus}; no further items can be recorded.</p>
                  {run.runStatus === 'finished' && (
                    <RunCorrectionsPanel
                      projectId={projectId}
                      run={run}
                      runItems={runItems}
                      items={itemsQuery.data ?? []}
                      inventories={inventories}
                      itemLabel={(itemId) => itemLabel.get(itemId) ?? itemId}
                      inventoryLabel={(inventoryId) => inventoryLabel.get(inventoryId) ?? inventoryId}
                      itemUnitCode={(itemId) => {
                        const unitId = (itemsQuery.data ?? []).find((item) => item.itemId === itemId)?.unitId
                        return unitId ? unitCodeById.get(unitId) : undefined
                      }}
                    />
                  )}
                </>
              )}
              <RunCostPanel runId={run.productionRunId} />
              <RunSetupPanel projectId={projectId} runId={run.productionRunId} open={isRunOpen(run.runStatus)} />
              <RunEquipmentTimePanel projectId={projectId} runId={run.productionRunId} open={isRunOpen(run.runStatus)} />
              <RunLaborPanel projectId={projectId} runId={run.productionRunId} open={isRunOpen(run.runStatus)} />
              <RunLaborCost projectId={projectId} runId={run.productionRunId} />
              <RunQualityPanel
                projectId={projectId}
                run={run}
                runItems={runItems}
                itemLabel={(itemId) => itemLabel.get(itemId) ?? itemId}
              />
            </div>
          </div>
        </>
      )}
    </div>
  )
}
