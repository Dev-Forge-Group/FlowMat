import { flowRunJsonDisplay } from '../model/flowRunJson'

export function FlowRunJsonValue({ label, json, value, showAbsent = false }: {
  label: string
  json: string | null | undefined
  value: unknown
  showAbsent?: boolean
}) {
  const display = flowRunJsonDisplay(json, value)
  if (!display.present && !showAbsent) return null
  return <div>
    <strong>{label}</strong>
    <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
      {display.present ? display.text : 'No JSON value.'}
    </pre>
    {display.present && display.legacy && <p className="inspector-hint">
      Legacy JSON response: numeric precision may already be lost.
    </p>}
  </div>
}
