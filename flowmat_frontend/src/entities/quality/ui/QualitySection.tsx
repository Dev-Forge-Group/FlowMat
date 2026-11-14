import { useMemo, useState, type FormEvent, type ReactNode } from 'react'
import { errorMessage } from '../../../shared/lib/errorMessage'
import type { DefectDto, InventoryDto, QualityInspectionDto } from '../../../shared/types/api'
import { useNcrDefectLinksQuery, useNonconformitiesQuery, useNonconformityMutations } from '../api/useNonconformities'
import { canGather, ncrBadge, ncrByDefect, ncrOptionLabel, ncrTitle } from '../model/ncrFromDefectsModel'
import {
  useDefectsQuery,
  useLogDefectMutation,
  useQualityInspectionsQuery,
  useRecordInspectionMutation,
  useResolveDefectMutation,
  type QualityFilter,
} from '../api/useQuality'
import { useInspectionStandardsQuery } from '../api/useInspectionStandards'
import type { InspectionTarget } from '../model/qualityModel'
import { DefectForm, DefectList, InspectionForm, InspectionList } from './QualityRecords'

interface Props {
  projectId: string
  /** Which records to show: one run's or one LOT's. */
  filter: QualityFilter
  /** What can be inspected from here. */
  targets: InspectionTarget[]
  targetLabel: (target: InspectionTarget) => string
  /** Sent with new records; null when they are not about a run. */
  productionRunId: string | null
  itemLabel: (itemId: string) => string
  /** Shown when there is nothing to inspect. */
  emptyHint: string
  /** The project's stock records, offered for scrapping when a defect is resolved. */
  stock: InventoryDto[]
  intro?: ReactNode
}

/**
 * Inspections and defects for one run or one LOT (docs/domain/quality-inspection.md): the lists, and the forms to add
 * to them. A failed inspection of a LOT can quarantine it; logging a defect moves no stock.
 */
