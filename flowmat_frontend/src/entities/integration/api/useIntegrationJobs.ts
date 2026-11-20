import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse, unwrapApiVoidResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'
import type { IntegrationJob, IntegrationRun } from '../model/types'

export const integrationJobsKey = (projectId: string) => ['integration-jobs', projectId] as const
const jobsPath = (projectId: string, jobId?: string) => `/projects/${encodeURIComponent(projectId)}/integration-jobs`
  + (jobId ? `/${encodeURIComponent(jobId)}` : '')

export function useIntegrationJobs(projectId: string) {
  return useQuery({ queryKey: integrationJobsKey(projectId), enabled: Boolean(projectId),
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<IntegrationJob[]>>(jobsPath(projectId))) })
}

export function useIntegrationRuns(projectId: string, jobId: string) {
  return useQuery({ queryKey: [...integrationJobsKey(projectId), jobId, 'runs'], enabled: Boolean(projectId && jobId),
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<IntegrationRun[]>>(`${jobsPath(projectId, jobId)}/runs`)) })
}

export function useIntegrationJobMutations(projectId: string) {
  const client = useQueryClient()
  const refresh = async () => {
    await client.cancelQueries({ queryKey: integrationJobsKey(projectId) })
    await client.invalidateQueries({ queryKey: integrationJobsKey(projectId) })
  }
  const save = useMutation({ mutationFn: async ({ jobId, body }: { jobId?: string; body: string }) => unwrapApiResponse(jobId
    ? await httpClient.putJson<ApiEnvelope<IntegrationJob>>(jobsPath(projectId, jobId), body)
    : await httpClient.postJson<ApiEnvelope<IntegrationJob>>(jobsPath(projectId), body)), onSuccess: refresh, onError: refresh })
  const remove = useMutation({ mutationFn: async (jobId: string) =>
    unwrapApiVoidResponse(await httpClient.delete<ApiEnvelope<null>>(jobsPath(projectId, jobId))), onSuccess: refresh, onError: refresh })
  const schedule = useMutation({ mutationFn: async ({ jobId, cron, enabled, version }: {
    jobId: string; cron: string; enabled: boolean; version: number
  }) => unwrapApiResponse(await httpClient.put<ApiEnvelope<IntegrationJob>>(`${jobsPath(projectId, jobId)}/schedule`,
    { cron: cron.trim() || null, enabled, version })), onSuccess: refresh, onError: refresh })
  // A preview saves nothing, so nothing is refreshed after it.
  const preview = useMutation({ mutationFn: async (jobId: string) =>
    unwrapApiResponse(await httpClient.post<ApiEnvelope<IntegrationRun>>(`${jobsPath(projectId, jobId)}/preview`, {})) })
  // A run that failed to answer may still have been kept, so the list is read again either way.
  const run = useMutation({ mutationFn: async (jobId: string) =>
    unwrapApiResponse(await httpClient.post<ApiEnvelope<IntegrationRun>>(`${jobsPath(projectId, jobId)}/runs`, {})),
  onSuccess: refresh, onError: refresh })
  return { save, remove, schedule, preview, run }
}
