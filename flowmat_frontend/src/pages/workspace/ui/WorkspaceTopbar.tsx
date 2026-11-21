import { useEffect, useRef, useState, type MutableRefObject } from 'react'
import { Link } from 'react-router-dom'
import type { WorkflowHeaderViewModel } from '../../../entities/workflow/model/types'
import type { WorkflowPaletteTool } from '../../../entities/workflow/model/nodeCatalog'
import { getToolLabel } from '../model/workspaceTools'

interface Props {
  workflow: WorkflowHeaderViewModel
  activeTool: WorkflowPaletteTool
  paletteDefinitions: Parameters<typeof getToolLabel>[1]
  /** Whether the name is being edited, for collaboration, which holds back a remote rename meanwhile. */
  editingRef: MutableRefObject<boolean>
  onRename(name: string): Promise<unknown>
}

/** The workspace's top bar: the way home, the workflow's name (double-click to rename) and what the current tool does. */
export function WorkspaceTopbar({ workflow, activeTool, paletteDefinitions, editingRef, onRename }: Props) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(workflow.workflowName)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    editingRef.current = editing
    if (editing) inputRef.current?.select()
  }, [editing, editingRef])

  async function commit() {
    setEditing(false)
    const trimmed = draft.trim()
    if (!trimmed || trimmed === workflow.workflowName) return
    await onRename(trimmed)
  }

  return (
    <header className="workspace-topbar">
      <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
        <Link to="/" className="topbar-home-link" title="Back to home">Home</Link>
        <div style={{ display: 'grid', gap: '4px' }}>
          {editing ? (
            <input
              ref={inputRef}
              className="workspace-topbar__name-input"
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              onBlur={() => void commit()}
              onKeyDown={(e) => {
                if (e.key === 'Enter') void commit()
                if (e.key === 'Escape') {
                  setDraft(workflow.workflowName)
                  setEditing(false)
                }
              }}
            />
          ) : (
            <span
              className="workspace-topbar__project"
              title="Double-click to rename workflow"
              onDoubleClick={() => {
                setDraft(workflow.workflowName)
                setEditing(true)
              }}
            >
              {workflow.workflowName}
            </span>
          )}
          <span className="workspace-topbar__status">
            {workflow.workflowStatus} | {workflow.workflowType}
          </span>
        </div>
        {/* The workflow switcher, save status and presence are on the ribbon's Collaborate tab. */}
      </div>
      <div style={{ display: 'flex', gap: '12px', alignItems: 'center', flexWrap: 'wrap', justifyContent: 'flex-end' }}>
        {/* Pointer and annotation tools, undo, redo, layout and export are on the ribbon (Home and Annotate tabs). */}
        <span style={{ fontSize: '13px', opacity: 0.8 }}>
          Current tool: {getToolLabel(activeTool, paletteDefinitions)}.{' '}
          {toolHint(activeTool)}
        </span>
      </div>
    </header>
  )
}

function toolHint(tool: WorkflowPaletteTool): string {
  if (tool === 'annotation-freehand') {
    return 'Drag on empty canvas to sketch. Collaborators see a live preview before the final stroke is saved.'
  }
  if (tool === 'annotation-shape') return 'Click empty canvas to drop a shape annotation.'
  if (tool === 'annotation-text') return 'Click empty canvas to drop a text annotation.'
  if (tool.startsWith('editor-')) return 'Click empty canvas to create a backend editor element.'
  return 'Click the canvas to place a node, then drag between handles to connect.'
}
