/** Integration jobs (docs/architecture/adr/ADR-038-integration-jobs.md), as the server sends them. */
export type IntegrationKind = 'pull_items' | 'pull_stock' | 'push_stock'

export type IntegrationRunStatus = 'running' | 'succeeded' | 'partial' | 'failed' | 'outcome_unknown'

export interface IntegrationRunItem {
  no: number
  key: string | null
  result: string
  message: string | null
}

export interface IntegrationRun {
  runId: string | null
  jobId: string
  trigger: 'manual' | 'schedule' | 'preview'
  scheduledFor: string | null
  status: IntegrationRunStatus
  runBy: string
  startedAt: string | null
  finishedAt: string | null
  rowsTotal: number | null
  rowsApplied: number | null
  rowsLeftOut: number | null
  message: string | null
  items: IntegrationRunItem[]
}

export interface IntegrationJob {
  jobId: string
  projectId: string
  name: string
  kind: IntegrationKind
  profileVersionId: string
  operationId: string
  verifyOperationId: string | null
  mapping: unknown
  note: string | null
  scheduleCron: string | null
  scheduleEnabled: boolean
  nextRunAt: string | null
  runAs: string | null
  pausedReason: string | null
  failuresInRow: number
  version: number
  lastRun: IntegrationRun | null
}
