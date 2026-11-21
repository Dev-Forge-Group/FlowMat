import { useEffect, useMemo, useState } from 'react'
import { useUnitsQuery } from '../../catalog/api/useUnitsQuery'
import { loadFlowKernel, type FlowKernel, type KernelValidation } from '../../../shared/lib/flowKernel'
import { kernelUnits, toKernelGraph } from '../model/toKernelGraph'
import type { WorkflowCanvasViewModel } from '../model/types'

/** How long the canvas must be still before it is checked again (ADR-040 decision 7). */
const SETTLE_MS = 150

/**
 * The draft check as drawn, by the flow kernel in the browser: the server's 31 rules with its messages, without saving
 * or asking the server. Null until the kernel and the unit catalog have loaded, or when either cannot; the server's
 * check, which publishing still uses, is then all there is.
 */
export function useLiveValidation(canvas: Pick<WorkflowCanvasViewModel, 'nodes' | 'edges'>): KernelValidation | null {
  const units = useUnitsQuery(true)
  const [kernel, setKernel] = useState<FlowKernel | null>(null)
  const [drawn, setDrawn] = useState({ nodes: canvas.nodes, edges: canvas.edges })

  useEffect(() => {
    let current = true
    loadFlowKernel().then((loaded) => { if (current) setKernel(loaded) }, () => {})
    return () => { current = false }
  }, [])

  useEffect(() => {
    const settled = setTimeout(() => setDrawn({ nodes: canvas.nodes, edges: canvas.edges }), SETTLE_MS)
    return () => clearTimeout(settled)
  }, [canvas.nodes, canvas.edges])

  return useMemo(() => {
    if (!kernel || !units.data) return null
    try {
      return kernel.validate(toKernelGraph(drawn), kernelUnits(units.data))
    } catch {
      return null
    }
  }, [kernel, units.data, drawn])
}
