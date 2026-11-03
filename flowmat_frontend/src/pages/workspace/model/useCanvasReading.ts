import { useCallback, useMemo, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import type { CanvasEdgeViewModel, CanvasNodeViewModel } from '../../../entities/workflow/model/types'
import { reachCounts, readingView } from './canvasReadingModel'
import { readingGraph, type ReachDirection } from './graphReading'
import { formatReadingHash, parseReadingHash, type ReadingState } from './readingHash'

/**
 * Reading the process canvas: a process with its neighbours, everything before or after it, or the way between two.
 * The address hash is the one source of the reading, so a copied link opens the same view; nothing is saved or shared
 * with collaborators. A route is picked by naming its start, then clicking its end.
 */
export function useCanvasReading(canvas: { nodes: CanvasNodeViewModel[]; edges: CanvasEdgeViewModel[] }) {
  const location = useLocation()
  const navigate = useNavigate()
  const graph = useMemo(() => readingGraph(canvas), [canvas])
  const names = useMemo(() => new Map(canvas.nodes.map((node) => [node.processId, node.name])), [canvas.nodes])
  const known = useMemo(() => new Set(graph.nodeIds), [graph])
  const state = useMemo(() => parseReadingHash(location.hash, known), [location.hash, known])
  const nameOf = useCallback((processId: string) => names.get(processId) ?? processId, [names])
  const view = useMemo(() => (state ? readingView(graph, state, nameOf) : null), [graph, state, nameOf])
  // undefined: not picking a route; null: waiting for its start; an id: waiting for its end.
  const [routeFrom, setRouteFrom] = useState<string | null | undefined>(undefined)

  const write = useCallback((next: ReadingState | null) => {
    navigate({ pathname: location.pathname, search: location.search, hash: formatReadingHash(next) }, { replace: true })
  }, [location.pathname, location.search, navigate])

  const focus = useCallback((processId: string, direction: ReachDirection | null = null) => {
    setRouteFrom(undefined)
    write({ kind: 'focus', processId, reach: direction })
  }, [write])

  const startRoute = useCallback((from: string | null = null) => {
    setRouteFrom(from)
  }, [])

  /** A clicked process while a route is being picked; false when no route is being picked. */
  const pick = useCallback((processId: string): boolean => {
    if (routeFrom === undefined) return false
    if (routeFrom === null || routeFrom === processId) {
      setRouteFrom(processId)
      return true
    }
    setRouteFrom(undefined)
    write({ kind: 'route', from: routeFrom, to: processId })
    return true
  }, [routeFrom, write])

  const clear = useCallback(() => {
    setRouteFrom(undefined)
    if (state || location.hash) write(null)
  }, [location.hash, state, write])

  const counts = useCallback((processId: string) => reachCounts(graph, processId), [graph])
  const knows = useCallback((processId: string) => known.has(processId), [known])

  return {
    state,
    view,
    picking: routeFrom === undefined ? null : { from: routeFrom },
    active: state != null || routeFrom !== undefined,
    nameOf,
    knows,
    counts,
    focus,
    startRoute,
    pick,
    clear,
  }
}

export type CanvasReading = ReturnType<typeof useCanvasReading>
