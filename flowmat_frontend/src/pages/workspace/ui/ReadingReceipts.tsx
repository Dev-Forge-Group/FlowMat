/**
 * The receipts of what is shown over the canvas while its panel is closed (archify's reading receipts): the semantic lens,
 * a production run and a compared revision, each with its own way out. Nothing is shown when none is on.
 */
export function ReadingReceipts({ lens, run, changes }: {
  lens: { text: string; onClear(): void } | null
  run: { text: string; onHide(): void } | null
  changes: { text: string; onHide(): void } | null
}) {
  if (!lens && !run && !changes) return null
  return (
    <div className="reading-receipts" role="region" aria-label="Reading receipts">
      {lens && (
        <p className="reading-receipt" data-receipt="lens">
          <span className="reading-receipt-label">Lens</span>
          <span role="status">{lens.text}</span>
          <button type="button" aria-label="Clear semantic lens" onClick={lens.onClear}>Clear</button>
        </p>
      )}
      {run && (
        <p className="reading-receipt" data-receipt="run">
          <span className="reading-receipt-label">Run</span>
          <span className="canvas-run-summary">{run.text}</span>
          <button type="button" onClick={run.onHide}>Hide run</button>
        </p>
      )}
      {changes && (
        <p className="reading-receipt" data-receipt="changes">
          <span className="reading-receipt-label">Changes</span>
          <span className="canvas-run-summary">{changes.text}</span>
          <button type="button" onClick={changes.onHide}>Hide changes</button>
        </p>
      )}
    </div>
  )
}
