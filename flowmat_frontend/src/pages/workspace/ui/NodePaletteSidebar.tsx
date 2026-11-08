import { useMemo, useState, type DragEvent, type ReactNode } from 'react'
import {
  Archive,
  ChevronDown,
  ChevronRight,
  Cog,
  Diamond,
  LogIn,
  LogOut,
  Merge,
  MousePointer2,
  Search,
  Split,
  Wrench,
} from 'lucide-react'
import { cn } from '../../../lib/utils'
import { PALETTE_DRAG_MIME } from './canvasConstants'
import type { WorkflowNodeDefinition, WorkflowPaletteTool } from '../../../entities/workflow/model/nodeCatalog'

/**
 * How the palette lists the nodes: compact icon rows (the left sidebar design from the jungbin-login-design branch), or
 * the earlier cards that show each node's description.
 */
export type NodePaletteView = 'compact' | 'detailed'

const VIEW_KEY = 'flowmat.nodePaletteView'

/** The view this browser last chose; compact when none was chosen or storage is unavailable. */
function readView(): NodePaletteView {
  try {
    return localStorage.getItem(VIEW_KEY) === 'detailed' ? 'detailed' : 'compact'
  } catch {
    return 'compact'
  }
}

function saveView(view: NodePaletteView) {
  try {
    localStorage.setItem(VIEW_KEY, view)
  } catch {
    // Only a preference: the palette works the same without it.
  }
}

const TOOL_ICONS: Record<string, typeof MousePointer2> = {
  select: MousePointer2,
  process: Cog,
  equipment: Wrench,
  storage: Archive,
  input: LogIn,
  output: LogOut,
  exclusive: Diamond,
  join: Merge,
  split: Split,
}

function startDrag(event: DragEvent<HTMLButtonElement>, tool: string) {
  event.dataTransfer.setData(PALETTE_DRAG_MIME, tool)
  event.dataTransfer.effectAllowed = 'move'
}

function PaletteRow({ tool, label, hint, active, draggable, onClick }: {
  tool: string
  label: string
  hint?: string
  active: boolean
  draggable?: boolean
  onClick(): void
}) {
  const Icon = TOOL_ICONS[tool] ?? Cog
  return (
    <button
      type="button"
      title={hint}
      draggable={draggable}
      onDragStart={draggable ? (event) => startDrag(event, tool) : undefined}
      onClick={onClick}
      className={cn(
        'flex w-full items-center gap-2 rounded-md px-2 py-[7px] text-left text-[13px] transition-colors',
        active
          ? 'bg-[var(--accent-bg)] text-[var(--text-h)]'
          : 'text-[var(--text)] hover:bg-[var(--surface)] hover:text-[var(--text-h)]',
        draggable && 'cursor-grab active:cursor-grabbing',
      )}
    >
      <span
        className={cn(
          'flex h-5 w-5 flex-shrink-0 items-center justify-center',
          active ? 'text-[var(--accent)]' : 'text-[var(--text)] opacity-70',
        )}
      >
        <Icon className="h-3.5 w-3.5" strokeWidth={2} aria-hidden="true" />
      </span>
      <span className="truncate">{label}</span>
    </button>
  )
}

function PaletteCard({ tool, label, description, active, draggable, onClick }: {
  tool: string
  label: string
  description?: string
  active: boolean
  draggable?: boolean
  onClick(): void
}) {
  return (
    <button
      type="button"
      draggable={draggable}
      onDragStart={draggable ? (event) => startDrag(event, tool) : undefined}
      onClick={onClick}
      style={{
        textAlign: 'left',
        padding: '12px 14px',
        borderRadius: '12px',
        border: active ? '1px solid var(--accent)' : '1px solid var(--border)',
        background: active ? 'var(--accent-bg)' : 'var(--surface)',
        display: 'grid',
        gap: '4px',
        cursor: draggable ? 'grab' : 'pointer',
      }}
    >
      <strong>{label}</strong>
      {description && <span style={{ fontSize: '12px', opacity: 0.75 }}>{description}</span>}
    </button>
  )
}

