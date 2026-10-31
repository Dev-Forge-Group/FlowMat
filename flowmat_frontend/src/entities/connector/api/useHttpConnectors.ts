import { useMutation, useQuery, useQueryClient, type QueryClient, type QueryFilters } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse, unwrapApiVoidResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'
import { requireJson } from '../model/connectorForms'
import type { HttpExecution, HttpProfile, HttpProfileVersion, HttpRequestPreview, ProcessHttpBinding } from '../model/types'
import { httpAttentionKey } from './useHttpExecutionAttention'

export const httpProfilesKey = (projectId: string) => ['http-profiles', projectId] as const
const profilePath = (projectId: string, profileId?: string) => `/projects/${encodeURIComponent(projectId)}/http-profiles`
  + (profileId ? `/${encodeURIComponent(profileId)}` : '')
const bindingKey = (processId: string) => ['http-binding', processId] as const
const bindingPath = (processId: string) => `/processes/${encodeURIComponent(processId)}/execution-binding`
const executionPath = (runId: string, stepId: string) => `/flow-runs/${encodeURIComponent(runId)}/steps/${encodeURIComponent(stepId)}`
export const httpExecutionsKey = (runId: string, stepId: string) => ['http-executions', runId, stepId] as const

function executionStates(rows: HttpExecution[]): string {
  return rows.map((row) => JSON.stringify([row.executionId, row.transportStatus, row.applyStatus, row.httpStatus, row.errorCode]))
    .sort().join('\n')
}

async function reconcileRunViews(client: QueryClient, runId: string): Promise<void> {
  const filters: QueryFilters[] = [
    { queryKey: ['flow-runs'], predicate: (query) => Array.isArray(query.state.data) && query.state.data.some((run: unknown) =>
      run !== null && typeof run === 'object' && 'flowRunId' in run && run.flowRunId === runId) },
    ...['flow-run-steps', 'flow-run-attempts', 'flow-run-events', 'flow-run-lineage'].map((kind) => ({ queryKey: [kind, runId] })),
    { queryKey: httpAttentionKey },
  ]
  // An older read must not restore a pre-execution step after durable HTTP evidence is recovered.
  await Promise.all(filters.map((filter) => client.cancelQueries(filter)))
  await Promise.all(filters.map((filter) => client.invalidateQueries(filter)))
}

export function useHttpProfiles(projectId: string) {
  return useQuery({ queryKey: httpProfilesKey(projectId), enabled: Boolean(projectId),
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<HttpProfile[]>>(profilePath(projectId))) })
}

export function useHttpProfileVersions(projectId: string, profileId: string) {
  return useQuery({ queryKey: [...httpProfilesKey(projectId), profileId, 'versions'], enabled: Boolean(projectId && profileId),
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<HttpProfileVersion[]>>(`${profilePath(projectId, profileId)}/versions`)) })
}

export function usePinnedHttpProfileVersion(projectId: string, profileVersionId: string) {
  return useQuery({ queryKey: ['http-profile-version-metadata', projectId, profileVersionId], enabled: Boolean(projectId && profileVersionId),
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<HttpProfileVersion>>(
      `${profilePath(projectId)}/by-version/${encodeURIComponent(profileVersionId)}/metadata`)) })
}

export function useHttpProfileMutations(projectId: string) {
  const client = useQueryClient()
  const refresh = async () => {
    await client.cancelQueries({ queryKey: httpProfilesKey(projectId) })
    await client.invalidateQueries({ queryKey: httpProfilesKey(projectId) })
  }
  const save = useMutation({ mutationFn: async ({ profileId, body }: { profileId?: string; body: string }) =>
    unwrapApiResponse(profileId
      ? await httpClient.putJson<ApiEnvelope<HttpProfile>>(profilePath(projectId, profileId), body)
      : await httpClient.postJson<ApiEnvelope<HttpProfile>>(profilePath(projectId), body)), onSuccess: refresh, onError: refresh })
  const status = useMutation({ mutationFn: async ({ profileId, expectedVersion, enabled }: {
    profileId: string; expectedVersion: number; enabled: boolean
  }) => unwrapApiResponse(await httpClient.patch<ApiEnvelope<HttpProfile>>(`${profilePath(projectId, profileId)}/status`, { expectedVersion, enabled })),
  onSuccess: refresh, onError: refresh })
  const remove = useMutation({ mutationFn: async ({ profileId, expectedVersion }: { profileId: string; expectedVersion: number }) =>
    unwrapApiVoidResponse(await httpClient.delete<ApiEnvelope<null>>(`${profilePath(projectId, profileId)}?expectedVersion=${expectedVersion}`)),
  onSuccess: refresh, onError: refresh })
  return { save, status, remove }
}

