/** A connection's delay in words (docs/architecture/adr/ADR-010-connection-delay.md decision 9): 2 h, 1 h 30 min, 45 s. */
export function delayText(seconds: number): string {
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds - hours * 3600) / 60)
  const rest = Math.round((seconds - hours * 3600 - minutes * 60) * 100) / 100
  return [hours > 0 ? `${hours} h` : '', minutes > 0 ? `${minutes} min` : '',
    rest > 0 || (hours === 0 && minutes === 0) ? `${rest} s` : ''].filter(Boolean).join(' ')
}
