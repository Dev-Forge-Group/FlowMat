import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse, unwrapApiVoidResponse } from '../../../shared/api/unwrapApiResponse'
import type { ApiEnvelope } from '../../../shared/types/api'

export interface WorkInstructionStepDto {
  stepId: string
  stepNo: number
  text: string
  required: boolean
  recordsValue: boolean
  /** What the value is, such as "Oven °C". */
  valueLabel: string | null
  /** Limits for the value (docs/domain/work-instruction.md R7); null when there are none. */
  valueMin: number | null
  valueMax: number | null
  /** The inspection standard the limits come from (W13); null when they were typed. */
  standardId?: string | null
  /** The standard's check, such as "Moisture". */
  standardName?: string | null
  /** current; changed: the standard's limits changed since; inactive: it can no longer be followed (W15). */
  standardStatus?: 'current' | 'changed' | 'inactive' | null
  /** The process the step belongs to and its name (docs/architecture/adr/ADR-011-production-steps.md); null for the whole run. */
  processId?: string | null
  processName?: string | null
}

/** One revision of a product's work instruction (docs/domain/work-instruction.md). */
export interface WorkInstructionDto {
  instructionId: string
  projectId: string
  itemId: string
  itemCode: string | null
  itemName: string | null
  revisionNo: number
  status: 'draft' | 'released' | 'retired'
  title: string
  body: string | null
  documentUrl: string | null
  releasedBy: string | null
  releasedAt: string | null
  updatedAt: string | null
  steps: WorkInstructionStepDto[]
  /** A run cannot finish until the required steps are confirmed. */
  blocksFinish: boolean
}

/** A run's checklist: the revision it works to and what is confirmed. */
export interface RunInstructionDto {
  productionRunId: string
  /** The run can still be confirmed (pending or running). */
  open: boolean
  instruction: WorkInstructionDto | null
  /**
   * {@code outOfLimits}: the value lies outside the step's limits; it is recorded all the same. {@code inspectionResult}:
   * the quality inspection a step that follows a standard also recorded (R9), pass or fail.
   */
  checks: {
    stepId: string
    value: string | null
    note: string | null
    checkedBy: string
    checkedAt: string
    outOfLimits: boolean
    inspectionId?: string | null
    inspectionResult?: 'pass' | 'fail' | null
  }[]
  requiredSteps: number
  requiredDone: number
  complete: boolean
  /** Confirmations that were undone, the earliest undo first (docs/domain/work-instruction.md R6). */
  undone: {
    stepId: string
    stepNo: number
    value: string | null
    note: string | null
    checkedBy: string
    checkedAt: string
    undoneBy: string
    undoneAt: string
  }[]
}

export interface WorkInstructionTextInput {
  title: string
  body: string | null
  documentUrl: string | null
  blocksFinish: boolean
}

export interface WorkInstructionStepInput {
  text: string
  required: boolean
  recordsValue: boolean
  valueLabel: string | null
  valueMin?: number
  valueMax?: number
  /** Follow this inspection standard's limits instead of typing them (W13). */
  standardId?: string
  /** The process the step belongs to (ADR-011); left out for the whole run. */
  processId?: string
}

const listKey = (projectId: string) => ['work-instructions', projectId]
const runKey = (runId: string) => ['run-instruction', runId]
const base = (instructionId: string) => `/work-instructions/${encodeURIComponent(instructionId)}`

/** Every revision of the project's instructions, per product with the newest revision first. */
export function useWorkInstructionsQuery(projectId: string) {
  return useQuery<WorkInstructionDto[]>({
    queryKey: listKey(projectId),
    queryFn: async () =>
      unwrapApiResponse(await httpClient.get<ApiEnvelope<WorkInstructionDto[]>>(`/work-instructions?projectId=${encodeURIComponent(projectId)}`)),
    enabled: Boolean(projectId),
  })
}

