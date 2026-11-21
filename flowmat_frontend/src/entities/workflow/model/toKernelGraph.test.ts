import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { beforeAll, describe, expect, it } from 'vitest'
import { flowKernel, type FlowKernel, type KernelIssue } from '../../../shared/lib/flowKernel'
import type { ProcessConnectionDto, ProcessDto, ProcessIoDto, WorkflowCanvasDto } from '../../../shared/types/api'
import { kernelUnits, toKernelGraph } from './toKernelGraph'
import { toWorkflowCanvasViewModel } from './toWorkflowCanvasViewModel'

/**
 * What the editor draws, through the canvas view model and back to the kernel's graph, must get the issues the server
 * recorded for the same graph (compute/flow-kernel/fixtures/validation.json). Ports reach the kernel node by node, so
 * the issues are compared as a set.
 */
const fixtures = fileURLToPath(new URL('../../../../../compute/flow-kernel/fixtures/', import.meta.url))
const wasm = fileURLToPath(new URL('../../../shared/wasm/flowmat_flow_kernel.wasm', import.meta.url))

let kernel: FlowKernel

beforeAll(async () => {
  const { instance } = await WebAssembly.instantiate(readFileSync(wasm))
  kernel = flowKernel(instance.exports)
})

type Row = Record<string, any>

/** The canvas the server would send for a recorded graph. */
function canvasOf(graph: { processes: Row[]; ports: Row[]; connections: Row[] }): WorkflowCanvasDto {
  return {
    workflow: { workflowId: 'wf', projectId: 'prj', workflowName: 'Recorded', workflowDesc: null, workflowType: 'standard',
      workflowStatus: 'draft' },
    graphSeq: 0,
    processes: graph.processes.map((process, index) => ({ projectId: 'prj', workflowId: 'wf', processType: 'process',
      processStatus: 'ready', colorScheme: 'slate', posX: index * 200, posY: 0, width: 150, height: 72, processDesc: null,
      version: 1, versionNonce: 1, ...process }) as ProcessDto),
    processIos: graph.ports.map((port) => ({ itemId: null, ioName: null, ioType: null, quantity: null, unit: null,
      formula: null, colorScheme: null, role: null, schemaJson: null, validationRule: null, ...port }) as ProcessIoDto),
    connections: graph.connections.map((connection) => ({ projectId: 'prj', workflowId: 'wf', fromIoId: null, toIoId: null,
      sourceHandle: 'out-default', targetHandle: 'in-default', connectionType: 'flow', connectionLabel: null, flowRate: null,
      unit: null, delayTimeSec: null, lossRate: null, priority: null, conditionExpr: null, capacity: null,
      failurePolicy: 'stop', version: 1, versionNonce: 1, ...connection }) as ProcessConnectionDto),
    annotations: [],
    currentUserRole: 'owner',
  }
}

/** Graphs a saved workflow can hold: every port is an input or output with Y/N flags and a resource type. */
function storable(graph: { ports: Row[] }) {
  return graph.ports.every((port) => ['input', 'output'].includes(port.direction)
    && ['Y', 'N'].includes(port.requiredYn) && ['Y', 'N'].includes(port.allowShortageYn)
    && typeof port.resourceType === 'string')
}

const asSet = (issues: KernelIssue[]) => issues.map((issue) => JSON.stringify(issue)).sort()

describe('the canvas as the kernel takes it', () => {
  it('gets the issues the server recorded for the same graph', () => {
    const file = JSON.parse(readFileSync(fixtures + 'validation.json', 'utf8'))
    const graphs = file.cases.filter((entry: { graph: { ports: Row[] } }) => storable(entry.graph))
    expect(graphs.length).toBeGreaterThan(50)
    for (const entry of graphs) {
      const canvas = toWorkflowCanvasViewModel(canvasOf(entry.graph))
      const answer = kernel.validate(toKernelGraph(canvas), file.context.knownUnits)
      expect({ errors: answer.errors, warnings: answer.warnings }, entry.id)
        .toEqual({ errors: entry.expected.errors, warnings: entry.expected.warnings })
      expect(asSet(answer.issues), entry.id).toEqual(asSet(entry.expected.issues))
    }
  })

  it('reads the unit catalog as code to type', () => {
    expect(kernelUnits([{ unitCode: 'kg', unitType: 'mass' }, { unitCode: 'EA', unitType: 'count' }]))
      .toEqual({ kg: 'mass', EA: 'count' })
  })
})
