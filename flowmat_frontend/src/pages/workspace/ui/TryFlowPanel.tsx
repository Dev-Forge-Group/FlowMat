import { useEffect, useState } from 'react'
import { loadFlowKernel, type KernelWalk } from '../../../shared/lib/flowKernel'
import { toKernelGraph } from '../../../entities/workflow/model/toKernelGraph'
import type { WorkflowCanvasViewModel } from '../../../entities/workflow/model/types'
import { connectionFlows, summarizeWalk, tryInput, useTryFlowStore, walkRows } from '../model/tryFlow'

/**
 * Trying the flow as drawn (ADR-040 decision 7): an input at the start nodes, followed to the end by the flow kernel as
 * the server would run it, every step passing on what it received. What reaches each connection shows on the canvas.
 * Nothing is saved, published or run.
 */
export function TryFlowPanel({ canvas, nameOf, onSelectProcess }: {
  canvas: Pick<WorkflowCanvasViewModel, 'nodes' | 'edges'>
  nameOf: (id: string) => string
  onSelectProcess: (processId: string) => void
}) {
  const [open, setOpen] = useState(false)
  const [quantity, setQuantity] = useState('10')
  const [unit, setUnit] = useState('')
  const [attributes, setAttributes] = useState('')
  const [walk, setWalk] = useState<KernelWalk | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const setFlows = useTryFlowStore((state) => state.setFlows)

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
    try {
      const kernel = await loadFlowKernel()
      const result = kernel.walk(toKernelGraph(canvas), read.input)
      setProblem(null)
      setWalk(result)
      setFlows(connectionFlows(result))
    } catch (error) {
      setProblem(error instanceof Error ? error.message : 'The flow could not be tried.')
    }
  }

  const summary = walk ? summarizeWalk(walk, nameOf) : null
  return (
    <section aria-label="Try the flow" className="try-flow">
      <button type="button" aria-expanded={open} onClick={() => setOpen((value) => !value)}>Try it</button>
      {open && (
        <form className="try-flow__form" onSubmit={(event) => { event.preventDefault(); void run() }}>
          <label>Quantity <input value={quantity} onChange={(event) => setQuantity(event.target.value)} inputMode="decimal" /></label>
          <label>Unit <input value={unit} onChange={(event) => setUnit(event.target.value)} /></label>
          <label>Attributes <input value={attributes} placeholder='{"lot": "A"}'
            onChange={(event) => setAttributes(event.target.value)} /></label>
          <button type="submit">Run through</button>
          <span className="inspector-hint">Each step passes on what it receives; gateways, Joins and connections act as in a run.</span>
        </form>
      )}
      {open && problem && <p role="alert">{problem}</p>}
      {open && walk && summary && (
        <div role="status">
          <p className={`try-flow__summary try-flow__summary--${summary.tone}`}>{summary.headline}</p>
          <ol className="try-flow__steps">
            {walkRows(walk, nameOf).map((row) => (
              <li key={row.index}>
                <button type="button" onClick={() => onSelectProcess(row.nodeId)}>
                  {row.name}: {row.received} ({row.status})
                </button>
                {row.note && <span className="try-flow__note"> — {row.note}</span>}
              </li>
            ))}
          </ol>
        </div>
      )}
    </section>
  )
}
