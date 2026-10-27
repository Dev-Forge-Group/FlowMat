import type { HttpProfile } from './types'

export interface ProfileForm {
  profileId?: string
  expectedVersion?: number
  name: string
  baseOrigin: string
  credentialAlias: string
  enabled: boolean
  operations: string
}

export const emptyProfileForm = (): ProfileForm => ({ name: '', baseOrigin: '', credentialAlias: '', enabled: true,
  operations: '[\n  {"operationId":"send","method":"POST","relativePath":"/jobs","successStatuses":[200,202]}\n]' })

export const profileForm = (profile: HttpProfile): ProfileForm => ({ profileId: profile.profileId,
  expectedVersion: profile.version, name: profile.name, baseOrigin: profile.baseOrigin,
  credentialAlias: profile.credentialAlias ?? '', enabled: profile.enabled,
  operations: JSON.stringify(profile.operations, null, 2) })

export function requireJson(value: string, label: string): string {
  try { JSON.parse(value) }
  catch { throw new Error(`${label} must contain valid JSON.`) }
  return value
}

/** Preserve operation JSON so the server can reject fractional statuses and duplicate keys before normalization. */
export function profileBody(form: ProfileForm): string {
  const operations = requireJson(form.operations, 'Operations')
  const parsed: unknown = JSON.parse(operations)
  if (!Array.isArray(parsed) || parsed.length === 0) throw new Error('Operations must be a nonempty JSON array.')
  const metadata = { ...(form.expectedVersion === undefined ? {} : { expectedVersion: form.expectedVersion }),
    name: form.name.trim(), baseOrigin: form.baseOrigin.trim(), credentialAlias: form.credentialAlias.trim() || null,
    enabled: form.enabled }
  return `${JSON.stringify(metadata).slice(0, -1)},"operations":${operations}}`
}

export interface BindingForm {
  profileVersionId: string
  operationId: string
  outputPortId: string
  requestMapping: string
  responseMapping: string
  expectedVersion: number
}

/** JSON is validated but never parsed and serialized for transport: decimal digits and scale must survive editing. */
export function bindingBody(form: BindingForm | null, clearVersion = 0): string {
  if (!form) return JSON.stringify({ profileVersionId: null, operationId: null, outputPortId: null,
    requestMapping: null, responseMapping: null, expectedVersion: clearVersion })
  const request = requireJson(form.requestMapping, 'Request mapping')
  const response = requireJson(form.responseMapping, 'Response mapping')
  return `{"profileVersionId":${JSON.stringify(form.profileVersionId)},"operationId":${JSON.stringify(form.operationId)},`
    + `"outputPortId":${JSON.stringify(form.outputPortId)},"requestMapping":${request},"responseMapping":${response},`
    + `"expectedVersion":${JSON.stringify(form.expectedVersion)}}`
}

export function rawRunInputBody(metadata: { workflowId: string; workflowRevisionId: string; runType: string }, input: string): string {
  requireJson(input, 'Run input')
  return `${JSON.stringify(metadata).slice(0, -1)},"inputPayload":${input}}`
}
