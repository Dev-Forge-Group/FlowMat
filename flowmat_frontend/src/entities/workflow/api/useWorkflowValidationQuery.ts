import { useQuery } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

export interface WorkflowValidationIssue {
  severity: 'error' | 'warning'
  code: string
  processId: string | null
  ioId: string | null
  connectionId: string | null
  message: string
  /** What the rule looked at: stored values, counts and allowed values. */
  evidence?: Record<string, unknown>
  /** Edits that clear this issue by the check's own rules; never general advice. */
  supportedFixes?: string[]
  /** Codes of the derived warnings this issue explains, which are not listed separately. */
  suppresses?: string[]
}

export interface WorkflowValidationReport {
  errors: number
  warnings: number
  issues: WorkflowValidationIssue[]
}

export const workflowValidationQueryKey = (workflowId: string) => ['workflow-validation', workflowId] as const

export function useWorkflowValidationQuery(workflowId: string, enabled: boolean) {
  return useQuery({
    queryKey: workflowValidationQueryKey(workflowId),
    queryFn: async (): Promise<WorkflowValidationReport> => {
      const envelope = await httpClient.get<ApiEnvelope<WorkflowValidationReport>>(
        `/workflows/${encodeURIComponent(workflowId)}/validation`,
      )
      return unwrapApiResponse(envelope)
    },
    enabled: Boolean(workflowId) && enabled,
  })
}
