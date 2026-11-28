import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { usePlanConfirmMutation, usePlanPreviewMutation, type PlanRequirement, type PlanTarget } from '../../../entities/production/api/usePlanPreview'
import type { WorkflowCanvasViewModel } from '../../../entities/workflow/model/types'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { usePlanTraceStore } from '../model/planTrace'
import { PlanActuals } from './PlanActuals'
import { t } from '../../../shared/i18n/t'

interface Props {
  canvas: WorkflowCanvasViewModel
  nameOf(processId: string): string
}

const amount = (value: number | null | undefined) => value == null ? '' : String(Number(value.toFixed(4)))
/** Energy and water are drawn as used, not kept in stock (ADR-046). */
const notStocked = (row: PlanRequirement) => row.resourceType === 'energy' || row.resourceType === 'water'
const titleCase = (text: string) => text.charAt(0).toUpperCase() + text.slice(1)
const unknownCosts = (plan: { costs?: { unknown: number }[] }) => (plan.costs ?? []).reduce((sum, cost) => sum + cost.unknown, 0)
const at = (iso: string) => new Date(iso).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })

/**
 * Planning from a target in the editor (docs/status/ROADMAP-2026-11-23.md §8 week 3, ADR-043): pick an output and a
 * quantity, and see what each start needs against stock, what can be made now, each step's time and what limits the
 * plan. Choosing a material lights its path on the canvas. The plan reads the published revision, not the drawing.
 * Below, the confirmed plans compare with what their finished runs did (docs/domain/plan-vs-actual.md).
 */
