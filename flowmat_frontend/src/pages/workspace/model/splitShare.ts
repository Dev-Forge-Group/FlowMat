/** A Split gateway's share kinds (docs/architecture/adr/ADR-008-quantity-split.md decision 2). */
export type SplitKind = 'share' | 'fixed' | 'rest'

/**
 * What the connection inspector sends for a Split gateway's share, or why it cannot: a percent above 0 and at most 100, a
 * fixed quantity above 0, or the rest without a value; none removes the share.
 */
export function splitInput(kind: SplitKind | '', value: string):
  { splitKind: SplitKind; splitValue?: number } | { clearSplit: true } | { error: string } {
  if (kind === '') return { clearSplit: true }
  if (kind === 'rest') return { splitKind: 'rest' }
  const text = value.trim()
  const amount = Number(text)
  if (text === '' || !Number.isFinite(amount)) return { error: kind === 'share' ? 'Enter the percent.' : 'Enter the quantity.' }
  if (kind === 'share' && (amount <= 0 || amount > 100)) return { error: 'A percent share is above 0 and at most 100.' }
  if (kind === 'fixed' && amount <= 0) return { error: 'A fixed share is above 0.' }
  return { splitKind: kind, splitValue: amount }
}