export function useWorkInstructionMutations(projectId: string) {
  const queryClient = useQueryClient()
  const onSuccess = () => {
    void queryClient.invalidateQueries({ queryKey: listKey(projectId) })
    void queryClient.invalidateQueries({ queryKey: ['run-instruction'] })
  }
  const post = async (path: string, body: unknown = {}) => unwrapApiResponse(await httpClient.post<ApiEnvelope<WorkInstructionDto>>(path, body))
  return {
    create: useMutation({
      mutationFn: ({ itemId, title }: { itemId: string; title: string }) =>
        post('/work-instructions', { projectId, itemId, title, body: null, documentUrl: null }),
      onSuccess,
    }),
    update: useMutation({
      mutationFn: async ({ instructionId, ...text }: WorkInstructionTextInput & { instructionId: string }) =>
        unwrapApiResponse(await httpClient.put<ApiEnvelope<WorkInstructionDto>>(base(instructionId), text)),
      onSuccess,
    }),
    addStep: useMutation({
      mutationFn: ({ instructionId, ...step }: WorkInstructionStepInput & { instructionId: string }) => post(`${base(instructionId)}/steps`, step),
      onSuccess,
    }),
    removeStep: useMutation({
      mutationFn: async ({ instructionId, stepId }: { instructionId: string; stepId: string }) =>
        unwrapApiResponse(await httpClient.delete<ApiEnvelope<WorkInstructionDto>>(`${base(instructionId)}/steps/${encodeURIComponent(stepId)}`)),
      onSuccess,
    }),
    release: useMutation({ mutationFn: (instructionId: string) => post(`${base(instructionId)}/release`), onSuccess }),
    revise: useMutation({ mutationFn: (instructionId: string) => post(`${base(instructionId)}/revise`), onSuccess }),
    remove: useMutation({
      mutationFn: async (instructionId: string) => unwrapApiVoidResponse(await httpClient.delete<ApiEnvelope<null>>(base(instructionId))),
      onSuccess,
    }),
  }
}

export function useRunInstructionQuery(runId: string) {
  return useQuery<RunInstructionDto>({
    queryKey: runKey(runId),
    queryFn: async () =>
      unwrapApiResponse(await httpClient.get<ApiEnvelope<RunInstructionDto>>(`/production-runs/${encodeURIComponent(runId)}/instruction`)),
    enabled: Boolean(runId),
  })
}

/** Confirming or undoing a step returns the whole checklist. */
export function useRunInstructionMutations(runId: string) {
  const queryClient = useQueryClient()
  const onSuccess = (checklist: RunInstructionDto) => queryClient.setQueryData(runKey(runId), checklist)
  const path = (stepId: string) => `/production-runs/${encodeURIComponent(runId)}/instruction/steps/${encodeURIComponent(stepId)}/check`
  return {
    check: useMutation({
      mutationFn: async ({ stepId, value, note, result }: {
        stepId: string
        value: string | null
        note: string | null
        /** pass or fail for a step that follows a standard without limits (R10). */
        result?: 'pass' | 'fail' | null
      }) => unwrapApiResponse(await httpClient.post<ApiEnvelope<RunInstructionDto>>(path(stepId), { value, note, ...(result ? { result } : {}) })),
      onSuccess: (checklist: RunInstructionDto) => {
        onSuccess(checklist)
        // A step that follows a standard records an inspection too (R9): the run's quality checklist and records change.
        if (checklist.checks.some((one) => one.inspectionId)) {
          void queryClient.invalidateQueries({ queryKey: ['quality-inspections'] })
          void queryClient.invalidateQueries({ queryKey: ['quality-summary'] })
        }
      },
    }),
    uncheck: useMutation({
      mutationFn: async (stepId: string) => unwrapApiResponse(await httpClient.delete<ApiEnvelope<RunInstructionDto>>(path(stepId))),
      onSuccess,
    }),
  }
}