export function QualitySection({
  projectId,
  filter,
  targets,
  targetLabel,
  productionRunId,
  itemLabel,
  emptyHint,
  stock,
  intro,
}: Props) {
  const inspectionsQuery = useQualityInspectionsQuery(projectId, filter)
  const defectsQuery = useDefectsQuery(projectId, filter)
  const recordMutation = useRecordInspectionMutation(projectId)
  const logMutation = useLogDefectMutation(projectId)
  const resolveMutation = useResolveDefectMutation(projectId)
  const standardsQuery = useInspectionStandardsQuery(projectId)
  const [open, setOpen] = useState<null | 'inspection' | { defectFrom: QualityInspectionDto | null }>(null)
  const linksQuery = useNcrDefectLinksQuery(projectId)
  const { create: raiseNcr, addDefects } = useNonconformityMutations(projectId)
  // Defects ticked for a new nonconformity, and its title once typed (until then it follows the ticked defects).
  const [gathered, setGathered] = useState<string[]>([])
  const [ncrTitleInput, setNcrTitleInput] = useState<string | null>(null)
  const [raised, setRaised] = useState<string | null>(null)
  // '' raises a new NCR; otherwise the id of the open NCR the ticked defects are added to.
  const [target, setTarget] = useState('')

  const inspections = inspectionsQuery.data ?? []
  const defects = defectsQuery.data ?? []
  const links = useMemo(() => ncrByDefect(linksQuery.data ?? []), [linksQuery.data])
  // A defect gathered elsewhere since it was ticked drops out of the selection.
  const chosen = defects.filter((defect) => gathered.includes(defect.defectLogId) && canGather(defect, links))
  const title = ncrTitleInput ?? ncrTitle(chosen)
  // Open NCRs are only fetched once a defect is ticked.
  const openNcrs = useNonconformitiesQuery(chosen.length > 0 ? projectId : '', 'open').data ?? []
  // An NCR closed or cancelled since it was picked falls back to a new one.
  const addTo = openNcrs.find((ncr) => ncr.nonconformityId === target) ?? null
  const ncrPending = raiseNcr.isPending || addDefects.isPending
  const ncrError = raiseNcr.isError ? raiseNcr.error : addDefects.isError ? addDefects.error : null

  function toggleGathered(defect: DefectDto) {
    setRaised(null)
    raiseNcr.reset()
    addDefects.reset()
    setGathered((ids) => (ids.includes(defect.defectLogId) ? ids.filter((id) => id !== defect.defectLogId) : [...ids, defect.defectLogId]))
  }

  async function raiseFromDefects(event: FormEvent) {
    event.preventDefault()
    const defectLogIds = chosen.map((defect) => defect.defectLogId)
    const count = (n: number) => `${n} defect${n === 1 ? '' : 's'}`
    if (defectLogIds.length === 0) return
    if (addTo) {
      await addDefects.mutateAsync({ id: addTo.nonconformityId, defectLogIds })
      setGathered([])
      setRaised(`Added ${count(defectLogIds.length)} to ${addTo.ncrNo}.`)
      return
    }
    if (!title.trim()) return
    const ncr = await raiseNcr.mutateAsync({ title: title.trim(), description: null, severity: null, defectLogIds })
    setGathered([])
    setNcrTitleInput(null)
    setRaised(`Raised ${ncr.ncrNo} from ${count(ncr.defects.length)}.`)
  }

  function subjectOf(record: { itemId: string | null; itemCode: string | null; lotNo: string | null }) {
    const item = record.itemId ? itemLabel(record.itemId) : (record.itemCode ?? '')
    return record.lotNo ? `${item} · LOT ${record.lotNo}` : item
  }

  function openForm(next: typeof open) {
    recordMutation.reset()
    logMutation.reset()
    setOpen(next)
  }

  const saveError = recordMutation.isError
    ? errorMessage(recordMutation.error, 'The inspection could not be saved.')
    : logMutation.isError
      ? errorMessage(logMutation.error, 'The defect could not be saved.')
      : null

  return (
    <>
      {intro}
      <h4 style={{ margin: '8px 0 4px' }}>Inspections</h4>
      {inspectionsQuery.isError && (
        <p style={{ color: '#dc2626', fontSize: 12 }}>{errorMessage(inspectionsQuery.error, 'Failed to load inspections.')}</p>
      )}
      {inspections.length === 0 && !inspectionsQuery.isLoading && <p className="inspector-hint">No inspections yet.</p>}
      <InspectionList inspections={inspections} subjectOf={subjectOf} onLogDefect={(inspection) => openForm({ defectFrom: inspection })} />

      <h4 style={{ margin: '12px 0 4px' }}>Defects</h4>
      {defectsQuery.isError && <p style={{ color: '#dc2626', fontSize: 12 }}>{errorMessage(defectsQuery.error, 'Failed to load defects.')}</p>}
      {defects.length === 0 && !defectsQuery.isLoading && <p className="inspector-hint">No defects logged.</p>}
      <DefectList
        defects={defects}
        subjectOf={subjectOf}
        stock={stock}
        resolving={resolveMutation.isPending}
        onResolve={(defect, resolution) => resolveMutation.mutateAsync({ defectLogId: defect.defectLogId, ...resolution })}
        ncrOf={(defect) => {
          const link = links.get(defect.defectLogId)
          return link ? ncrBadge(link) : null
        }}
        gathered={gathered}
        onGather={toggleGathered}
      />
      {chosen.length > 0 && (
        <form aria-label="Raise NCR from defects" onSubmit={(event) => void raiseFromDefects(event).catch(() => undefined)}
          style={{ display: 'flex', gap: 8, alignItems: 'end', flexWrap: 'wrap', fontSize: 12, marginTop: 8 }}>
          <label style={{ display: 'grid', gap: 2 }}>
            <span>Add to</span>
            <select value={addTo?.nonconformityId ?? ''} onChange={(event) => setTarget(event.target.value)}>
              <option value="">New NCR</option>
              {openNcrs.map((ncr) => <option key={ncr.nonconformityId} value={ncr.nonconformityId}>{ncrOptionLabel(ncr)}</option>)}
            </select>
          </label>
          {!addTo && (
            <label style={{ display: 'grid', gap: 2, flex: '1 1 240px' }}>
              <span>NCR title *</span>
              <input value={title} maxLength={200} required onChange={(event) => setNcrTitleInput(event.target.value)} />
            </label>
          )}
          <button type="submit" disabled={ncrPending || (!addTo && !title.trim())}>
            {ncrPending ? 'Saving...' : addTo
              ? `Add ${chosen.length} defect${chosen.length === 1 ? '' : 's'} to ${addTo.ncrNo}`
              : `Raise NCR from ${chosen.length} defect${chosen.length === 1 ? '' : 's'}`}
          </button>
          <button type="button" onClick={() => { setGathered([]); setNcrTitleInput(null) }}>Clear</button>
          <span className="inspector-hint" style={{ flexBasis: '100%' }}>
            Severity is the highest of the defects; cause, disposition and actions are recorded on the Quality tab.
          </span>
        </form>
      )}
      {raised && <p role="status" style={{ fontSize: 12, margin: '4px 0' }}>{raised}</p>}
      {ncrError && (
        <p role="alert" style={{ color: '#dc2626', fontSize: 12 }}>
          {errorMessage(ncrError, 'The nonconformity could not be saved.')}
        </p>
      )}
      {resolveMutation.isError && (
        <p role="alert" style={{ color: '#dc2626', fontSize: 12 }}>
          {errorMessage(resolveMutation.error, 'The defect could not be resolved.')}
        </p>
      )}

      {open === null && (
        <div style={{ display: 'flex', gap: 8, marginTop: 12, flexWrap: 'wrap' }}>
          <button type="button" disabled={targets.length === 0} onClick={() => openForm('inspection')}>
            Record inspection
          </button>
          <button type="button" disabled={targets.length === 0} onClick={() => openForm({ defectFrom: null })}>
            Log defect
          </button>
          {targets.length === 0 && <span className="inspector-hint">{emptyHint}</span>}
        </div>
      )}
      {open === 'inspection' && (
        <InspectionForm
          targets={targets}
          targetLabel={targetLabel}
          productionRunId={productionRunId}
          standards={standardsQuery.data ?? []}
          pending={recordMutation.isPending}
          onSubmit={(body) => recordMutation.mutateAsync(body)}
          onDone={() => setOpen(null)}
          onCancel={() => openForm(null)}
        />
      )}
      {open !== null && open !== 'inspection' && (
        <DefectForm
          key={open.defectFrom?.inspectionId ?? 'new'}
          targets={targets}
          targetLabel={targetLabel}
          productionRunId={productionRunId}
          fromInspection={
            open.defectFrom
              ? { inspectionId: open.defectFrom.inspectionId, label: `${open.defectFrom.inspectionType} (${subjectOf(open.defectFrom)})` }
              : null
          }
          pending={logMutation.isPending}
          onSubmit={async (body) => {
            await logMutation.mutateAsync(body)
            setOpen(null)
          }}
          onCancel={() => openForm(null)}
        />
      )}
      {saveError && (
        <p role="alert" style={{ color: '#dc2626', fontSize: 12 }}>
          {saveError}
        </p>
      )}
    </>
  )
}
