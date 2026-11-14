import { useMemo, useRef, useState } from 'react'
import { getWorkflowNodeDefinition } from '../../../entities/workflow/model/nodeCatalog'
import type { CanvasNodeViewModel } from '../../../entities/workflow/model/types'
import { findProcesses } from '../model/graphReading'

/** What the finder looks for: any process, or while a route is picked its possible starts or ends, each with a badge. */
export type FinderContext =
  | { kind: 'focus' }
  | { kind: 'route-source'; badges: ReadonlyMap<string, string> }
  | { kind: 'route-target'; fromName: string; badges: ReadonlyMap<string, string> }

const plural = (value: number, one: string, many = `${one}s`) => `${value} ${value === 1 ? one : many}`

function contextCopy(context: FinderContext) {
  if (context.kind === 'route-source') {
    return { title: 'Choose route start', placeholder: 'Search route sources', results: 'Processes that can start a route',
      empty: 'No matching route sources', noun: 'route sources' }
  }
  if (context.kind === 'route-target') {
    return { title: `Destination from ${context.fromName}`, placeholder: 'Search reachable destinations',
      results: 'Reachable route destinations', empty: 'No matching reachable destinations', noun: 'reachable destinations' }
  }
  return { title: 'Find a process', placeholder: 'Search names or IDs', results: 'Processes', empty: 'No matching processes',
    noun: 'processes' }
}

/**
 * archify's node finder (adapted from archify node-finder.js and template.source.html, Apache-2.0), opened with "/": every
 * process (or every possible route start or end, nearest first) as a row with its name, a badge and its kind and id;
 * typing narrows the rows, Enter takes the first, the arrows move through them, Esc or the close button puts focus back.
 */
export function ProcessFinder({ nodes, context, linksOf, onPick, onClose }: {
  nodes: CanvasNodeViewModel[]
  context: FinderContext
  /** How many connections touch a process. */
  linksOf(processId: string): number
  onPick(processId: string): void
  onClose(): void
}) {
  const [query, setQuery] = useState('')
  // Who had focus before this opened, read while it first renders: by the time effects run, the search box has already taken focus (or,
  // with React's StrictMode running effects twice, the second run would read this panel's own button).
  const [opener] = useState(() => document.activeElement as HTMLElement | null)
  const resultsRef = useRef<HTMLDivElement>(null)
  const copy = contextCopy(context)
  const badges = context.kind === 'focus' ? null : context.badges

  const available = useMemo(() => {
    if (!badges) return nodes
    const byId = new Map(nodes.map((node) => [node.processId, node]))
    return [...badges.keys()].flatMap((processId) => byId.get(processId) ?? [])
  }, [badges, nodes])
  const visible = useMemo(() => (query.trim() ? findProcesses(available, query, available.length) : available), [available, query])

  function close() {
    opener?.focus?.()
    onClose()
  }

  function resultButtons(): HTMLButtonElement[] {
    return [...(resultsRef.current?.querySelectorAll<HTMLButtonElement>('.node-finder-result') ?? [])]
  }

  return (
    <div className="node-finder" role="dialog" aria-modal="false" aria-labelledby="node-finder-title" data-context={context.kind}
      onKeyDown={(event) => {
        if (event.key !== 'Escape') return
        event.preventDefault()
        event.stopPropagation()
        close()
      }}>
      <div className="node-finder-head">
        <strong id="node-finder-title">{copy.title}</strong>
        <button type="button" className="node-finder-close" aria-label="Close process finder" onClick={close}>&#215;</button>
      </div>
      <div className="node-finder-search">
        <span aria-hidden="true">&#8981;</span>
        <input className="node-finder-input" type="search" autoFocus autoComplete="off" spellCheck={false}
          placeholder={copy.placeholder} aria-label="Search processes" aria-describedby="node-finder-status" value={query}
          onChange={(event) => setQuery(event.target.value)}
          onKeyDown={(event) => {
            if (event.ctrlKey || event.altKey || event.metaKey || event.shiftKey) return
            if (event.key === 'ArrowDown' && visible.length > 0) {
              event.preventDefault()
              resultButtons()[0]?.focus()
            } else if (event.key === 'Enter' && visible.length > 0) {
              event.preventDefault()
              onPick(visible[0].processId)
            }
          }} />
        <kbd aria-hidden="true">/</kbd>
      </div>
      <div ref={resultsRef} className="node-finder-results" role="group" aria-label={copy.results}
        onKeyDown={(event) => {
          if (event.ctrlKey || event.altKey || event.metaKey || event.shiftKey) return
          const buttons = resultButtons()
          const index = buttons.indexOf(document.activeElement as HTMLButtonElement)
          if (index < 0) return
          const next = event.key === 'ArrowDown' ? (index + 1) % buttons.length
            : event.key === 'ArrowUp' ? (index - 1 + buttons.length) % buttons.length
              : event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : null
          if (next === null) return
          event.preventDefault()
          buttons[next].focus()
        }}>
        {visible.map((node) => {
          const links = linksOf(node.processId)
          const badge = badges?.get(node.processId) ?? plural(links, 'connection')
          const action = context.kind === 'route-source' ? `Choose ${node.name} as route start`
            : context.kind === 'route-target' ? `Choose ${node.name} as route destination, ${badge}`
              : `Focus ${node.name}, ${plural(links, 'related connection')}`
          return (
            <button key={node.processId} type="button" className="node-finder-result" data-node-id={node.processId}
              aria-label={action} onClick={() => onPick(node.processId)}>
              <strong>{node.name}</strong>
              <em data-zero={context.kind === 'focus' && links === 0 ? 'true' : undefined}>{badge}</em>
              <small>{getWorkflowNodeDefinition(node.nodeType).label} · {node.processId}</small>
            </button>
          )
        })}
      </div>
      {visible.length === 0 && <p className="node-finder-empty">{copy.empty}</p>}
      <p className="node-finder-status" id="node-finder-status" aria-live="polite">
        {query.trim() ? `${visible.length} of ${available.length} ${copy.noun}` : `${available.length} ${copy.noun}`}
      </p>
    </div>
  )
}
