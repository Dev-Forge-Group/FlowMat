import { useMutation, useQuery } from '@tanstack/react-query'
import {
  backendDtoToEditorDocument,
  editorDocumentToBackendSaveInput,
  type BackendEditorDocumentDto,
} from '../model/editorDocumentBackendAdapter'
import type { EditorDocument } from '../../../lib/flowmat-editor'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

export const editorDocumentQueryKey = (workflowId: string) => ['workflow-editor-document', workflowId] as const

export async function fetchEditorDocument(workflowId: string): Promise<EditorDocument> {
  const envelope = await httpClient.get<ApiEnvelope<BackendEditorDocumentDto>>(
    `/workflows/${workflowId}/editor-document`
  )
  return backendDtoToEditorDocument(unwrapApiResponse(envelope))
}

export async function saveEditorDocument(
  workflowId: string,
  document: EditorDocument,
): Promise<EditorDocument> {
  const envelope = await httpClient.put<ApiEnvelope<BackendEditorDocumentDto>>(
    `/workflows/${workflowId}/editor-document`,
    editorDocumentToBackendSaveInput(document)
  )
  return backendDtoToEditorDocument(unwrapApiResponse(envelope))
}

export function useEditorDocumentQuery(workflowId: string, enabled = true) {
  return useQuery({
    queryKey: editorDocumentQueryKey(workflowId),
    queryFn: () => fetchEditorDocument(workflowId),
    enabled: enabled && workflowId.trim().length > 0,
  })
}

export function useSaveEditorDocumentMutation(workflowId: string) {
  return useMutation({
    mutationFn: (document: EditorDocument) => saveEditorDocument(workflowId, document),
  })
}
