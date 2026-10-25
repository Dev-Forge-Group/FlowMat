import { useState, type FormEvent } from 'react'
import { useStockMovementMutation, useStockTransferMutation } from '../../../entities/inventory/api/useStockMovements'
import { useStorageLocationsQuery } from '../../../entities/inventory/api/useStorageLocations'
import { errorMessage, errorStatus } from '../../../shared/lib/errorMessage'
import { formatQty } from '../../../shared/lib/formatQty'
import type { InventoryDto } from '../../../shared/types/api'
import type { LotRecord } from '../model/fefoModel'
import { fromPacks } from '../model/stockAlertModel'
import { MOVEMENT_LABELS, MOVEMENT_TYPES, type MovementType } from '../model/stockModel'
import { conditionWarning } from '../model/storageConditionModel'
import { LOCATION_OPTIONS_ID } from './LocationOptions'

type Action = MovementType | 'move'

/**
 * Records one movement against a stock record through the command API: the server keeps the stock invariants
 * (nothing below zero, reservations within what is on hand) and writes the history row in the same transaction.
 * "Move" sends a transfer to another place instead (docs/domain/stock-transfer.md). When issuing or reserving, a record of
 * a LOT that expires sooner ({@code earlier}) is pointed out, with a way to switch to it.
 */
