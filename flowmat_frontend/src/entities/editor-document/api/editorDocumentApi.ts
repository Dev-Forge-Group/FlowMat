import { useMutation, useQuery } from '@tanstack/react-query'
import {
  backendDtoToEditorDocument,
  editorDocumentToBackendSaveInput,
  editorElementChanges,
  type BackendEditorDocumentDto,
  type ServerEditorDocument,
} from '../model/editorDocumentBackendAdapter'
import type { EditorDocument } from '../../../lib/flowmat-editor'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

export const editorDocumentQueryKey = (workflowId: string) => ['workflow-editor-document', workflowId] as const

export async function fetchEditorDocument(workflowId: string): Promise<ServerEditorDocument> {
  const envelope = await httpClient.get<ApiEnvelope<BackendEditorDocumentDto>>(
    `/workflows/${workflowId}/editor-document`
  )
  return backendDtoToEditorDocument(unwrapApiResponse(envelope))
}

export async function saveEditorDocument(
  workflowId: string,
  document: EditorDocument,
): Promise<ServerEditorDocument> {
  const envelope = await httpClient.put<ApiEnvelope<BackendEditorDocumentDto>>(
    `/workflows/${workflowId}/editor-document`,
    editorDocumentToBackendSaveInput(document)
  )
  return backendDtoToEditorDocument(unwrapApiResponse(envelope))
}

/**
 * Saves what changed since the server's document `base` as element changes
 * (docs/architecture/adr/ADR-042-drawing-layer-element-changes.md): others' edits to other elements made meanwhile are
 * kept, and an element someone else changed since `base` refuses the request with 409, naming it. Nothing to send gives
 * `base` back.
 */
export async function patchEditorElements(
  workflowId: string,
  base: ServerEditorDocument,
  next: EditorDocument,
): Promise<ServerEditorDocument> {
  const input = editorElementChanges(base, next)
  if (input.changes.length === 0) return base
  const envelope = await httpClient.patch<ApiEnvelope<BackendEditorDocumentDto>>(
    `/workflows/${workflowId}/editor-document/elements`,
    input
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
