/**
 * A connection's loss rate is a fraction of what it carries, 0 up to but not 1; the inspector shows and takes it as a
 * percent with up to 2 decimals (docs/architecture/adr/ADR-009-connection-loss-rate.md decisions 2, 10).
 */
export function lossPercentText(rate: number | null | undefined): string {
  return rate == null ? '' : String(Math.round(rate * 10000) / 100)
}

/** What the inspector sends for a loss percent: the fraction, null for none, or why it cannot. */
export function lossRateInput(percent: string): { lossRate: number | null } | { error: string } {
  const text = percent.trim()
  if (text === '') return { lossRate: null }
  const value = Number(text)
  if (!Number.isFinite(value) || value < 0 || value >= 100) return { error: 'Loss % is 0 or more and below 100.' }
  const hundredths = Math.round(value * 100)
  if (Math.abs(hundredths - value * 100) > 1e-6) return { error: 'Loss % takes up to 2 decimals.' }
  return { lossRate: hundredths / 10000 }
}