export function StockMovementForm({
  projectId,
  inventory,
  earlier,
  onUseEarlier,
  packUnit,
  packQty,
  itemCode,
  itemCondition,
}: {
  projectId: string
  inventory: InventoryDto
  earlier?: LotRecord | null
  onUseEarlier?: () => void
  /** What the item is bought in and how many stock units one holds; a receipt can then be typed in those units. */
  packUnit?: string | null
  packQty?: number | null
  /** The item and its storage condition, to point out a place kept differently (docs/domain/storage-location.md SL3). */
  itemCode?: string
  itemCondition?: string | null
}) {
  const movement = useStockMovementMutation(projectId)
  const transfer = useStockTransferMutation(projectId)
  const [action, setAction] = useState<Action>('receipt')
  const [direction, setDirection] = useState<'increase' | 'decrease'>('increase')
  const [quantity, setQuantity] = useState('')
  const [packs, setPacks] = useState('')
  const [toLocation, setToLocation] = useState('')
  const [note, setNote] = useState('')
  const moving = action === 'move'
  const pending = movement.isPending || transfer.isPending
  const failure = moving ? transfer : movement
  const failureStatus = errorStatus(failure.error)
  const places = useStorageLocationsQuery(projectId).data ?? []
  const conditionNote = moving ? conditionWarning(places, toLocation, itemCode, itemCondition) : null

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (pending) return
    try {
      if (moving) {
        await transfer.mutateAsync({
          fromInventoryId: inventory.inventoryId,
          toLocation: toLocation.trim(),
          quantity: Number(quantity),
          note: note.trim() || undefined,
        })
        setToLocation('')
      } else {
        await movement.mutateAsync({
          inventoryId: inventory.inventoryId,
          transactionType: action,
          quantity: Number(quantity),
          direction: action === 'adjustment' ? direction : undefined,
          note: note.trim() || undefined,
        })
      }
      setQuantity('')
      setPacks('')
      setNote('')
    } catch {
      // Shown below (e.g. "Not enough available stock: 3 available, 5 needed.").
    }
  }

  const quarantined = inventory.inventoryStatus === 'quarantined'

  return (
    <form
      onSubmit={(e) => void handleSubmit(e)}
      aria-label="Record stock movement"
      aria-busy={pending}
      style={{ display: 'grid', gridTemplateColumns: '180px 90px 1fr auto', gap: 6, alignItems: 'end', margin: '0 0 12px' }}
    >
      <label style={{ display: 'grid', gap: 4, fontSize: 12 }}>
        <span>Movement</span>
        <select
          value={action}
          disabled={pending}
          onChange={(e) => {
            setAction(e.target.value as Action)
            movement.reset()
            transfer.reset()
          }}
        >
          {MOVEMENT_TYPES.map((value) => <option key={value} value={value}>{MOVEMENT_LABELS[value]}</option>)}
          <option value="move">Move to another place</option>
        </select>
      </label>
      <label style={{ display: 'grid', gap: 4, fontSize: 12 }}>
        <span>Quantity</span>
        <input
          type="number"
          min="0"
          step="any"
          value={quantity}
          disabled={pending}
          onChange={(e) => {
            setQuantity(e.target.value)
            setPacks('')
          }}
          required
        />
      </label>
      {moving ? (
        <label style={{ display: 'grid', gap: 4, fontSize: 12 }}>
          <span>To location</span>
          <input
            value={toLocation}
            disabled={pending}
            maxLength={100}
            list={LOCATION_OPTIONS_ID}
            onChange={(e) => setToLocation(e.target.value)}
            placeholder={`from ${inventory.location ?? 'no location'}, e.g. WH-B / Rack 2`}
            required
          />
        </label>
      ) : (
        <label style={{ display: 'grid', gap: 4, fontSize: 12 }}>
          <span>Note</span>
          <input disabled={pending} value={note} onChange={(e) => setNote(e.target.value)} placeholder="e.g. delivery 2026-09-24 / cycle count" />
        </label>
      )}
      <button type="submit" disabled={pending}>{pending ? 'Saving...' : moving ? 'Move' : 'Record'}</button>
      {action === 'adjustment' && (
        <div role="radiogroup" aria-label="Adjustment direction" style={{ gridColumn: '1 / -1', display: 'flex', gap: 12, fontSize: 12 }}>
          {(['increase', 'decrease'] as const).map((value) => (
            <label key={value} style={{ display: 'flex', gap: 4, alignItems: 'center' }}>
              <input disabled={pending} type="radio" name="adjustment-direction" checked={direction === value} onChange={() => setDirection(value)} />
              {value}
            </label>
          ))}
        </div>
      )}
      <span style={{ gridColumn: '1 / -1', fontSize: 11, opacity: 0.65 }}>
        {formatQty(inventory.availableQuantity)} available · {formatQty(inventory.reservedQuantity)} reserved
        {quarantined ? ' · quarantined: issue, reserve and move are refused until released' : ''}
        {moving ? ' · reserved stock stays here; the stock joins the record of the same item and LOT at the new place' : ''}
      </span>
      {action === 'receipt' && packUnit && packQty ? (
        <label style={{ gridColumn: '1 / -1', display: 'flex', gap: 6, alignItems: 'center', fontSize: 12 }}>
          <span>or</span>
          <input
            aria-label={`Receipt in ${packUnit}`}
            type="number"
            min="0"
            step="any"
            value={packs}
            disabled={pending}
            style={{ width: 70 }}
            onChange={(e) => {
              setPacks(e.target.value)
              const units = e.target.value.trim() === '' ? null : fromPacks(Number(e.target.value), packQty)
              setQuantity(units === null ? '' : String(units))
            }}
          />
          <span>
            {packUnit} × {formatQty(packQty)}
          </span>
        </label>
      ) : null}
      {earlier && (action === 'issue' || action === 'reserve') && (
        <p role="note" aria-label="Expires sooner" style={{ gridColumn: '1 / -1', fontSize: 12, margin: 0, color: '#b45309' }}>
          LOT {earlier.lot.lotNo} expires {earlier.lot.expiryDate}
          {inventory.lotNo ? ` (before this LOT ${inventory.lotNo})` : ''} and has {formatQty(earlier.inventory.availableQuantity)} available at{' '}
          {earlier.inventory.location ?? 'no location'}.{' '}
          {onUseEarlier && (
            <button type="button" disabled={pending} style={{ fontSize: 11 }} onClick={onUseEarlier}>
              Use that LOT
            </button>
          )}
        </p>
      )}
      {conditionNote && (
        <p role="note" aria-label="Storage condition" style={{ gridColumn: '1 / -1', fontSize: 12, margin: 0, color: '#b45309' }}>
          {conditionNote}
        </p>
      )}
      {failure.isError && (
        <p role="alert" style={{ gridColumn: '1 / -1', color: '#dc2626', fontSize: 12, margin: 0 }}>
          {errorMessage(failure.error, moving ? 'The move was refused.' : 'The movement was refused.')}
        </p>
      )}
      {failure.isError && (failureStatus === null || failureStatus >= 500) && (
        <p style={{ gridColumn: '1 / -1', fontSize: 12, margin: 0 }}>
          The result is unconfirmed. Retry with the same values before changing or leaving this history.
        </p>
      )}
    </form>
  )
}
