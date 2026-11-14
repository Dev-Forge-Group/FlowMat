import { useEffect, useRef, useState, type FormEvent } from 'react'
import type { WarehouseTaskCompletionInput, WarehouseTaskDto } from '../../../entities/inventory/api/useWarehouseTasks'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { formatQty } from '../../../shared/lib/formatQty'
import type { ItemDto } from '../../../shared/types/api'
import { findItemByScan } from '../model/itemScanModel'
import { scannedPlaceFits, selectedScanTask, taskCompletionInput, tasksForScan } from '../model/warehouseTaskModel'

/**
 * Doing a task with a scanner (docs/domain/warehouse-task.md W8): scan the item (barcode, SKU or code) to find its open
 * task, then scan the place it goes to. Only the right place does the task, so a stock record is not put away somewhere
 * else by mistake. A scanner that types and presses Enter works, and so does typing. Large is the handheld layout (W9):
 * bigger text and touch targets, one column, the item field focused.
 */
export function TaskScan({
  tasks,
  items,
  me,
  complete,
  large = false,
}: {
  /** The open tasks. */
  tasks: WarehouseTaskDto[]
  items: ItemDto[]
  me: string | null
  complete: (input: WarehouseTaskCompletionInput) => Promise<WarehouseTaskDto>
  large?: boolean
}) {
  const big = large ? { fontSize: 18, minHeight: 44 } : undefined
  const row = large
    ? ({ display: 'flex', flexDirection: 'column', gap: 8, alignItems: 'stretch' } as const)
    : ({ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' } as const)
  const [itemText, setItemText] = useState('')
  const [placeText, setPlaceText] = useState('')
  const [scannedItemId, setScannedItemId] = useState<string | null>(null)
  const [chosenTaskId, setChosenTaskId] = useState<string | null>(null)
  const found = tasksForScan(tasks, scannedItemId ? { itemId: scannedItemId } : null, me)
  const chosen = selectedScanTask(found, chosenTaskId)
  const [message, setMessage] = useState<{ error: boolean; text: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const itemInput = useRef<HTMLInputElement>(null)
  const placeInput = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (busy) return
    if (chosenTaskId) placeInput.current?.focus()
    else if (large) itemInput.current?.focus()
  }, [busy, chosenTaskId, large])

  function scanItem(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    const item = findItemByScan(items, itemText)
    const open = tasksForScan(tasks, item, me)
    setScannedItemId(item?.itemId ?? null)
    setChosenTaskId(open.length === 1 ? open[0].taskId : null)
    setPlaceText('')
    if (!item) setMessage({ error: true, text: `No item has the code ${itemText.trim()}.` })
    else if (open.length === 0) setMessage({ error: true, text: `No open task moves ${item.itemCode}.` })
    else setMessage(null)
  }

  async function scanPlace(event: FormEvent) {
    event.preventDefault()
    if (busy || !chosen) return
    if (!scannedPlaceFits(chosen, placeText)) {
      setMessage({ error: true, text: `That is ${placeText.trim()}; ${chosen.taskNo} goes to ${chosen.toLocation}.` })
      return
    }
    setBusy(true)
    try {
      const done = await complete(taskCompletionInput(chosen, { expectedToLocation: placeText.trim() }))
      setMessage({ error: false, text: `${done.taskNo} done: ${formatQty(done.quantity)} moved to ${done.toLocation}.` })
      setItemText('')
      setPlaceText('')
      setScannedItemId(null)
      setChosenTaskId(null)
    } catch (error) {
      setMessage({ error: true, text: errorMessage(error) })
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-label="Scan to do a task"
      style={{ border: '1px solid var(--border)', borderRadius: 8, padding: large ? 12 : 8, marginBottom: 8, fontSize: large ? 18 : 13 }}>
      <form onSubmit={scanItem} style={row}>
        <label style={large ? { display: 'grid', gap: 4 } : undefined}>Scan item <input ref={itemInput} aria-label="Scan item" value={itemText} disabled={busy}
          placeholder="barcode, SKU or code" autoFocus={large} style={big} onChange={(event) => setItemText(event.target.value)} /></label>
        <button type="submit" style={big} disabled={busy || !itemText.trim()}>Find task</button>
      </form>
      {found.length > 0 && !chosen && (
        <div role="group" aria-label="Tasks for this item" style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 6 }}>
          {found.map((task) => (
            <button key={task.taskId} type="button" style={big} disabled={busy} onClick={() => setChosenTaskId(task.taskId)}>
              {task.taskNo} · {formatQty(task.quantity)} to {task.toLocation}{task.assignedTo ? ` (${task.assignedTo})` : ''}
            </button>
          ))}
        </div>
      )}
      {chosenTaskId && !chosen && (
        <p role="alert" style={{ margin: '6px 0 0', color: '#b91c1c' }}>
          The selected task is no longer open. Scan the item again.
        </p>
      )}
      {chosen && (
        <form onSubmit={(event) => void scanPlace(event)} style={{ ...row, marginTop: 6 }}>
          <span>
            {chosen.taskNo}: move {formatQty(chosen.quantity)} {chosen.itemCode ?? ''} from {chosen.fromLocation ?? '-'} to{' '}
            <strong>{chosen.toLocation}</strong>.
          </span>
          <label style={large ? { display: 'grid', gap: 4 } : undefined}>Scan place <input ref={placeInput} aria-label="Scan place" value={placeText} disabled={busy}
            style={big} onChange={(event) => setPlaceText(event.target.value)} /></label>
          <button type="submit" style={big} disabled={busy || !placeText.trim()}>{busy ? 'Moving...' : 'Done here'}</button>
        </form>
      )}
      {message && (
        <p role={message.error ? 'alert' : 'status'} style={{ margin: '6px 0 0', color: message.error ? '#b91c1c' : '#047857' }}>
          {message.text}
        </p>
      )}
    </section>
  )
}
