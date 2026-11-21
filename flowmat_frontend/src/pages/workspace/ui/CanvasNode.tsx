import { type CSSProperties, useEffect, useRef, useState } from 'react'
import {
  Handle,
  NodeResizer,
  NodeToolbar,
  Position,
  useConnection,
  useNodeConnections,
  type NodeProps,
} from '@xyflow/react'
import type { CanvasNodeViewModel, CanvasPortViewModel } from '../../../entities/workflow/model/types'
import {
  getWorkflowNodeDefinition,
  getWorkflowNodeStyle,
} from '../../../entities/workflow/model/nodeCatalog'
import { useWorkspaceStore } from '../model/workspaceStore'
import { useCanvasInteractionStore } from '../model/canvasInteractionStore'
import { issueMarkLabel, useIssueMarksStore } from '../model/issueMarks'

const COLOR_MAP: Record<string, string> = {
  sky: '#0ea5e9',
  emerald: '#10b981',
  amber: '#f59e0b',
  rose: '#f43f5e',
  violet: '#8b5cf6',
  slate: '#64748b',
  gray: '#6b7280',
}

function resolveColor(scheme: string): string {
  return COLOR_MAP[scheme] ?? scheme
}

interface PortRowProps {
  port: CanvasPortViewModel
  onSelect(processIoId: string): void
}

function usePortHandleState(port: CanvasPortViewModel, handleType: 'source' | 'target') {
  const connection = useConnection()
  const baseColor = resolveColor(port.colorScheme)
  const isConnecting = connection.inProgress
  const draggedFromType = isConnecting ? connection.fromHandle.type : null
  const expectsThisHandleType =
    isConnecting &&
    ((handleType === 'target' && draggedFromType === 'source') ||
      (handleType === 'source' && draggedFromType === 'target'))

  const isOrigin =
    isConnecting &&
    connection.fromHandle.nodeId === port.processId &&
    connection.fromHandle.id === port.handleId

  const isHovered =
    isConnecting &&
    connection.toHandle?.nodeId === port.processId &&
    connection.toHandle?.id === port.handleId

  const isTargeted = isHovered && connection.isValid === true
  const isRejected = isHovered && connection.isValid === false
  const isCandidate = expectsThisHandleType && !isTargeted && !isRejected

  const background = isOrigin
    ? '#6366f1'
    : isTargeted
      ? '#22c55e'
      : isRejected
        ? '#ef4444'
        : isCandidate
          ? '#f8fafc'
          : baseColor

  const boxShadow = isOrigin
    ? '0 0 0 3px #c7d2fe'
    : isTargeted
      ? '0 0 0 3px #bbf7d0'
      : isRejected
        ? '0 0 0 3px #fecaca'
        : isCandidate
          ? '0 0 0 2px rgba(99, 102, 241, 0.18)'
          : undefined

  return {
    rowClassName: [
      'canvas-node__port',
      handleType === 'target' ? 'canvas-node__port--input' : 'canvas-node__port--output',
      isCandidate ? 'canvas-node__port--candidate' : '',
      isTargeted ? 'canvas-node__port--targeted' : '',
      isRejected ? 'canvas-node__port--rejected' : '',
      isOrigin ? 'canvas-node__port--origin' : '',
    ]
      .filter(Boolean)
      .join(' '),
    handleStyle: {
      background,
      boxShadow,
      transition: 'background 0.15s, box-shadow 0.15s',
    },
  }
}

function InputPortRow({ port, onSelect }: PortRowProps) {
  const { rowClassName, handleStyle } = usePortHandleState(port, 'target')
  const connections = useNodeConnections({ handleType: 'target', handleId: port.handleId })
  const connCount = connections.length

  return (
    <div className={rowClassName}>
      <Handle
        type="target"
        position={Position.Left}
        id={port.handleId}
        style={handleStyle}
        onClick={() => onSelect(port.processIoId)}
      />
      <span className="canvas-node__port-name">{port.name}</span>
      {connCount > 0 && (
        <span
          className="canvas-node__port-badge"
          title={`${connCount} connection${connCount === 1 ? '' : 's'}`}
        >
          {connCount}
        </span>
      )}
    </div>
  )
}

