import { useEffect, useMemo, useRef, useState } from 'react'
import type { CanvasNodeViewModel } from '../../../entities/workflow/model/types'
import { getWorkflowNodeDefinition, getWorkflowPaletteDefinitions } from '../../../entities/workflow/model/nodeCatalog'

/**
 * archify's node index (adapted from archify viewer/node-outline.js, Apache-2.0): every process in one list beside the
 * diagram, grouped as archify groups by lane, here by kind in palette order, since FlowMat has no lanes. Pointing at or
 * focusing an entry previews the process with its neighbours (Intent Trace); choosing one reads it as the finder does.
 * The process in hand is marked current, and one previewed on the canvas is marked and scrolled into view, so the list
 * answers "where is this?".
 *
 * Whoever may edit the workflow renames from the list, one process after another (settingTool's object list; its
 * behaviour, not its code): F2 or a double-click names the process in place, Enter saves and goes on to the next one
 * in the list, Esc stops. "Rename in order" starts at the top.
 */
export function ProcessIndex({ nodes, currentId, previewId, onPreview, onChoose, onClose, onRename }: {
  nodes: CanvasNodeViewModel[]
  /** The process selected or read, if any. */
  currentId: string | null
  /** The process previewed on the canvas, if any. */
  previewId: string | null
  onPreview(processId: string | null): void
  onChoose(processId: string): void
  onClose(): void
  /** Saves a new name; absent for someone who may not edit the workflow. */
  onRename?(processId: string, name: string): void
}) {
  const listRef = useRef<HTMLDivElement>(null)
  const [renaming, setRenaming] = useState<{ id: string; draft: string } | null>(null)
  const previewRef = useRef(onPreview)
  previewRef.current = onPreview

  const groups = useMemo(() => {
    const byKind = new Map<string, CanvasNodeViewModel[]>()
    for (const node of nodes) {
      const kind = getWorkflowNodeDefinition(node.nodeType).nodeType
      byKind.set(kind, [...(byKind.get(kind) ?? []), node])
    }
    return getWorkflowPaletteDefinitions()
      .filter((definition) => byKind.has(definition.nodeType))
      .map((definition) => ({ kind: definition.nodeType, label: definition.label, nodes: byKind.get(definition.nodeType)! }))
  }, [nodes])

  // The processes in the order the list shows them, for renaming one after another.
  const ordered = useMemo(() => groups.flatMap((group) => group.nodes), [groups])

  function startRename(processId: string) {
    const node = nodes.find((candidate) => candidate.id === processId)
    if (onRename && node) setRenaming({ id: processId, draft: node.name })
  }

  /** Saves the name typed (when it changed and is not blank), then renames the next process, or stops after the last. */
  function saveAndGoOn() {
    if (!renaming || !onRename) return
    const node = ordered.find((candidate) => candidate.id === renaming.id)
    const name = renaming.draft.trim()
    if (node && name && name !== node.name) onRename(node.id, name)
    const next = ordered[ordered.findIndex((candidate) => candidate.id === renaming.id) + 1]
    setRenaming(next ? { id: next.id, draft: next.name } : null)
    if (!next) {
      window.requestAnimationFrame(() => listRef.current
        ?.querySelector<HTMLButtonElement>(`[data-index-process="${CSS.escape(renaming.id)}"]`)?.focus())
    }
  }

  // Nothing stays previewed once the list is gone.
  useEffect(() => () => previewRef.current(null), [])

  // A process previewed on the canvas is brought into the list's view, unless the pointer is already on it here.
  useEffect(() => {
    if (!previewId) return
    const entry = listRef.current?.querySelector<HTMLElement>(`[data-index-process="${CSS.escape(previewId)}"]`)
    if (!entry || entry.matches(':hover')) return
    entry.scrollIntoView?.({ block: 'nearest' })
  }, [previewId])

  return (
    <section className="process-index" role="region" aria-label="Process index"
      onKeyDown={(event) => {
        if (renaming) return
        if (event.key === 'F2' && onRename) {
          const id = (document.activeElement as HTMLElement | null)?.dataset.indexProcess
          if (id) {
            event.preventDefault()
            startRename(id)
          }
          return
        }
        if (event.key === 'Escape') {
          event.preventDefault()
          event.stopPropagation()
          onClose()
        } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
          const entries = [...(listRef.current?.querySelectorAll<HTMLButtonElement>('.process-index-item') ?? [])]
          const at = entries.indexOf(document.activeElement as HTMLButtonElement)
          if (at < 0) return
          event.preventDefault()
          entries[Math.min(entries.length - 1, Math.max(0, at + (event.key === 'ArrowDown' ? 1 : -1)))]?.focus()
        }
      }}>
      <header className="process-index-head">
        <strong>Process index</strong>
        <small>{nodes.length} {nodes.length === 1 ? 'process' : 'processes'}</small>
        {onRename && ordered.length > 0 && (
          <button type="button" className="process-index-rename" title="Rename each process in turn: Enter goes on, Esc stops (F2 on one)"
            onClick={() => startRename(ordered[0].id)}>Rename in order</button>
        )}
        <button type="button" className="process-index-close" aria-label="Close process index" title="Close (Esc)"
          onClick={onClose}>&#215;</button>
      </header>
      <div ref={listRef} className="process-index-list">
        {nodes.length === 0 && <p className="process-index-empty">No processes on the canvas yet.</p>}
        {groups.map((group) => (
          <div key={group.kind} className="process-index-group" role="group" aria-label={`${group.label}, ${group.nodes.length}`}>
            {groups.length > 1 && (
              <span className="process-index-heading" aria-hidden="true">
                <span>{group.label}</span><small>{group.nodes.length}</small>
              </span>
            )}
            {group.nodes.map((node) => renaming?.id === node.id ? (
              <input key={node.id} className="process-index-rename-input" aria-label={`New name for ${node.name}`}
                value={renaming.draft} maxLength={100} autoFocus
                onFocus={(event) => event.target.select()}
                onChange={(event) => setRenaming({ id: node.id, draft: event.target.value })}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') {
                    event.preventDefault()
                    saveAndGoOn()
                  } else if (event.key === 'Escape') {
                    event.preventDefault()
                    event.stopPropagation()
                    setRenaming(null)
                    listRef.current?.querySelector<HTMLButtonElement>(`[data-index-process="${CSS.escape(node.id)}"]`)?.focus()
                  }
                }}
                onBlur={() => setRenaming((current) => (current?.id === node.id ? null : current))} />
            ) : (
              <button key={node.id} type="button" className="process-index-item" data-kind={group.kind}
                data-index-process={node.id} aria-current={node.id === currentId ? 'true' : undefined}
                data-previewing={node.id === previewId ? 'true' : undefined}
                onPointerEnter={(event) => { if (event.pointerType !== 'touch') onPreview(node.id) }}
                onPointerLeave={() => onPreview(null)}
                onFocus={() => onPreview(node.id)}
                onBlur={() => onPreview(null)}
                onClick={() => {
                  onPreview(null)
                  onChoose(node.id)
                }}
                onDoubleClick={() => startRename(node.id)}>
                <span className="process-index-dot" aria-hidden="true" />
                <strong>{node.name}</strong>
                {node.description && <small>{node.description}</small>}
              </button>
            ))}
          </div>
        ))}
      </div>
    </section>
  )
}
