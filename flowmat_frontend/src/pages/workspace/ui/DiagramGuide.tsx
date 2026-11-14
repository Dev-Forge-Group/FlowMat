import { useEffect, useRef } from 'react'

export type GuideAction = 'find' | 'route' | 'map' | 'lens' | 'runs' | 'changes' | 'present'

const plural = (value: number, one: string, many = `${one}s`) => `${value} ${value === 1 ? one : many}`

/** archify's numbered actions, with FlowMat's run and revision views before the presentation stage. */
const ACTIONS: { action: GuideAction; title: string; aria: string; hint: string; key?: string }[] = [
  { action: 'find', title: 'Find any process', aria: 'Find any process', hint: 'Search names, kinds and stable IDs.', key: '/' },
  { action: 'route', title: 'Trace a route', aria: 'Trace a directed route', hint: 'Ask how two processes connect along the arrows.' },
  { action: 'map', title: 'See the whole workflow', aria: 'See the whole workflow',
    hint: 'Open Semantic Radar with a live viewport and stable processes.', key: 'M' },
  { action: 'lens', title: 'Compare process kinds', aria: 'Compare process kinds',
    hint: 'Count kinds, reveal their traffic, and compare direct connections.' },
  { action: 'runs', title: 'Show a production run', aria: 'Show a production run',
    hint: 'Mark each process with its latest step: ○ ▶ ✓ ✕ ↷ ⊘.' },
  { action: 'changes', title: 'Compare with a revision', aria: 'Compare with a revision',
    hint: '+ added, Δ changed since a published revision; removed ones listed by name.' },
  { action: 'present', title: 'Enter Presentation Stage', aria: 'Enter Presentation Stage',
    hint: 'Give the canvas the whole window; the editing panels wait outside.', key: 'F' },
]

const SHORTCUTS: [string, string][] = [['?', 'Guide'], ['S', 'Style'], ['E', 'Export'], ['0', 'Reset'], ['+', 'Zoom in'], ['-', 'Zoom out'],
  ['Esc', 'Close']]

/**
 * archify's Diagram guide (adapted from archify template.source.html and viewer.css, Apache-2.0), opened with "?": what is
 * on the canvas, the ways to explore it as numbered actions with their keys, and the other shortcuts. Reading only
 * highlights what the workflow defines; nothing is changed or saved. Esc or the close button puts focus back.
 */
export function DiagramGuide({ processes, connections, onAction, onClose }: {
  processes: number
  connections: number
  onAction(action: GuideAction): void
  onClose(): void
}) {
  const actionsRef = useRef<HTMLDivElement>(null)
  const openerRef = useRef<HTMLElement | null>(null)

  useEffect(() => {
    openerRef.current = document.activeElement as HTMLElement | null
    actionsRef.current?.querySelector<HTMLButtonElement>('.diagram-guide-action')?.focus()
  }, [])

  function close() {
    openerRef.current?.focus?.()
    onClose()
  }

  return (
    <div className="diagram-guide" role="dialog" aria-modal="false" aria-labelledby="diagram-guide-title"
      onKeyDown={(event) => {
        if (event.key !== 'Escape') return
        event.preventDefault()
        event.stopPropagation()
        close()
      }}>
      <div className="diagram-guide-head">
        <span>
          <span className="diagram-guide-eyebrow">Diagram guide</span>
          <strong className="diagram-guide-title" id="diagram-guide-title">Explore this workflow</strong>
        </span>
        <button type="button" className="diagram-guide-close" aria-label="Close diagram guide" onClick={close}>&#215;</button>
      </div>
      <p className="diagram-guide-stats">{plural(processes, 'process', 'processes')} · {plural(connections, 'connection')}</p>
      <div ref={actionsRef} className="diagram-guide-actions" role="group" aria-label="Diagram exploration actions">
        {ACTIONS.map((entry, index) => (
          <button key={entry.action} type="button" className="diagram-guide-action" data-guide-action={entry.action}
            aria-label={entry.aria} onClick={() => onAction(entry.action)}>
            <span className="diagram-guide-index" aria-hidden="true">{String(index + 1).padStart(2, '0')}</span>
            <span className="diagram-guide-action-copy"><strong>{entry.title}</strong><small>{entry.hint}</small></span>
            {entry.key ? <kbd aria-hidden="true">{entry.key}</kbd> : <span aria-hidden="true" />}
          </button>
        ))}
      </div>
      <div className="diagram-guide-shortcuts" aria-label="Additional keyboard shortcuts">
        {SHORTCUTS.map(([key, label]) => <span key={key}><kbd>{key}</kbd> {label}</span>)}
      </div>
      <a className="diagram-guide-credit" href="https://github.com/tt-a1i/archify" target="_blank" rel="noopener noreferrer">
        <span>Reading tools adapted from Archify (Apache-2.0)</span>
        <span className="diagram-guide-credit-link">Archify <span aria-hidden="true">&#8599;</span></span>
      </a>
    </div>
  )
}
