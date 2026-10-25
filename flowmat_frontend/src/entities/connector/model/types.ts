export interface HttpOperation {
  operationId: string
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  relativePath: string
  successStatuses: number[]
}

export interface HttpProfileVersion {
  profileVersionId: string
  profileId: string
  revision: number
  baseOrigin: string
  credentialAlias: string | null
  operations: HttpOperation[]
  createdAt: string
}

export interface HttpProfile {
  profileId: string
  projectId: string
  name: string
  enabled: boolean
  version: number
  currentRevision: number
  profileVersionId: string
  baseOrigin: string
  credentialAlias: string | null
  operations: HttpOperation[]
  createdAt: string
  updatedAt: string
}

export interface HttpProfileInput {
  expectedVersion?: number
  name: string
  baseOrigin: string
  credentialAlias: string | null
  enabled: boolean
  operations: HttpOperation[]
}

export interface ProcessHttpBinding {
  processId: string
  profileVersionId: string | null
  operationId: string | null
  outputPortId: string | null
  requestMapping: unknown
  responseMapping: unknown
  /** Exact JSON from the server; keep these strings when opening and saving an editor. */
  requestMappingJson: string | null
  responseMappingJson: string | null
  version: number
}

export interface HttpExecution {
  executionId: string
  stepId: string
  attemptId: string
  requestId: string
  transportStatus: string
  applyStatus: string
  httpStatus: number | null
  errorCode: string | null
  output: unknown
  outputJson?: string | null
  startedAt: string
  completedAt: string | null
}

export interface HttpRequestPreview {
  profileVersionId: string
  operationId: string
  method: HttpOperation['method']
  target: string
  hasRequestBody: boolean
  /** Exact server JSON; null means no HTTP body, while the string "null" is a JSON body. */
  requestJson: string | null
}

export interface HttpProfileReadiness {
  profileVersionId: string
  configurationReady: boolean
  /** Unknown future codes must be shown as a generic requirement, never as raw server text. */
  blockers: string[]
  credentialValuesChecked: false
  connectionTested: false
}

export interface PinnedHttpBinding {
  processId: string
  profileVersionId: string
  operationId: string
}
