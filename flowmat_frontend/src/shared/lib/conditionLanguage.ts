import { useEffect, useState } from 'react'
import { loadKernelModule } from './flowKernel'

/**
 * FlowMat's condition language, checked in the browser by the server's own rules: the Rust implementation in
 * compute/condition-expression, which the flow kernel's WebAssembly module links in and exports (flowmat_flow_kernel.wasm,
 * rebuilt by compute/flow-kernel/wasm/build.sh and checked against its source in CI). It answers as the backend's
 * ConditionExpression does, message and position alike, so the editor can show while someone types what saving would
 * refuse. The server still checks on save; this only tells sooner.
 */

/** What a source is: a condition, with the attributes it reads and the declared-attribute check, or the reason it is not. */
export type ConditionReport =
  | { ok: true; attributes: string[]; declaredError?: { position: number; reason: string } | null }
  | { ok: false; position: number; reason: string; message: string }

export interface ConditionChecker {
  /**
   * {@code declared} is the names the port's schema declares; null or absent when there is no schema, and then, as on
   * the server, the attributes are not checked.
   */
  check(source: string, declared?: readonly string[] | null): ConditionReport
  /** Whether a condition holds for an output snapshot given as JSON text; null when the source or snapshot is not valid. */
  evaluate(source: string, snapshotJson: string): boolean | null
}

interface ConditionExports {
  memory: WebAssembly.Memory
  alloc(length: number): number
  dealloc(pointer: number, length: number): void
  check(source: number, sourceLength: number, declared: number, declaredLength: number): bigint
  evaluate(source: number, sourceLength: number, snapshot: number, snapshotLength: number): number
}

/** The checker over an instantiated module's exports. */
export function conditionChecker(exports: WebAssembly.Exports): ConditionChecker {
  const module = exports as unknown as ConditionExports
  const encoder = new TextEncoder()
  const decoder = new TextDecoder()
  const put = (text: string): [number, number] => {
    const bytes = encoder.encode(text)
    const pointer = module.alloc(bytes.length)
    new Uint8Array(module.memory.buffer, pointer, bytes.length).set(bytes)
    return [pointer, bytes.length]
  }
  const free = (...pieces: [number, number][]) => pieces.forEach(([pointer, length]) => module.dealloc(pointer, length))
  return {
    check(source, declared) {
      const text = put(source)
      const names = declared == null ? ([0, 0] as [number, number]) : put(JSON.stringify(declared))
      const packed = module.check(text[0], text[1], names[0], names[1])
      free(text, names)
      const pointer = Number(packed >> 32n)
      const length = Number(packed & 0xffffffffn)
      const report = JSON.parse(decoder.decode(new Uint8Array(module.memory.buffer, pointer, length))) as ConditionReport
      module.dealloc(pointer, length)
      return report
    },
    evaluate(source, snapshotJson) {
      const text = put(source)
      const snapshot = put(snapshotJson)
      const answer = module.evaluate(text[0], text[1], snapshot[0], snapshot[1])
      free(text, snapshot)
      return answer < 0 ? null : answer === 1
    },
  }
}

/** The checker over the flow kernel's module, loaded once; a failed load is tried again on the next call. */
export async function loadConditionChecker(): Promise<ConditionChecker> {
  return conditionChecker(await loadKernelModule())
}

/**
 * The report for a source as it is typed, or null while it is blank (no condition), while the checker loads, or when
 * it cannot load, in which case the server's check on save is all there is.
 */
export function useConditionCheck(source: string, declared?: readonly string[] | null): ConditionReport | null {
  const [checker, setChecker] = useState<ConditionChecker | null>(null)
  useEffect(() => {
    let current = true
    loadConditionChecker().then((loaded) => { if (current) setChecker(loaded) }, () => {})
    return () => { current = false }
  }, [])
  if (!checker || !source.trim()) return null
  return checker.check(source, declared)
}

/** The property names a port's draft schema declares, or null when it has none or is not a valid schema yet. */
export function declaredAttributes(schema: string | Record<string, unknown> | null | undefined): string[] | null {
  let parsed: unknown = schema
  if (typeof schema === 'string') {
    if (!schema.trim()) return null
    try {
      parsed = JSON.parse(schema)
    } catch {
      return null
    }
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return null
  const properties = (parsed as { properties?: unknown }).properties
  if (properties === undefined) return []
  return typeof properties === 'object' && properties !== null && !Array.isArray(properties) ? Object.keys(properties) : null
}
