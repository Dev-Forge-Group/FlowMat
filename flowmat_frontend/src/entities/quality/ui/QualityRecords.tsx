import { useRef, useState, type FormEvent } from 'react'
import { formatQty } from '../../../shared/lib/formatQty'
import type {
  DefectCreateRequest,
  DefectDto,
  InventoryDto,
  QualityInspectionCreateRequest,
  QualityInspectionDto,
} from '../../../shared/types/api'
import type { DefectResolution } from '../api/useQuality'
import type { InspectionStandardDto } from '../api/useInspectionStandards'
import { standardLabel, usableStandards } from '../model/standardModel'
import {
  EMPTY_DEFECT,
  EMPTY_INSPECTION,
  buildDefectRequest,
  buildInspectionRequest,
  buildResolution,
  describeMeasurement,
  measuredResult,
  scrapCandidates,
  type DefectDraft,
  type InspectionDraft,
  type InspectionTarget,
} from '../model/qualityModel'

/**
 * Lists and forms for inspections and defects (docs/domain/quality-inspection.md), shared by the run page and the LOT
 * page. They show and collect; the caller owns the queries and mutations.
 */

const RESULT_STYLE = {
  pass: { symbol: '✓', color: '#047857', label: 'Pass' },
  fail: { symbol: '✕', color: '#b91c1c', label: 'Fail' },
} as const

const field = { display: 'grid', gap: 4 } as const
const card = { border: '1px solid var(--border)', borderRadius: 8, padding: 8, fontSize: 12 } as const
const list = { listStyle: 'none', padding: 0, margin: 0, display: 'grid', gap: 8 } as const

type Subject = { itemId: string | null; itemCode: string | null; lotNo: string | null }

export function InspectionList({
  inspections,
  subjectOf,
  onLogDefect,
}: {
  inspections: QualityInspectionDto[]
  subjectOf: (record: Subject) => string
  /** Offered on failed inspections when given. */
  onLogDefect?: (inspection: QualityInspectionDto) => void
}) {
  return (
    <ol aria-label="Inspections" style={list}>
      {inspections.map((item) => {
        const style = RESULT_STYLE[item.resultStatus] ?? RESULT_STYLE.fail
        const measurement = describeMeasurement(item, formatQty)
        return (
          <li key={item.inspectionId} aria-label={`Inspection ${item.inspectionType}`} style={card}>
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
              <span>
                <strong style={{ color: style.color }}>
                  {style.symbol} {style.label}
                </strong>{' '}
                {item.inspectionType}
                {measurement ? `: ${measurement}` : ''}
              </span>
              {item.lotStatus === 'quarantined' && <span style={{ color: '#b45309' }}>LOT quarantined</span>}
            </div>
            <div>
              {subjectOf(item)}
              {item.runNumber ? ` · run ${item.runNumber}` : ''}
            </div>
            {item.note && <div>{item.note}</div>}
            <div style={{ opacity: 0.7 }}>
              {item.inspectedBy} · {new Date(item.inspectedAt).toLocaleString()}
            </div>
            {onLogDefect && item.resultStatus === 'fail' && (
              <button type="button" style={{ marginTop: 6, fontSize: 11 }} onClick={() => onLogDefect(item)}>
                Log defect
              </button>
            )}
          </li>
        )
      })}
    </ol>
  )
}

