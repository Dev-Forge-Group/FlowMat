// View Models (what components consume)

export interface WorkflowHeaderViewModel {
  workflowId: string
  projectId: string
  workflowName: string
  workflowDesc: string | null
  workflowType: string
  workflowStatus: string
  currentUserRole: 'viewer' | 'editor' | 'owner'
}

export interface CanvasPortViewModel {
  id: string
  processIoId: string
  processId: string
  itemId: string | null
  name: string
  direction: 'input' | 'output'
  ioType: string
  role: string | null
  resourceType: string
  schemaJson: Record<string, unknown> | null
  validationRule: string | null
  quantity: string
  unit: string | null
  formula: string | null
  colorScheme: string
  required: boolean
  allowShortage: boolean
  handleId: string
}

export interface CanvasNodeViewModel {
  id: string
  processId: string
  projectId: string
  workflowId: string
  name: string
  processType: string
  nodeType: string
  status: string
  colorScheme: string
  position: { x: number; y: number }
  size: { width: number; height: number }
  description: string | null
  inputs: CanvasPortViewModel[]
  outputs: CanvasPortViewModel[]
  inputCount: number
  outputCount: number
  version: number
  versionNonce: number
  /** Set to the remote user's userId when they are actively editing this node. */
  editingByUserId?: string | null
}

export interface CanvasEdgeViewModel {
  id: string
  connectionId: string
  source: string
  target: string
  sourceHandle: string
  targetHandle: string
  fromProcessId: string
  toProcessId: string
  fromIoId: string | null
  toIoId: string | null
  itemId?: string | null
  connectionType: string
  label: string | null
  flowRate: string | null
  unit: string | null
  delayTimeSec: number | null
  /** What the delay counts in production runs (ADR-022). */
  delayBasis?: DelayBasis
  lossRate: number | null
  priority: number | null
  conditionExpr: string | null
  capacity: number | null
  failurePolicy: 'stop' | 'skip' | 'retry'
  /** An Exclusive gateway's default connection (ADR-006). */
  defaultRoute?: boolean
  /** A Split gateway's share (ADR-008). */
  splitKind?: 'share' | 'fixed' | 'rest' | null
  splitValue?: number | null
  version: number
  versionNonce: number
}

/** All time, or only the work order's working time in production runs (docs/architecture/adr/ADR-022-working-time-delay.md). */
export type DelayBasis = 'elapsed' | 'working'

export type CanvasAnnotationType = 'shape' | 'freehand' | 'text'
export type CanvasAnnotationShapeKind = 'rectangle' | 'ellipse' | 'diamond'

export interface CanvasAnnotationPoint {
  x: number
  y: number
}

export interface CanvasAnnotationViewModel {
  id: string
  annotationId: string
  workflowId: string
  projectId: string
  annotationType: CanvasAnnotationType
  shapeKind: CanvasAnnotationShapeKind | null
  position: { x: number; y: number }
  size: { width: number; height: number }
  rotation: number
  points: CanvasAnnotationPoint[]
  textContent: string | null
  style: Record<string, unknown>
  zIndex: string
  groupId: string | null
  locked: boolean
  version: number
  versionNonce: number
}

export interface WorkflowCanvasViewModel {
  workflow: WorkflowHeaderViewModel
  graphSeq: number
  nodes: CanvasNodeViewModel[]
  edges: CanvasEdgeViewModel[]
  annotations: CanvasAnnotationViewModel[]
  nodeMap: Record<string, CanvasNodeViewModel>
  portMap: Record<string, CanvasPortViewModel>
  annotationMap: Record<string, CanvasAnnotationViewModel>
}

// Mutation input types

export interface UpdateProcessInput {
  processId: string
  processName?: string
  nodeType?: string
  processType?: string
  colorScheme?: string
  processDesc?: string
  processStatus?: string
  posX?: number
  posY?: number
  width?: number
  height?: number
}

export interface CreateProcessIoInput {
  processId: string
  /** Optional catalog item binding; data, file and API ports have none (ADR-003). */
  itemId?: string
  ioName: string
  direction: 'input' | 'output'
  ioType: string
  role?: string
  resourceType?: string
  schemaJson?: Record<string, unknown>
  validationRule?: string
  /** Needed by material and product ports and ports bound to an item only (docs/domain/port-measurement.md PM2-PM4). */
  quantity?: number
  unit?: string
  formula?: string
  colorScheme?: string
  requiredYn?: 'Y' | 'N'
  allowShortageYn?: 'Y' | 'N'
}

export interface UpdateProcessIoInput extends Partial<Omit<CreateProcessIoInput, 'processId'>> {
  clearItem?: boolean
  clearSchema?: boolean
  /** Empties quantity and unit; one sent along is set again (PM5). */
  clearMeasure?: boolean
  processIoId: string
}

export interface UpdateProcessConnectionInput {
  connectionId: string
  connectionLabel?: string | null
  connectionType?: string
  flowRate?: number | null
  unit?: string | null
  delayTimeSec?: number | null
  delayBasis?: DelayBasis
  lossRate?: number | null
  priority?: number | null
  conditionExpr?: string | null
  capacity?: number | null
  clearCapacity?: boolean
  failurePolicy?: 'stop' | 'skip' | 'retry'
  defaultRoute?: boolean
  splitKind?: 'share' | 'fixed' | 'rest'
  splitValue?: number
  clearSplit?: boolean
}

export type CanvasMode = 'select' | 'connect'

export interface ConnectionDraftState {
  fromProcessId: string
  fromIoId: string | null
  sourceHandle: string
}

export interface ConnectStartPayload {
  processId: string
  ioId: string | null
  handleId: string
}

export interface ConnectCompletePayload {
  fromProcessId: string
  toProcessId: string
  fromIoId: string | null
  toIoId: string | null
  sourceHandle: string
  targetHandle: string
}

export interface RuleTargetInput {
  projectId: string
  targetType: string
  targetId: string
}

export interface FlowRuleViewModel {
  ruleId: string
  projectId: string
  targetType: string
  targetId: string
  ruleName: string
  ruleDesc: string | null
  conditionType: string
  conditionExpression: string
  actionType: string
  actionConfig: string
  priority: number
  enabledYn: string
}
