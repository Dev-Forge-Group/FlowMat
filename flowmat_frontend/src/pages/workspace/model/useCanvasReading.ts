import { useCallback, useMemo, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import type { CanvasEdgeViewModel, CanvasNodeViewModel } from '../../../entities/workflow/model/types'
import {
  connectionPreview, kindCounts, reachCounts, readingTargets, readingView, routeChoices, routePickError, routePickText, routeTitle,
  toggleLensKind,
} from './canvasReadingModel'
import { readingGraph, type ReachDirection } from './graphReading'
import { processRelations } from './processRelations'
import {
  formatReadingHash, parseChangesParam, parseReadingHash, parseRunParam, withViewParams, type ReadingState,
} from './readingHash'

const NO_KINDS: readonly string[] = []

/**
 * Reading the process canvas: a process with its neighbours, everything before or after it, the way between two, or one
 * or two kinds of process.
 * The address hash is the one source of the reading, so a copied link opens the same view; nothing is saved or shared
 * with collaborators. A route is picked by naming its start, then one of the processes marked as reachable from it.
 * A reading picked by hand is brought into view with `bringIntoView`.
 */
export function useCanvasReading(
  canvas: { nodes: CanvasNodeViewModel[]; edges: CanvasEdgeViewModel[] },
  bringIntoView?: (processIds: string[]) => void,
) {
  const location = useLocation()
  const navigate = useNavigate()
  const graph = useMemo(() => readingGraph(canvas), [canvas])
  const names = useMemo(() => new Map(canvas.nodes.map((node) => [node.processId, node.name])), [canvas.nodes])
  const known = useMemo(() => new Set(graph.nodeIds), [graph])
  const state = useMemo(() => parseReadingHash(location.hash, known), [location.hash, known])
  const nameOf = useCallback((processId: string) => names.get(processId) ?? processId, [names])
  const view = useMemo(() => (state ? readingView(graph, state, nameOf) : null), [graph, state, nameOf])
  // undefined: not picking a route; null: waiting for its start; an id: waiting for its end. Picking belongs to the reading
  // the address held when it began, so a link opened in this tab or Back, which names another reading, ends it.
  const readingKey = formatReadingHash(state)
  const [picking, setPicking] = useState<{ from: string | null; during: string } | undefined>(undefined)
  const routeFrom = picking !== undefined && picking.during === readingKey ? picking.from : undefined
  const [pickError, setPickError] = useState<string | null>(null)
  const choices = useMemo(() => (routeFrom === undefined ? null : routeChoices(graph, routeFrom)), [graph, routeFrom])

  // The production run shown over the canvas stays in the address beside the reading; each changes without the other.
  const runId = useMemo(() => parseRunParam(location.hash), [location.hash])
  // So does the published revision the draft is compared with.
  const changesId = useMemo(() => parseChangesParam(location.hash), [location.hash])

  // The query is read from the address when the reading is written, so a change to it that has not rendered yet (the
  // presentation stage) is kept.
  const write = useCallback((next: ReadingState | null, run: string | null = runId, changes: string | null = changesId) => {
    navigate({ pathname: location.pathname, search: window.location.search,
      hash: withViewParams(formatReadingHash(next), run, changes) }, { replace: true })
  }, [changesId, location.pathname, navigate, runId])

  const showRun = useCallback((id: string | null) => write(state, id), [state, write])
  const showChanges = useCallback((id: string | null) => write(state, runId, id), [runId, state, write])

  const reveal = useCallback((processIds: string[]) => bringIntoView?.(processIds), [bringIntoView])

  const show = useCallback((next: ReadingState) => {
    write(next)
    reveal(readingTargets(graph, next))
  }, [graph, reveal, write])

  const cancelRoute = useCallback(() => {
    setPicking(undefined)
    setPickError(null)
  }, [])

  const focus = useCallback((processId: string, direction: ReachDirection | null = null) => {
    cancelRoute()
    show({ kind: 'focus', processId, reach: direction })
  }, [cancelRoute, show])

  const startRoute = useCallback((from: string | null = null) => {
    setPicking({ from, during: readingKey })
    setPickError(null)
  }, [readingKey])

  /**
   * A clicked or found process while a route is being picked: its start, its end, or an error that keeps the picking on.
   * False when no route is being picked.
   */
  const pick = useCallback((processId: string): boolean => {
    if (routeFrom === undefined || !choices) return false
    const error = routePickError(routeFrom, processId, choices, nameOf)
    setPickError(error)
    if (error) return true
    if (routeFrom === null) {
      setPicking({ from: processId, during: readingKey })
    } else {
      setPicking(undefined)
      show({ kind: 'route', from: routeFrom, to: processId })
    }
    return true
  }, [choices, nameOf, readingKey, routeFrom, show])

  const clear = useCallback(() => {
    cancelRoute()
    if (state || location.hash) write(null)
  }, [cancelRoute, location.hash, state, write])

  /** One Esc: stop picking a route, else stop reading; false when there was nothing to stop. */
  const escape = useCallback((): boolean => {
    if (routeFrom !== undefined) {
      cancelRoute()
      return true
    }
    if (!state) return false
    write(null)
    return true
  }, [cancelRoute, routeFrom, state, write])

  const kinds = useMemo(() => kindCounts(graph), [graph])
  const lensKinds = state?.kind === 'lens' ? state.kinds : NO_KINDS

  /** Turns a kind on or off in the lens; the reason when it is refused (a third kind), else null. */
  const toggleKind = useCallback((kind: string): string | null => {
    const next = toggleLensKind(lensKinds, kind)
    if (next.error) return next.error
    cancelRoute()
    if (next.kinds.length === 0) write(null)
    else show({ kind: 'lens', kinds: next.kinds })
    return null
  }, [cancelRoute, lensKinds, show, write])

  const counts = useCallback((processId: string) => reachCounts(graph, processId), [graph])
  const knows = useCallback((processId: string) => known.has(processId), [known])
  const relationsOf = useCallback((processId: string) => processRelations(canvas.edges, processId, known), [canvas.edges, known])

  // A connection shown while its row in the inspector is pointed at; never in the address.
  const [previewId, setPreviewId] = useState<string | null>(null)
  const preview = useMemo(() => (previewId ? connectionPreview(graph, previewId) : null), [graph, previewId])

  // archify's Intent Trace: a process pointed at or focused on the canvas, with its neighbours, before anything is read.
  // Never in the address; whoever shows it decides when it may (nothing read, picked or selected).
  const [intentId, setIntentId] = useState<string | null>(null)
  const intent = useMemo(() => (intentId && known.has(intentId)
    ? readingView(graph, { kind: 'focus', processId: intentId, reach: null }, nameOf)?.highlight ?? null
    : null), [graph, intentId, known, nameOf])

  return {
    state,
    view,
    /**
     * What the canvas marks: a previewed connection, else the processes a route can be picked at while picking, else
     * what is read.
     */
    highlight: preview ?? (choices ? choices.highlight : view?.highlight ?? null),
    picking: routeFrom === undefined || !choices ? null : {
      from: routeFrom,
      /** The processes that can be picked next, with a note for each (the steps to it, once the start is known). */
      choices: choices.notes,
      /** The route panel's title: where to start, or where the path from the start ends. */
      title: routeTitle(routeFrom, null, nameOf),
      text: routePickText(routeFrom, choices.notes.size, nameOf),
      error: pickError,
    },
    /** Reading by kind: the kinds on the canvas with their counts, the one or two chosen, and a way to turn one on or off. */
    lens: { kinds, chosen: lensKinds, toggle: toggleKind },
    /** The process pointed at or focused before it is read (archify's Intent Trace), its highlight, and a way to set it. */
    intent: { id: intent ? intentId : null, highlight: intent, show: setIntentId },
    /** The production run shown over the canvas (its id from the address), and a way to show another or none. */
    run: { id: runId, show: showRun },
    /** The published revision the draft is compared with (its id from the address), and a way to compare with another or none. */
    changes: { id: changesId, show: showChanges },
    nameOf,
    knows,
    counts,
    /** A process's connections leaving, entering and looping on it, for the inspector. */
    relationsOf,
    /** Shows one connection on the canvas for a moment; null puts back what was shown. */
    previewConnection: setPreviewId,
    focus,
    reveal,
    startRoute,
    cancelRoute,
    pick,
    clear,
    escape,
  }
}

export type CanvasReading = ReturnType<typeof useCanvasReading>
