import { createRevisionReceipts } from '../lib/revisionReceipts'
import { errorStatus } from '../../../shared/lib/errorMessage'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import type {
  ApiEnvelope,
  BomDto,
  BomLineImportResultDto,
  BomLineImportRowDto,
  BomLineType,
  BomRequirementDto,
  BomWhereUsedDto,
  BomWhereUsedTreeDto,
  BuildableQuantityDto,
  BomScrapPerformanceDto,
} from '../../../shared/types/api'

/** Mirrors BomCreateRequest. */
export interface BomCreateInput {
  targetItemId: string
  bomName: string
  baseQuantity: number
  baseUnit: string
  note?: string
}

/** Mirrors BomLineCreateRequest. */
export interface BomLineInput {
  childItemId: string
  quantity: number
  unit: string
  /** Blank is a material (docs/domain/bom-by-products.md). */
  lineType?: BomLineType
  /** A phantom sub-assembly: used through its own BOM (docs/domain/multi-level-bom.md P1); material lines only. */
  phantom?: boolean
  /** Planned scrap on top of the quantity, 0 to 1 (ADR-031); material lines only. */
  scrapRate?: number
  /** The material line this one stands in for; it goes last in that line's substitute group (ADR-032). */
  substituteFor?: string
  /** "Y": an optional material, planned only for a work order that picks it (ADR-033). */
  optionalYn?: 'Y'
}

/** submit needs write access; approve / reject / retire need project owner access. */
export type BomAction = 'submit' | 'approve' | 'reject' | 'retire' | 'revisions'

const path = (bomId: string) => `/boms/${encodeURIComponent(bomId)}`

/** BOM revisions that use the item as a line, approved first (GET /boms/where-used). */
export function useBomWhereUsedQuery(projectId: string, itemId: string | null) {
  return useQuery<BomWhereUsedDto[]>({
    queryKey: ['boms', projectId, 'where-used', itemId],
    queryFn: async () =>
      unwrapApiResponse(
        await httpClient.get<ApiEnvelope<BomWhereUsedDto[]>>(
          `/boms/where-used?projectId=${encodeURIComponent(projectId)}&itemId=${encodeURIComponent(itemId ?? '')}`,
        ),
      ),
    enabled: Boolean(projectId && itemId),
  })
}

/** Where the item is used at every level, through approved BOMs up to the top products. */
/** {@code on}: YYYY-MM-DD, or blank for the project's today (docs/domain/multi-level-bom.md M7). */
export function useBomWhereUsedTreeQuery(projectId: string, itemId: string | null, on = '') {
  return useQuery<BomWhereUsedTreeDto>({
    queryKey: ['boms', projectId, 'where-used-all-levels', itemId, on],
    queryFn: async () =>
      unwrapApiResponse(
        await httpClient.get<ApiEnvelope<BomWhereUsedTreeDto>>(
          `/boms/where-used/all-levels?projectId=${encodeURIComponent(projectId)}&itemId=${encodeURIComponent(itemId ?? '')}${on ? `&on=${encodeURIComponent(on)}` : ''}`,
        ),
      ),
    enabled: Boolean(projectId && itemId),
  })
}

export function useBomsQuery(projectId: string) {
  return useQuery<BomDto[]>({
    queryKey: ['boms', projectId],
    queryFn: async () =>
      unwrapApiResponse(await httpClient.get<ApiEnvelope<BomDto[]>>(`/boms?projectId=${encodeURIComponent(projectId)}`)),
    enabled: Boolean(projectId),
  })
}

export function useBomRequirementsQuery(bomId: string | null, quantity: number) {
  return useQuery<BomRequirementDto>({
    queryKey: ['bom-requirements', bomId, quantity],
    queryFn: async () =>
      unwrapApiResponse(
        await httpClient.get<ApiEnvelope<BomRequirementDto>>(`${path(bomId ?? '')}/requirements?quantity=${quantity}`),
      ),
    enabled: Boolean(bomId) && quantity > 0,
  })
}

/**
 * How much of the BOM's product usable stock could make now. Under ['inventories', projectId] so stock movements refresh
 * it, and fetched fresh when shown.
 */
export function useBomBuildableQuery(projectId: string, bomId: string | null) {
  return useQuery<BuildableQuantityDto>({
    queryKey: ['inventories', projectId, 'bom-buildable', bomId],
    queryFn: async () => unwrapApiResponse(await httpClient.get<ApiEnvelope<BuildableQuantityDto>>(`${path(bomId ?? '')}/buildable`)),
    enabled: Boolean(projectId && bomId),
    staleTime: 0,
  })
}

/** What the revision's finished runs lost against its planned scrap (GET /boms/{id}/scrap-performance, ADR-034). */
export function useBomScrapPerformanceQuery(projectId: string, bomId: string | null) {
  return useQuery<BomScrapPerformanceDto>({
    queryKey: ['inventories', projectId, 'bom-scrap-performance', bomId],
    queryFn: async () =>
      unwrapApiResponse(await httpClient.get<ApiEnvelope<BomScrapPerformanceDto>>(`${path(bomId ?? '')}/scrap-performance`)),
    enabled: Boolean(projectId && bomId),
  })
}