function Section({ title, count, children }: { title: string; count: number; children: ReactNode }) {
  const [open, setOpen] = useState(true)
  return (
    <div className="py-1">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
        className="flex w-full items-center gap-1 px-2 py-1.5 text-[11px] font-semibold uppercase tracking-wide text-[var(--text)] opacity-70 transition-opacity hover:opacity-100"
      >
        {open ? <ChevronDown className="h-3 w-3" aria-hidden="true" /> : <ChevronRight className="h-3 w-3" aria-hidden="true" />}
        <span>{title}</span>
        <span className="ml-auto font-normal normal-case tracking-normal opacity-70">{count}</span>
      </button>
      {open && children}
    </div>
  )
}

/**
 * The workspace's left panel: search the nodes, then click one and click an empty canvas area, or drag it onto the
 * canvas. Annotation, align and group tools are on the ribbon.
 */
export function NodePaletteSidebar({ paletteDefinitions, activeTool, setActiveTool }: {
  paletteDefinitions: WorkflowNodeDefinition[]
  activeTool: WorkflowPaletteTool
  setActiveTool(tool: WorkflowPaletteTool): void
}) {
  const [query, setQuery] = useState('')
  const [view, setView] = useState<NodePaletteView>(readView)
  const needle = query.trim().toLowerCase()
  const showPointer = !needle || 'pointer'.includes(needle)
  const definitions = useMemo(() => (needle
    ? paletteDefinitions.filter((definition) => [definition.label, definition.nodeType, ...(definition.aliases ?? [])]
      .some((text) => text.toLowerCase().includes(needle)))
    : paletteDefinitions), [needle, paletteDefinitions])
  const count = definitions.length + (showPointer ? 1 : 0)

  function chooseView(next: NodePaletteView) {
    setView(next)
    saveView(next)
  }

  const pointer = view === 'compact'
    ? <PaletteRow tool="select" label="Pointer" active={activeTool === 'select'} onClick={() => setActiveTool('select')} />
    : <PaletteCard tool="select" label="Pointer" active={activeTool === 'select'} onClick={() => setActiveTool('select')} />

  return (
    <div className="flex h-full flex-col">
      <div className="flex flex-shrink-0 flex-col gap-2 border-b border-[var(--border)] p-2">
        <div className="relative">
          <Search className="pointer-events-none absolute left-2 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-[var(--text)] opacity-50" aria-hidden="true" />
          <input
            type="search"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Search nodes"
            aria-label="Search nodes"
            className="w-full rounded-md border border-[var(--border)] bg-[var(--bg)] py-1.5 pl-7 pr-2 text-xs text-[var(--text-h)] outline-none transition-colors placeholder:text-[var(--text)] placeholder:opacity-50 focus:border-[var(--accent)]"
          />
        </div>
        <div role="group" aria-label="Palette view" className="flex gap-1 text-[11px]">
          {(['compact', 'detailed'] as const).map((option) => (
            <button
              key={option}
              type="button"
              aria-pressed={view === option}
              onClick={() => chooseView(option)}
              className={cn(
                'flex-1 rounded border px-1.5 py-1 capitalize transition-colors',
                view === option
                  ? 'border-[var(--accent)] bg-[var(--accent-bg)] text-[var(--text-h)]'
                  : 'border-[var(--border)] text-[var(--text)] hover:border-[var(--accent)] hover:text-[var(--text-h)]',
              )}
            >
              {option}
            </button>
          ))}
        </div>
      </div>

      <div className="flex-1 overflow-y-auto">
        <Section title="Node Palette" count={count}>
          {view === 'compact' ? (
            <div className="flex flex-col gap-0.5 px-1.5 pb-1">
              {showPointer && pointer}
              {definitions.map((definition) => (
                <PaletteRow key={definition.tool} tool={definition.tool} label={definition.label}
                  hint={definition.description} active={activeTool === definition.tool} draggable
                  onClick={() => setActiveTool(definition.tool)} />
              ))}
            </div>
          ) : (
            <div style={{ display: 'grid', gap: '10px', padding: '0 10px 10px' }}>
              <p className="panel-placeholder" style={{ margin: 0 }}>
                Pick a workflow node, then click any empty canvas area.
              </p>
              {definitions.map((definition) => (
                <PaletteCard key={definition.tool} tool={definition.tool} label={definition.label}
                  description={definition.description} active={activeTool === definition.tool} draggable
                  onClick={() => setActiveTool(definition.tool)} />
              ))}
              {showPointer && pointer}
            </div>
          )}
          {count === 0 && <p className="px-3 py-2 text-xs text-[var(--text)] opacity-70">No node matches.</p>}
        </Section>
      </div>
    </div>
  )
}
