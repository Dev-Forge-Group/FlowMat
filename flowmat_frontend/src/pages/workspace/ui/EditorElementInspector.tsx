import type { MutableRefObject } from 'react'
import {
  editorElementLabel,
  hasGroupedEditorSelection,
  isShapeEditorElement,
  readEditorSelectionStyle,
  toHexColor,
} from '../model/workspaceEditorElements'
import type { WorkspaceEditorCommandApi, WorkspaceEditorSelectionSnapshot } from './WorkspaceEditorLayer'

/** The inspector for selected editor elements: commands, colours, width, opacity and text. */
export function EditorElementInspector({
  selection,
  commandsRef,
}: {
  selection: WorkspaceEditorSelectionSnapshot
  commandsRef: MutableRefObject<WorkspaceEditorCommandApi | null>
}) {
  const selectedCount = selection.elements.length
  const firstElement = selection.elements[0] ?? null
  const summaryLabel = selectedCount === 1 && firstElement
    ? editorElementLabel(firstElement)
    : `${selectedCount} editor elements`
  const style = readEditorSelectionStyle(selection.elements)
  const supportsFill = selection.elements.some(isShapeEditorElement)
  const supportsStroke = selection.elements.some((element) => (
    isShapeEditorElement(element) || element.type === 'line' || element.type === 'freehand' || element.type === 'text'
  ))
  const supportsText = firstElement?.type === 'text' && selectedCount === 1
  const grouped = hasGroupedEditorSelection(selection.elements)

  return (
    <div className="inspector editor-inspector">
      <h2 className="inspector__title">{summaryLabel}</h2>
      <div className="editor-inspector__commands">
        <button type="button" onClick={() => commandsRef.current?.undo()} disabled={!commandsRef.current || !selection.canUndo}>
          Undo
        </button>
        <button type="button" onClick={() => commandsRef.current?.redo()} disabled={!commandsRef.current || !selection.canRedo}>
          Redo
        </button>
        <button type="button" onClick={() => commandsRef.current?.duplicateSelected()} disabled={!commandsRef.current}>
          Duplicate
        </button>
        <button type="button" onClick={() => commandsRef.current?.deleteSelected()} disabled={!commandsRef.current}>
          Delete
        </button>
        <button type="button" onClick={() => commandsRef.current?.groupSelected()} disabled={!commandsRef.current || selectedCount < 2}>
          Group
        </button>
        <button type="button" onClick={() => commandsRef.current?.ungroupSelected()} disabled={!commandsRef.current || !grouped}>
          Ungroup
        </button>
        <button type="button" onClick={() => commandsRef.current?.sendSelectedToBack()} disabled={!commandsRef.current}>
          Back
        </button>
        <button type="button" onClick={() => commandsRef.current?.bringSelectedToFront()} disabled={!commandsRef.current}>
          Front
        </button>
      </div>
      <div className="inspector__section">
        <label className="editor-inspector__field">
          <span>Fill</span>
          <input
            type="color"
            disabled={!supportsFill}
            value={toHexColor(style.fill, '#ffffff')}
            onChange={(event) => commandsRef.current?.updateSelectedStyle({ fill: event.target.value })}
          />
        </label>
        <label className="editor-inspector__field">
          <span>Stroke</span>
          <input
            type="color"
            disabled={!supportsStroke}
            value={toHexColor(style.stroke, '#111827')}
            onChange={(event) => commandsRef.current?.updateSelectedStyle({ stroke: event.target.value, color: event.target.value })}
          />
        </label>
        <label className="editor-inspector__field">
          <span>Width</span>
          <input
            type="number"
            min={0}
            max={24}
            step={0.5}
            disabled={!supportsStroke}
            value={style.strokeWidth}
            onChange={(event) => commandsRef.current?.updateSelectedStyle({ strokeWidth: Number(event.target.value) })}
          />
        </label>
        <label className="editor-inspector__field">
          <span>Opacity</span>
          <input
            type="range"
            min={0.05}
            max={1}
            step={0.05}
            value={style.opacity}
            onChange={(event) => commandsRef.current?.updateSelectedStyle({ opacity: Number(event.target.value) })}
          />
        </label>
        {supportsText && (
          <>
            <label className="editor-inspector__field">
              <span>Text</span>
              <input
                type="text"
                value={firstElement.text}
                onChange={(event) => commandsRef.current?.updateSelectedStyle({ text: event.target.value })}
              />
            </label>
            <label className="editor-inspector__field">
              <span>Size</span>
              <input
                type="number"
                min={8}
                max={96}
                step={1}
                value={firstElement.style.fontSize}
                onChange={(event) => commandsRef.current?.updateSelectedStyle({ fontSize: Number(event.target.value) })}
              />
            </label>
          </>
        )}
      </div>
    </div>
  )
}
