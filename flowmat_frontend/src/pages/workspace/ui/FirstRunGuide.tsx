import { Link } from 'react-router-dom'
import { useItemsQuery } from '../../../entities/catalog/api/useItemsQuery'
import { useInventoriesQuery } from '../../../entities/inventory/api/useInventoriesQuery'
import { useWorkflowRevisionsQuery } from '../../../entities/workflow/api/useWorkflowRevisions'
import type { WorkflowCanvasViewModel } from '../../../entities/workflow/model/types'

interface Props {
  canvas: WorkflowCanvasViewModel
}

/**
 * The first run in order (docs/status/ROADMAP-2026-11-23.md §8, next month 1): items, steps with measured ports, stock,
 * publishing, then a plan. Each step says whether it is done from what the project already has; the guide goes away once
 * everything a plan needs is there. It sits in the inspector while nothing is selected, so it never takes canvas space.
 */
export function FirstRunGuide({ canvas }: Props) {
  const projectId = canvas.workflow.projectId
  const items = useItemsQuery(projectId)
  const stock = useInventoriesQuery(projectId)
  const revisions = useWorkflowRevisionsQuery(canvas.workflow.workflowId)
  if (items.isPending || stock.isPending || revisions.isPending) return null

  const measured = canvas.nodes.some((node) => node.outputs.some((port) => port.itemId && Number(port.quantity) > 0))
  const published = (revisions.data ?? []).some((revision: { status?: string }) => revision.status === 'published')
  const steps = [
    { done: (items.data ?? []).length > 0, label: 'Add the items you buy and make',
      link: <Link to={`/projects/${projectId}/inventory`}>Items</Link> },
    { done: measured, label: 'Draw the steps and give their ports an item and a quantity per batch', link: null },
    { done: (stock.data ?? []).length > 0, label: 'Enter the stock you have',
      link: <Link to={`/projects/${projectId}/inventory?tab=stock`}>Stock</Link> },
    { done: published, label: 'Publish the flow: plans read the published revision', link: null },
  ]
  const done = steps.filter((step) => step.done).length
  if (done === steps.length) return null

  return (
    <details aria-label="Start here" open={done < 2} style={{ marginTop: '12px', border: '1px solid #e2e8f0', padding: '6px 10px' }}>
      <summary>Start here · {done} of {steps.length + 1} done</summary>
      <ol style={{ margin: '6px 0 0', paddingLeft: '20px' }}>
        {steps.map((step) => (
          <li key={step.label} style={{ opacity: step.done ? 0.6 : 1 }}>
            {step.done ? '✓ ' : ''}{step.label}{step.link ? <> · {step.link}</> : null}
          </li>
        ))}
        <li>Plan a target with Plan above the canvas: what it needs, what is short, how long it takes</li>
      </ol>
    </details>
  )
}