export function PlanPanel({ canvas, nameOf }: Props) {
  const outputs = useMemo(() => {
    const leaving = new Set(canvas.edges.map((edge) => edge.fromIoId).filter(Boolean))
    const all = canvas.nodes.flatMap((node) => node.outputs.map((port) => ({ node, port })))
    // The last output nothing continues from comes first: usually the product.
    return [...all.filter(({ port }) => !leaving.has(port.processIoId)).reverse(), ...all.filter(({ port }) => leaving.has(port.processIoId))]
  }, [canvas.nodes, canvas.edges])
  const [portId, setPortId] = useState(outputs[0]?.port.processIoId ?? '')
  const [quantity, setQuantity] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const [chosen, setChosen] = useState<string | null>(null)
  const preview = usePlanPreviewMutation(canvas.workflow.workflowId)
  const confirm = usePlanConfirmMutation(canvas.workflow.workflowId)
  const [planned, setPlanned] = useState<PlanTarget | null>(null)
  const [supplier, setSupplier] = useState('')
  // One key per plan seen: sending the same confirmation again after a lost reply makes nothing twice.
  const [requestId, setRequestId] = useState('')
  const setTraced = usePlanTraceStore((s) => s.setTraced)
  const plan = preview.data
  const unit = outputs.find(({ port }) => port.processIoId === portId)?.port.unit ?? ''
  useEffect(() => () => setTraced([]), [setTraced])

  function submit(event: FormEvent) {
    event.preventDefault()
    const target = Number(quantity)
    if (!portId) return setProblem(t('Add an output port to plan for.'))
    if (!quantity.trim() || !Number.isFinite(target) || target <= 0) return setProblem(t('The target must be more than 0.'))
    setProblem(null)
    setChosen(null)
    setTraced([])
    confirm.reset()
    const next = { processIoId: portId, quantity: target, unit: unit || null }
    setPlanned(next)
    setRequestId(`plan-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`)
    preview.mutate(next)
  }

  function choose(row: PlanRequirement) {
    setChosen(row.at)
    setTraced(row.trace.map((step) => step.processId).filter((id): id is string => Boolean(id)))
  }

  const bottleneck = plan?.limits.find((limit) => limit.kind === 'equipment-time')
  const short = plan?.requirements.some((row) => (row.shortage ?? 0) > 0) ?? false
  const failure = problem ?? (preview.isError ? errorMessage(preview.error) : confirm.isError ? errorMessage(confirm.error) : null)

  function confirmPlan() {
    if (!plan || !planned) return
    if (short && !supplier.trim()) return setProblem(t('Name the supplier for the draft purchase order of the shortages.'))
    setProblem(null)
    confirm.mutate({ target: planned, basis: plan.basis, requestId, supplierName: supplier.trim() || null })
  }

  return (
    <section aria-label={t('Plan the flow')} style={{ margin: '12px 16px 0', border: '1px solid #e2e8f0', padding: '10px' }}>
      <form onSubmit={submit} style={{ display: 'flex', gap: '8px', flexWrap: 'wrap', alignItems: 'end' }}>
        <label style={{ display: 'grid', gap: '4px' }}><span>{t('Output')}</span>
          <select value={portId} onChange={(event) => setPortId(event.target.value)}>
            {outputs.map(({ node, port }) => (
              <option key={port.processIoId} value={port.processIoId}>{node.name} · {port.name || port.processIoId}</option>
            ))}
          </select>
        </label>
        <label style={{ display: 'grid', gap: '4px' }}><span>{t('Target')}</span>
          <input inputMode="decimal" value={quantity} placeholder={unit ? t('quantity in {unit}', { unit }) : t('quantity')}
            onChange={(event) => setQuantity(event.target.value)} />
        </label>
        <button type="submit" disabled={preview.isPending}>{preview.isPending ? t('Planning...') : t('Plan')}</button>
      </form>
      <p className="inspector-hint" style={{ margin: '6px 0 0' }}>{t('Plans use the published revision. Nothing is reserved.')}</p>
      {failure && <p role="alert" style={{ color: '#991b1b' }}>{failure}</p>}
      {plan && (
        <div style={{ marginTop: '8px' }}>
          {plan.buildable != null && <p style={{ margin: '4px 0' }}>{t('Can make {count} now', { count: amount(plan.buildable) })}</p>}
          {bottleneck?.processId && <p style={{ margin: '4px 0' }}>{t('Bottleneck: {name}', { name: nameOf(bottleneck.processId) })}</p>}
          {plan.finishAt && <p style={{ margin: '4px 0' }}>{t('Finishes {at} if started now', { at: at(plan.finishAt) })}</p>}
          {!plan.feasible && <p role="alert">{t('This graph cannot bring the target as planned.')}</p>}
          <table style={{ borderCollapse: 'collapse', marginTop: '6px' }}>
            <thead><tr><th align="left">{t('Material')}</th><th align="right">{t('Needed')}</th><th align="right">{t('In stock')}</th><th align="right">{t('Short')}</th><th align="right">{t('Cost')}</th></tr></thead>
            <tbody>
              {plan.requirements.map((row) => (
                <tr key={row.at} aria-label={row.itemName ?? nameOf(row.processId)} onClick={() => choose(row)}
                  style={{ cursor: 'pointer', background: chosen === row.at ? '#eff6ff' : undefined }}>
                  <td>{row.itemName ?? nameOf(row.processId)}</td>
                  <td align="right">{amount(row.needed)} {row.unit}</td>
                  <td align="right">{row.available == null ? '—' : `${amount(row.available)} ${row.unit ?? ''}`}</td>
                  <td align="right">{notStocked(row) ? t('not stocked') : row.shortage ? t('{amount} short', { amount: `${amount(row.shortage)} ${row.unit ?? ''}` }) : t('enough')}</td>
                  <td align="right">{row.cost == null ? '—' : amount(row.cost)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {(plan.resources?.length || plan.totalCost != null) && (
            <p aria-label={t('Resources and cost')} style={{ margin: '6px 0 0' }}>
              {(plan.resources ?? []).map((resource) => `${t(titleCase(resource.resourceType))} ${amount(resource.needed)} ${resource.unit ?? ''}`).join(' · ')}
              {plan.resources?.length ? ' · ' : ''}{t('Cost {amount}', { amount: amount(plan.totalCost ?? 0) })}
              {unknownCosts(plan) > 0 ? t(' ({count} without a unit cost)', { count: unknownCosts(plan) }) : ''}
            </p>
          )}
          <ul aria-label={t('Steps')} style={{ margin: '8px 0 0', paddingLeft: '18px' }}>
            {plan.nodes.filter((node) => node.batches != null || node.minutes != null).map((node) => (
              <li key={node.processId}>
                {nameOf(node.processId)}: {node.batches != null ? t('{count} batches', { count: amount(node.batches) }) : `${amount(node.through)} ${node.unit ?? ''}`}
                {node.minutes != null ? t(', {count} min', { count: amount(node.minutes) }) : ''}
                {node.minutes != null && node.startAt && node.endAt ? ` (${at(node.startAt)} – ${at(node.endAt)})` : ''}
              </li>
            ))}
          </ul>
          {plan.limits.length > 0 && (
            <ul aria-label={t('Limits')} style={{ margin: '8px 0 0', paddingLeft: '18px' }}>
              {plan.limits.map((limit, index) => <li key={index}>{limit.message}</li>)}
            </ul>
          )}
          {plan.assumptions.some((assumption) => assumption.kind === 'loop') && (
            <ul aria-label={t('Loops')} style={{ margin: '8px 0 0', paddingLeft: '18px' }}>
              {plan.assumptions.filter((assumption) => assumption.kind === 'loop').map((assumption) => (
                <li key={assumption.at}>{nameOf(assumption.at)}: {assumption.value}</li>
              ))}
            </ul>
          )}
          {plan.feasible && !confirm.data && (
            <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap', alignItems: 'end', marginTop: '8px' }}>
              {short && (
                <label style={{ display: 'grid', gap: '4px' }}><span>{t('Supplier for the shortages')}</span>
                  <input value={supplier} onChange={(event) => setSupplier(event.target.value)} />
                </label>
              )}
              <button type="button" disabled={confirm.isPending} onClick={confirmPlan}>
                {confirm.isPending ? t('Confirming...') : t('Confirm plan')}
              </button>
            </div>
          )}
          {confirm.data && (
            <p role="status" style={{ marginTop: '8px' }}>
              {confirm.data.purchaseOrderIds.length > 0
                ? t('Draft work order made with a draft purchase order for the shortages.') : t('Draft work order made.')}
              {' '}<a href={`/projects/${canvas.workflow.projectId}/runs`}>{t('Open work orders')}</a>
            </p>
          )}
        </div>
      )}
      <PlanActuals workflowId={canvas.workflow.workflowId} nameOf={nameOf} />
    </section>
  )
}
