import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useWorkOrderLaborComparisonQuery } from '../../../entities/production/api/useWorkOrderLaborComparison'
import { useWorkOrderPlanSuggestionQuery } from '../../../entities/production/api/useWorkOrderPlan'
import { useSaveWorkOrderMutation } from '../../../entities/production/api/useWorkOrders'
import { errorMessage } from '../../../shared/lib/errorMessage'
import type { WorkOrderDto } from '../../../shared/types/api'
import { defaultPlanFrom, planInput, planSchedule, planSummary } from '../model/workOrderPlanModel'

/**
 * Suggests planned dates from the assigned equipment's shifts, downtime, changeover and the orders already planned on it
 * (docs/domain/equipment-schedule.md "계획 기간 제안"), and from the crew's shifts and other orders
 * (docs/domain/worker-calendar.md WC24-WC26), counting the labor its step plan leaves for the crew; without equipment it shows
 * once there is a step plan, and the crew's time decides (LB25). A draft can take them; other orders only show them.
 */
export function WorkOrderPlanSuggestion({ order, projectId }: { order: WorkOrderDto; projectId: string }) {
  const queryClient = useQueryClient()
  const [from, setFrom] = useState(() => defaultPlanFrom(order, new Date()))
  // null until asked; then the instant the search starts from.
  const [asked, setAsked] = useState<string | null>(null)
  const suggestion = useWorkOrderPlanSuggestionQuery(order.workOrderId, asked)
  const save = useSaveWorkOrderMutation(projectId)
  const labor = useWorkOrderLaborComparisonQuery(order.workOrderId)
  const plan = suggestion.isError ? undefined : suggestion.data

  function ask() {
    const start = new Date(from)
    if (Number.isNaN(start.getTime())) return
    const instant = start.toISOString()
    if (instant === asked) void suggestion.refetch()
    else setAsked(instant)
  }

  function apply() {
    if (!plan) return
    save.mutate({ workOrderId: order.workOrderId, ...planInput(order, plan) }, {
      onSuccess: () => {
        void queryClient.invalidateQueries({ queryKey: ['work-order-readiness', order.workOrderId] })
      },
    })
  }

  if (!order.equipmentId && labor.data?.stepPlannedMinutes == null) return null
  return (
    <div aria-label="Plan dates"
      style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', fontSize: 12, marginBottom: 8 }}>
      <label>
        Plan from{' '}
        <input type="datetime-local" value={from} onChange={(event) => setFrom(event.target.value)} />
      </label>
      <button type="button" onClick={ask} disabled={!from || suggestion.isFetching}>
        {suggestion.isFetching ? 'Suggesting...' : 'Suggest dates'}
      </button>
      {plan && <span role="status">{planSummary(plan)}</span>}
      {plan && planSchedule(plan).length > 0 && <ul aria-label="Who works when" style={{ margin: '4px 0', paddingLeft: 18, fontSize: 12 }}>
        {planSchedule(plan).map((line) => <li key={line}>{line}</li>)}
      </ul>}
      {plan && (order.workOrderStatus === 'draft'
        ? <button type="button" onClick={apply} disabled={save.isPending}>{save.isPending ? 'Saving...' : 'Use these dates'}</button>
        : <span className="inspector-hint">Only a draft's planned dates can change.</span>)}
      {suggestion.isError && <span role="alert" style={{ color: '#dc2626' }}>{errorMessage(suggestion.error)}</span>}
      {save.isError && <span role="alert" style={{ color: '#dc2626' }}>{errorMessage(save.error)}</span>}
    </div>
  )
}
