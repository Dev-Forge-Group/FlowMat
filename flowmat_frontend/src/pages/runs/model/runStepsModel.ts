/**
 * What a process step made or gets (docs/architecture/adr/ADR-011-production-steps.md decision 9): a completed step's output,
 * else the input it was given, such as "made 98 kg" or "gets 98 kg"; empty without a quantity.
 */
export function stepQuantityText(step: { status: string; inputSnapshot: unknown; outputSnapshot: unknown }): string {
  const read = (snapshot: unknown) => {
    const value = snapshot as { quantity?: unknown; unit?: unknown } | null
    return typeof value?.quantity === 'number' ? `${value.quantity}${typeof value.unit === 'string' ? ` ${value.unit}` : ''}` : null
  }
  if (step.status === 'completed') {
    const made = read(step.outputSnapshot)
    return made ? `made ${made}` : ''
  }
  const gets = read(step.inputSnapshot)
  return gets ? `gets ${gets}` : ''
}

/**
 * A running step's time limit (docs/domain/flow-run.md F12): when it is due, or that it is late once the limit passed or the
 * server marked it. A late step keeps running and can still complete. Null without a limit or once the step is not running.
 */
export function stepDeadline(
  step: { status: string; timeoutAt?: string | null; overdueAt?: string | null },
  now: Date = new Date(),
): { at: string; late: boolean } | null {
  if (step.status !== 'running' || !step.timeoutAt) return null
  return { at: step.timeoutAt, late: Boolean(step.overdueAt) || new Date(step.timeoutAt).getTime() <= now.getTime() }
}

/**
 * When a planned step can start, while that is still ahead: after a connection's delay
 * (docs/architecture/adr/ADR-010-connection-delay.md decision 1), held to the work order equipment's next working time in a
 * production run (docs/architecture/adr/ADR-015-shift-aware-delay.md). Null once it can start or when it is not planned.
 */
export function stepStartsFrom(step: { status: string; scheduledAt?: string | null }, now: Date = new Date()): string | null {
  if (step.status !== 'planned' || !step.scheduledAt) return null
  return new Date(step.scheduledAt).getTime() > now.getTime() ? step.scheduledAt : null
}