/** The same for every approved BOM of the project, against one read of the stock. */
export function useBuildableBomsQuery(projectId: string) {
  return useQuery<BuildableQuantityDto[]>({
    queryKey: ['inventories', projectId, 'bom-buildable'],
    queryFn: async () =>
      unwrapApiResponse(
        await httpClient.get<ApiEnvelope<BuildableQuantityDto[]>>(`/boms/buildable?projectId=${encodeURIComponent(projectId)}`),
      ),
    enabled: Boolean(projectId),
    staleTime: 0,
  })
}

function useInvalidateBoms(projectId: string) {
  const queryClient = useQueryClient()
  return () => void queryClient.invalidateQueries({ queryKey: ['boms', projectId] })
}

export function useCreateBomMutation(projectId: string) {
  const onSuccess = useInvalidateBoms(projectId)
  return useMutation({
    mutationFn: async (input: BomCreateInput) =>
      unwrapApiResponse(await httpClient.post<ApiEnvelope<BomDto>>('/boms', { projectId, ...input })),
    onSuccess,
  })
}

export function useBomLineMutations(projectId: string) {
  const onSuccess = useInvalidateBoms(projectId)
  const add = useMutation({
    mutationFn: async ({ bomId, ...line }: BomLineInput & { bomId: string }) =>
      unwrapApiResponse(await httpClient.post<ApiEnvelope<BomDto>>(`${path(bomId)}/lines`, line)),
    onSuccess,
  })
  const remove = useMutation({
    mutationFn: async ({ bomId, bomLineId }: { bomId: string; bomLineId: string }) =>
      unwrapApiResponse(
        await httpClient.delete<ApiEnvelope<BomDto>>(`${path(bomId)}/lines/${encodeURIComponent(bomLineId)}`),
      ),
    onSuccess,
  })
  return { add, remove }
}

// Pending request IDs survive reloads in this tab when session storage is available; denied storage falls back to this page. No tokens or recipe contents are stored.
const pendingRevisions = createRevisionReceipts(() => typeof window === 'undefined' ? null : window.sessionStorage)

export function useBomActionMutation(projectId: string) {
  const onSuccess = useInvalidateBoms(projectId)
  return useMutation({
    mutationFn: async ({ bomId, action, note, endEarlier }: { bomId: string; action: BomAction; note?: string; endEarlier?: boolean }) => {
      // endEarlier: approve and end the revisions this one replaces the day before it starts (multi-level-bom.md M4-M6).
      if (action !== 'revisions') return unwrapApiResponse(await httpClient.post<ApiEnvelope<BomDto>>(`${path(bomId)}/${action}`,
        { ...(note ? { note } : {}), ...(action === 'approve' && endEarlier ? { endEarlier: true } : {}) }))
      const requestId = pendingRevisions.requestId(projectId, bomId)
      try {
        const result = unwrapApiResponse(await httpClient.post<ApiEnvelope<BomDto>>(`${path(bomId)}/revisions`, { requestId }))
        if (!result || typeof result.bomId !== 'string' || !result.bomId || result.bomId === bomId
          || result.projectId !== projectId || !Number.isSafeInteger(result.bomVersion) || result.bomVersion < 1)
          throw new Error('Revision creation response is invalid. Retry New revision to recover the same revision.')
        pendingRevisions.confirmed(projectId, bomId, requestId)
        return result
      } catch (error) {
        const status = errorStatus(error)
        if (status != null && status >= 400 && status < 500) pendingRevisions.confirmed(projectId, bomId, requestId)
        throw error
      }
    },
    onSuccess,
    retry: false,
  })
}

/** Checks (dry run) or saves a draft's materials from a spreadsheet (docs/domain/item-import.md "BOM 자재"). */
export function useImportBomLinesMutation(projectId: string) {
  const onSuccess = useInvalidateBoms(projectId)
  return useMutation({
    mutationFn: async ({ bomId, ...body }: { bomId: string; rows: BomLineImportRowDto[]; dryRun: boolean; replace: boolean }) =>
      unwrapApiResponse(await httpClient.post<ApiEnvelope<BomLineImportResultDto>>(`${path(bomId)}/lines/import`, body)),
    onSuccess,
  })
}

/** Another product's first draft BOM from this one's base and materials (POST /boms/{id}/copy). */
export function useCopyBomMutation(projectId: string) {
  const onSuccess = useInvalidateBoms(projectId)
  return useMutation({
    mutationFn: async ({ bomId, targetItemId, bomName }: { bomId: string; targetItemId: string; bomName?: string }) =>
      unwrapApiResponse(await httpClient.post<ApiEnvelope<BomDto>>(`${path(bomId)}/copy`, { targetItemId, bomName })),
    onSuccess,
  })
}
