import type { WorkflowCanvasViewModel } from '../../../entities/workflow/model/types'

/** The workflow as the JSON export writes it: processes with their ports, connections and annotations. */
export function workflowJson(canvas: Pick<WorkflowCanvasViewModel, 'workflow' | 'nodes' | 'edges' | 'annotations'>) {
  return {
    version: 1,
    exportedAt: new Date().toISOString(),
    workflow: { id: canvas.workflow.workflowId, name: canvas.workflow.workflowName },
    nodes: canvas.nodes.map((n) => ({
      id: n.id,
      name: n.name,
      nodeType: n.nodeType,
      processType: n.processType,
      colorScheme: n.colorScheme,
      posX: Math.round(n.position.x),
      posY: Math.round(n.position.y),
      width: n.size.width,
      height: n.size.height,
      description: n.description,
      inputs: n.inputs.map((p) => ({ name: p.name, ioType: p.ioType, direction: p.direction, quantity: p.quantity, unit: p.unit, colorScheme: p.colorScheme })),
      outputs: n.outputs.map((p) => ({ name: p.name, ioType: p.ioType, direction: p.direction, quantity: p.quantity, unit: p.unit, colorScheme: p.colorScheme })),
    })),
    edges: canvas.edges.map((e) => ({
      id: e.id,
      source: e.source,
      target: e.target,
      connectionType: e.connectionType,
      label: e.label,
      flowRate: e.flowRate,
      unit: e.unit,
      delayTimeSec: e.delayTimeSec,
      lossRate: e.lossRate,
      priority: e.priority,
    })),
    annotations: canvas.annotations.map((annotation) => ({
      id: annotation.id,
      annotationType: annotation.annotationType,
      shapeKind: annotation.shapeKind,
      posX: Math.round(annotation.position.x),
      posY: Math.round(annotation.position.y),
      width: annotation.size.width,
      height: annotation.size.height,
      rotation: annotation.rotation,
      points: annotation.points.map((point) => ({ x: point.x, y: point.y })),
      textContent: annotation.textContent,
      style: annotation.style,
      zIndex: annotation.zIndex,
      groupId: annotation.groupId,
    })),
  }
}
