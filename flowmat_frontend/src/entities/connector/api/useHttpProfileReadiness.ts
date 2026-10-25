import { useMutation } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'
import type { HttpProfileReadiness } from '../model/types'

/** Manual metadata check only. It neither tests a connection nor resolves credential values. */
export function useHttpProfileReadiness(projectId: string, profileVersionId: string) {
  return useMutation({ mutationFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<HttpProfileReadiness>>(
    `/projects/${encodeURIComponent(projectId)}/http-profiles/by-version/${encodeURIComponent(profileVersionId)}/readiness`)),
  retry: false })
}
