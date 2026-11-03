import { useMemo, useState } from 'react'
import type { CanvasNodeViewModel } from '../../../entities/workflow/model/types'
import { findProcesses } from '../model/graphReading'

/** Finds a process by name, type or id ("/"); the arrows move through the matches, Enter picks one, Esc closes. */
export function ProcessFinder({ nodes, onPick, onClose }: {
  nodes: CanvasNodeViewModel[]
  onPick(processId: string): void
  onClose(): void
}) {
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)
  const matches = useMemo(() => findProcesses(nodes, query, 12), [nodes, query])
  const current = Math.min(active, Math.max(0, matches.length - 1))

  return (
    <div role="dialog" aria-label="Find a process" className="canvas-reading-popup canvas-reading-popup--finder">
      <input
        autoFocus
        aria-label="Process name, type or id"
        placeholder="Find a process"
        value={query}
        role="combobox"
        aria-expanded={matches.length > 0}
        aria-controls="process-finder-list"
        aria-activedescendant={matches[current] ? `process-finder-${matches[current].processId}` : undefined}
        onChange={(event) => {
          setQuery(event.target.value)
          setActive(0)
        }}
        onKeyDown={(event) => {
          if (event.key === 'Escape') {
            event.preventDefault()
            event.stopPropagation()
            onClose()
          } else if (event.key === 'ArrowDown') {
            event.preventDefault()
            setActive(Math.min(current + 1, matches.length - 1))
          } else if (event.key === 'ArrowUp') {
            event.preventDefault()
            setActive(Math.max(current - 1, 0))
          } else if (event.key === 'Enter' && matches[current]) {
            event.preventDefault()
            onPick(matches[current].processId)
          }
        }}
      />
      <ul id="process-finder-list" role="listbox" aria-label="Matching processes">
        {matches.map((node, index) => (
          <li
            key={node.processId}
            id={`process-finder-${node.processId}`}
            role="option"
            aria-selected={index === current}
            onMouseDown={(event) => {
              event.preventDefault()
              onPick(node.processId)
            }}
          >
            {node.name} <span className="canvas-reading-chip__hint">{node.processType}</span>
          </li>
        ))}
      </ul>
      {query.trim() && matches.length === 0 && <p className="canvas-reading-chip__hint">No process matches.</p>}
    </div>
  )
}
