import { Circle, Minus, Pencil, Shapes, Square, Triangle, Type as TypeIcon, Waypoints } from 'lucide-react'
import type { WorkflowEditorTool, WorkflowPaletteTool } from '../../../entities/workflow/model/nodeCatalog'
import type { useWorkflowCanvasActions } from './useWorkflowCanvasActions'

/** The workspace's annotation and editor tools, their names and their keyboard shortcuts. */

export const ANNOTATION_TOOL_DEFINITIONS: Array<{
  tool: Extract<WorkflowPaletteTool, 'annotation-shape' | 'annotation-text' | 'annotation-freehand'>
  label: string
  description: string
  icon: typeof Square
}> = [
  { tool: 'annotation-shape', label: 'Shape', description: 'Place shape annotations on the canvas.', icon: Shapes },
  { tool: 'annotation-text', label: 'Text', description: 'Drop standalone notes on the canvas.', icon: TypeIcon },
  {
    tool: 'annotation-freehand',
    label: 'Freehand',
    description: 'Sketch directly with live collaborator previews.',
    icon: Pencil,
  },
]

export const EDITOR_TOOL_DEFINITIONS: Array<{
  tool: WorkflowEditorTool
  label: string
  description: string
  icon: typeof Square
}> = [
  { tool: 'editor-rectangle', label: 'Rectangle', description: 'Create backend editor rectangles.', icon: Square },
  { tool: 'editor-ellipse', label: 'Ellipse', description: 'Create backend editor circles and ovals.', icon: Circle },
  { tool: 'editor-triangle', label: 'Triangle', description: 'Create backend editor polygon triangles.', icon: Triangle },
  { tool: 'editor-line', label: 'Line', description: 'Create backend editor lines.', icon: Minus },
  {
    tool: 'editor-connector',
    label: 'Connector',
    description: 'Drag between two shapes to draw a line that follows them.',
    icon: Waypoints,
  },
  { tool: 'editor-text', label: 'Text', description: 'Create backend editor text.', icon: TypeIcon },
]

export function getToolLabel(
  tool: WorkflowPaletteTool,
  paletteDefinitions: ReturnType<typeof useWorkflowCanvasActions>['paletteDefinitions']
) {
  if (tool === 'select') return 'Pointer'
  const editorDefinition = EDITOR_TOOL_DEFINITIONS.find((definition) => definition.tool === tool)
  if (editorDefinition) return editorDefinition.label
  const annotationDefinition = ANNOTATION_TOOL_DEFINITIONS.find((definition) => definition.tool === tool)
  if (annotationDefinition) return annotationDefinition.label
  return paletteDefinitions.find((definition) => definition.tool === tool)?.label ?? tool
}

export function getWorkspaceToolShortcut(event: KeyboardEvent): WorkflowPaletteTool | null {
  if (event.ctrlKey || event.metaKey || event.altKey) return null
  switch (event.key.toLowerCase()) {
    case 'v':
      return 'select'
    case 'r':
      return 'editor-rectangle'
    case 'o':
      return 'editor-ellipse'
    case 'l':
      return 'editor-line'
    case 'c':
      return 'editor-connector'
    case 't':
      return 'editor-text'
    default:
      return null
  }
}

export function getUndoRedoShortcut(event: KeyboardEvent): 'undo' | 'redo' | null {
  if (!(event.ctrlKey || event.metaKey) || event.altKey) return null
  const key = event.key.toLowerCase()
  if (key === 'z') return event.shiftKey ? 'redo' : 'undo'
  if (key === 'y') return 'redo'
  return null
}