export function DefectList({
  defects,
  subjectOf,
  stock,
  onResolve,
  resolving,
  ncrOf,
  gathered,
  onGather,
}: {
  defects: DefectDto[]
  subjectOf: (record: Subject) => string
  /** The project's stock records, to offer scrapping from when a defect is resolved. */
  stock: InventoryDto[]
  onResolve: (defect: DefectDto, resolution: DefectResolution) => Promise<unknown>
  resolving: boolean
  /** The NCR number to show on a defect a nonconformity holds (docs/domain/nonconformity.md). */
  ncrOf?: (defect: DefectDto) => string | null
  /** Defects ticked for a new nonconformity; with {@link onGather}, open defects no NCR holds get a tick box. */
  gathered?: string[]
  onGather?: (defect: DefectDto) => void
}) {
  const [resolvingId, setResolvingId] = useState<string | null>(null)
  return (
    <ol aria-label="Defects" style={list}>
      {defects.map((item) => (
        <li key={item.defectLogId} aria-label={`Defect ${item.defectType}`} style={card}>
          <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
            <span>
              <strong>{item.defectType}</strong> {formatQty(item.quantity)} {item.unit ?? ''} · {item.severity}
            </span>
            <span style={{ display: 'flex', gap: 8 }}>
              {ncrOf?.(item) && <span aria-label="Nonconformity" style={{ fontWeight: 600 }}>{ncrOf(item)}</span>}
              <span style={{ color: item.resolved ? '#047857' : '#b45309' }}>{item.resolved ? 'Resolved' : 'Open'}</span>
            </span>
          </div>
          <div>
            {subjectOf(item)}
            {item.runNumber ? ` · run ${item.runNumber}` : ''}
          </div>
          {item.reason && <div>{item.reason}</div>}
          <div style={{ opacity: 0.7 }}>
            Logged by {item.loggedBy} · {new Date(item.loggedAt).toLocaleString()}
            {item.resolved && item.resolvedAt && (
              <>
                <br />
                Resolved by {item.resolvedBy} · {new Date(item.resolvedAt).toLocaleString()}: {item.actionTaken}
              </>
            )}
          </div>
          {!item.resolved && resolvingId !== item.defectLogId && (
            <button type="button" style={{ marginTop: 6, fontSize: 11 }} onClick={() => setResolvingId(item.defectLogId)}>
              Resolve
            </button>
          )}
          {onGather && !item.resolved && !ncrOf?.(item) && (
            <label style={{ display: 'inline-flex', gap: 4, marginLeft: 8, fontSize: 11 }}>
              <input type="checkbox" checked={gathered?.includes(item.defectLogId) ?? false} onChange={() => onGather(item)} />
              For a new NCR
            </label>
          )}
          {!item.resolved && resolvingId === item.defectLogId && (
            <ResolveDefectForm
              defect={item}
              candidates={scrapCandidates(item, stock)}
              pending={resolving}
              onSubmit={async (resolution) => {
                await onResolve(item, resolution)
                setResolvingId(null)
              }}
              onCancel={() => setResolvingId(null)}
            />
          )}
        </li>
      ))}
    </ol>
  )
}

/** What was done about a defect, optionally writing the defective stock off from one record in the same step. */
function ResolveDefectForm({
  defect,
  candidates,
  pending,
  onSubmit,
  onCancel,
}: {
  defect: DefectDto
  candidates: InventoryDto[]
  pending: boolean
  onSubmit: (resolution: DefectResolution) => Promise<void>
  onCancel: () => void
}) {
  const [actionTaken, setActionTaken] = useState('')
  const [scrapFrom, setScrapFrom] = useState('')
  const [scrapQuantity, setScrapQuantity] = useState('')
  const [error, setError] = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    const built = buildResolution({ actionTaken, scrapFrom, scrapQuantity })
    if (!built.ok) {
      setError(built.error)
      return
    }
    setError(null)
    try {
      await onSubmit(built.body)
    } catch {
      // The caller shows the server's answer.
    }
  }

  return (
    <form
      aria-label={`Resolve defect ${defect.defectType}`}
      onSubmit={(e) => void submit(e)}
      style={{ display: 'grid', gap: 6, marginTop: 6 }}
    >
      <label style={field}>
        <span>What was done *</span>
        <input value={actionTaken} maxLength={2000} onChange={(e) => setActionTaken(e.target.value)} />
      </label>
      {candidates.length > 0 && (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 90px', gap: 6 }}>
          <label style={field}>
            <span>Scrap from stock (optional)</span>
            <select value={scrapFrom} onChange={(e) => setScrapFrom(e.target.value)}>
              <option value="">Nothing to scrap</option>
              {candidates.map((row) => (
                <option key={row.inventoryId} value={row.inventoryId}>
                  {row.location ?? 'no location'}
                  {row.lotNo ? ` · LOT ${row.lotNo}` : ''} · {formatQty(row.quantity)} on hand
                  {row.inventoryStatus === 'quarantined' ? ' · quarantined' : ''}
                </option>
              ))}
            </select>
          </label>
          <label style={field}>
            <span>Quantity</span>
            <input inputMode="decimal" value={scrapQuantity} disabled={!scrapFrom} onChange={(e) => setScrapQuantity(e.target.value)} />
          </label>
        </div>
      )}
      {error && (
        <p role="alert" style={{ color: '#dc2626', fontSize: 12, margin: 0 }}>
          {error}
        </p>
      )}
      <div style={{ display: 'flex', gap: 6 }}>
        <button type="submit" disabled={pending} style={{ fontSize: 11 }}>
          {pending ? 'Saving...' : scrapFrom ? 'Resolve and scrap' : 'Resolve'}
        </button>
        <button type="button" onClick={onCancel} style={{ fontSize: 11 }}>
          Cancel
        </button>
      </div>
    </form>
  )
}