export function useHttpBinding(processId: string) {
  return useQuery({ queryKey: bindingKey(processId), enabled: Boolean(processId),
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<ProcessHttpBinding>>(bindingPath(processId))) })
}

export function useSaveHttpBinding(processId: string) {
  const client = useQueryClient()
  return useMutation({ mutationFn: async (json: string) =>
    unwrapApiResponse(await httpClient.putJson<ApiEnvelope<ProcessHttpBinding>>(bindingPath(processId), json)),
  onSuccess: async (binding) => {
    await client.cancelQueries({ queryKey: bindingKey(processId) })
    client.setQueryData(bindingKey(processId), binding)
  }, onError: () => { void client.invalidateQueries({ queryKey: bindingKey(processId) }) } })
}

export function useJsonMappingPreview(projectId: string) {
  return useMutation({ mutationFn: async ({ source, mapping }: { source: string; mapping: string }) => {
    requireJson(source, 'Preview source')
    requireJson(mapping, 'Mapping')
    return unwrapApiResponse(await httpClient.postJson<ApiEnvelope<{ version: number; result: unknown; resultJson?: string }>>(
      `/projects/${encodeURIComponent(projectId)}/json-mapping/preview`, `{"version":1,"source":${source},"mapping":${mapping}}`))
  } })
}

export function useHttpExecutions(runId: string, stepId: string) {
  const client = useQueryClient()
  return useQuery({ queryKey: httpExecutionsKey(runId, stepId), enabled: Boolean(runId && stepId),
    queryFn: async () => {
      const rows = unwrapApiResponse(await httpClient.get<ApiEnvelope<HttpExecution[]>>(`${executionPath(runId, stepId)}/http-executions`))
      const previous = client.getQueryData<HttpExecution[]>(httpExecutionsKey(runId, stepId)) ?? []
      if (executionStates(previous) !== executionStates(rows)) await reconcileRunViews(client, runId)
      return rows
    },
    refetchInterval: (query) => query.state.data?.some((row) => row.transportStatus === 'PENDING') ? 3000 : false })
}

/** Deliberately manual: opening a run does not inspect its generated request body. */
export function useHttpRequestPreview(runId: string, stepId: string) {
  return useMutation({ mutationFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<HttpRequestPreview>>(
    `${executionPath(runId, stepId)}/http-request-preview`)), retry: false })
}

export function useHttpExecutionMutations(runId: string, stepId: string) {
  const client = useQueryClient()
  const refresh = async (result: HttpExecution) => {
    const key = httpExecutionsKey(runId, stepId)
    await client.cancelQueries({ queryKey: key })
    client.setQueryData<HttpExecution[]>(key, (rows) => [...(rows ?? []).filter((row) => row.executionId !== result.executionId), result])
    await Promise.all([
      client.invalidateQueries({ queryKey: key }),
      client.invalidateQueries({ queryKey: ['flow-runs'] }),
      client.invalidateQueries({ queryKey: ['flow-run-steps', runId] }),
      client.invalidateQueries({ queryKey: ['flow-run-attempts', runId] }),
      client.invalidateQueries({ queryKey: ['flow-run-events', runId] }),
      client.invalidateQueries({ queryKey: ['flow-run-lineage', runId] }),
      client.invalidateQueries({ queryKey: httpAttentionKey }),
    ])
  }
  const execute = useMutation({ mutationFn: async (requestId: string) =>
    unwrapApiResponse(await httpClient.post<ApiEnvelope<HttpExecution>>(`${executionPath(runId, stepId)}/execute`, { requestId })),
  retry: false, onSuccess: refresh })
  const apply = useMutation({ mutationFn: async (executionId: string) => unwrapApiResponse(await httpClient.post<ApiEnvelope<HttpExecution>>(
    `${executionPath(runId, stepId)}/http-executions/${encodeURIComponent(executionId)}/apply`, {})), retry: false, onSuccess: refresh })
  return { execute, apply }
}
