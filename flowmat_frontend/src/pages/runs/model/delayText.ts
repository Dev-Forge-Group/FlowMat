/**
 * What a delayed step was then held for (docs/architecture/adr/ADR-015-shift-aware-delay.md decision 5,
 * docs/architecture/adr/ADR-016-crew-aware-delay.md decision 5): the work order's equipment, its crew or both. Empty when
 * the step was not held.
 */
export function heldText(payload: { readyAt?: unknown; equipmentId?: unknown; crew?: unknown }): string {
  if (typeof payload.readyAt !== 'string') return ''
  const equipment = typeof payload.equipmentId === 'string'
  const crew = Array.isArray(payload.crew) && payload.crew.length > 0
  if (equipment && crew) return ', then the next time the equipment and crew work'
  return crew ? ", then the crew's next working time" : ", then the equipment's next working time"
}

/** A connection's delay in words (docs/architecture/adr/ADR-010-connection-delay.md decision 9): 2 h, 1 h 30 min, 45 s. */
export function delayText(seconds: number): string {
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds - hours * 3600) / 60)
  const rest = Math.round((seconds - hours * 3600 - minutes * 60) * 100) / 100
  return [hours > 0 ? `${hours} h` : '', minutes > 0 ? `${minutes} min` : '',
    rest > 0 || (hours === 0 && minutes === 0) ? `${rest} s` : ''].filter(Boolean).join(' ')
}
