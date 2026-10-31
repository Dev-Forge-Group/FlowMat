type FlowRunJsonField = 'inputSnapshot' | 'outputSnapshot' | 'outputPayload'

type FlowRunJsonMetadata = {
  nodeId?: string
  scheduledAt?: string | null
}

export function rawFlowRunJsonBody(field: FlowRunJsonField, json: string, metadata: FlowRunJsonMetadata = {}): string {
  try {
    JSON.parse(json)
  } catch {
    throw new Error('Enter valid JSON.')
  }
  const fields = JSON.stringify(metadata).slice(1, -1)
  return `{${fields ? `${fields},` : ''}${JSON.stringify(field)}:${json}}`
}

export function flowRunJsonDisplay(json: string | null | undefined, value: unknown): {
  present: boolean
  text: string
  legacy: boolean
} {
  if (typeof json === 'string') return { present: true, text: json, legacy: false }
  if (json === null) return { present: false, text: '', legacy: false }
  return { present: value != null, text: value == null ? '' : JSON.stringify(value, null, 2), legacy: true }
}

const numberSource = Symbol('Flow Run JSON numeric source')

/** Reads a response field's exact token. Older parsers withhold the amount instead of rounding it. */
export function flowRunNumberDisplay(json: string | null | undefined, field: string, legacyValue: unknown): {
  text: string | null
  legacy: boolean
} {
  if (json === undefined) {
    return { text: typeof legacyValue === 'number' && Number.isFinite(legacyValue) ? String(legacyValue) : null, legacy: true }
  }
  if (typeof json !== 'string') return { text: null, legacy: false }
  try {
    const parsed: unknown = JSON.parse(json, (_key: string, value: unknown, context?: { source?: string }) =>
      typeof value === 'number' ? { [numberSource]: context?.source } : value)
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed) && Object.prototype.hasOwnProperty.call(parsed, field)) {
      const value = (parsed as Record<string, unknown>)[field]
      if (value && typeof value === 'object' && Object.prototype.hasOwnProperty.call(value, numberSource)) {
        const token = (value as { [numberSource]?: unknown })[numberSource]
        if (typeof token === 'string') return { text: token, legacy: false }
      }
    }
  } catch {
    // Exact server JSON remains authoritative; malformed content must not expose a rounded fallback.
  }
  return { text: null, legacy: false }
}
