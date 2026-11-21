/**
 * FlowMat's flow kernel in the browser (docs/architecture/adr/ADR-040-flow-kernel.md): the Rust crate in
 * compute/flow-kernel, built to WebAssembly (flowmat_flow_kernel.wasm, rebuilt by its wasm/build.sh and checked against
 * its source in CI). It checks a draft as the backend's WorkflowValidationService does and routes a published revision
 * as FlowRunGraph does, with the same answers on every graph Java recorded in its fixtures. The server's check still
 * decides at publish; this lets the editor show the same issues while someone draws.
 */

/** A graph as the kernel takes it: processes, ports and connections in the server's response shape. */
export interface KernelGraph {
  processes: Record<string, unknown>[]
  ports: Record<string, unknown>[]
  connections: Record<string, unknown>[]
}

export interface KernelIssue {
  severity: 'error' | 'warning'
  code: string
  processId: string | null
  ioId: string | null
  connectionId: string | null
  message: string
  evidence: Record<string, unknown>
  supportedFixes: string[]
  suppresses: string[]
}

export interface KernelValidation {
  errors: number
  warnings: number
  issues: KernelIssue[]
}

export interface KernelRefusal {
  code: string
  message: string
}

export interface KernelDecision {
  connectionId: string
  targetNodeId: string
  willRoute: boolean
  reason: string | null
  quantity: number | null
  loss: { sent: number; rate: number; lost: number; arrived: number } | null
}

/** An output sent from a node, and what it does on each outgoing connection or why it cannot go on. */
export interface KernelProbe {
  nodeId: string
  output: unknown
}

export type KernelRouting =
  | { error: KernelRefusal }
  | {
      roots: string[]
      delays: Record<string, { seconds: number; working: boolean }>
      joins: Record<string, number>
      probes: ({ decisions: KernelDecision[] } | { error: KernelRefusal })[]
    }

export interface FlowKernel {
  /** The draft check; {@code knownUnits} is the unit catalog, code to unit type. */
  validate(graph: KernelGraph, knownUnits: Record<string, string>): KernelValidation
  /** Routing the graph as a published revision would be routed. */
  route(graph: KernelGraph, probes: KernelProbe[], schemaVersion?: number): KernelRouting
}

interface KernelExports {
  memory: WebAssembly.Memory
  alloc(length: number): number
  dealloc(pointer: number, length: number): void
  validate(request: number, length: number): bigint
  route(request: number, length: number): bigint
}

/** The kernel over an instantiated module's exports. */
export function flowKernel(exports: WebAssembly.Exports): FlowKernel {
  const module = exports as unknown as KernelExports
  const encoder = new TextEncoder()
  const decoder = new TextDecoder()
  const call = (exported: (request: number, length: number) => bigint, request: string): unknown => {
    const bytes = encoder.encode(request)
    const pointer = module.alloc(bytes.length)
    new Uint8Array(module.memory.buffer, pointer, bytes.length).set(bytes)
    const packed = exported(pointer, bytes.length)
    module.dealloc(pointer, bytes.length)
    const at = Number(packed >> 32n)
    const length = Number(packed & 0xffffffffn)
    const answer = JSON.parse(decoder.decode(new Uint8Array(module.memory.buffer, at, length))) as unknown
    module.dealloc(at, length)
    if (answer && typeof answer === 'object' && 'failure' in answer) {
      throw new Error(`The flow kernel refused the request: ${String((answer as { failure: unknown }).failure)}`)
    }
    return answer
  }
  return {
    validate(graph, knownUnits) {
      return call(module.validate, JSON.stringify({ graph, knownUnits })) as KernelValidation
    },
    route(graph, probes, schemaVersion = 3) {
      return call(module.route, JSON.stringify({ graph, probes, schemaVersion })) as KernelRouting
    },
  }
}

let loadingModule: Promise<WebAssembly.Exports> | null = null

/**
 * The kernel's module, fetched and instantiated once, when first needed. It also carries the condition language's
 * exports, so the condition checker uses the same instance. A failed load is tried again on the next call.
 */
export function loadKernelModule(): Promise<WebAssembly.Exports> {
  loadingModule ??= (async () => {
    const response = await fetch(new URL('../wasm/flowmat_flow_kernel.wasm', import.meta.url))
    if (!response.ok) throw new Error(`The flow kernel could not be loaded (${response.status}).`)
    const { instance } = await WebAssembly.instantiate(await response.arrayBuffer())
    return instance.exports
  })().catch((error: unknown) => {
    loadingModule = null
    throw error
  })
  return loadingModule
}

/** The kernel over the shared module. */
export async function loadFlowKernel(): Promise<FlowKernel> {
  return flowKernel(await loadKernelModule())
}