function OutputPortRow({ port, onSelect }: PortRowProps) {
  const { rowClassName, handleStyle } = usePortHandleState(port, 'source')
  const connections = useNodeConnections({ handleType: 'source', handleId: port.handleId })
  const connCount = connections.length

  return (
    <div className={rowClassName}>
      {connCount > 0 && (
        <span
          className="canvas-node__port-badge"
          title={`${connCount} connection${connCount === 1 ? '' : 's'}`}
        >
          {connCount}
        </span>
      )}
      <span className="canvas-node__port-name">{port.name}</span>
      <Handle
        type="source"
        position={Position.Right}
        id={port.handleId}
        style={handleStyle}
        onClick={() => onSelect(port.processIoId)}
      />
    </div>
  )
}

type CanvasNodeComponentProps = Omit<NodeProps, 'data'> & {
  data: CanvasNodeViewModel
}

export function CanvasNode({ data: node, selected }: CanvasNodeComponentProps) {
  const inlineEditingNodeId = useWorkspaceStore((s) => s.inlineEditingNodeId)
  const selectPort = useWorkspaceStore((s) => s.selectPort)
  const startInlineEdit = useWorkspaceStore((s) => s.startInlineEdit)
  const stopInlineEdit = useWorkspaceStore((s) => s.stopInlineEdit)
  const commitRename = useWorkspaceStore((s) => s.commitRename)
  const activeColorPickerNodeId = useWorkspaceStore((s) => s.activeColorPickerNodeId)
  const openColorPicker = useWorkspaceStore((s) => s.openColorPicker)
  const closeColorPicker = useWorkspaceStore((s) => s.closeColorPicker)
  const requestDeleteNode = useCanvasInteractionStore((s) => s.requestDeleteNode)
  const requestColorChange = useCanvasInteractionStore((s) => s.requestColorChange)
  const requestDuplicateNode = useCanvasInteractionStore((s) => s.requestDuplicateNode)
  const issueMark = useIssueMarksStore((s) => s.marks.processes[node.processId])
  const isColorPickerOpen = activeColorPickerNodeId === node.id
  const editing = inlineEditingNodeId === node.id
  const [draftName, setDraftName] = useState(node.name)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    setDraftName(node.name)
  }, [node.name])

  useEffect(() => {
    if (editing) inputRef.current?.select()
  }, [editing])

  useEffect(() => {
    if (!selected && isColorPickerOpen) closeColorPicker()
  }, [selected, isColorPickerOpen, closeColorPicker])

  const headerColor = resolveColor(node.colorScheme)
  const nodeDefinition = getWorkflowNodeDefinition(node.nodeType)

  return (
    <>
    {/* Beside the node's box, so it sits on the corner of React Flow's node wrapper without moving the handles. */}
    {issueMark && (
      <span role="img" aria-label={issueMarkLabel(issueMark)} title={issueMarkLabel(issueMark)}
        className={`canvas-issue-mark canvas-issue-mark--node ${issueMark.errors > 0 ? 'canvas-issue-mark--error' : 'canvas-issue-mark--warning'}`}>
        {issueMark.errors > 0 ? issueMark.errors : issueMark.warnings}
      </span>
    )}
    <div
      className={`canvas-node ${selected ? 'canvas-node--selected' : ''} ${editing ? 'canvas-node--editing' : ''}`}
      style={getWorkflowNodeStyle(node.nodeType) as CSSProperties}
    >
      <NodeToolbar isVisible={selected} position={Position.Top} offset={6} align="end">
        <div className="node-toolbar__group">
          <button
            type="button"
            className="node-toolbar__btn node-toolbar__btn--swatch nodrag nopan"
            title="Change node color"
            aria-label="Change node color"
            onClick={(e) => {
              e.stopPropagation()
              isColorPickerOpen ? closeColorPicker() : openColorPicker(node.id)
            }}
            style={{ background: resolveColor(node.colorScheme), border: '2px solid white' }}
          />
          {isColorPickerOpen && (
            <div className="color-picker-popup nodrag nopan">
              {Object.entries(COLOR_MAP).map(([scheme, hex]) => (
                <button
                  key={scheme}
                  type="button"
                  className="color-picker-popup__chip"
                  style={{
                    background: hex,
                    outline: node.colorScheme === scheme ? '2px solid var(--accent)' : 'none',
                  }}
                  title={scheme}
                  onClick={(e) => {
                    e.stopPropagation()
                    closeColorPicker()
                    requestColorChange(node.id, scheme)
                  }}
                />
              ))}
            </div>
          )}
          <button
            type="button"
            className="node-toolbar__btn nodrag nopan"
            title="Rename node"
            aria-label="Rename node"
            onClick={(e) => {
              e.stopPropagation()
              closeColorPicker()
              startInlineEdit(node.id)
            }}
          >
            Aa
          </button>
          <button
            type="button"
            className="node-toolbar__btn nodrag nopan"
            title="Duplicate node (Ctrl+D)"
            aria-label="Duplicate node"
            onClick={(e) => {
              e.stopPropagation()
              closeColorPicker()
              requestDuplicateNode(node.id)
            }}
          >
            Copy
          </button>
          <button
            type="button"
            className="node-toolbar__btn node-toolbar__btn--delete nodrag nopan"
            title="Delete node (Delete)"
            aria-label="Delete node"
            onClick={(e) => {
              e.stopPropagation()
              closeColorPicker()
              requestDeleteNode(node.id)
            }}
          >
            Del
          </button>
        </div>
      </NodeToolbar>
      <NodeResizer
        isVisible={selected}
        minWidth={120}
        minHeight={60}
        lineStyle={{ borderColor: 'var(--accent)', borderWidth: 1 }}
        handleStyle={{
          width: 8,
          height: 8,
          borderRadius: 2,
          background: 'white',
          border: '1.5px solid var(--accent)',
        }}
      />
      <div className="canvas-node__header" style={{ borderTopColor: headerColor }}>
        <span
          className="canvas-node__color-chip"
          style={{ background: headerColor }}
          title={node.colorScheme}
        />
        {editing ? (
          <input
            ref={inputRef}
            className="canvas-node__name-input nodrag"
            value={draftName}
            onChange={(e) => setDraftName(e.target.value)}
            onBlur={() => commitRename(node.id, draftName.trim() || node.name)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') commitRename(node.id, draftName.trim() || node.name)
              if (e.key === 'Escape') {
                setDraftName(node.name)
                stopInlineEdit()
              }
            }}
          />
        ) : (
          <span className="canvas-node__name">{node.name}</span>
        )}
        <span className="canvas-node__type">{nodeDefinition.label}</span>
        {node.editingByUserId && (
          <span className="canvas-node__editing-badge" title={`${node.editingByUserId} is editing`}>
            Edit
          </span>
        )}

      </div>

      {node.inputs.length > 0 && (
        <div className="canvas-node__section">
          {node.inputs.map((port) => (
            <InputPortRow key={port.id} port={port} onSelect={selectPort} />
          ))}
        </div>
      )}

      {node.outputs.length > 0 && (
        <div className="canvas-node__section">
          {node.outputs.map((port) => (
            <OutputPortRow key={port.id} port={port} onSelect={selectPort} />
          ))}
        </div>
      )}

      {node.inputs.length === 0 && <Handle type="target" position={Position.Left} id="in-default" />}
      {node.outputs.length === 0 && <Handle type="source" position={Position.Right} id="out-default" />}
    </div>
    </>
  )
}
