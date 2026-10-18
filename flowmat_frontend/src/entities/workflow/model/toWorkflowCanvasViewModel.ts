import type {
  WorkflowCanvasDto,
  ProcessDto,
  ProcessIoDto,
  ProcessConnectionDto,
  CanvasAnnotationDto,
} from '../../../shared/types/api'
import type {
  WorkflowCanvasViewModel,
  CanvasAnnotationViewModel,
  CanvasNodeViewModel,
  CanvasPortViewModel,
  CanvasEdgeViewModel,
  WorkflowHeaderViewModel,
} from './types'

export function toPortViewModel(dto: ProcessIoDto): CanvasPortViewModel {
  const ioType = dto.ioType ?? 'material'
  return {
    id: dto.processIoId,
    processIoId: dto.processIoId,
    processId: dto.processId,
    itemId: dto.itemId,
    name: dto.ioName ?? '',
    direction: dto.direction,
    ioType,
    role: dto.role ?? null,
    resourceType: dto.resourceType ?? ioType,
    schemaJson: dto.schemaJson ?? null,
    validationRule: dto.validationRule ?? null,
    quantity: String(dto.quantity ?? ''),
    unit: dto.unit,
    formula: dto.formula,
    colorScheme: dto.colorScheme ?? (dto.direction === 'input' ? 'sky' : 'emerald'),
    required: dto.requiredYn === 'Y',
    allowShortage: dto.allowShortageYn === 'Y',
    handleId: dto.processIoId,
  }
}

export function toNodeViewModel(dto: ProcessDto, processIos: ProcessIoDto[]): CanvasNodeViewModel {
  const inputs = processIos.filter((io) => io.direction === 'input').map(toPortViewModel)
  const outputs = processIos.filter((io) => io.direction === 'output').map(toPortViewModel)

  return {
    id: dto.processId,
    processId: dto.processId,
    projectId: dto.projectId,
    workflowId: dto.workflowId,
    name: dto.processName,
    processType: dto.processType,
    nodeType: dto.nodeType,
    status: dto.processStatus,
    colorScheme: dto.colorScheme,
    position: { x: dto.posX, y: dto.posY },
    size: { width: dto.width, height: dto.height },
    description: dto.processDesc,
    inputs,
    outputs,
    inputCount: inputs.length,
    outputCount: outputs.length,
    version: dto.version,
    versionNonce: dto.versionNonce,
  }
}

export function toEdgeViewModel(dto: ProcessConnectionDto): CanvasEdgeViewModel {
  return {
    id: dto.connectionId,
    connectionId: dto.connectionId,
    source: dto.fromProcessId,
    target: dto.toProcessId,
    sourceHandle: dto.sourceHandle,
    targetHandle: dto.targetHandle,
    fromProcessId: dto.fromProcessId,
    toProcessId: dto.toProcessId,
    fromIoId: dto.fromIoId,
    toIoId: dto.toIoId,
    itemId: dto.itemId,
    connectionType: dto.connectionType,
    label: dto.connectionLabel,
    flowRate: dto.flowRate !== null ? String(dto.flowRate) : null,
    unit: dto.unit,
    delayTimeSec: dto.delayTimeSec,
    lossRate: dto.lossRate,
    priority: dto.priority,
    conditionExpr: dto.conditionExpr ?? null,
    capacity: dto.capacity ?? null,
    failurePolicy: dto.failurePolicy ?? 'stop',
    defaultRoute: dto.defaultRoute ?? false,
    splitKind: dto.splitKind ?? null,
    splitValue: dto.splitValue ?? null,
    version: dto.version,
    versionNonce: dto.versionNonce,
  }
}

function normalizeStyle(style: Record<string, unknown> | null): Record<string, unknown> {
  return style ?? {}
}

export function toAnnotationViewModel(dto: CanvasAnnotationDto): CanvasAnnotationViewModel {
  const points = (dto.points ?? []).map(([x, y]) => ({ x, y }))
  return {
    id: dto.annotationId,
    annotationId: dto.annotationId,
    workflowId: dto.workflowId,
    projectId: dto.projectId,
    annotationType: dto.annotationType,
    shapeKind: dto.shapeKind,
    position: { x: dto.posX, y: dto.posY },
    size: { width: dto.width ?? 180, height: dto.height ?? 88 },
    rotation: dto.rotation ?? 0,
    points,
    textContent: dto.textContent,
    style: normalizeStyle(dto.style),
    zIndex: dto.zIndex,
    groupId: dto.groupId,
    locked: dto.lockedYn === 'Y',
    version: dto.version,
    versionNonce: dto.versionNonce,
  }
}

export function buildWorkflowCanvasViewModel(
  workflow: WorkflowHeaderViewModel,
  graphSeq: number,
  nodes: CanvasNodeViewModel[],
  edges: CanvasEdgeViewModel[],
  annotations: CanvasAnnotationViewModel[]
): WorkflowCanvasViewModel {
  const nodeMap: Record<string, CanvasNodeViewModel> = {}
  const portMap: Record<string, CanvasPortViewModel> = {}
  const annotationMap: Record<string, CanvasAnnotationViewModel> = {}

  for (const node of nodes) {
    nodeMap[node.id] = node
    for (const port of [...node.inputs, ...node.outputs]) {
      portMap[port.id] = port
    }
  }

  for (const annotation of annotations) {
    annotationMap[annotation.id] = annotation
  }

  return {
    workflow,
    graphSeq,
    nodes,
    edges,
    annotations,
    nodeMap,
    portMap,
    annotationMap,
  }
}

export function toWorkflowCanvasViewModel(dto: WorkflowCanvasDto): WorkflowCanvasViewModel {
  const iosByProcess: Record<string, ProcessIoDto[]> = {}
  for (const io of dto.processIos) {
    if (!iosByProcess[io.processId]) iosByProcess[io.processId] = []
    iosByProcess[io.processId].push(io)
  }

  const workflow: WorkflowHeaderViewModel = {
    workflowId: dto.workflow.workflowId,
    projectId: dto.workflow.projectId,
    workflowName: dto.workflow.workflowName,
    workflowDesc: dto.workflow.workflowDesc,
    workflowType: dto.workflow.workflowType,
    workflowStatus: dto.workflow.workflowStatus,
    currentUserRole: dto.currentUserRole,
  }
  const nodes = dto.processes.map((process) => toNodeViewModel(process, iosByProcess[process.processId] ?? []))
  const edges = dto.connections.map(toEdgeViewModel)
  const annotations = dto.annotations.map(toAnnotationViewModel)

  return buildWorkflowCanvasViewModel(workflow, dto.graphSeq, nodes, edges, annotations)
}