interface FormProps<T> {
  targets: InspectionTarget[]
  targetLabel: (target: InspectionTarget) => string
  productionRunId: string | null
  pending: boolean
  onSubmit: (body: T) => Promise<unknown>
  onCancel: () => void
  /** Called once a save ends with the form as it was sent; a form changed while saving stays open with the new draft. */
  onDone?: () => void
}

export function InspectionForm({
  targets,
  targetLabel,
  productionRunId,
  pending,
  onSubmit,
  onCancel,
  onDone,
  standards,
}: FormProps<Omit<QualityInspectionCreateRequest, 'projectId'>> & {
  /** The project's inspection standards; those of the inspected item can be followed (docs/domain/inspection-standard.md). */
  standards?: InspectionStandardDto[]
}) {
  const [draft, setDraft] = useState<InspectionDraft>({ ...EMPTY_INSPECTION, targetKey: targets[0]?.key ?? '' })
  const [error, setError] = useState<string | null>(null)
  const [savedNote, setSavedNote] = useState(false)
  // The draft as last rendered, so a save that ends can tell whether anything was typed while it waited.
  const latestDraft = useRef(draft)
  latestDraft.current = draft
  const measured = measuredResult(draft)
  const failed = (measured ?? draft.result) === 'fail'
  const target = targets.find((t) => t.key === draft.targetKey)
  const offered = usableStandards(standards ?? [], target?.itemId)
  const followed = offered.find((one) => one.standardId === draft.standardId)

  // Following a standard fills in its check, limits and unit, which the server then insists on.
  function follow(standardId: string) {
    const standard = offered.find((one) => one.standardId === standardId)
    setDraft(standard
      ? {
          ...draft,
          standardId,
          inspectionType: standard.inspectionType,
          standardMin: standard.standardMin === null ? '' : String(standard.standardMin),
          standardMax: standard.standardMax === null ? '' : String(standard.standardMax),
          unit: standard.unit ?? '',
        }
      : { ...draft, standardId: '' })
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    // The box only counts while the result is a failure; it stays ticked underneath if the result changes.
    const result = buildInspectionRequest({ ...draft, quarantineLot: draft.quarantineLot && failed }, targets, productionRunId)
    if (!result.ok) {
      setError(result.error)
      return
    }
    setError(null)
    setSavedNote(false)
    const submitted = draft
    try {
      await onSubmit(result.body)
    } catch {
      // The caller shows the server's answer.
      return
    }
    // What was typed while this saved is the next inspection: keep it open instead of closing over it.
    if (latestDraft.current === submitted) onDone?.()
    else setSavedNote(true)
  }

  return (
    <form aria-label="Record inspection" onSubmit={(e) => void submit(e)} style={{ display: 'grid', gap: 8, fontSize: 13, marginTop: 12 }}>
      {targets.length > 1 && (
        <label style={field}>
          <span>What was inspected</span>
          <select value={draft.targetKey} onChange={(e) => setDraft({ ...draft, targetKey: e.target.value, quarantineLot: false, standardId: '' })}>
            {targets.map((t) => (
              <option key={t.key} value={t.key}>
                {targetLabel(t)}
              </option>
            ))}
          </select>
        </label>
      )}
      {offered.length > 0 && (
        <label style={field}>
          <span>Standard</span>
          <select value={draft.standardId ?? ''} onChange={(e) => follow(e.target.value)}>
            <option value="">(none, type the check)</option>
            {offered.map((one) => (
              <option key={one.standardId} value={one.standardId}>
                {standardLabel(one)}
              </option>
            ))}
          </select>
        </label>
      )}
      <label style={field}>
        <span>Check *</span>
        <input
          value={draft.inspectionType}
          maxLength={50}
          readOnly={Boolean(followed)}
          placeholder="Moisture, Visual, Weight..."
          onChange={(e) => setDraft({ ...draft, inspectionType: e.target.value })}
        />
      </label>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, minmax(0, 1fr))', gap: 6 }}>
        {(
          [
            ['Measured', 'measuredValue'],
            ['Min', 'standardMin'],
            ['Max', 'standardMax'],
          ] as const
        ).map(([label, key]) => (
          <label key={key} style={field}>
            <span>{label}</span>
            <input
              inputMode="decimal"
              value={draft[key]}
              readOnly={Boolean(followed) && key !== 'measuredValue'}
              onChange={(e) => setDraft({ ...draft, [key]: e.target.value })}
            />
          </label>
        ))}
        <label style={field}>
          <span>Unit</span>
          <input value={draft.unit} maxLength={20} readOnly={Boolean(followed?.unit)} onChange={(e) => setDraft({ ...draft, unit: e.target.value })} />
        </label>
      </div>
      {measured ? (
        <p style={{ margin: 0, color: RESULT_STYLE[measured].color }}>Result from the measurement: {RESULT_STYLE[measured].label}</p>
      ) : (
        <label style={field}>
          <span>Result *</span>
          <select value={draft.result} onChange={(e) => setDraft({ ...draft, result: e.target.value as InspectionDraft['result'] })}>
            <option value="">Choose...</option>
            <option value="pass">Pass</option>
            <option value="fail">Fail</option>
          </select>
        </label>
      )}
      <label style={field}>
        <span>Note</span>
        <input value={draft.note} maxLength={2000} onChange={(e) => setDraft({ ...draft, note: e.target.value })} />
      </label>
      {target?.lotId && (
        <label style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
          <input
            type="checkbox"
            checked={draft.quarantineLot && failed}
            disabled={!failed}
            onChange={(e) => setDraft({ ...draft, quarantineLot: e.target.checked })}
          />
          Quarantine this LOT (failed inspections only)
        </label>
      )}
      {error && (
        <p role="alert" style={{ color: '#dc2626', fontSize: 12, margin: 0 }}>
          {error}
        </p>
      )}
      {savedNote && (
        <p role="status" style={{ color: '#047857', fontSize: 12, margin: 0 }}>
          Saved. What you typed while it saved is kept here as the next inspection.
        </p>
      )}
      <div style={{ display: 'flex', gap: 8 }}>
        <button type="submit" disabled={pending}>
          {pending ? 'Saving...' : 'Save inspection'}
        </button>
        <button type="button" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

export function DefectForm({
  targets,
  targetLabel,
  productionRunId,
  fromInspection,
  pending,
  onSubmit,
  onCancel,
}: FormProps<Omit<DefectCreateRequest, 'projectId'>> & {
  /** The failed inspection the defect comes from; run, LOT and item are then taken from it. */
  fromInspection: { inspectionId: string; label: string } | null
}) {
  const [draft, setDraft] = useState<DefectDraft>({
    ...EMPTY_DEFECT,
    inspectionId: fromInspection?.inspectionId ?? null,
    targetKey: targets.length === 1 ? targets[0].key : '',
  })
  const [error, setError] = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    const result = buildDefectRequest(draft, targets, productionRunId)
    if (!result.ok) {
      setError(result.error)
      return
    }
    setError(null)
    try {
      await onSubmit(result.body)
    } catch {
      // The caller shows the server's answer.
    }
  }

  return (
    <form aria-label="Log defect" onSubmit={(e) => void submit(e)} style={{ display: 'grid', gap: 8, fontSize: 13, marginTop: 12 }}>
      {draft.inspectionId && fromInspection ? (
        <p style={{ margin: 0 }}>
          Found by {fromInspection.label}{' '}
          <button type="button" style={{ fontSize: 11 }} onClick={() => setDraft({ ...draft, inspectionId: null })}>
            Pick another target
          </button>
        </p>
      ) : (
        targets.length > 1 && (
          <label style={field}>
            <span>Where was it found</span>
            <select value={draft.targetKey} onChange={(e) => setDraft({ ...draft, targetKey: e.target.value })}>
              <option value="">Choose...</option>
              {targets.map((t) => (
                <option key={t.key} value={t.key}>
                  {targetLabel(t)}
                </option>
              ))}
            </select>
          </label>
        )
      )}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 6 }}>
        <label style={field}>
          <span>Defect *</span>
          <input value={draft.defectType} maxLength={50} onChange={(e) => setDraft({ ...draft, defectType: e.target.value })} />
        </label>
        <label style={field}>
          <span>Quantity *</span>
          <input inputMode="decimal" value={draft.quantity} onChange={(e) => setDraft({ ...draft, quantity: e.target.value })} />
        </label>
        <label style={field}>
          <span>Severity</span>
          <select value={draft.severity} onChange={(e) => setDraft({ ...draft, severity: e.target.value as DefectDraft['severity'] })}>
            <option value="minor">minor</option>
            <option value="major">major</option>
            <option value="critical">critical</option>
          </select>
        </label>
      </div>
      <label style={field}>
        <span>Reason</span>
        <input value={draft.reason} maxLength={2000} onChange={(e) => setDraft({ ...draft, reason: e.target.value })} />
      </label>
      {error && (
        <p role="alert" style={{ color: '#dc2626', fontSize: 12, margin: 0 }}>
          {error}
        </p>
      )}
      <div style={{ display: 'flex', gap: 8 }}>
        <button type="submit" disabled={pending}>
          {pending ? 'Saving...' : 'Save defect'}
        </button>
        <button type="button" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}
