import type { UnitDto } from '../../../shared/types/api'
import type { KernelGraph } from '../../../shared/lib/flowKernel'
import type { WorkflowCanvasViewModel } from './types'

/**
 * The canvas as the flow kernel takes it (docs/architecture/adr/ADR-040-flow-kernel.md decision 3): processes, ports and
 * connections under the server's field names, so the kernel checks what is drawn as the server checks what is saved.
 * Ports come node by node (inputs, then outputs), not in the order they were created, so issues about different ports
 * may be listed in another order than the server's; each issue itself is the same.
 */
export function toKernelGraph(canvas: Pick<WorkflowCanvasViewModel, 'nodes' | 'edges'>): KernelGraph {
  return {
    processes: canvas.nodes.map((node) => ({
      processId: node.processId,
      processName: node.name,
      nodeType: node.nodeType,
    })),
    ports: canvas.nodes.flatMap((node) => [...node.inputs, ...node.outputs]).map((port) => ({
      processIoId: port.processIoId,
      processId: port.processId,
      direction: port.direction,
      ioName: port.name,
      ioType: port.ioType,
      itemId: port.itemId,
      quantity: port.quantity.trim() === '' || !Number.isFinite(Number(port.quantity)) ? null : Number(port.quantity),
      unit: port.unit,
      requiredYn: port.required ? 'Y' : 'N',
      allowShortageYn: port.allowShortage ? 'Y' : 'N',
      resourceType: port.resourceType,
      schemaJson: port.schemaJson,
      validationRule: port.validationRule,
    })),
    connections: canvas.edges.map((edge) => ({
      connectionId: edge.connectionId,
      fromProcessId: edge.fromProcessId,
      toProcessId: edge.toProcessId,
      fromIoId: edge.fromIoId,
      toIoId: edge.toIoId,
      itemId: edge.itemId ?? null,
      conditionExpr: edge.conditionExpr,
      unit: edge.unit,
      delayTimeSec: edge.delayTimeSec,
      delayBasis: edge.delayBasis ?? 'elapsed',
      lossRate: edge.lossRate,
      priority: edge.priority,
      capacity: edge.capacity,
      failurePolicy: edge.failurePolicy,
      defaultRoute: edge.defaultRoute ?? false,
      splitKind: edge.splitKind ?? null,
      splitValue: edge.splitValue ?? null,
    })),
  }
}

/** The unit catalog as the kernel looks units up: code to unit type, inactive units included, as the server's check. */
export function kernelUnits(units: readonly Pick<UnitDto, 'unitCode' | 'unitType'>[]): Record<string, string> {
  return Object.fromEntries(units.map((unit) => [unit.unitCode, unit.unitType]))
}
