import { layoutWarningText, type LayoutWarning } from '../model/layoutChecks'

/**
 * The layout warnings from the last workflow check (layoutChecks.ts): lines over a process they do not join and lines that
 * cross, measured from the canvas as drawn. Warnings only; publishing never looks at them. Each one brings its processes
 * into view and selects the connection.
 */
export function LayoutWarningList({ warnings, nameOf, onSelect }: {
  warnings: LayoutWarning[] | null
  nameOf: (processId: string) => string
  onSelect: (warning: LayoutWarning) => void
}) {
  if (!warnings) return null
  return (
    <section aria-label="Layout" className="layout-warnings">
      <p>
        {warnings.length === 0
          ? 'Layout: no line passes over a process or crosses another.'
          : `Layout: ${warnings.length} ${warnings.length === 1 ? 'warning' : 'warnings'}, measured from the canvas as drawn.`}
      </p>
      {warnings.length > 0 && (
        <ul className="layout-warnings__list">
          {warnings.map((warning, index) => {
            const key = warning.code === 'LINE_OVER_PROCESS'
              ? `${warning.connectionId}-${warning.processId}` : warning.connectionIds.join('-')
            return (
              <li key={`${warning.code}-${key}-${index}`}>
                <button type="button" onClick={() => onSelect(warning)}>
                  warning: {warning.code} — {layoutWarningText(warning, nameOf)}
                </button>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
