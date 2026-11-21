import { useEffect, useMemo, useState } from 'react'
import { loadFlowKernel, type KernelWalk } from '../../../shared/lib/flowKernel'
import { toKernelGraph } from '../../../entities/workflow/model/toKernelGraph'
import type { WorkflowCanvasViewModel } from '../../../entities/workflow/model/types'
import { useWorkOrdersQuery } from '../../../entities/production/api/useWorkOrders'
import { fetchWorkOrderWorkingTime } from '../../../entities/production/api/workOrderWorkingTime'
import {
  connectionFlows,
  kernelTime,
  startTime,
  summarizeWalk,
  timeNotes,
  timeSummary,
  tryInput,
  useTryFlowStore,
  walkRows,
} from '../model/tryFlow'

/** Now as a `datetime-local` value, to the minute. */
function localNow(): string {
  const now = new Date()
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}T${pad(now.getHours())}:${pad(now.getMinutes())}`
}

/**
 * Trying the flow as drawn (ADR-040 decision 7): an input at the start nodes, followed to the end by the flow kernel as
 * the server would run it, every step passing on what it received. What reaches each connection shows on the canvas.
 * With a work order of this workflow, its equipment and crew's working time also says when each step could start and what
 * it waits for, as a production run of the order would schedule it (ADR-041). Nothing is saved, published or run. It is
 * open below the check row while "Try it" is pressed; closing it clears the canvas.
 */
export function TryFlowPanel({ canvas, nameOf, onSelectProcess }: {
  canvas: Pick<WorkflowCanvasViewModel, 'nodes' | 'edges'> & { workflow: Pick<WorkflowCanvasViewModel['workflow'], 'workflowId' | 'projectId'> }
  nameOf: (id: string) => string
  onSelectProcess: (processId: string) => void
}) {
  const [quantity, setQuantity] = useState('10')
  const [unit, setUnit] = useState('')
  const [attributes, setAttributes] = useState('')
  const [workOrderId, setWorkOrderId] = useState('')
  const [startsAt, setStartsAt] = useState(localNow)
  const [walk, setWalk] = useState<KernelWalk | null>(null)
  const [timed, setTimed] = useState<{ start: string; equipmentCode: string | null } | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const setFlows = useTryFlowStore((state) => state.setFlows)
  const workOrders = useWorkOrdersQuery(canvas.workflow.projectId)
  // Orders on this workflow that can still run; cancelled and completed ones keep no time to try against.
  const orders = useMemo(() => (workOrders.data ?? []).filter((order) => order.workflowId === canvas.workflow.workflowId
    && order.workOrderStatus !== 'cancelled' && order.workOrderStatus !== 'completed'), [canvas.workflow.workflowId, workOrders.data])

  // A try is about the drawing it was made on; a changed drawing clears it.
  useEffect(() => {
    setWalk(null)
    setFlows({})
  }, [canvas.nodes, canvas.edges, setFlows])
  useEffect(() => () => setFlows({}), [setFlows])

  async function run() {
    const read = tryInput(quantity, unit, attributes)
    if ('problem' in read) {
      setProblem(read.problem)
      return
    }
    const start = workOrderId ? startTime(startsAt, -new Date(startsAt).getTimezoneOffset()) : null
    if (workOrderId && !start) {
      setProblem('The start is not a date and time.')
      return
    }
    try {
      const working = workOrderId && start ? await fetchWorkOrderWorkingTime(workOrderId, start) : null
      const kernel = await loadFlowKernel()
      const result = kernel.walk(toKernelGraph(canvas), read.input, 3, working && start ? kernelTime(start, working) : undefined)
      setProblem(null)
      setWalk(result)
      setTimed(working && start ? { start, equipmentCode: working.equipmentCode } : null)
      setFlows(connectionFlows(result))
    } catch (error) {
      setProblem(error instanceof Error ? error.message : 'The flow could not be tried.')
    }
  }

  const summary = walk ? summarizeWalk(walk, nameOf) : null
  const notes = walk && timed ? timeNotes(walk, timed.equipmentCode) : []
  const ends = walk && timed ? timeSummary(walk, timed.start) : null
  return (
    <section aria-label="Try the flow" className="try-flow">
      <form className="try-flow__form" onSubmit={(event) => { event.preventDefault(); void run() }}>
        <label>Quantity <input value={quantity} onChange={(event) => setQuantity(event.target.value)} inputMode="decimal" /></label>
        <label>Unit <input value={unit} onChange={(event) => setUnit(event.target.value)} /></label>
        <label>Attributes <input value={attributes} placeholder='{"lot": "A"}'
          onChange={(event) => setAttributes(event.target.value)} /></label>
        <label>Work order <select value={workOrderId} onChange={(event) => setWorkOrderId(event.target.value)}>
          <option value="">None (amounts only)</option>
          {orders.map((order) => (
            <option key={order.workOrderId} value={order.workOrderId}>{order.workOrderNumber} {order.workOrderTitle}</option>
          ))}
        </select></label>
        {workOrderId && (
          <label>Starts <input type="datetime-local" value={startsAt} onChange={(event) => setStartsAt(event.target.value)} /></label>
        )}
        <button type="submit">Run through</button>
        <span className="inspector-hint">
          Each step passes on what it receives; gateways, Joins and connections act as in a run.
          {workOrderId ? ' Steps wait for the work order’s equipment and crew as a production run would.' : ''}
        </span>
      </form>
      {problem && <p role="alert">{problem}</p>}
      {walk && summary && (
        <div role="status">
          <p className={`try-flow__summary try-flow__summary--${summary.tone}`}>{summary.headline}</p>
          {ends && <p className="try-flow__ends">{ends}</p>}
          <ol className="try-flow__steps">
            {walkRows(walk, nameOf).map((row) => (
              <li key={row.index}>
                <button type="button" onClick={() => onSelectProcess(row.nodeId)}>
                  {row.name}: {row.received} ({row.status})
                </button>
                {notes[row.index] && <span className="try-flow__time"> {notes[row.index]}</span>}
                {row.note && <span className="try-flow__note"> — {row.note}</span>}
              </li>
            ))}
          </ol>
        </div>
      )}
    </section>
  )
}
