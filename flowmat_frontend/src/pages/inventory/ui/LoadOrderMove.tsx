import { useState, type FormEvent } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import type { EquipmentLoadOrderDto } from '../../../entities/production/api/useEquipmentLoad'
import { useRescheduleWorkOrderMutation } from '../../../entities/production/api/useWorkOrderReschedule'
import { useSaveWorkOrderMutation } from '../../../entities/production/api/useWorkOrders'
import { errorMessage } from '../../../shared/lib/errorMessage'
import type { WorkOrderDto } from '../../../shared/types/api'
import { planInput, rescheduleInput } from '../../runs/model/workOrderPlanModel'
import { localDateTimeInput, movedWindow } from '../model/equipmentLoadModel'

/**
 * Moves an order to a new start from the load board, keeping its length (docs/domain/equipment-load.md M1-M4). A draft is
 * saved as it is with the new dates; an approved or running order is rescheduled by the project owner with a reason.
 */
export function LoadOrderMove({ projectId, order, full, role }: {
  projectId: string; order: EquipmentLoadOrderDto; full: WorkOrderDto | undefined; role: string | null
}) {
  const draft = order.workOrderStatus === 'draft'
  const allowed = draft ? role === 'owner' || role === 'editor'
    : (order.workOrderStatus === 'approved' || order.workOrderStatus === 'in_progress') && role === 'owner'
  const [open, setOpen] = useState(false)
  const [start, setStart] = useState('')
  const [reason, setReason] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const client = useQueryClient()
  const save = useSaveWorkOrderMutation(projectId)
  const reschedule = useRescheduleWorkOrderMutation(projectId, order.workOrderId)
  if (!allowed) return null
  const pending = save.isPending || reschedule.isPending
  const failure = save.error ?? reschedule.error

  function close() {
    setOpen(false)
    setProblem(null)
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const moved = movedWindow(order, start)
    if (!moved) {
      setProblem('Pick a new start that differs from the planned one.')
      return
    }
    if (!draft && !reason.trim()) {
      setProblem('Give a reason for moving an approved order.')
      return
    }
    setProblem(null)
    const done = () => {
      void client.invalidateQueries({ queryKey: ['equipment-load', projectId] })
      close()
    }
    if (draft) {
      if (!full) return
      save.mutate({ workOrderId: order.workOrderId, ...planInput(full, moved) }, { onSuccess: done })
    } else {
      reschedule.mutate(rescheduleInput(order, moved, reason.trim()), { onSuccess: done })
    }
  }

  if (!open) {
    return <button type="button" aria-label={`Move ${order.workOrderNumber}`} disabled={draft && !full}
      onClick={() => { setStart(localDateTimeInput(order.plannedStartAt)); setReason(''); setOpen(true) }}>Move</button>
  }
  return (
    <form aria-label={`Move ${order.workOrderNumber}`} onSubmit={submit} style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'end' }}>
      <label style={{ display: 'grid', gap: 2 }}>New start
        <input type="datetime-local" aria-label={`New start of ${order.workOrderNumber}`} value={start}
          onChange={(event) => setStart(event.target.value)} />
      </label>
      {!draft && (
        <label style={{ display: 'grid', gap: 2 }}>Reason
          <input aria-label={`Reason for moving ${order.workOrderNumber}`} maxLength={1000} value={reason}
            onChange={(event) => setReason(event.target.value)} />
        </label>
      )}
      <button type="submit" disabled={pending}>{pending ? 'Saving...' : 'Save move'}</button>
      <button type="button" onClick={close}>Cancel</button>
      <span className="inspector-hint">Keeps the order&apos;s length; the end moves with the start.</span>
      {problem && <p role="alert" style={{ flexBasis: '100%', margin: 0 }}>{problem}</p>}
      {failure && <p role="alert" style={{ flexBasis: '100%', margin: 0 }}>{errorMessage(failure, 'Failed to move the order.')}</p>}
    </form>
  )
}
