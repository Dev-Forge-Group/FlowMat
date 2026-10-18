import type { CSSProperties } from 'react'

export type WorkflowNodePaletteTool = 'process' | 'equipment' | 'storage' | 'input' | 'output' | 'exclusive' | 'join' | 'split'

export type WorkflowAnnotationTool = 'annotation-shape' | 'annotation-text' | 'annotation-freehand'

export type WorkflowEditorTool =
  | 'editor-rectangle'
  | 'editor-ellipse'
  | 'editor-triangle'
  | 'editor-line'
  | 'editor-connector'
  | 'editor-text'

export type WorkflowPaletteTool =
  | 'select'
  | WorkflowNodePaletteTool
  | WorkflowAnnotationTool
  | WorkflowEditorTool

const WORKFLOW_EDITOR_TOOLS: readonly WorkflowEditorTool[] = [
  'editor-rectangle',
  'editor-ellipse',
  'editor-triangle',
  'editor-line',
  'editor-connector',
  'editor-text',
]

type WorkflowShapeKind = 'block' | 'circle' | 'pill' | 'dashed'

export interface WorkflowNodeDefinition {
  tool: WorkflowNodePaletteTool
  nodeType: string
  processType: string
  colorScheme: string
  label: string
  description: string
  width: number
  height: number
  shapeKind: WorkflowShapeKind
  aliases?: string[]
  defaultConnectionType: string
  isTerminal: boolean
}

const WORKFLOW_NODE_DEFINITIONS: WorkflowNodeDefinition[] = [
  {
    tool: 'process',
    nodeType: 'process',
    processType: 'generic',
    colorScheme: 'slate',
    label: 'Process',
    description: 'General transformation or work step.',
    width: 180,
    height: 88,
    shapeKind: 'block',
    defaultConnectionType: 'material_flow',
    isTerminal: false,
  },
  {
    tool: 'equipment',
    nodeType: 'equipment',
    processType: 'equipment',
    colorScheme: 'amber',
    label: 'Equipment',
    description: 'Machine, tool, or station in the workflow.',
    width: 200,
    height: 96,
    shapeKind: 'block',
    defaultConnectionType: 'equipment_flow',
    isTerminal: false,
  },
  {
    tool: 'storage',
    nodeType: 'storage',
    processType: 'storage',
    colorScheme: 'violet',
    label: 'Storage',
    description: 'Buffer, warehouse, or stock holding step.',
    width: 188,
    height: 88,
    shapeKind: 'pill',
    defaultConnectionType: 'material_flow',
    isTerminal: false,
  },
  {
    tool: 'input',
    nodeType: 'input',
    processType: 'input',
    colorScheme: 'sky',
    label: 'Input',
    description: 'Incoming material or source node.',
    width: 168,
    height: 88,
    shapeKind: 'circle',
    defaultConnectionType: 'material_flow',
    isTerminal: true,
  },
  {
    tool: 'output',
    nodeType: 'output',
    processType: 'output',
    colorScheme: 'emerald',
    label: 'Output',
    description: 'Outgoing result or sink node.',
    width: 180,
    height: 88,
    shapeKind: 'pill',
    aliases: ['decision'],
    defaultConnectionType: 'material_flow',
    isTerminal: true,
  },
  {
    tool: 'exclusive',
    nodeType: 'exclusive',
    processType: 'gateway',
    colorScheme: 'rose',
    label: 'Exclusive',
    description: 'Gateway: sends what arrives down the first connection whose condition holds, by priority; else its default.',
    width: 150,
    height: 72,
    shapeKind: 'dashed',
    defaultConnectionType: 'control_flow',
    isTerminal: false,
  },
  {
    tool: 'join',
    nodeType: 'join',
    processType: 'gateway',
    colorScheme: 'gray',
    label: 'Join',
    description: 'Gateway: waits for every branch that can still reach it, then sends on what arrived, once.',
    width: 150,
    height: 72,
    shapeKind: 'dashed',
    defaultConnectionType: 'control_flow',
    isTerminal: false,
  },
  {
    tool: 'split',
    nodeType: 'split',
    processType: 'gateway',
    colorScheme: 'violet',
    label: 'Split',
    description: 'Gateway: divides the quantity that arrives between its connections: fixed amounts, percents and the rest.',
    width: 150,
    height: 72,
    shapeKind: 'dashed',
    defaultConnectionType: 'material_flow',
    isTerminal: false,
  },
]

const definitionEntries = WORKFLOW_NODE_DEFINITIONS.flatMap((definition) => {
  const keys = [definition.nodeType, ...(definition.aliases ?? [])]
  return keys.map((key) => [key, definition] as const)
})

const definitionMap = new Map<string, WorkflowNodeDefinition>(definitionEntries)

export function getWorkflowPaletteDefinitions() {
  return WORKFLOW_NODE_DEFINITIONS
}

export function getWorkflowDefaultNodeDefinition() {
  return WORKFLOW_NODE_DEFINITIONS[0]
}

export function getWorkflowNodeDefinition(nodeType: string | null | undefined) {
  if (!nodeType) return getWorkflowDefaultNodeDefinition()
  return definitionMap.get(nodeType.trim().toLowerCase()) ?? getWorkflowDefaultNodeDefinition()
}

export function getDefaultConnectionType(fromNodeType: string | null | undefined): string {
  return getWorkflowNodeDefinition(fromNodeType).defaultConnectionType
}

export function getWorkflowNodeStyle(nodeType: string | null | undefined): CSSProperties {
  const definition = getWorkflowNodeDefinition(nodeType)

  switch (definition.shapeKind) {
    case 'circle':
      return { borderRadius: '999px' }
    case 'pill':
      return { borderRadius: '28px' }
    case 'dashed':
      return { borderRadius: '10px', borderStyle: 'dashed' }
    case 'block':
    default:
      return { borderRadius: '16px' }
  }
}

export function isWorkflowEditorTool(tool: WorkflowPaletteTool): tool is WorkflowEditorTool {
  return WORKFLOW_EDITOR_TOOLS.includes(tool as WorkflowEditorTool)
}
